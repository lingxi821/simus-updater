package org.lsposed.lspatch.loader.util;

/**
 * The loader's log, which is the only account of what happened inside a patched app.
 *
 * Not gated on the build type. It was, and a release loader -- the only kind anyone runs -- therefore threw away every
 * line the signature bypass, the module loader and the service client wrote, so the log a reader collects from a
 * patched app was missing exactly the half that explains the patch. Whether those lines are collected is the
 * collector's decision, on the far side of logcat, not something a build flag should have already decided here.
 */
public class XLog {

    private static boolean enableLog = true;

    public static void d(String tag, String msg) {
        if (enableLog) {
            android.util.Log.d(tag, msg);
        }
    }

    public static void v(String tag, String msg) {
        if (enableLog) {
            android.util.Log.v(tag, msg);
        }
    }

    public static void w(String tag, String msg) {
        if (enableLog) {
            android.util.Log.w(tag, msg);
        }
    }

    public static void i(String tag, String msg) {
        if (enableLog) {
            android.util.Log.i(tag, msg);
        }
    }

    public static void e(String tag, String msg) {
        if (enableLog) {
            android.util.Log.e(tag, msg);
        }
    }

    public static void e(String tag, String msg, Throwable tr) {
        if (enableLog) {
            android.util.Log.e(tag, msg, tr);
        }
    }
}
