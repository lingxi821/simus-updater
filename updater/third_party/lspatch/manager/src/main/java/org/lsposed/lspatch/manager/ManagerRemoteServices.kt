package org.lsposed.lspatch.manager

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.service.IXposedService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.lsposed.lspatch.config.ConfigManager
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.share.LSPConfig
import org.lsposed.lspatch.share.remote.FrameworkInfo
import org.lsposed.lspatch.share.remote.LSPatchModuleService
import org.lsposed.lspatch.share.remote.LSPatchXposedService
import org.lsposed.lspatch.share.remote.PreferenceChangeNotifier
import org.lsposed.lspatch.share.remote.RemoteFileStore
import org.lsposed.lspatch.share.remote.RemotePreferenceStore
import org.lsposed.lspatch.share.remote.ScopeSource

/**
 * The manager-mode wiring of the shared remote-service layer — the manager standing in for Vector's
 * daemon as each module's service owner.
 *
 * <p>The stores live in the <b>manager's</b> own storage, so they are persistent and reached, over the
 * binder, by both a module's hook (its [LSPatchModuleService] on `LoadedModule.service`) and its
 * companion app (its [LSPatchXposedService], pushed to the companion's exported provider). One store,
 * two readers/writers: a companion's write is what the hook reads. A per-module [PreferenceChangeNotifier]
 * is shared between the two stubs so the write reaches the hook's subscription.</p>
 *
 * <p>Scope is real here — the apps patched with the module — unlike the fixed host package embedded mode
 * reports. Hot reload is driven by [ManagerHotReloadDriver] over the hosts' attached process channels.</p>
 */
object ManagerRemoteServices {

    private const val TAG = "LSPatch-XposedService"

    private val prefs by lazy { RemotePreferenceStore(lspApp) }
    private val files by lazy { RemoteFileStore(lspApp) }

    private val notifiers = ConcurrentHashMap<String, PreferenceChangeNotifier>()
    private val moduleServices = ConcurrentHashMap<String, LSPatchModuleService>()
    private val xposedServices = ConcurrentHashMap<String, LSPatchXposedService>()

    // The manager plays the daemon for hot reload, driving it into the hosts that attached a channel.
    private val hotReloadDriver = ManagerHotReloadDriver()

    // Off the binder thread: a push opens (and can start) the companion's provider process, so it must
    // not block the getModules() reply the host is waiting on. This one carries only the trusted
    // internal triggers (getModules, config change, app change).
    private val pushExecutor = Executors.newSingleThreadExecutor { Thread(it, "lspatch-companion-push") }

    // On-demand pushes (the Shizuku companion watcher) ride their own thread, so a companion whose
    // provider hangs a push cannot stall the trusted getModules path above.
    private val onDemandExecutor = Executors.newSingleThreadExecutor { Thread(it, "lspatch-push-ondemand") }

    // Untrusted pushes (the exported requestPush any app may call) are isolated further still: a small
    // pool, so one caller's deliberately-hung provider cannot starve another caller's request, and
    // never the trusted or on-demand paths. Rate-limiting per uid lives in XposedPullService.
    private val untrustedExecutor = Executors.newFixedThreadPool(2) { Thread(it, "lspatch-push-untrusted") }

    // The last time a push to a package actually landed, so two pushes racing the same process start
    // (the watcher's edge and an opt-in requestPush, or a retry after a real failure) collapse to one
    // SEND_BINDER -- the stock XposedServiceHelper has no dedup and would otherwise fire onServiceBind
    // twice. Keyed on success only, so a retry after a failed push is never coalesced away.
    private val lastSuccessfulPush = ConcurrentHashMap<String, Long>()

    private val frameworkInfo by lazy {
        FrameworkInfo(
            "LSPatch",
            "${LSPConfig.instance.VERSION_NAME} (${LSPConfig.instance.VERSION_CODE})",
            LSPConfig.instance.VERSION_CODE.toLong(),
            IXposedService.PROP_CAP_REMOTE,
        )
    }

    // A module's scope is the set of apps patched with it. Resolved at call time, so this holds no
    // reference into ConfigManager's init.
    private val scopeSource = ScopeSource { pkg -> runBlocking { ConfigManager.getAppsForModule(pkg) } }

    private fun notifier(pkg: String) = notifiers.getOrPut(pkg) { PreferenceChangeNotifier() }

    /** The read service that rides on `LoadedModule.service`. */
    fun moduleService(pkg: String): LSPatchModuleService =
        moduleServices.getOrPut(pkg) {
            LSPatchModuleService(pkg, IXposedService.PROP_CAP_REMOTE, prefs, files, notifier(pkg))
        }

    /** The full service pushed to the companion app. */
    fun xposedService(pkg: String): LSPatchXposedService =
        xposedServices.getOrPut(pkg) {
            LSPatchXposedService(pkg, frameworkInfo, prefs, files, notifier(pkg), scopeSource, hotReloadDriver)
        }

    /**
     * Pushes the module's service into its companion app's exported `XposedProvider`. A plain
     * `ContentResolver.call` reaches an exported provider without the privileged
     * `getContentProviderExternal` a rooted daemon uses; it quietly fails when the module ships no
     * companion (no such authority), which is the common case and not worth logging loudly.
     */
    fun pushToCompanion(pkg: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        lastSuccessfulPush[pkg]?.let { if (now - it < PUSH_COALESCE_MS) return true }
        val uri = Uri.parse("content://$pkg${IXposedService.AUTHORITY_SUFFIX}")
        val ok = runCatching {
            val extras = Bundle().apply { putBinder("binder", xposedService(pkg).asBinder()) }
            lspApp.contentResolver.call(uri, IXposedService.SEND_BINDER, null, extras) != null
        }.getOrElse {
            Log.d(TAG, "No companion to receive the service for $pkg: ${it.message}")
            false
        }
        if (ok) lastSuccessfulPush[pkg] = SystemClock.elapsedRealtime()
        return ok
    }

    /** Best-effort push to each named module's companion, off the caller's thread. */
    fun pushToCompanionsAsync(pkgs: Collection<String>) {
        if (pkgs.isEmpty()) return
        val snapshot = pkgs.toList()
        pushExecutor.execute { snapshot.forEach { pushToCompanion(it) } }
    }

    /**
     * A push triggered by the Shizuku companion watcher spotting the settings app start. Isolated from
     * the trusted trigger path, and retried once after a short delay: the watcher fires the instant the
     * process appears in `ps`, which can be a hair before the companion's provider is published, and a
     * single failed [pushToCompanion] would otherwise strand the companion for its whole process life.
     * The retry runs only after a real failure -- a success sets the coalesce stamp and returns early.
     */
    fun pushToCompanionOnDemand(pkg: String) {
        onDemandExecutor.execute { pushWithOneRetry(pkg) }
    }

    /** A push asked for by the exported requestPush endpoint. Same retry, on the isolated pool. */
    fun pushToCompanionUntrusted(pkg: String) {
        untrustedExecutor.execute { pushWithOneRetry(pkg) }
    }

    private fun pushWithOneRetry(pkg: String) {
        if (pushToCompanion(pkg)) return
        try {
            Thread.sleep(ON_DEMAND_RETRY_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }
        pushToCompanion(pkg)
    }

    private const val PUSH_COALESCE_MS = 3_000L
    private const val ON_DEMAND_RETRY_MS = 600L
}
