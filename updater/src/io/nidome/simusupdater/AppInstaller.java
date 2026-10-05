package io.nidome.simusupdater;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 用 App 自己的 PackageInstaller 会话安装分卷包 —— **不需要 Shizuku**。
 *
 * 为什么需要这条路：实测这台 vivo（PD2344 / Android 14）的安装拦截器
 * （com.android.packageinstaller.PackageInterceptActivity）会拒掉 shell 身份发起的
 * 大体积安装（单体 189 MB 以上就会弹「解析软件包时出现问题」→
 * INSTALL_FAILED_ABORTED: User rejected permissions），无论是 `pm install`、
 * 多 APK 会话、还是 -i 伪装成应用商店都一样。
 * 但 App 自己是 installer 时会走系统标准的「安装/更新」确认界面，属于另一条路径。
 *
 * 流程：createSession → 逐个 openWrite 写入 base + 各分卷 → commit(带结果广播)。
 * 用户需要在系统弹出的确认界面上点一下「安装」。
 */
public class AppInstaller {

    public static final String ACTION_RESULT = "io.nidome.simusupdater.INSTALL_RESULT";
    private static final String TAG = "SIMUS";

    public interface Callback {
        void onLog(String line);
    }

    /** 发起安装。返回本次会话 id（失败抛异常）。 */
    public static int install(Context ctx, File dir, String appPackage, Callback cb) throws Exception {
        File[] all = dir.listFiles();
        if (all == null || all.length == 0) throw new Exception("目录为空: " + dir);
        java.util.List<File> list = new java.util.ArrayList<>();
        for (File f : all) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".apk")) list.add(f);
        }
        return installFiles(ctx, list, appPackage, cb);
    }

    /** 安装一组 APK（单体包就传一个）。 */
    public static int installFiles(Context ctx, java.util.List<File> apks, String appPackage,
                                   Callback cb) throws Exception {
        if (apks == null || apks.isEmpty()) throw new Exception("没有可安装的 APK");
        File[] arr = apks.toArray(new File[0]);
        int n = arr.length;
        long total = 0;
        for (File f : arr) total += f.length();
        // base 必须第一个写入
        Arrays.sort(arr, Comparator.comparingInt(f -> f.getName().equals("base.apk") ? 0 : 1));

        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setSize(total);
        if (Build.VERSION.SDK_INT >= 31) {
            p.setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER);
        }
        if (Build.VERSION.SDK_INT >= 33 && appPackage != null) {
            try {
                p.setAppPackageName(appPackage);   // 让系统按「更新」而不是「新安装」处理
            } catch (Throwable t) {
                Log.w(TAG, "setAppPackageName: " + t);
            }
        }

        int sid = pi.createSession(p);
        cb.onLog("已创建安装会话 " + sid + "（" + n + " 个分卷，"
                + ApkMirror.human(total) + "）");
        try (PackageInstaller.Session s = pi.openSession(sid)) {
            int i = 0;
            for (File f : apks) {
                i++;
                try (OutputStream o = s.openWrite(f.getName(), 0, f.length());
                     InputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[1 << 16];
                    int r;
                    while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
                }
                if (i % 10 == 0 || i == n) cb.onLog("   已写入 " + i + "/" + n);
            }
            Intent res = new Intent(ACTION_RESULT).setPackage(ctx.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent cbPi = PendingIntent.getBroadcast(ctx, sid, res, flags);
            s.commit(cbPi.getIntentSender());
            cb.onLog("已提交，请在系统界面上点「安装」");
        }
        return sid;
    }
}
