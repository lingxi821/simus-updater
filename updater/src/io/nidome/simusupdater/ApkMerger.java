package io.nidome.simusupdater;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import pxb.android.axml.AxmlReader;
import pxb.android.axml.AxmlVisitor;
import pxb.android.axml.AxmlWriter;
import pxb.android.axml.NodeVisitor;

/**
 * 把「补丁 base + ABI 分卷」合成一个**单体 APK**。
 *
 * 为什么必须合成单体：实测这台 vivo（PD2344 / Android 14）的安装器
 * （com.android.packageinstaller.PackageInterceptActivity）**处理不了多 APK 会话** ——
 * 任何分卷安装，无论走 Shizuku 的 pm 会话还是 App 自己的 PackageInstaller 会话，
 * 都会弹「解析软件包时出现问题」并以 INSTALL_FAILED_ABORTED 收场；
 * 而单体 APK（实测到 213 MB）能正常走完它的安装界面。
 *
 * 合成做两件事：
 *  ① manifest 里去掉 {@code android:requiredSplitTypes}（否则 base 单独安装会
 *     INSTALL_FAILED_MISSING_SPLIT），并确保 {@code extractNativeLibs=true}
 *     （这样 .so 可以压缩存放，不必做页对齐）；
 *  ② 把 ABI 分卷里的 {@code lib/<abi>/*.so} 全部塞进 base。
 *
 * 密度/语言/df 分卷不合并：缺少它们时系统按默认配置回退，App 仍可运行。
 */
public class ApkMerger {

    public interface Progress {
        void onLog(String line);
    }

    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";

