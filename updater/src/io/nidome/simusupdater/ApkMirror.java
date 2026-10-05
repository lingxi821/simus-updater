package io.nidome.simusupdater;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * APKMirror 抓取：版本页 → 变体页 → downloadButton → 最终直链 → 下载。
 * 链路已在桌面实测跑通（见设计文档 §4）。
 */
public class ApkMirror {

    private static final String BASE = "https://www.apkmirror.com";

    static {
        // APKMirror 的下载流程依赖会话 cookie（桌面用 curl -c/-b 才跑通；
        // 不带 cookie 时下载页可能给不出 download.php 直链）
        try {
            java.net.CookieHandler.setDefault(new java.net.CookieManager());
        } catch (Throwable ignored) {
        }
    }
    private static final String UA =
            "Mozilla/5.0 (X11; Linux x86_64; rv:122.0) Gecko/20100101 Firefox/122.0";

    /** TikTok 正式版在 APKMirror 的固定路径片段。 */
    private static final String APP_PATH = "/apk/tiktok-pte-ltd/tik-tok-including-musical-ly/";

    public interface Progress {
        void onStage(String text);
        void onBytes(long done, long total);
    }

    /**
     * 取最新版本页链接，例如 .../tiktok-47-1-4-release/
     *
     * 注意：APKMirror 的 appcategory 页会返回 404，但 HTML 里仍带着版本链接，
     * 所以不能因状态码非 200 就放弃，要照常解析。
     */
    public static String latestVersionUrl() throws IOException {
        String html = getLenient(BASE + "/uploads/?appcategory=tiktok");
        // 页面上可能同时出现多个版本，取**版本号最大**的那个（不能靠顺序假设）
        Matcher m = Pattern.compile("href=\"(" + Pattern.quote(APP_PATH)
                + "tiktok-([0-9][0-9-]*)-release/)\"", Pattern.CASE_INSENSITIVE).matcher(html);
        String best = null, bestVer = null;
        while (m.find()) {
            String ver = m.group(2).replace('-', '.');
            if (bestVer == null || compareVersions(ver, bestVer) > 0) {
                bestVer = ver;
                best = m.group(1);
            }
        }
        if (best != null) return BASE + best;

        // 兜底：换用 APKMirror 的搜索接口
        String q = getLenient(BASE + "/?post_type=app_release&searchtype=apk&s=tiktok");
        Matcher m2 = Pattern.compile("href=\"(" + Pattern.quote(APP_PATH)
                + "tiktok-[0-9][0-9-]*-release/)\"", Pattern.CASE_INSENSITIVE).matcher(q);
        if (m2.find()) return BASE + m2.group(1);
        throw new IOException("找不到最新版本页（APKMirror 可能改版）");
    }

    /**
     * 版本页 → 选**分卷包（BUNDLE）**变体页。
     *
     * 关键：APKMirror 的 TikTok 有两个变体 —— 第一个是 BUNDLE（base + 71 splits，走 .apkm），
     * 第二个是 nodpi 单体 APK（450 MB，在 vivo 上必然「解析软件包时出现问题」）。
     * 分卷包那行带 BUNDLE 徽章，且徽章紧跟在同行的变体链接之后。
     */
    public static String variantUrl(String versionUrl) throws IOException {
        String html = get(versionUrl);
        List<String> links = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        Matcher m = Pattern.compile("href=\"(" + Pattern.quote(APP_PATH) + "[^\"]*?-android-apk-download/)\"")
                .matcher(html);
        while (m.find()) {
            String u = m.group(1);
            if (!links.contains(u)) { links.add(u); ends.add(m.end()); }
        }
        if (links.isEmpty()) throw new IOException("版本页里没找到变体下载链接");
        for (int i = 0; i < links.size(); i++) {
            int from = ends.get(i);
            int to = Math.min(html.length(), (i + 1 < ends.size() ? ends.get(i + 1) : from + 2000));
            String row = html.substring(from, Math.max(from, to));
            if (row.toUpperCase(Locale.ROOT).contains(">BUNDLE<")) return BASE + links.get(i);
        }
        return BASE + links.get(0);   // 页面上没有分卷包时退回第一个变体
    }

