package org.lsposed.lspatch.service;

import android.os.Bundle;
import android.util.Log;

import org.matrix.vector.impl.core.VectorModuleManager;
import org.matrix.vector.ipc.HotReloadOutcome;
import org.matrix.vector.ipc.IHotReloadOutcomeReceiver;
import org.matrix.vector.ipc.IProcessChannel;
import org.matrix.vector.ipc.LoadedModule;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The host's inbound hot-reload channel for LSPatch's manager.
 *
 * <p>Vector's {@code VectorProcessChannel} gates hot reload on the system uid -- right for a rooted
 * daemon, but LSPatch's manager is a plain app and would be refused. This runs the same in-process
 * generation swap ({@code VectorModuleManager.hotReload}) on the manager's behalf; the manager is the
 * only party ever handed this channel, so the gate the daemon needs does not apply here.</p>
 *
 * <p>The swap runs the old code's {@code onHotReloading} and the new code's {@code onHotReloaded} --
 * module code with no time bound -- so it runs off a single worker thread and answers out of band
 * through the receiver, the shape {@code IProcessChannel.hotReload} is oneway for.</p>
 */
public class LSPatchProcessChannel extends IProcessChannel.Stub {

    private static final String TAG = "LSPatch-HotReload";

    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "lspatch-hot-reload-host"));

    @Override
    public void hotReload(String modulePackageName, Bundle extras, LoadedModule module,
            IHotReloadOutcomeReceiver receiver) {
        worker.execute(() -> {
            HotReloadOutcome outcome = null;
            try {
                outcome = VectorModuleManager.INSTANCE.hotReload(modulePackageName, extras, module);
            } catch (Throwable t) {
                Log.e(TAG, "Hot reload swap of " + modulePackageName + " failed", t);
            }
            try {
                if (receiver != null) receiver.onOutcome(outcome);
            } catch (Throwable t) {
                Log.w(TAG, "Cannot report the hot reload outcome", t);
            }
        });
    }
}
