package org.lsposed.lspatch.ui.page

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.lsposed.lspatch.data.repository.LSPLogSource
import org.lsposed.lspatch.ui.navigation.LogTrace
import org.matrix.vector.ui.logs.LogsScreen as SharedLogsScreen
import org.matrix.vector.ui.navigation.Navigator

/**
 * The Logs page. A thin host over the shared `org.matrix.vector.ui.logs.LogsScreen`.
 *
 * LSPatch reads the device log through the Shizuku shell rather than a root daemon, and where this once put a "grant
 * Shizuku" wall it now always opens the log: without the shell the source falls back to this process's own entries,
 * which an app may read unprivileged. That is the reading worth having precisely when Shizuku is the thing that is
 * broken — the manager's own lines are then the only account of why — and the screen drops the unfold control rather
 * than offer a verbose stream that would be the same lines twice.
 *
 * The shared screen supplies the whole surface — the level-coloured rows, the tag/level filter sheet, the day breaks,
 * the search and jump-to-newest — driven by the snapshot the source reads. A trace opens in place or, when the reader
 * turns that setting off, on the shared trace screen this navigates to.
 */
@Composable
fun LogsScreen(navigator: Navigator) {
    val context = LocalContext.current
    val source = remember { LSPLogSource(context.applicationContext) }
    SharedLogsScreen(
        source = source,
        onOpenTrace = { text -> navigator.go(LogTrace(text = text)) },
    )
}
