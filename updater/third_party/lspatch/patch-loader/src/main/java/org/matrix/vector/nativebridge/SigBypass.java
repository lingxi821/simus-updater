package org.matrix.vector.nativebridge;

public class SigBypass {
    public static native void enableOpenatHook(String origApkPath, String cacheApkPath);

    /**
     * Level 3. Instruments inline {@code svc} syscalls in the app's own native libraries so a packer
     * that reads its apk with a raw syscall -- bypassing the libc {@code __openat} redirect that
     * {@link #enableOpenatHook} installs -- is still pointed at the stored original. Must be called
     * after {@link #enableOpenatHook}, which records the apk paths this reuses.
     */
    public static native void enableSvcRedirect();
}
