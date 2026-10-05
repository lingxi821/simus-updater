package org.lsposed.lspatch.ui.page

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import org.lsposed.lspatch.R
import org.lsposed.lspatch.ui.appearance.LSPSettings
import org.lsposed.lspatch.ui.page.manage.AppManageBody
import org.lsposed.lspatch.ui.page.manage.AppManageFab
import org.lsposed.lspatch.ui.page.manage.ModuleManageBody
import org.lsposed.lspatch.ui.viewmodel.manage.AppManageViewModel
import org.lsposed.lspatch.ui.viewmodel.manage.ModuleManageViewModel
import org.matrix.vector.ui.SearchField
import org.matrix.vector.ui.TabbedListPanel
import org.matrix.vector.ui.navigation.Navigator

/**
 * Two lists under one header: the apps LSPatch has patched, and the Xposed modules installed beside them. Same panel
 * skeleton as every other screen — the Scaffold owns the status-bar inset, [PanelHeader] draws tight to the top, and
 * the tabs sit directly beneath it. The FAB is the one control unique to the Apps tab, so it is shown only there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageScreen(navigator: Navigator) {
    // 0 = Applications, 1 = Modules. Read once, from the panel's own remembered tab rather than
    // from an argument on the route that reached it: a panel is identified by that route, so one
    // carrying a tab would be a different panel every time a card asked for a different one.
    val initialTab = remember { LSPSettings.manageTab.value }
    val pagerState = rememberPagerState(initialPage = initialTab.coerceIn(0, 1), pageCount = { 2 })

    // Written back so the panel comes back on the tab it was left on, and so the Home cards have
    // somewhere to say which one they mean.
    LaunchedEffect(pagerState.currentPage) { LSPSettings.setManageTab(pagerState.currentPage) }

    // The same view models the two bodies read, so the header's count is the list's own count
    // rather than a second, separately-derived tally that could disagree with it.
    val appViewModel = viewModel<AppManageViewModel>()
    val moduleViewModel = viewModel<ModuleManageViewModel>()
    val patched = appViewModel.appList.size
    val modules = moduleViewModel.appList.size
    var query by remember { mutableStateOf("") }

    Scaffold(floatingActionButton = { if (pagerState.currentPage == 0) AppManageFab(navigator) }) { innerPadding ->
        TabbedListPanel(
            modifier = Modifier.padding(innerPadding),
            title = stringResource(R.string.screen_manage),
            tabLabels = listOf(stringResource(R.string.apps), stringResource(R.string.modules)),
            pagerState = pagerState,
            description = {
                Text(
                    text = stringResource(R.string.manage_summary, patched, modules),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            search = {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.manage_search),
                )
            },
        ) { page ->
            when (page) {
                0 -> AppManageBody(navigator, query)
                1 -> ModuleManageBody(query)
            }
        }
    }
}
