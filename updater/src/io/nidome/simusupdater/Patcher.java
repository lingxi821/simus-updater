package io.nidome.simusupdater;

import android.content.Context;
import android.util.Log;

import org.lsposed.patch.ApkPatcher;
import org.lsposed.patch.KeystoreSpec;
import org.lsposed.patch.PatchSpec;
import org.lsposed.patch.util.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 把 LSPatch 的打包引擎包成 App 内的一个调用。
 *
 * 关键点（来自可行性调查）：
 *  - 用自己生成的 PKCS12 keystore（官方 CLI jar 那份是 JKS，Android 读不了）
 *  - 绝不调用 org.lsposed.patch.LSPatch（它的 main 里有 System.exit，会杀掉 App）
 *  - loader.dex / metaloader.dex / liblspatch.so 必须来自 assets（不能放 lib/）
 */
public class Patcher {

    /** 签名参数：跨版本恒定 —— 覆盖安装能保留数据的前提。 */
    static final String KS_ASSET = "keystore.bks";
    static final String KS_PASS = "123456";
    static final String KS_ALIAS = "key0";

    public interface Progress {
        void onLog(String line);
        void onStage(String stage, int index, int total);
    }

    private final Context ctx;
    private final Progress cb;

    public Patcher(Context ctx, Progress cb) {
        this.ctx = ctx;
        this.cb = cb;
    }

    /** 把 assets 里的 keystore 释放到私有目录，返回文件。 */
    public File keystoreFile() throws Exception {
        File f = new File(ctx.getFilesDir(), "signing.bks");
        if (!f.exists() || f.length() == 0) {
            try (InputStream in = ctx.getAssets().open(KS_ASSET);
                 FileOutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
        return f;
    }

    /**
     * 打包。
     *
     * @param targetApks 目标 APK（base 必须在第一个；split 也要一起传）
     * @param moduleApk  要嵌入的 Xposed 模块
     * @param outDir     输出目录
     */
    public List<File> patch(List<File> targetApks, File moduleApk, File outDir) throws Exception {
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new Exception("无法创建输出目录: " + outDir);
        }
        for (File f : targetApks) {
            if (f == null || !f.exists()) throw new Exception("目标 APK 不存在: " + f);
        }
        if (!moduleApk.exists()) throw new Exception("模块 APK 不存在: " + moduleApk);

        Logger logger = new Logger() {
            @Override public void d(String msg) { cb.onLog(msg); }
            @Override public void i(String msg) { cb.onLog(msg); }
            @Override public void e(String msg) { Log.e("SIMUS-PATCH", msg); cb.onLog("E: " + msg); }
            @Override public void stage(Stage stage, int index, int total) {
                cb.onStage(String.valueOf(stage), index, total);
            }
        };
        logger.verbose = true;

        PatchSpec spec = PatchSpec.builder()
                .apks(targetApks)
                .outputDir(outDir)
                .useManager(false)                       // 集成模式：模块直接打进 APK
                .modules(Collections.singletonList(moduleApk))
                .sigBypassLevel(2)                       // pm + openat，应对自校验
                .injectDex(false)
                .forceOverwrite(true)
                .verbose(true)
                .keystore(KeystoreSpec.of(keystoreFile(), KS_PASS, KS_ALIAS, KS_PASS))
                .build();

        List<File> produced = new ApkPatcher(logger, spec).patch();
        return produced == null ? new ArrayList<>() : produced;
    }

    /**
     * 从 assets 释放模块 APK。
     *
     * **每次都直接覆盖**：模块升级后（比如这次的官方平板模式 hook），
     * 私有目录里那份旧的会一直被用下去；按大小比对也不行 —— 两次构建的模块字节数可能正好相同
     * （踩过：新旧都是 12749 字节，于是又打了个旧模块进去）。
     */
    public File moduleFile() throws Exception {
        File f = new File(ctx.getFilesDir(), "simspoof.apk");
        try (InputStream in = ctx.getAssets().open("simspoof.apk");
             FileOutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return f;
    }
}