    /** 变体页 → downloadButton 的 href（带 key）。 */
    public static String downloadButtonUrl(String variantUrl) throws IOException {
        String html = get(variantUrl);
        Matcher m = Pattern.compile(
                "class=\"[^\"]*downloadButton[^\"]*\"[^>]*href=\"([^\"]+)\"").matcher(html);
        if (!m.find()) {
            m = Pattern.compile("href=\"([^\"]*download/\\?key=[^\"]*)\"").matcher(html);
            if (!m.find()) throw new IOException("变体页里没找到 downloadButton");
        }
        String href = m.group(1);
        href = href.replace("&amp;", "&");
        // forcebaseapk=true 会强制给单体 base APK，而不是分卷包 —— 必须去掉
        href = href.replace("&forcebaseapk=true", "").replace("?forcebaseapk=true", "?");
        return href.startsWith("http") ? href : BASE + href;
    }

    /** 下载页 → 最终直链 download.php。referer 用变体页（APKMirror 会校验来源）。 */
    public static String directUrl(String buttonUrl, String referer) throws IOException {
        String html = get(buttonUrl, referer);
        // 注意：两个候选正则要用各自的 matcher —— 在同一个 matcher 上连续 find() 会从上次匹配结尾继续找，
        // 于是「第一次已命中」也会被第二次 find() 判成没命中（这个坑踩过，报「下载页里没找到最终直链」）
        Matcher m = Pattern.compile("href=\"([^\"]*download\\.php\\?id=[^\"]*)\"").matcher(html);
        boolean found = m.find();
        if (!found) {
            Matcher m2 = Pattern.compile("(?:url=|location\\.href=\\s*['\"])([^'\"]*download\\.php[^'\"]*)")
                    .matcher(html);
            found = m2.find();
            if (found) m = m2;
        }
        if (!found) {
            // 把整页落盘，方便事后比对（体积极小）
            try {
                File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), "simus-out");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                try (java.io.FileOutputStream o =
                             new java.io.FileOutputStream(new File(dir, "last-download-page.html"))) {
                    o.write(html.getBytes("UTF-8"));
                }
            } catch (Throwable ignored) {
            }
            String flat = html.replaceAll("\\s+", " ").trim();
            String hint = "";
            if (flat.contains("Just a moment") || flat.contains("cf-chl")) hint = "（Cloudflare 人机校验）";
            else if (flat.contains("rate limit") || flat.contains("Too Many")) hint = "（疑似限流）";
            else if (flat.contains("not available in your country")) hint = "（地区限制）";
            // 把「App 眼里那个位置长什么样」也打出来，方便判断是编码/转义还是页面不同
            int idx = html.indexOf("download.php");
            String around = idx < 0 ? "（整页都没有 download.php 字样）"
                    : html.substring(Math.max(0, idx - 80), Math.min(html.length(), idx + 80))
                            .replace("\n", " ");
            throw new IOException("下载页里没找到最终直链" + hint
                    + "（" + html.length() + " 字符，已存到 Download/simus-out/last-download-page.html）"
                    + " 首个 download.php 位置=" + idx + " 片段=[" + around + "]");
        }
        String href = m.group(1).replace("&amp;", "&").replace("\\/", "/");
        return href.startsWith("http") ? href : BASE + href;
    }

    /** 兼容旧签名。 */
    public static String directUrl(String buttonUrl) throws IOException {
        return directUrl(buttonUrl, BASE + "/");
    }

    /** 走完整链路，返回可下载的直链。 */
    public static String resolveDirect(Progress p) throws IOException {
        p.onStage("① 查询 APKMirror 最新版本…");
        String ver = latestVersionUrl();
        String verName = versionFromUrl(ver);
        p.onStage("   最新版本: " + (verName == null ? ver : verName));
        String variant = variantUrl(ver);
        p.onStage("② 选取变体（BUNDLE 分卷包）…");
        String btn = downloadButtonUrl(variant);
        p.onStage("③ 获取下载按钮…");
        String direct = directUrl(btn, variant);
        p.onStage("④ 已取得直链");
        return direct;
    }

    /** 下载到目标文件。 */
    public static void download(String directUrl, File dest, Progress p) throws IOException {
        HttpURLConnection c = open(directUrl, BASE + "/");
        p.onStage("   直链: " + (directUrl.length() > 80 ? directUrl.substring(0, 80) + "…" : directUrl));
        long total = c.getContentLengthLong();
        p.onStage("⑤ 下载中… (" + (total > 0 ? human(total) : "大小未知") + ")");
        try (InputStream in = new BufferedInputStream(c.getInputStream(), 1 << 16);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            long last = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (done - last > (2 << 20)) { // 每 2MB 报一次
                    p.onBytes(done, total);
                    last = done;
                }
            }
            p.onBytes(done, total);
        } finally {
            c.disconnect();
        }
        // APKMirror 出错时会给一个很小的 HTML
        if (dest.length() < 1024 * 1024) {
            throw new IOException("下载文件过小（" + dest.length() + " B），可能被拦截或需要等待");
        }
    }

    private static HttpURLConnection open(String url, String referer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*");
        if (referer != null) c.setRequestProperty("Referer", referer);
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(30000);
        c.setReadTimeout(60000);
        return c;
    }

    /** 宽容版：4xx 也读取页面内容（APKMirror 的 404 页里有可用链接）。 */
    private static String getLenient(String url) throws IOException {
        HttpURLConnection c = open(url, BASE + "/");
        try {
            int code = c.getResponseCode();
            java.io.InputStream in = (code >= 200 && code < 300)
                    ? c.getInputStream() : c.getErrorStream();
            if (in == null) throw new IOException("HTTP " + code + " 且无内容: " + url);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 15];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            String html = bo.toString("UTF-8");
            if (html.length() < 200) throw new IOException("HTTP " + code + " 内容过短: " + url);
            return html;
        } finally {
            c.disconnect();
        }
    }

    private static String get(String url) throws IOException {
        return get(url, BASE + "/");
    }

    private static String get(String url, String referer) throws IOException {
        HttpURLConnection c = open(url, referer);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " for " + url);
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 15];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** 从版本页链接里取版本号：tiktok-47-1-4-release → 47.1.4 */
    public static String versionFromUrl(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("tiktok-([0-9]+(?:-[0-9]+)*)-release").matcher(url);
        return m.find() ? m.group(1).replace('-', '.') : null;
    }

    /** 从文件名里取版本号：..._musically_47.1.4-2024701040_2arch_...apkm → 47.1.4 */
    public static String versionFromFileName(String name) {
        if (name == null) return null;
        Matcher m = Pattern.compile("_(\\d+(?:\\.\\d+)+)-").matcher(name);
        return m.find() ? m.group(1) : null;
    }

    /** 版本号比较：a > b 返回正数，相等返回 0。按 . 分段做数值比较。 */
    public static int compareVersions(String a, String b) {
        if (a == null || b == null) return 0;
        String[] x = a.trim().split("\\.");
        String[] y = b.trim().split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? parseIntSafe(x[i]) : 0;
            int yi = i < y.length ? parseIntSafe(y[i]) : 0;
            if (xi != yi) return xi - yi;
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Throwable t) { return 0; }
    }

    public static String human(long b) {
        if (b > 1024L * 1024 * 1024) return String.format("%.2f GB", b / 1073741824.0);
        if (b > 1024L * 1024) return String.format("%.1f MB", b / 1048576.0);
        if (b > 1024) return String.format("%.1f KB", b / 1024.0);
        return b + " B";
    }
}
