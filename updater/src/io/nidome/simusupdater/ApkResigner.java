package io.nidome.simusupdater;

import android.content.Context;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 用固定密钥重新签名 APK。
 *
 * 为什么需要：TikTok 的 .apkm 是分卷包，而且（LSPatch 之外）分卷仍是官方签名。
 * Android 要求同一 split 家族的所有 APK 签名一致，所以除 base 外的分卷也必须
 * 用同一密钥重签，否则安装会失败。
 */
public class ApkResigner {

    private final Context ctx;
    private final String assetName, pass, alias;

    public ApkResigner(Context ctx, String assetName, String pass, String alias) {
        this.ctx = ctx;
        this.assetName = assetName;
        this.pass = pass;
        this.alias = alias;
    }

    /** 把 assets 里的 keystore 释放到私有目录。 */
    public File keystoreFile() throws Exception {
        File f = new File(ctx.getFilesDir(), "signing.bks");
        if (!f.exists() || f.length() == 0) {
            try (InputStream in = ctx.getAssets().open(assetName);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
        return f;
    }

    private KeyStore.PrivateKeyEntry entry() throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        try (InputStream in = new FileInputStream(keystoreFile())) {
            ks.load(in, pass.toCharArray());
        }
        KeyStore.PrivateKeyEntry e = (KeyStore.PrivateKeyEntry) ks.getEntry(
                alias, new KeyStore.PasswordProtection(pass.toCharArray()));
        if (e == null) throw new Exception("keystore 里找不到别名 " + alias);
        return e;
    }

    /** 就地重签名（输出到 out 文件）。 */
    public void resign(File in, File out) throws Exception {
        KeyStore.PrivateKeyEntry e = entry();
        PrivateKey key = e.getPrivateKey();
        List<X509Certificate> chain = new ArrayList<>();
        for (java.security.cert.Certificate c : e.getCertificateChain()) {
            chain.add((X509Certificate) c);
        }
        ApkSigner.SignerConfig cfg = new ApkSigner.SignerConfig.Builder(alias, key, chain).build();
        ApkSigner signer = new ApkSigner.Builder(Arrays.asList(cfg))
                .setInputApk(in)
                .setOutputApk(out)
                // v1（JAR 签名）必须开：实测这台 vivo 的安装器会读 v1 签名，
                // 只有 v2 的包（LSPatch / apksig 默认产物）会报「解析软件包时出现问题」。
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .build();
        signer.sign();
    }
}
