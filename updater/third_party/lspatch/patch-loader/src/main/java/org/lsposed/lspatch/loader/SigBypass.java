package org.lsposed.lspatch.loader;

import static org.lsposed.lspatch.share.Constants.ORIGINAL_APK_ASSET_PATH;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.Signature;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Base64;
import android.util.Log;

import com.google.gson.JsonSyntaxException;

import org.lsposed.lspatch.loader.util.XLog;
import org.lsposed.lspatch.share.Constants;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SigBypass {

    private static final String TAG = "LSPatch-SigBypass";
    // Synchronized: afterHookedMethod runs on arbitrary caller threads, and a plain HashMap
    // under concurrent structural change can lose entries or throw. Null values are kept
    // (null == not a patched package), which rules out ConcurrentHashMap.
    private static final Map<String, String> signatures =
            java.util.Collections.synchronizedMap(new HashMap<>());

    /**
     * The original signature to present for a package, read from the {@code lspatch} manifest
     * metadata and cached. Null (cached) for a package that was not patched or carries no original.
     */
    private static String getReplacement(Context context, String packageName) {
        if (signatures.containsKey(packageName)) return signatures.get(packageName);
        String replacement = null;
        try {
            var metaData = context.getPackageManager()
                    .getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData;
            String encoded = metaData == null ? null : metaData.getString("lspatch");
            if (encoded != null) {
                var json = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
                try {
                    replacement = new JSONObject(json).getString("originalSignature");
                } catch (JSONException e) {
                    Log.w(TAG, "fail to get originalSignature", e);
                }
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        signatures.put(packageName, replacement);
        return replacement;
    }

    private static void replaceSignature(Context context, PackageInfo packageInfo) {
        boolean hasSignature = (packageInfo.signatures != null && packageInfo.signatures.length != 0) || packageInfo.signingInfo != null;
        if (hasSignature) {
            String packageName = packageInfo.packageName;
            String replacement = getReplacement(context, packageName);
            if (replacement != null) {
                Signature original = new Signature(replacement);
                // Every signer, not only the first. An original signed by more than one key, or an
                // app that reads signatures[1..], would otherwise still see the LSPatch key in the
                // entries the old code left untouched.
                if (packageInfo.signatures != null && packageInfo.signatures.length > 0) {
                    XLog.d(TAG, "Replace signatures[] for `" + packageName + "`");
                    for (int i = 0; i < packageInfo.signatures.length; i++) {
                        packageInfo.signatures[i] = original;
                    }
                }
                if (packageInfo.signingInfo != null) {
                    XLog.d(TAG, "Replace signingInfo for `" + packageName + "`");
                    Signature[] signers = packageInfo.signingInfo.getApkContentsSigners();
                    if (signers != null) {
                        for (int i = 0; i < signers.length; i++) signers[i] = original;
                    }
                    // getApkContentsSigners() may hand back a copy; the spoof only sticks for
                    // callers that read the details object or reflect into it if the backing array
                    // inside SigningDetails is overwritten too.
                    replaceSigningDetails(packageInfo.signingInfo, original);
                }
            }
        }
    }

    /**
     * Overwrites the signature array inside a SigningInfo's backing SigningDetails.
     *
     * The field moved when SigningDetails was promoted from PackageParser$SigningDetails (a nested
     * class, field {@code signatures}) to the top-level android.content.pm.SigningDetails on Android
     * 13 (field {@code mSignatures}). Both names are tried, and any failure is swallowed: this runs
     * inside the patched app, so a reflection miss on an unfamiliar build must never take the app
     * down -- the outer getApkContentsSigners() replacement already covers the common path.
     */
    private static void replaceSigningDetails(Object signingInfo, Signature original) {
        try {
            Object details = XposedHelpers.getObjectField(signingInfo, "mSigningDetails");
            if (details == null) return;
            for (String field : new String[]{"mSignatures", "signatures"}) {
                try {
                    Object current = XposedHelpers.getObjectField(details, field);
                    if (current instanceof Signature[]) {
                        int len = Math.max(((Signature[]) current).length, 1);
                        Signature[] replaced = new Signature[len];
                        for (int i = 0; i < len; i++) replaced[i] = original;
                        XposedHelpers.setObjectField(details, field, replaced);
                        return;
                    }
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable t) {
            XLog.d(TAG, "replaceSigningDetails skipped: " + t.getMessage());
        }
    }

    private static void hookPackageParser(Context context) {
        XposedBridge.hookAllMethods(PackageParser.class, "generatePackageInfo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                var packageInfo = (PackageInfo) param.getResult();
                if (packageInfo == null) return;
                replaceSignature(context, packageInfo);
            }
        });
    }

    private static void proxyPackageInfoCreator(Context context) {
        Parcelable.Creator<PackageInfo> originalCreator = PackageInfo.CREATOR;
        Parcelable.Creator<PackageInfo> proxiedCreator = new Parcelable.Creator<>() {
            @Override
            public PackageInfo createFromParcel(Parcel source) {
                PackageInfo packageInfo = originalCreator.createFromParcel(source);
                replaceSignature(context, packageInfo);
                return packageInfo;
            }

            @Override
            public PackageInfo[] newArray(int size) {
                return originalCreator.newArray(size);
            }
        };
        XposedHelpers.setStaticObjectField(PackageInfo.class, "CREATOR", proxiedCreator);
        try {
            Map<?, ?> mCreators = (Map<?, ?>) XposedHelpers.getStaticObjectField(Parcel.class, "mCreators");
            mCreators.clear();
        } catch (NoSuchFieldError ignore) {
        } catch (Throwable e) {
            Log.w(TAG, "fail to clear Parcel.mCreators", e);
        }
        try {
            Map<?, ?> sPairedCreators = (Map<?, ?>) XposedHelpers.getStaticObjectField(Parcel.class, "sPairedCreators");
            sPairedCreators.clear();
        } catch (NoSuchFieldError ignore) {
        } catch (Throwable e) {
            Log.w(TAG, "fail to clear Parcel.sPairedCreators", e);
        }
    }

    /**
     * Spoofs the app-local getPackageInfo path.
     *
     * The PackageParser hook catches the in-process parse and the CREATOR proxy catches results
     * unmarshalled from the system across binder; this catches ApplicationPackageManager, the front
     * door most apps actually call, including the modern GET_SIGNING_CERTIFICATES form. Belt and
     * suspenders, but the belts cover different paths.
     */
    private static void hookApplicationPackageManager(Context context) {
        try {
            Class<?> apm = Class.forName("android.app.ApplicationPackageManager");
            XposedBridge.hookAllMethods(apm, "getPackageInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getResult() instanceof PackageInfo info) {
                        replaceSignature(context, info);
                    }
                }
            });
        } catch (Throwable t) {
            XLog.d(TAG, "hookApplicationPackageManager skipped: " + t.getMessage());
        }
    }

    /**
     * Spoofs the in-process archive parse, {@code getPackageArchiveInfo}.
     *
     * A tamper check often reads its own installed apk as a file --
     * {@code getPackageArchiveInfo(getPackageResourcePath(), GET_SIGNING_CERTIFICATES)} -- to recover
     * the signer. On API 30+ that parses the apk in-process and never routes through
     * {@code PackageParser.generatePackageInfo} nor the {@code PackageInfo} CREATOR, so neither the
     * parser hook nor the CREATOR proxy fires; without this the archive's real (LSPatch) key leaks.
     * The method is declared on {@code PackageManager} itself (not overridden in
     * ApplicationPackageManager), so it is hooked there to catch both the {@code (String,int)} and the
     * API 33 {@code (String,PackageInfoFlags)} overloads.
     *
     * {@link #replaceSignature} only rewrites a package an original is held for (getReplacement returns
     * null otherwise), so parsing a foreign archive is left untouched. At level 2+ the openat redirect
     * already makes the parse read the stored original, so this mainly closes the gap at level 1 -- it
     * is cheap belt-and-suspenders for the redirecting levels.
     */
    private static void hookPackageArchiveInfo(Context context) {
        try {
            XposedBridge.hookAllMethods(PackageManager.class, "getPackageArchiveInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getResult() instanceof PackageInfo info) {
                        replaceSignature(context, info);
                    }
                }
            });
        } catch (Throwable t) {
            XLog.d(TAG, "hookPackageArchiveInfo skipped: " + t.getMessage());
        }
    }

    /**
     * Defeats the comparison-side check, {@code hasSigningCertificate}.
     *
     * Some integrity libraries never read the signature bytes; they hand the framework the original
     * certificate and ask whether it matches. Against a re-signed app that returns false, so the
     * answer is corrected: when the certificate presented is the app's own original -- raw X509 or
     * its SHA-256, per the {@code type} argument -- the call is forced to true. Only the app's own
     * package is answered; a genuine cross-app check is left alone.
     */
    private static void hookSigningCertificateCheck(Context context) {
        try {
            Class<?> apm = Class.forName("android.app.ApplicationPackageManager");
            XposedBridge.hookAllMethods(apm, "hasSigningCertificate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(param.getResult())) return;
                    Object[] args = param.args;
                    // Locate the certificate bytes and the type flag among the two overloads
                    // (by package name, and by uid).
                    byte[] presented = null;
                    int type = 0; // PackageManager.CERT_INPUT_RAW_X509
                    String pkg = null;
                    for (Object a : args) {
                        if (a instanceof byte[]) presented = (byte[]) a;
                        else if (a instanceof String) pkg = (String) a;
                        else if (a instanceof Integer) type = (Integer) a;
                    }
                    if (presented == null) return;
                    // Only vouch for the app's own package. The uid overload carries no name; the
                    // patched app querying itself is the case worth answering, and answering a
                    // foreign package would be a real spoof rather than a self-check.
                    String self = context.getPackageName();
                    if (pkg != null && !pkg.equals(self)) return;
                    String replacement = signatures.get(self);
                    if (replacement == null) return;
                    byte[] original = new Signature(replacement).toByteArray();
                    byte[] expected = type == 1 /* CERT_INPUT_SHA256 */ ? sha256(original) : original;
                    if (expected != null && java.security.MessageDigest.isEqual(expected, presented)) {
                        XLog.d(TAG, "hasSigningCertificate spoofed true for `" + self + "`");
                        param.setResult(true);
                    }
                }
            });
        } catch (Throwable t) {
            XLog.d(TAG, "hookSigningCertificateCheck skipped: " + t.getMessage());
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Throwable t) {
            return null;
        }
    }

    static void doSigBypass(Context context, int sigBypassLevel) throws IOException {
        if (sigBypassLevel >= Constants.SIGBYPASS_LV_PM) {
            // Prime the cache so hasSigningCertificate has the original on hand from the first call.
            getReplacement(context, context.getPackageName());
            hookPackageParser(context);
            proxyPackageInfoCreator(context);
            hookApplicationPackageManager(context);
            hookPackageArchiveInfo(context);
            hookSigningCertificateCheck(context);
        }
        if (sigBypassLevel >= Constants.SIGBYPASS_LV_PM_OPENAT) {
            String cacheApkPath;
            try (ZipFile sourceFile = new ZipFile(context.getPackageResourcePath())) {
                cacheApkPath = context.getCacheDir() + "/lspatch/origin/" + sourceFile.getEntry(ORIGINAL_APK_ASSET_PATH).getCrc() + ".apk";
            }
            org.matrix.vector.nativebridge.SigBypass.enableOpenatHook(context.getPackageResourcePath(), cacheApkPath);
        }
        if (sigBypassLevel >= Constants.SIGBYPASS_LV_PM_OPENAT_SVC) {
            // Reuses the apk paths enableOpenatHook just recorded; must run after it.
            org.matrix.vector.nativebridge.SigBypass.enableSvcRedirect();
        }
    }
}
