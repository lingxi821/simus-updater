package org.lsposed.lspatch.ui.page

import androidx.compose.runtime.Composable
import org.matrix.vector.ui.logs.LogTraceScreen as SharedLogTraceScreen
import org.matrix.vector.ui.navigation.Navigator

/**
 * The stack-trace page: a thin host over the shared `org.matrix.vector.ui.logs.LogTraceScreen`.
 *
 * Reached from the Logs page when the reader has turned "open traces in place" off, the same setting and the same
 * screen Vector uses — LSPatch owns only the navigation, not a second trace renderer.
 */
@Composable
fun LogTraceScreen(navigator: Navigator, text: String) {
    SharedLogTraceScreen(text = text, onNavigateBack = { navigator.back() })
}
