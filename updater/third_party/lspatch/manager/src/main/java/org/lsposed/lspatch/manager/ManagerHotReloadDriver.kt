package org.lsposed.lspatch.manager

import android.os.Bundle
import android.util.Log
import io.github.libxposed.service.HookedProcess
import io.github.libxposed.service.IHotReloadCallback
import io.github.libxposed.service.IXposedService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.lsposed.lspatch.config.ConfigManager
import org.lsposed.lspatch.share.remote.HotReloadDriver
import org.matrix.vector.ipc.HotReloadOutcome
import org.matrix.vector.ipc.IHotReloadOutcomeReceiver

/**
 * Manager-mode hot reload: the manager standing in for Vector's daemon.
 *
 * A companion app asks for a module's running targets and then asks to reload one; both questions are
 * answered from [HotReloadRegistry], the live hosts that handed the manager their process channels.
 * The reload itself is the daemon's move, ported: build the new generation from the module's currently
 * installed apk and hand it to that host's channel, which runs the in-process swap (Vector's
 * `VectorModuleManager.hotReload`). The swap runs arbitrary module code, so it is driven off a worker
 * thread and bounded by a timeout, exactly as `ModuleAppService.runHotReload` bounds it.
 */
class ManagerHotReloadDriver : HotReloadDriver {

    private companion object {
        const val TAG = "LSPatch-HotReload"
        const val RELOAD_TIMEOUT_SECONDS = 30L
    }

    // One slow target must not delay another; per-target serialisation is the target's own concern.
    private val executor = Executors.newCachedThreadPool { Thread(it, "lspatch-hot-reload") }

    override fun getRunningTargets(modulePackageName: String): List<HookedProcess> =
        HotReloadRegistry.targetsFor(modulePackageName).map { target ->
            HookedProcess().apply {
                targetId = target.id
                uid = target.uid
                pid = target.pid
                processName = target.processName
                // The manager does not track each host's loaded generation, so it reports neither
                // up-to-date nor stale with authority; the diagnostic version is left at 0 (unknown).
                state = HookedProcess.TARGET_STATE_UP_TO_DATE
                loadedVersionCode = 0
            }
        }

    override fun hotReload(
        modulePackageName: String,
        targetId: Long,
        data: Bundle?,
        callback: IHotReloadCallback?,
    ) {
        val target = HotReloadRegistry.target(targetId)
        if (target == null || modulePackageName !in target.modules) {
            report(callback, IXposedService.HOT_RELOAD_PROCESS_DIED, "Target is no longer running $modulePackageName")
            return
        }
        executor.execute {
            try {
                val newModule = runBlocking { ConfigManager.buildLoadedModule(modulePackageName) }
                if (newModule == null) {
                    report(callback, IXposedService.HOT_RELOAD_UNSUPPORTED,
                        "No installed generation of $modulePackageName to load")
                    return@execute
                }
                if (newModule.code.moduleClassNames.size != 1) {
                    newModule.code.preLoadedDexes.forEach { runCatching { it.close() } }
                    report(callback, IXposedService.HOT_RELOAD_UNSUPPORTED,
                        "Module has no single Java entry class")
                    return@execute
                }

                val answered = CountDownLatch(1)
                var outcome: HotReloadOutcome? = null
                val receiver = object : IHotReloadOutcomeReceiver.Stub() {
                    override fun onOutcome(result: HotReloadOutcome?) {
                        outcome = result
                        answered.countDown()
                    }
                }
                // oneway: the host runs the swap and answers through the receiver, out of band.
                Log.d(TAG, "Driving hot reload of $modulePackageName into ${target.processName}")
                target.channel.hotReload(modulePackageName, data, newModule, receiver)

                if (!answered.await(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    report(callback, IXposedService.HOT_RELOAD_FAILED,
                        "${target.processName} did not answer within ${RELOAD_TIMEOUT_SECONDS}s")
                    return@execute
                }
                val result = outcome
                Log.d(TAG, "Hot reload of $modulePackageName reported status=${result?.status}")
                if (result == null) {
                    report(callback, IXposedService.HOT_RELOAD_FAILED, "${target.processName} answered with nothing")
                } else {
                    report(callback, result.status, result.message)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Hot reload of $modulePackageName failed", t)
                report(callback, IXposedService.HOT_RELOAD_FAILED, "${t.javaClass.name}: ${t.message}")
            }
        }
    }

    private fun report(callback: IHotReloadCallback?, status: Int, message: String?) {
        runCatching { callback?.onHotReloadResult(status, message) }
            .onFailure { Log.w(TAG, "Cannot deliver the hot reload result", it) }
    }
}
