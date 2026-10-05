package io.nidome.simusupdater;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * 手写 Binder 桩（不用 aidl 工具）。
 *
 * 同时是 Shizuku UserService 的实现类：Shizuku 会在 **shell 身份**的独立进程里
 * 实例化它，于是可以调用带 shell 权限的 `pm` 命令完成静默安装。
 *
 * 事务 1 = install(apkPath, replace) -> String(结果文本)
 */
public interface IInstaller extends IInterface {

    String DESCRIPTOR = "io.nidome.simusupdater.IInstaller";
    int TX_INSTALL = IBinder.FIRST_CALL_TRANSACTION + 0;
    int TX_INSTALL_FROM = IBinder.FIRST_CALL_TRANSACTION + 1;
    int TX_INSTALL_BUNDLE = IBinder.FIRST_CALL_TRANSACTION + 2;

    String install(String apkPath, boolean replace) throws RemoteException;

    /** 先复制到 /data/local/tmp 再安装（绕开 system_server 读不了 /sdcard 的限制）。 */
    String installFrom(String srcPath, boolean replace) throws RemoteException;

    /** 安装一个目录下的全部分卷（多 APK 会话）。 */
    String installBundle(String dirPath, boolean replace) throws RemoteException;

    /** Shizuku UserService 本体：跑在 shell 进程里。 */
    class Service extends Binder implements IInstaller {

        public Service() {
        }

