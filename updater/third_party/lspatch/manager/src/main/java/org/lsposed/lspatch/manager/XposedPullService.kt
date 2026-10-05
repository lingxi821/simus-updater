package org.lsposed.lspatch.manager

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import org.lsposed.lspatch.IXposedServicePull
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.util.LSPPackageManager

/**
 * On-demand service delivery, entirely manager-side: an exported service a module's own app binds to
 * ask the manager to push it its writable service the moment its settings UI opens -- the trigger a
 * rootless manager otherwise lacks, which is why a change made there did not reach a running hook
 * until the target was force-stopped.
 *
 * Deliberately a *request to push*, not a service handed back: the push travels the manager's existing
 * [ManagerRemoteServices.pushToCompanion] path into the module's ordinary, unmodified libxposed
 * XposedProvider, so nothing in the module or in the pristine upstream libxposed submodule changes.
 * The companion needs only a copy of [IXposedServicePull] and one call on process start; without that
 * opt-in, the Shizuku companion watcher still delivers the same push.
 *
 * Discovery is by the [ACTION] intent action, never by a hard-coded package, so a renamed or cloaked
 * manager is still found. Authentication is by [Binder.getCallingUid]: the manager only ever pushes to
 * the caller's **own** package, so exporting this grants nothing a module could not already do to its
 * own preferences.
 */
class XposedPullService : Service() {

    private val binder = object : IXposedServicePull.Stub() {
        override fun requestPush(): Boolean {
            val uid = Binder.getCallingUid()
            // Rate-limit per uid: this is exported and unauthenticated, and each accepted call ends in
            // a SEND_BINDER into the caller's own provider. Throttling caps how fast one app can drive
            // that, so a flood cannot monopolise even the isolated untrusted push pool.
            val now = SystemClock.elapsedRealtime()
            val previous = lastRequestAt[uid]
            if (previous != null && now - previous < THROTTLE_MS) return false
            lastRequestAt[uid] = now

            val packages = runCatching { lspApp.packageManager.getPackagesForUid(uid) }.getOrNull()
                ?: return false
            // A shared-uid caller can front several packages; push to the first that is a module this
            // manager knows. Resolving against every package for the uid (not getNameForUid) avoids
            // the shared-uid miss.
            val pkg = packages.firstOrNull { isServableModule(it) } ?: return false
            Log.d(TAG, "Companion $pkg requested a push")
            // On the isolated untrusted executor so a hung companion provider cannot starve the
            // trusted or Shizuku push paths.
            ManagerRemoteServices.pushToCompanionUntrusted(pkg)
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Whether [pkg] is a module the manager serves. Fails closed while the package list has not been
     * scanned yet: an exported endpoint should not widen its trigger to arbitrary apps during the
     * boot window, and a genuine companion simply retries (its own process start re-drives the call).
     */
    private fun isServableModule(pkg: String): Boolean {
        val list = LSPPackageManager.appList
        if (list.isEmpty()) return false
        return list.any { it.app.packageName == pkg && it.isModule }
    }

    companion object {
        const val TAG = "LSPatch-XposedService"

        /** The intent action a companion resolves this service by. LSPatch-owned. */
        const val ACTION = "org.lsposed.lspatch.action.REQUEST_PUSH"

        /** Minimum gap between accepted requestPush calls from one uid. */
        private const val THROTTLE_MS = 3_000L

        private val lastRequestAt = ConcurrentHashMap<Int, Long>()
    }
}