    /**
     * @param baseApk  已打补丁的 base APK
     * @param abiSplits 要合入的 ABI 分卷（可多个，按顺序覆盖同名条目）
     * @param out      输出的单体 APK
     * @param manifestOverride 非空时用它替换输出里的 AndroidManifest.xml
     * @param cb       日志回调
     */
    public static void merge(File baseApk, List<File> abiSplits, File out,
                             byte[] manifestOverride, Progress cb) throws Exception {
        byte[] buf = new byte[1 << 16];
        Set<String> written = new HashSet<>();
        int libs = 0;
        Counting out0 = new Counting(new BufferedOutputStream(new FileOutputStream(out), 1 << 16));
        try (ZipFile base = new ZipFile(baseApk);
             ZipOutputStream zos = new ZipOutputStream(out0)) {
            Enumeration<? extends ZipEntry> en = base.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (isSignatureEntry(name)) continue;      // 旧签名丢掉，最后统一重签
                if (name.equals("AndroidManifest.xml")) {
                    byte[] edited = manifestOverride != null
                            ? manifestOverride
                            : editManifest(readAll(base.getInputStream(e)));
                    putBytes(zos, name, e.getTime(), edited, out0);
                    written.add(name);
                    continue;
                }
                copyEntry(zos, e, base.getInputStream(e), buf, out0);
                written.add(name);
            }
            for (File split : abiSplits) {
                if (split == null || !split.exists()) continue;
                try (ZipFile z = new ZipFile(split)) {
                    Enumeration<? extends ZipEntry> es = z.entries();
                    while (es.hasMoreElements()) {
                        ZipEntry e = es.nextElement();
                        String name = e.getName();
                        if (!name.startsWith("lib/") || !name.endsWith(".so")) continue;
                        if (written.contains(name)) continue;   // base 里已有同名的就不覆盖
                        ZipEntry ne = new ZipEntry(name);
                        ne.setTime(e.getTime());
                        zos.putNextEntry(ne);                   // DEFLATED：base 是 extractNativeLibs=true
                        copy(z.getInputStream(e), zos, buf);
                        zos.closeEntry();
                        written.add(name);
                        libs++;
                    }
                }
            }
        }
        cb.onLog("合入 " + libs + " 个 .so；单体包大小 " + human(out.length()));
    }

    // ---------------- manifest 改写 ----------------

    /** LSPatch 的 AppComponentFactory（loader 的入口）。 */
    static final String PROXY_APP_COMPONENT_FACTORY =
            "org.lsposed.lspatch.metaloader.LSPAppComponentFactoryStub";

    /** 从 APK 里读一个条目的全部字节。 */
    public static byte[] readEntry(File apk, String name) throws IOException {
        try (ZipFile z = new ZipFile(apk)) {
            ZipEntry e = z.getEntry(name);
            if (e == null) return null;
            return readAll(z.getInputStream(e));
        }
    }

    /**
     * 基于**原版 manifest** 重建补丁包要用的 manifest。
     *
     * 为什么不能直接用 LSPatch 写出来的那份：实测这台 vivo 的安装器
     * （com.android.packageinstaller.PackageInterceptActivity）对 LSPatch 重写的 manifest
     * 一律判「解析软件包时出现问题」；而拿原版 manifest 只施加下面这几处改动，它就能正常解析
     * （每一项、以及全部组合都单独验过）。
     *
     * 改动（等价于 LSPatch 的 modifyManifestFile）：
     *  ① requiredSplitTypes 置空 —— AOSP 的 ApkLiteParseUtils 把空值当作「没有这个属性」，
     *     否则单体包会被 PMS 判 INSTALL_FAILED_MISSING_SPLIT；
     *  ② minSdkVersion 提到 28（loader 是按 28 编译的）；
     *  ③ debuggable=false；
     *  ④ appComponentFactory 指向 LSPatch 的 Stub；
     *  ⑤ application 里补 <meta-data name="lspatch" value=base64(config.json)>（签名绕过要用）；
     *  ⑥ extractNativeLibs=true —— 合入的 .so 是压缩存放的，需要安装时解压。
     */
    public static byte[] buildPatchedManifest(byte[] stockManifest, String lspatchMeta)
            throws IOException {
        final AxmlWriter writer = new AxmlWriter();
        new AxmlReader(stockManifest).accept(new AxmlVisitor(writer) {
            @Override
            public NodeVisitor child(String ns, String name) {
                return edit(super.child(ns, name), name, lspatchMeta, true);
            }
        });
        return writer.toByteArray();
    }

    /**
     * 递归包装访问器。
     *
     * 坑：pxb 的访问器是逐层回调的 —— 顶层 visitor 只会收到**根元素**的 child()，
     * 根元素下面的 <uses-sdk>/<application> 要由「上一层返回的那个 visitor」再收一次。
     * 所以每层都必须继续把自己包下去，否则嵌套元素上的改动会静默失效。
     */
    private static NodeVisitor edit(final NodeVisitor nv, final String name,
                                    final String meta, final boolean loaderBits) {
        return new NodeVisitor(nv) {
            private boolean sawFactory, sawDebuggable, sawExtract;

            @Override
            public void attr(String ans, String an, int rid, int type, Object value) {
                if ("manifest".equals(name) && "requiredSplitTypes".equals(an)) {
                    super.attr(ans, an, rid, type, "");
                    return;
                }
                if (loaderBits && "uses-sdk".equals(name) && "minSdkVersion".equals(an)) {
                    super.attr(ans, an, rid, type, 28);
                    return;
                }
                if ("application".equals(name)) {
                    if (loaderBits && "appComponentFactory".equals(an)) {
                        sawFactory = true;
                        super.attr(ans, an, rid, NodeVisitor.TYPE_STRING,
                                PROXY_APP_COMPONENT_FACTORY);
                        return;
                    }
                    if (loaderBits && "debuggable".equals(an)) {
                        sawDebuggable = true;
                        super.attr(ans, an, rid, NodeVisitor.TYPE_INT_BOOLEAN, Boolean.FALSE);
                        return;
                    }
                    if ("extractNativeLibs".equals(an)) {
                        sawExtract = true;
                        super.attr(ans, an, rid, NodeVisitor.TYPE_INT_BOOLEAN, Boolean.TRUE);
                        return;
                    }
                }
                super.attr(ans, an, rid, type, value);
            }

            @Override
            public NodeVisitor child(String ns, String cname) {
                return edit(super.child(ns, cname), cname, meta, loaderBits);
            }

            @Override
            public void end() {
                if ("application".equals(name)) {
                    if (loaderBits && !sawFactory) {
                        super.attr(NS_ANDROID, "appComponentFactory", 0x0101057a,
                                NodeVisitor.TYPE_STRING, PROXY_APP_COMPONENT_FACTORY);
                    }
                    if (loaderBits && !sawDebuggable) {
                        super.attr(NS_ANDROID, "debuggable", 0x0101000f,
                                NodeVisitor.TYPE_INT_BOOLEAN, Boolean.FALSE);
                    }
                    if (!sawExtract) {
                        super.attr(NS_ANDROID, "extractNativeLibs", 0x010104ea,
                                NodeVisitor.TYPE_INT_BOOLEAN, Boolean.TRUE);
                    }
                    if (loaderBits) {
                        NodeVisitor md = super.child(null, "meta-data");
                        md.attr(NS_ANDROID, "name", 0x01010003, NodeVisitor.TYPE_STRING, "lspatch");
                        md.attr(NS_ANDROID, "value", 0x01010024, NodeVisitor.TYPE_STRING,
                                meta == null ? "" : meta);
                        md.end();
                    }
                }
                super.end();
            }
        };
    }

    /** 把 requiredSplitTypes 的资源 ID 置 0 / splitTypes，并保证 extractNativeLibs=true。 */
    static byte[] editManifest(byte[] in) throws IOException {
        final AxmlWriter writer = new AxmlWriter();
        new AxmlReader(in).accept(new AxmlVisitor(writer) {
            @Override
            public NodeVisitor child(String ns, String name) {
                return edit(super.child(ns, name), name, null, false);
            }
        });
        return writer.toByteArray();
    }

    // ---------------- zip 工具 ----------------

    private static boolean isSignatureEntry(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String n = name.toUpperCase();
        return n.endsWith(".SF") || n.endsWith(".MF") || n.endsWith(".RSA") || n.endsWith(".DSA")
                || n.endsWith(".EC");
    }

    private static void putBytes(ZipOutputStream zos, String name, long time, byte[] data,
                                 Counting out) throws IOException {
        ZipEntry ne = new ZipEntry(name);
        ne.setTime(time);
        zos.putNextEntry(ne);
        zos.write(data);
        zos.closeEntry();
    }

    private static void copyEntry(ZipOutputStream zos, ZipEntry src, InputStream in, byte[] buf,
                                  Counting out) throws IOException {
        ZipEntry ne = new ZipEntry(src.getName());
        ne.setTime(src.getTime());
        if (src.getMethod() == ZipEntry.STORED) {
            ne.setMethod(ZipEntry.STORED);
            ne.setSize(src.getSize());
            ne.setCrc(src.getCrc());
            // 未压缩条目要按类型对齐：.so 必须页对齐（loader 会直接 dlopen APK 里的
            // assets/lspatch/so/*/liblspatch.so），resources.arsc 至少 4 字节，否则 Android 11+ 判坏包
            byte[] extra = alignExtra(out.count(), src.getName().getBytes("UTF-8").length,
                    alignmentFor(src.getName()));
            if (extra != null) ne.setExtra(extra);
        }
        zos.putNextEntry(ne);
        copy(in, zos, buf);
        zos.closeEntry();
    }

    /** 条目数据需要的对齐字节数（仅对未压缩条目有意义）。 */
    private static int alignmentFor(String name) {
        if (name.endsWith(".so")) return 16384;         // 4096/16384 页都能用
        if (name.endsWith("origin.apk")) return 4096;   // 与 apkzlib 的 AlignmentRules 一致
        return 4;
    }

    /**
     * 造一个「填充用」的 extra 字段，使该条目的数据偏移落在 align 的整数倍上。
     * extra 结构：[id(2) size(2) data(size)]，总长必须 ≥ 4，且长度差要是 align 的倍数。
     */
    private static byte[] alignExtra(long offset, int nameLen, int align) {
        int cur = (int) ((offset + 30 + nameLen) % align);
        if (cur == 0) return null;
        int need = align - cur;
        int total = need < 4 ? need + align : need;
        int size = total - 4;
        byte[] b = new byte[total];
        b[0] = (byte) 0xD9;                            // 任意 id，仅作填充
        b[1] = (byte) 0x35;
        b[2] = (byte) (size & 0xff);
        b[3] = (byte) ((size >> 8) & 0xff);
        return b;
    }

    /** 统计已写出字节数，用于计算 zip 条目数据偏移。 */
    static final class Counting extends java.io.FilterOutputStream {
        private long count;

        Counting(java.io.OutputStream out) {
            super(out);
        }

        long count() {
            return count;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }

    private static void copy(InputStream in, java.io.OutputStream out, byte[] buf)
            throws IOException {
        try (InputStream i = in) {
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    static String human(long b) {
        if (b > 1024L * 1024 * 1024) return String.format("%.2f GB", b / 1073741824.0);
        if (b > 1024L * 1024) return String.format("%.1f MB", b / 1048576.0);
        if (b > 1024) return String.format("%.1f KB", b / 1024.0);
        return b + " B";
    }

    /**
     * 选出要合入的 ABI 分卷：**只取本机主 ABI**。
     * 带上第二个 ABI（armeabi_v7a）会让单体包从约 210 MB 涨到约 260 MB，
     * 而实测 vivo 安装器对 213 MB 的单体包正常、更大的就不一定了。
     */
    public static List<File> pickAbiSplits(List<File> parts, String preferredAbi) {
        String[] want = {"arm64_v8a", "armeabi_v7a"};
        if (preferredAbi != null && preferredAbi.startsWith("x86_64")) want = new String[]{"x86_64"};
        else if (preferredAbi != null && preferredAbi.startsWith("x86")) want = new String[]{"x86"};
        else if (preferredAbi != null && preferredAbi.startsWith("armeabi")) want = new String[]{"armeabi_v7a"};
        for (String abi : want) {
            for (File f : parts) {
                if (f.getName().equals("split_config." + abi + ".apk")) {
                    List<File> one = new ArrayList<>();
                    one.add(f);
                    return one;
                }
            }
        }
        return new ArrayList<>();
    }
}
