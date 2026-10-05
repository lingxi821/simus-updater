package org.lsposed.lspatch.metaloader;

import android.annotation.SuppressLint;
import android.app.AppComponentFactory;
import android.app.ActivityThread;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.os.Build;
import android.os.Process;
import android.os.ServiceManager;
import android.util.JsonReader;
import android.util.Log;

import org.lsposed.hiddenapibypass.HiddenApiBypass;
import org.lsposed.lspatch.share.Constants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipFile;

@SuppressLint("UnsafeDynamicallyLoadedCode")
public class LSPAppComponentFactoryStub extends AppComponentFactory {

    private static final String TAG = "LSPatch-MetaLoader";
    private static final Map<String, String> archToLib = new HashMap<String, String>(4);

    public static byte[] dex;

    static {
        final boolean appZygote = ActivityThread.currentActivityThread() == null;
        if (appZygote) {
            Log.i(TAG, "Skip loading liblspatch.so for appZygote");
        } else {
            bootstrap();
        }
    }

    private static void bootstrap() {
        try {
            archToLib.put("arm", "armeabi-v7a");
            archToLib.put("arm64", "arm64-v8a");
            archToLib.put("x86", "x86");
            archToLib.put("x86_64", "x86_64");

            var cl = Objects.requireNonNull(LSPAppComponentFactoryStub.class.getClassLoader());
            Class<?> VMRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = VMRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Method vmInstructionSet = VMRuntime.getDeclaredMethod("vmInstructionSet");
            vmInstructionSet.setAccessible(true);
            String arch = (String) vmInstructionSet.invoke(getRuntime.invoke(null));
            String libName = archToLib.get(arch);

            boolean useManager = false;
            String managerPackageName = Constants.MANAGER_PACKAGE_NAME;
            String soPath;

            try (var is = cl.getResourceAsStream(Constants.CONFIG_ASSET_PATH);
                 var reader = new JsonReader(new InputStreamReader(is))) {
                reader.beginObject();
                while (reader.hasNext()) {
                    var name = reader.nextName();
                    if (name.equals("useManager")) {
                        useManager = reader.nextBoolean();
                    } else if (name.equals("managerPackageName")) {
                        var value = reader.nextString();
                        if (value != null && !value.isEmpty()) {
                            managerPackageName = value;
                        }
                    } else {
                        reader.skipValue();
                    }
                }
            }

            if (useManager) {
                var ipm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
                int userId = Process.myUid() / 100000;
                // Resolve the manager the loader lives in, tolerant of a renamed (cloaked) or
                // reverted manager. A manager-mode app binds its manager by package name baked in at
                // patch time; when that package no longer resolves -- the manager was cloaked to a
                // random id, or a cloak was reverted and its temporary id uninstalled -- looking only
                // at the recorded name read null and the app crashed on every start reading a null
                // sourceDir. So the recorded name is tried first, then the stock package, and a
                // candidate is accepted only once its apk is confirmed to actually carry the loader.
                ApplicationInfo manager = null;
                String chosen = null;
                for (var candidate : managerCandidates(managerPackageName)) {
                    var info = getApplicationInfoOrNull(ipm, candidate, userId);
                    if (info == null || info.sourceDir == null) {
                        Log.w(TAG, "Manager candidate not installed: " + candidate);
                        continue;
                    }
                    if (!carriesLoader(info.sourceDir)) {
                        Log.w(TAG, "Manager candidate carries no loader: " + candidate);
                        continue;
                    }
                    manager = info;
                    chosen = candidate;
                    break;
                }
                if (manager == null) {
                    throw new IllegalStateException(
                            "No installed LSPatch manager carries the loader (tried "
                                    + String.join(", ", managerCandidates(managerPackageName))
                                    + "); re-patch this app or reinstall the manager");
                }
                Log.i(TAG, "Bootstrap loader from manager: " + chosen);
                try (var zip = new ZipFile(new File(manager.sourceDir));
                     var is = zip.getInputStream(zip.getEntry(Constants.LOADER_DEX_ASSET_PATH));
                     var os = new ByteArrayOutputStream()) {
                    transfer(is, os);
                    dex = os.toByteArray();
                }
                soPath = manager.sourceDir + "!/assets/lspatch/so/" + libName + "/liblspatch.so";
            } else {
                Log.i(TAG, "Bootstrap loader from embedment");
                try (var is = cl.getResourceAsStream(Constants.LOADER_DEX_ASSET_PATH);
                     var os = new ByteArrayOutputStream()) {
                    transfer(is, os);
                    dex = os.toByteArray();
                }
                String resourcePath = cl.getResource("assets/lspatch/so/" + libName + "/liblspatch.so").getPath();
                Log.d(TAG, "Resource path: " + resourcePath);
                String[] pathParts = resourcePath.split("file:");
                soPath = pathParts[pathParts.length - 1];
            }

            System.load(soPath);
        } catch (Throwable e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * The manager packages to try, in order: the one this app was patched against, then the stock id.
     *
     * The stock fallback is what lets a manager-mode app survive a manager rename it was not retargeted
     * for -- a cloak whose re-patch of this app failed, or a revert back to stock after this app had
     * been pointed at the (now uninstalled) cloaked id. The recorded name is still tried first, so a
     * correctly retargeted app keeps loading from the manager it was told about.
     */
    private static String[] managerCandidates(String recorded) {
        if (recorded == null || recorded.isEmpty() || recorded.equals(Constants.MANAGER_PACKAGE_NAME)) {
            return new String[]{Constants.MANAGER_PACKAGE_NAME};
        }
        return new String[]{recorded, Constants.MANAGER_PACKAGE_NAME};
    }

    private static ApplicationInfo getApplicationInfoOrNull(IPackageManager ipm, String packageName, int userId) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return (ApplicationInfo) HiddenApiBypass.invoke(IPackageManager.class, ipm, "getApplicationInfo", packageName, 0L, userId);
            }
            return ipm.getApplicationInfo(packageName, 0, userId);
        } catch (Throwable t) {
            // A package that is not installed for this user answers with null or throws depending on
            // the platform; either way it is simply not a manager this app can load from.
            return null;
        }
    }

    /** Whether the apk at [sourceDir] actually holds the loader dex -- so a same-named non-manager is skipped. */
    private static boolean carriesLoader(String sourceDir) {
        try (var zip = new ZipFile(new File(sourceDir))) {
            return zip.getEntry(Constants.LOADER_DEX_ASSET_PATH) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void transfer(InputStream is, OutputStream os) throws IOException {
        byte[] buffer = new byte[8192];
        int n;
        while (-1 != (n = is.read(buffer))) {
            os.write(buffer, 0, n);
        }
    }
}
