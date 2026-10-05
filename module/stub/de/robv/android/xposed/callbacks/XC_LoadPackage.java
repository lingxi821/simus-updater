package de.robv.android.xposed.callbacks;
/** 仅用于编译。 */
public class XC_LoadPackage {
    public static class LoadPackageParam {
        public String packageName;
        public ClassLoader classLoader;
        public String processName;
        public boolean isFirstApplication;
    }
}