        public Service(android.content.Context ctx) {
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            if (code == TX_INSTALL || code == TX_INSTALL_FROM || code == TX_INSTALL_BUNDLE) {
                data.enforceInterface(DESCRIPTOR);
                String apkPath = data.readString();
                boolean replace = data.readInt() != 0;
                String result;
                if (code == TX_INSTALL) result = install(apkPath, replace);
                else if (code == TX_INSTALL_FROM) result = installFrom(apkPath, replace);
                else result = installBundle(apkPath, replace);
                reply.writeNoException();
                reply.writeString(result);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        @Override
        public String install(String apkPath, boolean replace) {
            StringBuilder log = new StringBuilder();
            log.append("uid=").append(android.os.Process.myUid()).append('\n');
            log.append(runPm(apkPath, replace));
            return log.toString();
        }

        /**
         * 关键：真正执行安装的 system_server 读不了 /sdcard（FUSE + SELinux 拒绝），
         * 但能读 /data/local/tmp。shell 有权限写那里，所以先复制过去再装。
         */
        @Override
        public String installFrom(String srcPath, boolean replace) {
            StringBuilder log = new StringBuilder();
            log.append("uid=").append(android.os.Process.myUid()).append('\n');
            String dst = "/data/local/tmp/simus-install.apk";
            try {
                java.io.File src = new java.io.File(srcPath);
                if (!src.exists()) {
                    return log.append("源文件不存在: ").append(srcPath).append('\n').toString();
                }
                log.append("复制 ").append(human(src.length())).append(" → ").append(dst).append('\n');
                long t0 = System.currentTimeMillis();
                try (java.io.InputStream in = new java.io.FileInputStream(src);
                     java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                new java.io.File(dst).setReadable(true, false);
                log.append("复制完成 ").append((System.currentTimeMillis() - t0) / 1000).append("s\n");
                log.append(runPm(dst, replace));
            } catch (Throwable t) {
                log.append("复制失败: ").append(t).append('\n');
            } finally {
                try { new java.io.File(dst).delete(); } catch (Throwable ignored) {}
            }
            return log.toString();
        }

        /**
         * 多 APK 会话安装：分卷包必须整体安装（base + 各 config split）。
         * 用 pm install-create / install-write / install-commit 三步走。
         */
        @Override
        public String installBundle(String dirPath, boolean replace) {
            StringBuilder log = new StringBuilder();
            log.append("uid=").append(android.os.Process.myUid()).append('\n');
            java.io.File dir = new java.io.File(dirPath);
            java.io.File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                return log.append("目录为空或不可读: ").append(dirPath).append('\n').toString();
            }
            java.util.List<java.io.File> apks = new java.util.ArrayList<>();
            for (java.io.File f : files) {
                if (f.isFile() && f.getName().toLowerCase().endsWith(".apk")) apks.add(f);
            }
            if (apks.isEmpty()) return log.append("目录里没有 .apk\n").toString();
            // base 必须排在第一位
            apks.sort((a, b) -> {
                boolean ab = a.getName().equals("base.apk"), bb = b.getName().equals("base.apk");
                if (ab != bb) return ab ? -1 : 1;
                return a.getName().compareTo(b.getName());
            });
            log.append("分卷数: ").append(apks.size()).append('\n');

            String stage = "/data/local/tmp/simus-splits";
            try {
                java.io.File st = new java.io.File(stage);
                st.mkdirs();
                // 清空旧内容
                java.io.File[] old = st.listFiles();
                if (old != null) for (java.io.File f : old) f.delete();

                long t0 = System.currentTimeMillis();
                java.util.List<String> local = new java.util.ArrayList<>();
                for (java.io.File f : apks) {
                    java.io.File dst = new java.io.File(st, f.getName());
                    copy(f, dst);
                    local.add(dst.getAbsolutePath());
                }
                log.append("复制 ").append(local.size()).append(" 个分卷完成 ")
                   .append((System.currentTimeMillis() - t0) / 1000).append("s\n");

                // 1) create
                StringBuilder args = new StringBuilder("pm install-create -r");
                if (replace) args.append(" -d");
                args.append(" -S ").append(totalSize(apks));
                String r1 = exec(args.toString());
                log.append("install-create: ").append(r1.trim()).append('\n');
                String sid = extractSessionId(r1);
                if (sid == null) return log.append("无法解析 sessionId\n").toString();

                // 2) write 每个分卷
                int i = 0;
                for (String p : local) {
                    i++;
                    String r = exec("pm install-write -S " + new java.io.File(p).length()
                            + " " + sid + " " + i + " " + p);
                    if (r != null && r.contains("Failure")) {
                        log.append("write 失败(").append(i).append("): ").append(r.trim()).append('\n');
                    }
                }
                log.append("已写入 ").append(i).append(" 个分卷\n");

                // 3) commit
                log.append("install-commit: ").append(exec("pm install-commit " + sid).trim()).append('\n');
            } catch (Throwable t) {
                log.append("异常: ").append(t).append('\n');
            } finally {
                try {
                    java.io.File st = new java.io.File(stage);
                    java.io.File[] fs = st.listFiles();
                    if (fs != null) for (java.io.File f : fs) f.delete();
                } catch (Throwable ignored) {}
            }
            return log.toString();
        }

        private long totalSize(java.util.List<java.io.File> fs) {
            long t = 0;
            for (java.io.File f : fs) t += f.length();
            return t;
        }

        private String extractSessionId(String s) {
            if (s == null) return null;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\[(\\d+)\\]").matcher(s);
            if (m.find()) return m.group(1);
            m = java.util.regex.Pattern.compile("(\\d+)").matcher(s);
            return m.find() ? m.group(1) : null;
        }

        private String exec(String cmd) {
            try {
                Process p = Runtime.getRuntime().exec(cmd.split(" "));
                String out = readAll(p.getInputStream());
                String err = readAll(p.getErrorStream());
                p.waitFor();
                return (out == null ? "" : out) + (err == null ? "" : err);
            } catch (Throwable t) {
                return "exec 异常: " + t;
            }
        }

        private void copy(java.io.File src, java.io.File dst) throws java.io.IOException {
            try (java.io.InputStream in = new java.io.FileInputStream(src);
                 java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            dst.setReadable(true, false);
        }

        private String runPm(String path, boolean replace) {
            StringBuilder log = new StringBuilder();
            String[] cmd = replace
                    ? new String[]{"pm", "install", "-r", "-d", path}
                    : new String[]{"pm", "install", path};
            try {
                Process p = Runtime.getRuntime().exec(cmd);
                // 安装（可能带确认框）需要时间，等它真正结束，最多 120 秒
                String out = readAll(p.getInputStream());
                String err = readAll(p.getErrorStream());
                int rc = p.waitFor();
                log.append("rc=").append(rc).append('\n');
                if (out != null && !out.isEmpty()) log.append(out).append('\n');
                if (err != null && !err.isEmpty()) log.append(err).append('\n');
            } catch (Throwable t) {
                log.append("异常: ").append(t).append('\n');
            }
            return log.toString();
        }

        private static String human(long b) {
            if (b > 1048576) return String.format("%.1f MB", b / 1048576.0);
            if (b > 1024) return (b / 1024) + " KB";
            return b + " B";
        }

        private static String readAll(java.io.InputStream in) throws java.io.IOException {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }

    /** 客户端侧代理。 */
    class Proxy implements IInstaller {
        private final IBinder mRemote;

        public Proxy(IBinder remote) {
            mRemote = remote;
        }

        @Override
        public IBinder asBinder() {
            return mRemote;
        }

        @Override
        public String install(String apkPath, boolean replace) throws RemoteException {
            return tx(TX_INSTALL, apkPath, replace);
        }

        @Override
        public String installFrom(String srcPath, boolean replace) throws RemoteException {
            return tx(TX_INSTALL_FROM, srcPath, replace);
        }

        @Override
        public String installBundle(String dirPath, boolean replace) throws RemoteException {
            return tx(TX_INSTALL_BUNDLE, dirPath, replace);
        }

        private String tx(int code, String path, boolean replace) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(path);
                data.writeInt(replace ? 1 : 0);
                mRemote.transact(code, data, reply, 0);
                reply.readException();
                return reply.readString();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
    }

    static IInstaller asInterface(IBinder obj) {
        if (obj == null) return null;
        return new Proxy(obj);
    }
}
