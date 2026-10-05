package io.nidome.simusupdater;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import rikka.shizuku.Shizuku;

/**
 * SIM US Updater —— 把 TikTok 重新打包成「美国区」补丁版并安装。
 *
 * 输入来源有三种，按可靠性排序：
 *   ① 本地 APK 文件（用系统文件选择器选，或自动扫描 Download）—— 网络无关，最可靠
 *   ② APKMirror 自动下载 —— 需要手机能访问 APKMirror
 */
public class MainActivity extends Activity {

    private static final String TAG = "SIMUS";
    private static final String PKG = "com.zhiliaoapp.musically";
    private static final int REQ_SHIZUKU = 1001;
    private static final int REQ_PICK = 2001;
    private static final int REQ_STORAGE = 2002;

    /** 补丁版（LSPatch 内置密钥）的签名证书指纹；官方版和它不一致，覆盖安装必被拒。 */
    private static final String PATCHED_CERT_SHA256 =
            "c081890cf2a1adf13e56d7b50a4f3d8edb35b7c46d6ccc732dd997b7e433be1d";

    /**
     * 补丁逻辑版本号：改了打包流程 / manifest 重建规则 / 内嵌模块之后必须 +1，
     * 否则「复用上次打好的分卷」会把旧产物直接装上去（踩过：竖屏锁改动没生效）。
     */
    private static final int PATCH_SCHEMA = 8;
    /** 需要「先卸载官方版」时，卸载完成后要继续做的事。 */
    private volatile Runnable afterUninstall;

    private TextView logView, statusTitle, statusSub, chipVersion, chipEnv, chipLatest,
            chipInstall, btnMain, btnLocal;
    private LinearLayout statusCard, logCard;
    private ScrollView logScroll;
    private android.widget.ProgressBar progress;
    private android.view.View spinner;
    private final TextView[] steps = new TextView[4];
    private final android.text.SpannableStringBuilder logBuf = new android.text.SpannableStringBuilder();
    private TextView logHint;
    private static final String[] STEP_NAMES = {"检查", "下载", "打包", "安装"};
    private volatile boolean busy = false;

    private IInstaller installer;
    private long lastBindAttempt = 0;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable envTick = new Runnable() {
        @Override public void run() {
            refreshShizukuState();
            refreshInstallPermission();
            ui.postDelayed(this, 2000);
        }
    };
    private final Shizuku.UserServiceArgs svcArgs = new Shizuku.UserServiceArgs(
            new ComponentName(BuildConfig.APPLICATION_ID, IInstaller.Service.class.getName()))
            .daemon(false)
            .processNameSuffix("installer")
            .tag("simus-installer")
            .version(1);

    private final ServiceConnection svcConn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            installer = IInstaller.asInterface(b);
            say("Shizuku 安装服务已连接");
            refreshShizukuState();
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            installer = null;
            say("Shizuku 安装服务已断开");
            refreshShizukuState();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        buildUi();
        // 打包 + 安装要几分钟，期间不能黑屏（vivo 的安装确认界面锁屏下不显示）
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        say("已安装 TikTok: " + installedVersion());
        phase(-1, "准备就绪", "点下面的按钮开始");

