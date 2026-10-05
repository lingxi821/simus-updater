package org.lsposed.lspatch.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * What the module picker chose, on its way back to the screen that opened it.
 *
 * A back stack of typed routes has no channel for a screen to answer the one below it: a route is a
 * value, and a value cannot carry a callback. So the answer is left here and read by the screen that
 * asked for it once the picker has gone -- which is the same shape as a launch intent's destination,
 * and for the same reason.
 *
 * Addressed, because two screens embed modules -- a patch being configured and a patched app being
 * edited -- and either may be the one standing below the picker. An answer nobody came back for
 * would otherwise be applied to whichever of them was opened next.
 *
 * Read once. Leaving it set would apply the same selection again the next time its asker came back
 * into view, whether or not the picker had been opened at all.
 */
object ModuleSelection {

    /** [requestedBy] is the asker's own identity: a request token, or a package name. */
    data class Selection(val requestedBy: String, val packageNames: List<String>)

    private val _pending = MutableStateFlow<Selection?>(null)

    val pending: StateFlow<Selection?> = _pending.asStateFlow()

    /** Called by the picker as it leaves. */
    fun offer(requestedBy: String, packageNames: List<String>) {
        _pending.value = Selection(requestedBy, packageNames)
    }

    /** The selection [requestedBy] asked for, or null when the pending one is not theirs. */
    fun consume(requestedBy: String): List<String>? =
        _pending.getAndUpdate { if (it?.requestedBy == requestedBy) null else it }
            ?.takeIf { it.requestedBy == requestedBy }
            ?.packageNames
}