        Shizuku.addRequestPermissionResultListener((c, r) -> {
            if (r == PackageManager.PERMISSION_GRANTED) { say("Shizuku 权限已授予"); bind(); }
            else say("Shizuku 权限被拒绝");
        });
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        ensureShizuku();
        registerInstallResult();
        abandonStaleSessions();
        requestStorage();
    }

    @Override protected void onResume() {
        super.onResume();
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
            f.addDataScheme("package");
            registerReceiver(packageRemoved, f);
        } catch (Throwable ignored) {}
        ui.removeCallbacks(envTick);
        ui.post(envTick);
    }

    @Override protected void onPause() {
        super.onPause();
        try { unregisterReceiver(packageRemoved); } catch (Throwable ignored) {}
        ui.removeCallbacks(envTick);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(envTick);
        try { Shizuku.removeBinderDeadListener(binderDead); } catch (Throwable ignored) {}
        try { Shizuku.removeBinderReceivedListener(binderReceived); } catch (Throwable ignored) {}
        try { unregisterReceiver(installResult); } catch (Throwable ignored) {}
        try { Shizuku.unbindUserService(svcArgs, svcConn, true); } catch (Throwable ignored) {}
    }

    // ---------------- 界面 ----------------

    private void buildUi() {
        int pad = Ui.dp(this, 16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(pad, pad, pad, Ui.dp(this, 10));
        root.setFitsSystemWindows(true);

        root.addView(buildHeader());

        statusCard = buildStatusCard();
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(-1, -2);
        scLp.topMargin = Ui.dp(this, 14);
        root.addView(statusCard, scLp);

        LinearLayout buttons = buildButtons();
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-1, -2);
        bLp.topMargin = Ui.dp(this, 14);
        root.addView(buttons, bLp);

        logCard = buildLogCard();
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lLp.topMargin = Ui.dp(this, 14);
        root.addView(logCard, lLp);

        root.addView(buildFooter());
        setContentView(root);
    }

    /** 顶部：图标 + 标题 + 当前版本徽章。 */
    private LinearLayout buildHeader() {
        LinearLayout row = Ui.row(this);
        TextView icon = Ui.bold(Ui.tv(this, "US", 15, Ui.ON_ACCENT));
        icon.setGravity(Gravity.CENTER);
        int s = Ui.dp(this, 42);
        icon.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        icon.setBackground(Ui.gradient(Ui.CYAN, Ui.PINK, 12, this));
        row.addView(icon);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
        clp.leftMargin = Ui.dp(this, 12);
        col.setLayoutParams(clp);
        TextView title = Ui.bold(Ui.tv(this, "SIM US Updater", 18, Ui.TEXT));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(title);
        TextView sub = Ui.tv(this, "TikTok 美区一键更新", 12, Ui.TEXT_DIM);
        sub.setPadding(0, Ui.dp(this, 2), 0, 0);
        col.addView(sub);
        row.addView(col);

        chipVersion = Ui.tv(this, "—", 11, Ui.CYAN);
        chipVersion.setSingleLine(true);
        chipVersion.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5));
        chipVersion.setBackground(Ui.stroked(Ui.alpha(Ui.CYAN, 0.10f), Ui.alpha(Ui.CYAN, 0.35f), 20, this));
        row.addView(chipVersion);
        return row;
    }

    /** 状态卡片：状态文字 + 四步进度 + 进度条。 */
    private LinearLayout buildStatusCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        card.setBackground(Ui.stroked(Ui.SURFACE, Ui.STROKE, 16, this));

        LinearLayout line = Ui.row(this);
        spinner = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        int ss = Ui.dp(this, 18);
        spinner.setLayoutParams(new LinearLayout.LayoutParams(ss, ss));
        spinner.setVisibility(android.view.View.GONE);
        line.addView(spinner);
        statusTitle = Ui.bold(Ui.tv(this, "准备就绪", 15, Ui.TEXT));
        statusTitle.setPadding(Ui.dp(this, 6), 0, 0, 0);
        line.addView(statusTitle);
        line.addView(Ui.spacer(this));
        statusSub = Ui.tv(this, "", 12, Ui.TEXT_DIM);
        line.addView(statusSub);
        card.addView(line);

        LinearLayout stepRow = Ui.row(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Ui.dp(this, 12);
        stepRow.setLayoutParams(slp);
        for (int i = 0; i < steps.length; i++) {
            TextView t = Ui.tv(this, STEP_NAMES[i], 12, Ui.TEXT_FAINT);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = Ui.dp(this, 6);
            t.setLayoutParams(lp);
            steps[i] = t;
            styleStep(i, 0);
            stepRow.addView(t);
        }
        card.addView(stepRow);

        progress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.CYAN));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.SURFACE_2));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 5));
        plp.topMargin = Ui.dp(this, 12);
        progress.setLayoutParams(plp);
        progress.setVisibility(android.view.View.GONE);
        card.addView(progress);

        LinearLayout env = Ui.row(this);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.topMargin = Ui.dp(this, 12);
        env.setLayoutParams(elp);
        chipInstall = Ui.tv(this, "", 11, Ui.AMBER);
        chipInstall.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
        ilp.topMargin = Ui.dp(this, 6);
        chipInstall.setLayoutParams(ilp);
        chipInstall.setOnClickListener(v -> openInstallPermission());
        card.addView(chipInstall);

        chipEnv = Ui.tv(this, "Shizuku 检查中…", 11, Ui.TEXT_FAINT);
        env.addView(chipEnv);
        env.addView(Ui.spacer(this));
        chipLatest = Ui.tv(this, "最新版 未检查", 11, Ui.TEXT_FAINT);
        env.addView(chipLatest);
        chipEnv.setOnClickListener(v -> {
            try {
                if (Shizuku.getBinder() != null && Shizuku.pingBinder()
                        && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(REQ_SHIZUKU);
                    return;
                }
            } catch (Throwable ignored) {}
            openShizuku();
        });
        card.addView(env);
        return card;
    }

    private LinearLayout buildButtons() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        btnMain = Ui.bold(Ui.tv(this, "一键更新并安装", 16, Ui.ON_ACCENT));
        btnMain.setGravity(Gravity.CENTER);
        btnMain.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(this, 54)));
        btnMain.setBackground(Ui.gradient(Ui.CYAN, Ui.PINK, 14, this));
        btnMain.setOnClickListener(v -> oneClick());
        pressFeedback(btnMain);
        col.addView(btnMain);

        btnLocal = Ui.tv(this, "本地安装包（下载不通时用这个）", 13, Ui.TEXT_DIM);
        btnLocal.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
        lp.topMargin = Ui.dp(this, 10);
        btnLocal.setLayoutParams(lp);
        btnLocal.setBackground(Ui.stroked(Ui.SURFACE, Ui.STROKE, 14, this));
        btnLocal.setOnClickListener(v -> fromLocal());
        pressFeedback(btnLocal);
        col.addView(btnLocal);
        return col;
    }

    /** 日志卡片（可选中复制）。 */
    private LinearLayout buildLogCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 10));
        card.setBackground(Ui.stroked(Ui.SURFACE, Ui.STROKE, 16, this));

        LinearLayout head = Ui.row(this);
        head.addView(Ui.tv(this, "运行日志", 12, Ui.TEXT_DIM));
        head.addView(Ui.spacer(this));
        TextView copy = Ui.tv(this, "复制", 12, Ui.CYAN);
        copy.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("simus-log", logBuf.toString()));
            say("日志已复制到剪贴板");
        });
        head.addView(copy);
        TextView clear = Ui.tv(this, "清空", 12, Ui.TEXT_DIM);
        clear.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        clear.setOnClickListener(v -> {
            logBuf.clear();
            logView.setText("");
            if (logHint != null) logHint.setVisibility(android.view.View.VISIBLE);
        });
        head.addView(clear);
        card.addView(head);

        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
        slp.topMargin = Ui.dp(this, 6);
        box.setLayoutParams(slp);

        logScroll = new ScrollView(this);
        logScroll.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, -1));
        logView = Ui.mono(this, 11, Ui.TEXT);
        logView.setPadding(0, 0, 0, Ui.dp(this, 6));
        logScroll.addView(logView);
        box.addView(logScroll);

        logHint = Ui.tv(this, "运行日志会显示在这里", 12, Ui.TEXT_FAINT);
        android.widget.FrameLayout.LayoutParams hlp = new android.widget.FrameLayout.LayoutParams(-2, -2);
        hlp.gravity = Gravity.CENTER;
        logHint.setLayoutParams(hlp);
        box.addView(logHint);

        card.addView(box);
        return card;
    }

    /** 底部署名。 */
    private TextView buildFooter() {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder("由 灵曦以茗 制作");
        int start = sb.toString().indexOf("灵曦以茗");
        sb.setSpan(new android.text.style.ForegroundColorSpan(Ui.PINK), start, start + 4,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, start + 4,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView t = Ui.tv(this, sb, 11, Ui.TEXT_FAINT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        return t;
    }

    /** 步骤药丸样式：0 未开始 / 1 进行中 / 2 已完成。 */
    private void styleStep(int i, int state) {
        TextView t = steps[i];
        if (t == null) return;
        if (state == 1) {
            t.setTextColor(Ui.CYAN);
            t.setBackground(Ui.stroked(Ui.alpha(Ui.CYAN, 0.14f), Ui.alpha(Ui.CYAN, 0.5f), 10, this));
            Ui.bold(t);
        } else if (state == 2) {
            t.setText("✓ " + STEP_NAMES[i]);
            t.setTextColor(Ui.GREEN);
            t.setBackground(Ui.stroked(Ui.alpha(Ui.GREEN, 0.10f), Ui.alpha(Ui.GREEN, 0.35f), 10, this));
        } else {
            t.setText(STEP_NAMES[i]);
            t.setTextColor(Ui.TEXT_FAINT);
            t.setBackground(Ui.bg(Ui.SURFACE_2, 10, this));
        }
    }

    /** 切换阶段：step = 0..4（4 表示全部完成）。 */
    private void phase(int step, String title, String sub) {
        runOnUiThread(() -> {
            for (int i = 0; i < steps.length; i++) {
                if (step < 0) styleStep(i, 0);                       // 空闲：全部未开始
                else if (step >= steps.length) styleStep(i, 2);      // 完成：全部打勾
                else styleStep(i, i < step ? 2 : (i == step ? 1 : 0));
            }
            if (title != null) {
                statusTitle.setText(title);
                int c = Ui.TEXT;
                int stroke = Ui.STROKE;
                if (title.contains("成功")) { c = Ui.GREEN; stroke = Ui.alpha(Ui.GREEN, 0.45f); }
                else if (title.contains("未完成") || title.contains("失败")) { c = Ui.RED; stroke = Ui.alpha(Ui.RED, 0.45f); }
                statusTitle.setTextColor(c);
                statusCard.setBackground(Ui.stroked(Ui.SURFACE, stroke, 16, this));
            }
            if (sub != null) statusSub.setText(sub);
            boolean working = step >= 0 && step < 4 && busy;
            spinner.setVisibility(working ? android.view.View.VISIBLE : android.view.View.GONE);
            progress.setVisibility(busy ? android.view.View.VISIBLE : android.view.View.GONE);
            if (!busy) progress.setProgress(0);
            chipVersion.setText("v" + installedShortVersion());
        });
    }

    /** 下载进度（确定进度条 + 百分比）。 */
    private void downloadProgress(long done, long total) {
        if (total <= 0) return;
        int pct = (int) Math.min(100, done * 100 / total);
        runOnUiThread(() -> {
            progress.setIndeterminate(false);
            progress.setProgress(pct);
            statusSub.setText(pct + "% · " + ApkMirror.human(done) + " / " + ApkMirror.human(total));
        });
    }

    /** 按下时轻微变暗，给点触感反馈（纯代码，不依赖 ripple 资源）。 */
    private void pressFeedback(final android.view.View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().alpha(0.82f).setDuration(60).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().alpha(view.isEnabled() ? 1f : 0.55f).setDuration(90).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** 已装 TikTok 的签名指纹（没装返回 null）。 */
    private String installedCertSha256() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(
                    PKG, PackageManager.GET_SIGNING_CERTIFICATES);
            android.content.pm.SigningInfo si = pi.signingInfo;
            if (si == null) return null;
            android.content.pm.Signature[] sigs =
                    si.hasMultipleSigners() ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
            if (sigs == null || sigs.length == 0) return null;
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(sigs[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 设备上的 TikTok 是不是「官方版」（签名与补丁版不同）。
     * 官方版无法被补丁版覆盖安装（INSTALL_FAILED_UPDATE_INCOMPATIBLE），必须先卸载。
     */
    private boolean installedIsOfficialBuild() {
        String cert = installedCertSha256();
        return cert != null && !cert.equalsIgnoreCase(PATCHED_CERT_SHA256);
    }

    /** 弹「需要先卸载官方版」的确认；确认后走系统卸载界面，卸载完成再继续。 */
    private void confirmUninstallOfficial(Runnable then) {
        runOnUiThread(() -> new android.app.AlertDialog.Builder(this)
                .setTitle("需要先卸载官方版 TikTok")
                .setMessage("这台设备上的 TikTok 是官方版（签名和补丁版不同），"
                        + "补丁版无法覆盖安装。\n\n"
                        + "需要先卸载官方版：**会丢失 App 内数据**（登录状态、草稿等），"
                        + "之后重新登录即可。\n\n要现在卸载吗？")
                .setPositiveButton("卸载并继续", (d, w) -> {
                    afterUninstall = then;
                    say("请在系统界面确认卸载官方版 TikTok…");
                    try {
                        Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + PKG));
                        startActivity(i);
                    } catch (Throwable t) {
                        say("打不开卸载界面: " + t);
                        afterUninstall = null;
                    }
                })
                .setNegativeButton("取消", (d, w) -> {
                    say("已取消（官方版没被卸载）");
                    busy = false;
                    setButtons(true);
                    phase(-1, "已取消", "需要先卸载官方版才能装补丁版");
                })
                .show());
    }

    /**
     * 设备上是官方版 → 先让用户卸载，卸载完再跑同一套流程；返回 true 表示「已拦截，调用方直接结束」。
     */
    private boolean stopIfOfficialBuild(Runnable retry) {
        if (!installedIsOfficialBuild()) return false;
        say("⚠️ 设备上装的是官方版 TikTok（签名与补丁版不同），无法覆盖安装");
        busy = true;
        setButtons(false);
        phase(0, "需要先卸载官方版", "签名不一致 · 卸载后自动继续");
        confirmUninstallOfficial(() -> {
            busy = false;
            say("官方版已卸载 → 继续");
            retry.run();
        });
        return true;
    }

    private String installedShortVersion() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(PKG, 0);
            return pi.versionName;
        } catch (Throwable t) { return "未安装"; }
    }

    private void setEnv(String text, int color) {
        runOnUiThread(() -> {
            if (chipEnv == null) return;
            chipEnv.setText(text);
            chipEnv.setTextColor(color);
        });
    }

    private void say(String s) {
        Log.i(TAG, s);
        runOnUiThread(() -> {
            for (String line : s.split("\n", -1)) appendLog(line);
        });
    }

    private void appendLog(String line) {
        int color = logColor(line);
        int start = logBuf.length();
        logBuf.append(line).append("\n");
        logBuf.setSpan(new android.text.style.ForegroundColorSpan(color), start, logBuf.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (logBuf.length() > 80000) logBuf.delete(0, logBuf.length() - 50000);
        logView.setText(logBuf);
        if (logHint != null) logHint.setVisibility(android.view.View.GONE);
        logScroll.post(() -> logScroll.fullScroll(android.view.View.FOCUS_DOWN));
    }

    private static int logColor(String line) {
        String l = line == null ? "" : line;
        if (l.contains("✅") || l.contains("成功") || l.contains("Success") || l.contains("已连接")
                || l.contains("已就绪")) return Ui.GREEN;
        if (l.contains("⚠️") || l.contains("警告") || l.contains("Warning")) return Ui.AMBER;
        if (l.contains("❌") || l.contains("失败") || l.contains("Failure") || l.contains("错误")
                || l.contains("异常")) return Ui.RED;
        if (l.trim().startsWith("[engine]")) return Ui.TEXT_FAINT;
        if (l.startsWith("   ")) return Ui.TEXT_DIM;
        return Ui.TEXT;
    }

    private String installedVersion() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(PKG, 0);
            return pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (Throwable t) { return "未安装"; }
    }

    // ---------------- 权限与 Shizuku ----------------

    private boolean hasAllFiles() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                return Environment.isExternalStorageManager();
            }
            return checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE")
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) { return false; }
    }

    private void requestStorage() {
        // 已有权限就不再打扰（否则每次启动都弹设置页，挡住界面）
        if (hasAllFiles()) { say("存储权限: 已具备"); return; }
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+ 用 MANAGE_EXTERNAL_STORAGE 才能真正读 /sdcard/Download
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
                say("请在系统设置里授予「所有文件访问权限」（用于扫描 Download 目录）");
            } catch (Throwable t) {
                say("无法打开权限页: " + t);
            }
        } else {
            requestPermissions(new String[]{"android.permission.READ_EXTERNAL_STORAGE"}, REQ_STORAGE);
        }
    }

    private void ensureShizuku() {
        try {
            if (Shizuku.getBinder() == null) {
                refreshShizukuState();
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bind();
            else Shizuku.requestPermission(REQ_SHIZUKU);
        } catch (Throwable t) { say("Shizuku 检查失败: " + t); }
        refreshShizukuState();
    }

    /**
     * 查**真实**状态并刷新那一行字。
     *
     * 之前只在「用户服务连上」时写一次文案，于是 Shizuku 服务停了 / 权限被撤了，
     * 界面还停在「已连接」。这里每次都实地判断：
     *   装了没 → binder 在不在 → 服务真的能通吗（pingBinder + 一次真实调用）→ 授权了吗 → 用户服务连上了吗
     */
    private void refreshShizukuState() {
        String text;
        int color;
        try {
            boolean installed = true;
            try {
                getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            } catch (Throwable t) { installed = false; }

            if (!installed) {
                text = "没有 Shizuku · 用系统安装器";
                color = Ui.AMBER;
            } else if (Shizuku.getBinder() == null) {
                text = "Shizuku 未运行 · 用系统安装器";
                color = Ui.AMBER;
            } else if (!shizukuServerAlive()) {
                text = "Shizuku 未运行 · 用系统安装器";
                color = Ui.AMBER;
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                text = "Shizuku 未授权 · 点这里授权";
                color = Ui.AMBER;
            } else if (installer == null) {
                text = "Shizuku 已运行 · 正在连接安装服务…";
                color = Ui.TEXT_DIM;
                // 别每 2 秒狂 bind 一次，隔 6 秒再试
                long now = System.currentTimeMillis();
                if (now - lastBindAttempt > 6000) {
                    lastBindAttempt = now;
                    bind();
                }
            } else {
                text = "Shizuku 已连接 · 会话安装就绪";
                color = Ui.GREEN;
            }
        } catch (Throwable t) {
            text = "Shizuku 状态未知";
            color = Ui.TEXT_FAINT;
        }
        final String ft = text;
        final int fc = color;
        runOnUiThread(() -> {
            if (chipEnv == null) return;
            chipEnv.setText(ft);
            chipEnv.setTextColor(fc);
        });
    }

    /**
     * 新设备首次使用必须给的「安装未知应用」权限。
     * 没给的话，发起安装时系统会中途弹窗打断（平板实测），这里提前提示并一键跳转授权。
     */
    private void refreshInstallPermission() {
        boolean ok;
        try {
            ok = getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            ok = true;
        }
        final boolean granted = ok;
        runOnUiThread(() -> {
            if (chipInstall == null) return;
            if (granted) {
                chipInstall.setVisibility(android.view.View.GONE);
            } else {
                chipInstall.setVisibility(android.view.View.VISIBLE);
                chipInstall.setText("未授权「安装未知应用」 · 点这里授权（否则装到一半会被系统打断）");
                chipInstall.setTextColor(Ui.AMBER);
            }
        });
    }

    private void openInstallPermission() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            say("打不开授权页: " + t);
        }
    }

    /** binder 存在不代表服务在跑：真调一次才算数。 */
    private boolean shizukuServerAlive() {
        try {
            if (!Shizuku.pingBinder()) return false;
            Shizuku.getUid();          // 真实调用，服务没跑会抛
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void bind() {
        try { Shizuku.bindUserService(svcArgs, svcConn); }
        catch (Throwable t) { say("绑定安装服务失败: " + t); }
    }

    // ---------------- 输入来源 ----------------

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            new Thread(() -> {
                try {
                    say("正在读取所选文件…");
                    File tmp = new File(getCacheDir(), "picked.apk");
                    try (InputStream in = getContentResolver().openInputStream(data.getData());
                         FileOutputStream o = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[1 << 16];
                        int n; long total = 0;
                        while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); total += n; }
                        say("   已读取 " + ApkMirror.human(total));
                    }
                    startUpdate(tmp);
                } catch (Throwable t) { say("读取失败: " + t); }
            }).start();
        }
    }

    /** 扫描 Download 等目录里的 TikTok 安装包，按「分卷包优先 + 最新修改」排序。 */
    private List<File> scanLocalBundles() {
        List<File> found = new ArrayList<>();
        File[] dirs = {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                new File("/sdcard/Download"),
                new File("/sdcard/Downloads"),
                new File("/sdcard/BaiduNetdisk"),
                new File("/sdcard/Android/data/com.vivo.browser/files/Download"),
        };
        for (File d : dirs) {
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                String n = f.getName().toLowerCase();
                if (f.isFile() && (n.endsWith(".apk") || n.endsWith(".apkm") || n.endsWith(".xapk"))
                        && f.length() > 50L * 1024 * 1024
                        && (n.contains("musically") || n.contains("tiktok"))) {
                    if (!found.contains(f)) found.add(f);
                }
            }
        }
        found.sort((a, b) -> {
            boolean ab = isBundleName(a), bb = isBundleName(b);
            if (ab != bb) return ab ? -1 : 1;
            return Long.compare(b.lastModified(), a.lastModified());
        });
        return found;
    }

    /** 本地最新的一个安装包（没有就返回 null）。 */
    private File newestLocalBundle() {
        List<File> found = scanLocalBundles();
        return found.isEmpty() ? null : found.get(0);
    }

    /** 简单确认框（框架 AlertDialog，跟随深色主题）。 */
    private void confirm(String title, String message, Runnable onYes) {
        runOnUiThread(() -> new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("继续安装", (d, w) -> onYes.run())
                .setNegativeButton("取消", (d, w) -> {
                    busy = false;
                    setButtons(true);
                    phase(-1, "已取消", "点下面的按钮重新开始");
                })
                .show());
    }

    private void setLatestChip(String ver) {
        runOnUiThread(() -> {
            if (chipLatest == null) return;
            if (ver == null) {
                chipLatest.setText("最新版 未检查");
                chipLatest.setTextColor(Ui.TEXT_FAINT);
            } else {
                boolean newer = ApkMirror.compareVersions(ver, installedShortVersion()) > 0;
                chipLatest.setText("最新 v" + ver);
                chipLatest.setTextColor(newer ? Ui.CYAN : Ui.GREEN);
            }
        });
    }

    /** 兜底入口：先扫 Download，扫不到再让用户手选文件。 */
    private void fromLocal() {
        if (busy) return;
        if (stopIfOfficialBuild(() -> fromLocal())) return;
        busy = true;
        setButtons(false);
        phase(0, "① 查找本地安装包", "扫描 Download…");
        new Thread(() -> {
            try {
                List<File> found = scanLocalBundles();
                if (found.isEmpty()) {
                    say("Download 里没找到 TikTok 的安装包，请手动选择文件。");
                    busy = false;
                    setButtons(true);
                    phase(0, "需要手动选文件", "请在弹出的选择器里挑 .apkm");
                    pickFile();
                    return;
                }
                File newest = found.get(0);
                say("找到: " + newest.getName() + "  (" + ApkMirror.human(newest.length()) + ")");
                String lv = ApkMirror.versionFromFileName(newest.getName());
                String installed = installedShortVersion();
                say("本地包版本: " + (lv == null ? "未知" : lv) + "，已安装: " + installed);
                busy = false;
                int cmp = lv == null ? 1 : ApkMirror.compareVersions(lv, installed);
                if (cmp > 0) {
                    phase(1, "② 使用本地安装包", "v" + lv);
                    startUpdate(newest);
                } else if (cmp == 0) {
                    confirm("版本相同", "本地包和已装版本都是 " + installed
                            + "，仍要重新安装一遍吗？", () -> startUpdate(newest));
                } else {
                    confirm("本地包更旧", "本地包是 " + lv + "，比已装的 " + installed
                            + " 旧，仍要安装吗？", () -> startUpdate(newest));
                }
            } catch (Throwable t) {
                say("扫描失败: " + t);
                busy = false;
                setButtons(true);
            }
        }).start();
    }

    /** 主流程：自动检查 → 下载 → 打包 → 安装；网络不通时自动转本地兜底。 */
    private void oneClick() {
        if (busy) return;
        if (stopIfOfficialBuild(() -> oneClick())) return;
        busy = true;
        setButtons(false);
        phase(0, "① 检查最新版", "查询 APKMirror…");
        new Thread(() -> {
            try {
                ApkMirror.Progress p = new ApkMirror.Progress() {
                    @Override public void onStage(String t) {
                        say(t);
                        if (t.contains("下载")) phase(1, "② 下载分卷包", "连接中…");
                    }
                    @Override public void onBytes(long d, long t) {
                        if (t > 0) say("   " + ApkMirror.human(d) + " / " + ApkMirror.human(t));
                        downloadProgress(d, t);
                    }
                };
                String latest = ApkMirror.versionFromUrl(ApkMirror.latestVersionUrl());
                String installed = installedShortVersion();
                say("APKMirror 最新版本: " + latest + "，已安装: " + installed);
                setLatestChip(latest);
                if (latest != null && ApkMirror.compareVersions(latest, installed) <= 0) {
                    say("✅ 已是最新版本（" + installed + "），无需更新");
                    busy = false;
                    setButtons(true);
                    phase(4, "已是最新版本", "v" + installed);
                    return;
                }
                phase(1, "② 下载分卷包", "最新版 " + latest);
                String direct = ApkMirror.resolveDirect(p);
                File dl = new File(getCacheDir(), "tiktok-download.apkm");
                ApkMirror.download(direct, dl, p);
                say("   已下载 " + ApkMirror.human(dl.length()));
                if (!looksLikeBundle(dl)) {
                    say("⚠️ 下到的是单体 APK（" + ApkMirror.human(dl.length())
                            + "），建议改用「本地安装包」选分卷包 .apkm");
                }
                busy = false;          // startUpdate 自己会重新置位
                startUpdate(dl);
            } catch (Throwable t) {
                say("检查更新失败: " + t);
                say("   多半是手机访问不到 APKMirror（DNS 被污染）。");
                say("   → 把「SIM US Updater」加进 NekoBox 的分应用代理后重试；");
                say("   → 或把 .apkm 放进 Download，点「本地安装包」");
                setLatestChip(null);
                busy = false;
                setButtons(true);
                phase(0, "检查失败", "网络不通（见日志）");
                File local = newestLocalBundle();
                String lv = local == null ? null : ApkMirror.versionFromFileName(local.getName());
                if (lv != null && ApkMirror.compareVersions(lv, installedShortVersion()) > 0) {
                    say("本地已有更新的安装包: " + local.getName() + "（" + lv + "）→ 直接用它");
                    startUpdate(local);
                } else {
                    say("本地也没有比已装版本更新的包，已停下（不会拿旧包装一遍）");
                }
            }
        }).start();
    }

    // ---------------- 打包 + 安装 ----------------

    private synchronized void startUpdate(File src) {
        if (busy) { say("已有任务在运行"); return; }
        busy = true;
        setButtons(false);
        new Thread(() -> {
            try {
                boolean bundle = looksLikeBundle(src);
                say("判定: " + src.getName() + " → " + (bundle ? "分卷包" : "单体 APK"));

                phase(2, "③ 端上打包", bundle ? "解包分卷…" : "读取安装包…");
                if (bundle) updateFromBundle(src);
                else updateFromMonolith(src);
            } catch (Throwable t) {
                Log.e(TAG, "update failed", t);
                say("失败: " + t);
            } finally {
                busy = false;
                setButtons(true);
            }
        }).start();
    }

    /** 输出目录：必须是公共区，shell 身份的安装服务读不到 App 私有目录。 */
    private File outRoot() {
        File d = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "simus-out");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /**
     * 中间产物指纹：PATCH_SCHEMA + 内嵌模块的（长度 + SHA-256 前 8 字节）。
     * 只要换了 App 版本/模块/补丁规则，指纹就变 → 强制重新打包，不会误用旧产物。
     */
    private String patchFingerprint() {
        StringBuilder sb = new StringBuilder("schema=").append(PATCH_SCHEMA);
        java.io.InputStream in = null;
        try {
            in = getAssets().open("simspoof.apk");
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            long len = 0;
            int r;
            while ((r = in.read(buf)) > 0) {
                md.update(buf, 0, r);
                len += r;
            }
            byte[] d = md.digest();
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < 8; i++) h.append(String.format("%02x", d[i]));
            sb.append(";module=").append(len).append(':').append(h);
        } catch (Throwable t) {
            sb.append(";module=?");
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
        return sb.toString();
    }

    private static String readText(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 4096)];
            int n = in.read(b);
            return n <= 0 ? "" : new String(b, 0, n, "UTF-8").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void writeText(File f, String s) throws java.io.IOException {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(s.getBytes("UTF-8"));
        }
    }

    private Patcher.Progress progressCb() {
        return new Patcher.Progress() {
            @Override public void onLog(String line) {
                if (line != null && !line.trim().isEmpty()) say("   [engine] " + line);
            }
            @Override public void onStage(String stage, int i, int n) {
                say("   · " + stage + " (" + i + "/" + n + ")");
            }
        };
    }

    /**
     * 分卷包：只给 base 打补丁 → 把 ABI 分卷合进 base 合成**单体 APK** → 签名 → 安装。
     *
     * 为什么必须合成单体（2026-10-04 实测结论）：这台 vivo 的安装器
     * （com.android.packageinstaller.PackageInterceptActivity）**处理不了多 APK 会话**，
     * 无论走 Shizuku 的 pm 会话还是 App 自己的 PackageInstaller 会话，分卷安装都会弹
     * 「解析软件包时出现问题」→ INSTALL_FAILED_ABORTED；而单体 APK 到 213 MB 都能走完
     * 它自己的确认界面（点「已了解…」+「继续安装」即可），且签名一致所以数据保留。
     */
    private void updateFromBundle(File bundle) throws Exception {
        File work = new File(outRoot(), "bundle-work");
        File finalDir = new File(work, "final");
        File baseOut = new File(finalDir, "base.apk");
        File marker = new File(finalDir, ".session-ok");
        int have = 0;
        File[] fs = finalDir.listFiles();
        if (fs != null) for (File f : fs) if (isApk(f)) have++;
        // 上次已经打好、只是安装那一步没成：直接复用。
        // 但必须比对**补丁指纹**（PATCH_SCHEMA + 内嵌模块哈希）——否则改了打包/manifest
        // 逻辑或换了模块之后，会把旧中间产物当成新的装上去（踩过一次：竖屏锁的改动没生效）。
        String fp = patchFingerprint();
        if (marker.exists() && baseOut.exists() && have > 1
                && fp.equals(readText(marker))
                && baseOut.lastModified() > bundle.lastModified()) {
            say("复用上次打好的分卷（" + have + " 个 · 指纹一致）");
            installSplitSession(finalDir);
            return;
        }
        if (work.exists()) deleteTree(work);
        File raw = new File(work, "raw");
        List<File> parts = extractBundle(bundle, raw);
        if (parts.size() < 2) {
            say("⚠️ 只解出 " + parts.size() + " 个 APK，退回单体流程");
            updateFromMonolith(bundle);
            return;
        }
        File base = pickBase(parts);
        say("base = " + base.getName() + "（" + ApkMirror.human(base.length()) + "），其余分卷 "
                + (parts.size() - 1) + " 个");
        phase(2, "③ 端上打包", "已解出 " + parts.size() + " 个分卷");
        long free = new File(getFilesDir().getParentFile(), "").getUsableSpace();
        say("可用存储: " + ApkMirror.human(free));

        Patcher patcher = new Patcher(this, progressCb());
        File patchOut = new File(work, "patched");
        say("开始给 base 打补丁（内嵌 simspoof 模块）…");
        long t0 = System.currentTimeMillis();
        List<File> produced = patcher.patch(
                Collections.singletonList(base), patcher.moduleFile(), patchOut);
        File patchedBase = null;
        for (File f : produced) if (isApk(f) && f.length() > 1024 * 1024) patchedBase = f;
        if (patchedBase == null) throw new Exception("引擎没有产出补丁后的 base");
        say("补丁 base 完成：" + ApkMirror.human(patchedBase.length())
                + "，用时 " + ((System.currentTimeMillis() - t0) / 1000) + " 秒");
        if (new File(outRoot(), ".keep-intermediates").exists()) {
            try {
                File keep = new File(outRoot(), "patched-base.apk");
                copyTo(patchedBase, keep);
                say("   已保留补丁 base: " + keep.getAbsolutePath());
            } catch (Throwable t) { say("   保留补丁 base 失败: " + t); }
        }

        // manifest 用**原版**重建：LSPatch 自己写的那份会被 vivo 安装器判「解析软件包时出现问题」
        byte[] stockManifest = ApkMerger.readEntry(base, "AndroidManifest.xml");
        byte[] configJson = ApkMerger.readEntry(patchedBase, "assets/lspatch/config.json");
        byte[] newManifest = null;
        if (stockManifest != null) {
            String meta = configJson == null ? null
                    : android.util.Base64.encodeToString(configJson, android.util.Base64.NO_WRAP);
            newManifest = ApkMerger.buildPatchedManifest(stockManifest, meta);
            say("   已用原版 manifest 重建（vivo 安装器只认这种）");
        }
        File unsignedBase = new File(work, "base-unsigned.apk");
        ApkMerger.merge(patchedBase, Collections.emptyList(), unsignedBase, newManifest,
                line -> {});
        deleteTree(patchOut);

        ApkResigner resigner = new ApkResigner(this, Patcher.KS_ASSET, Patcher.KS_PASS, Patcher.KS_ALIAS);
        say("签名（v1 + v2，与已装版本同一密钥）…");
        finalDir.mkdirs();
        resigner.resign(unsignedBase, baseOut);
        makeReadable(baseOut);
        // noinspection ResultOfMethodCallIgnored
        unsignedBase.delete();

        // 其余分卷逐个重签（Android 要求同一 split 家族签名一致）
        int total = parts.size() - 1;
        int n = 0;
        long t1 = System.currentTimeMillis();
        for (File p : parts) {
            if (p.equals(base)) continue;
            File dst = new File(finalDir, p.getName());
            resigner.resign(p, dst);
            makeReadable(dst);
            n++;
            if (n % 25 == 0) say("   已重签 " + n + "/" + total);
        }
        say("分卷就绪：" + (n + 1) + " 个，用时 " + ((System.currentTimeMillis() - t1) / 1000) + " 秒");
        phase(2, "③ 端上打包", "完成 · 用时 " + ((System.currentTimeMillis() - t1) / 1000) + " 秒");
        try { writeText(marker, fp); } catch (Throwable ignored) {}
        deleteTree(raw);
        installSplitSession(finalDir);
    }

    /** 用会话安装「base + 各分卷」——这样密度/语言/功能分卷都在，App 才能完整运行。 */
    private void checkInstallPermissionOrWarn() {
        try {
            if (!getPackageManager().canRequestPackageInstalls()) {
                say("⚠️ 还没授权「安装未知应用」，系统会在安装时打断");
                say("   已帮你打开授权页：打开「允许来自此来源的应用」再回来点一次即可");
                openInstallPermission();
            }
        } catch (Throwable ignored) {
        }
    }

    private void installSplitSession(File dir) {
        checkInstallPermissionOrWarn();
        File[] fs = dir.listFiles();
        int cnt = 0;
        long sum = 0;
        if (fs != null) {
            for (File f : fs) {
                if (isApk(f)) { cnt++; sum += f.length(); }
            }
        }
        say("安装 " + cnt + " 个分卷（合计 " + ApkMirror.human(sum) + "）…");
        phase(3, "④ 安装分卷", cnt + " 个 · " + ApkMirror.human(sum));
        if (waitInstaller(8)) {
            new Thread(() -> {
                try {
                    String r = installer.installBundle(dir.getAbsolutePath(), true);
                    say(r.replace("\n", "\n   "));
                    String v = installedVersion();
                    say("安装后版本: " + v);
                    boolean bad = r == null || r.contains("Failure") || r.contains("User rejected")
                            || r.contains("失败");
                    busy = false;
                    setButtons(true);
                    if (bad) {
                        phase(3, "安装未完成", "请看上方日志");
                    } else {
                        phase(4, "安装成功", "TikTok " + v);
                        say("✅ 安装成功，当前版本: " + v);
                    }
                } catch (Throwable t) {
                    say("Shizuku 会话安装失败: " + t);
                    busy = false;
                    setButtons(true);
                    phase(3, "安装失败", "见日志");
                    try {
                        AppInstaller.install(this, dir, PKG, line -> say("   " + line));
                    } catch (Throwable t2) { say("系统安装会话也失败: " + t2); }
                }
            }).start();
            return;
        }
        say("没有 Shizuku → 改用系统安装器");
        say("   安装时请在系统界面点「已了解应用的风险检测结果」+「继续安装」");
        try {
            AppInstaller.install(this, dir, PKG, line -> say("   " + line));
        } catch (Throwable t) { say("系统安装会话失败: " + t); }
    }

    /** 安装结果广播。 */
    private final android.content.BroadcastReceiver installResult =
            new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) {
            int status = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999);
            String msg = i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (status == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // 系统把「确认安装」界面作为 Intent 交回来，必须由我们拉起来
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(confirm);
                        say("请在系统界面上点「已了解应用的风险检测结果」+「继续安装」");
                    } catch (Throwable t) { say("无法打开安装确认界面: " + t); }
                } else {
                    say("系统未提供安装确认界面（EXTRA_INTENT 为空）");
                }
                return;
            }
            say("安装结果: status=" + status + (msg == null ? "" : " / " + msg));
            if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                say("✅ 安装成功，当前版本: " + installedVersion());
                say("请打开 TikTok 确认美国区正常。");
                busy = false;
                setButtons(true);
                phase(4, "安装成功", "TikTok " + installedVersion());
            } else if (msg != null && msg.contains("UPDATE_INCOMPATIBLE")) {
                say("⚠️ 装不上：设备上是**官方版** TikTok，签名和补丁版不同");
                say("   → 需要先卸载官方版（会丢 App 内数据）再装补丁版");
                busy = false;
                setButtons(true);
                phase(3, "签名不一致", "需先卸载官方版");
            } else {
                say("⚠️ 安装未完成（status " + status + "）");
                busy = false;
                setButtons(true);
                phase(3, "安装未完成", "status " + status);
            }
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceived = () -> {
        say("Shizuku 服务已就绪");
        ensureShizuku();
        refreshShizukuState();
    };

    private final Shizuku.OnBinderDeadListener binderDead = () -> {
        installer = null;
        say("Shizuku 服务已停止");
        refreshShizukuState();
    };

    /** 卸载完成后继续之前挂起的流程。 */
    private final android.content.BroadcastReceiver packageRemoved =
            new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) {
            if (i == null || i.getData() == null) return;
            if (!PKG.equals(i.getData().getSchemeSpecificPart())) return;
            Runnable r = afterUninstall;
            afterUninstall = null;
            if (r != null) {
                say("检测到官方版已卸载");
                r.run();
            }
        }
    };

    private void registerInstallResult() {
        IntentFilter f = new IntentFilter(AppInstaller.ACTION_RESULT);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(installResult, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(installResult, f);
            }
        } catch (Throwable t) { Log.w(TAG, "registerReceiver: " + t); }
    }

    /**
     * 清掉上次遗留的安装会话。
     * 不清的话：系统的「确认安装」界面会因为那个挂着的老会话反复弹出来，
     * 而且它是模态的，会把触摸都吃掉，连本 App 的按钮都点不到。
     */
    private void abandonStaleSessions() {
        new Thread(() -> {
            try {
                android.content.pm.PackageInstaller pi = getPackageManager().getPackageInstaller();
                for (android.content.pm.PackageInstaller.SessionInfo si : pi.getMySessions()) {
                    try {
                        pi.abandonSession(si.getSessionId());
                        say("已清理遗留安装会话 " + si.getSessionId());
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable t) { Log.w(TAG, "abandonStaleSessions: " + t); }
        }).start();
    }

    /**
     * 安装合成好的单体包。
     *
     * 首选 Shizuku 的 `pm install -r`（会把包先复制到 /data/local/tmp）：实测这条通道
     * 对单体包能正常走完 vivo 的「继续安装」界面并装成功（213 MB 实测）。
     * 没有 Shizuku 时才用 App 自己的 PackageInstaller 会话。
     */
    private void installSingle(File apk) {
        say("安装 " + apk.getName() + "（" + ApkMirror.human(apk.length()) + "）…");
        if (waitInstaller(8)) {
            new Thread(() -> {
                try {
                    String r = installer.installFrom(apk.getAbsolutePath(), true);
                    say(r.replace("\n", "\n   "));
                    say("安装后版本: " + installedVersion());
                } catch (Throwable t) {
                    say("Shizuku 安装失败: " + t);
                }
            }).start();
            return;
        }
        say("Shizuku 未连接，改用系统安装会话（需要手动点确认）");
        try {
            AppInstaller.installFiles(this, Collections.singletonList(apk), PKG,
                    line -> say("   " + line));
        } catch (Throwable t) {
            say("系统安装会话失败: " + t);
        }
    }

    /** 单体 APK：直接补丁 → 安装（几百 MB 的包 vivo 安装器可能拒收）。 */
    private void updateFromMonolith(File apk) throws Exception {
        long free = new File(getFilesDir().getParentFile(), "").getUsableSpace();
        say("可用存储: " + ApkMirror.human(free));
        if (free < apk.length() * 2) {
            say("⚠️ 空间可能不足（需要约 " + ApkMirror.human(apk.length() * 2) + "）");
        }
        Patcher patcher = new Patcher(this, progressCb());
        File outDir = outRoot();
        List<File> targets = new ArrayList<>();
        targets.add(apk);

        say("开始端上重打包…");
        long t0 = System.currentTimeMillis();
        List<File> produced = patcher.patch(targets, patcher.moduleFile(), outDir);
        say("打包成功！用时 " + ((System.currentTimeMillis() - t0) / 1000) + " 秒");
        ApkResigner resigner = new ApkResigner(this, Patcher.KS_ASSET, Patcher.KS_PASS, Patcher.KS_ALIAS);
        for (File f : produced) {
            if (!isApk(f)) continue;
            say("   产出: " + f.getName() + "  " + ApkMirror.human(f.length()));
            // 引擎产物只有 v2 签名，补 v1（见 ApkResigner 注释）
            File signed = new File(outDir, f.getName().replaceAll("\\.apk$", "") + "-v1v2.apk");
            resigner.resign(f, signed);
            makeReadable(signed);
            install(signed);
        }
    }

    private boolean isBundleName(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".apkm") || n.endsWith(".xapk") || n.endsWith(".apks");
    }

    /** .apkm/.xapk 或内部含 base.apk 的 zip 视为分卷包。 */
    private boolean looksLikeBundle(File f) {
        String n = f.getName().toLowerCase();
        if (n.endsWith(".apkm") || n.endsWith(".xapk") || n.endsWith(".apks")) return true;
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(f)) {
            return z.getEntry("base.apk") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 解出分卷包里的全部 .apk。 */
    private List<File> extractBundle(File bundle, File outDir) {
        List<File> out = new ArrayList<>();
        if (outDir.exists()) deleteTree(outDir);
        outDir.mkdirs();
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(bundle)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                String name = e.getName();
                if (e.isDirectory() || !name.toLowerCase().endsWith(".apk")) continue;
                File dst = new File(outDir, new File(name).getName());
                try (InputStream in = z.getInputStream(e);
                     FileOutputStream o = new FileOutputStream(dst)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                }
                out.add(dst);
            }
            say("   解包 " + out.size() + " 个分卷到 " + outDir.getName());
        } catch (Throwable t) {
            say("解包失败: " + t);
        }
        return out;
    }

    /** 找 base：优先名叫 base.apk 的，其次最小的（base 不带 config）。 */
    private File pickBase(List<File> parts) {
        for (File f : parts) if (f.getName().equals("base.apk")) return f;
        return parts.get(0);
    }

    private void copyTo(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src);
             FileOutputStream o = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
    }

    private void deleteTree(File f) {
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) deleteTree(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** 等 Shizuku 用户服务连上（bind 是异步的，第一次调用常常还没连上）。 */
    private boolean waitInstaller(int seconds) {
        if (installer != null) return true;
        try { ensureShizuku(); } catch (Throwable ignored) {}
        for (int i = 0; i < seconds * 2 && installer == null; i++) {
            try { Thread.sleep(500); } catch (InterruptedException ignored) { break; }
        }
        return installer != null;
    }

    private void openShizuku() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (i == null) return;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) { Log.w(TAG, "openShizuku: " + t); }
    }

    private boolean isApk(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".apk") || n.endsWith(".apks");
    }

    private void install(File apk) {
        say("安装 " + apk.getName() + " …");
        if (!waitInstaller(3)) {
            say("⚠️ Shizuku 安装服务未连接，交给系统安装器（请点「安装」）");
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable t) {
                say("启动安装器失败: " + t + "\n文件位置: " + apk.getAbsolutePath());
            }
            return;
        }
        new Thread(() -> {
            try {
                String r = installer.installFrom(apk.getAbsolutePath(), true);
                say(r.replace("\n", "\n   "));
                String v = installedVersion();
                say("安装后版本: " + v);
                runOnUiThread(() -> {
                    busy = false;
                    setButtons(true);
                    boolean bad = r == null || r.contains("Failure") || r.contains("User rejected");
                    phase(bad ? 3 : 4, bad ? "安装未完成" : "安装成功", "TikTok " + v);
                });
            } catch (Throwable t) { say("安装调用失败: " + t); }
        }).start();
    }

    private void makeReadable(File f) {
        try {
            Class<?> c = Class.forName("android.os.FileUtils");
            Method m = c.getMethod("setPermissions", File.class, int.class, int.class, int.class);
            m.invoke(null, f, 0644, -1, -1);
            File dir = f.getParentFile();
            if (dir != null) {
                m.invoke(null, dir, 0755, -1, -1);
                if (dir.getParentFile() != null) m.invoke(null, dir.getParentFile(), 0755, -1, -1);
            }
        } catch (Throwable t) { Log.w(TAG, "setPermissions: " + t); }
    }

    private void setButtons(boolean enabled) {
        runOnUiThread(() -> {
            btnMain.setEnabled(enabled);
            btnLocal.setEnabled(enabled);
            btnMain.setAlpha(enabled ? 1f : 0.55f);
            btnLocal.setAlpha(enabled ? 1f : 0.55f);
            spinner.setVisibility(enabled ? android.view.View.GONE : android.view.View.VISIBLE);
            progress.setVisibility(enabled ? android.view.View.GONE : android.view.View.VISIBLE);
            if (!enabled) {
                progress.setIndeterminate(true);
            } else {
                progress.setIndeterminate(false);
                progress.setProgress(0);
            }
        });
    }
}
