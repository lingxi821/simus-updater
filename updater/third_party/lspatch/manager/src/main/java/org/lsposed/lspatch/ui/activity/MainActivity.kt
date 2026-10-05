@file:OptIn(
    ExperimentalMaterial3AdaptiveNavigationSuiteApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
)

package org.lsposed.lspatch.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.ui.appearance.LSPFloatingNavSettings
import org.lsposed.lspatch.ui.appearance.LSPNavPanelStore
import org.lsposed.lspatch.ui.appearance.LSPSettings
import org.lsposed.lspatch.ui.component.ShizukuFailureDialog
import org.lsposed.lspatch.ui.navigation.AppDetail
import org.lsposed.lspatch.ui.navigation.LogTrace
import org.lsposed.lspatch.ui.navigation.NewPatch
import org.lsposed.lspatch.ui.navigation.RepoDetails
import org.lsposed.lspatch.ui.navigation.SelectModules
import org.lsposed.lspatch.ui.navigation.SelectPatchTarget
import org.lsposed.lspatch.ui.navigation.TOP_LEVEL_DESTINATIONS
import org.lsposed.lspatch.ui.navigation.TopLevelRoute
import org.lsposed.lspatch.ui.navigation.Update
import org.lsposed.lspatch.ui.page.AppDetailScreen
import org.lsposed.lspatch.ui.page.HomeScreen
import org.lsposed.lspatch.ui.page.LogTraceScreen
import org.lsposed.lspatch.ui.page.LogsScreen
import org.lsposed.lspatch.ui.page.ManageScreen
import org.lsposed.lspatch.ui.page.NewPatchScreen
import org.lsposed.lspatch.ui.page.RepoDetailsScreen
import org.lsposed.lspatch.ui.page.RepoScreen
import org.lsposed.lspatch.ui.page.SelectModulesScreen
import org.lsposed.lspatch.ui.page.SelectPatchTargetScreen
import org.lsposed.lspatch.ui.page.UpdateScreen
import org.lsposed.lspatch.ui.theme.LSPTheme
import org.lsposed.lspatch.ui.util.LocalSnackbarHost
import org.lsposed.lspatch.util.ShizukuApi
import org.matrix.vector.ui.LocalDialogLocalizer
import org.matrix.vector.ui.SharedSnackbarHost
import org.matrix.vector.ui.locale.LocalizedContent
import org.matrix.vector.ui.locale.LocalizedOverlay
import org.matrix.vector.ui.navigation.FloatingPanelNav
import org.matrix.vector.ui.navigation.LocalNavigator
import org.matrix.vector.ui.navigation.Navigator
import org.matrix.vector.ui.navigation.PanelBar
import org.matrix.vector.ui.navigation.PanelEditDone
import org.matrix.vector.ui.navigation.rememberNavigator

class MainActivity : ComponentActivity() {

    // Shizuku can be started, granted or revoked while the manager sits in the background, and only
    // the first of those calls back. Re-reading on every return is what keeps the badge, the grant
    // card and every "needs Shizuku" hint describing the device rather than a memory of it.
    override fun onResume() {
        super.onResume()
        ShizukuApi.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Someone opened the manager, so the work the app defers until it has a reason can run.
        lspApp.startBackgroundWork()
        enableEdgeToEdge()
        setContent {
            // Above the language override, not inside it. Choosing a language rebuilds everything
            // below that point -- and keeps two copies of it alive while the two crossfade -- so a
            // back stack held there would be thrown away, or duplicated, by a change that has
            // nothing to do with where the reader is standing.
            val navigator = rememberNavigator(LSPNavPanelStore, TOP_LEVEL_DESTINATIONS)
            val themeMode by LSPSettings.themeMode.collectAsState()
            val dynamicColor by LSPSettings.dynamicColor.collectAsState()
            val seed by LSPSettings.seedColor.collectAsState()
            val amoled by LSPSettings.amoledBlack.collectAsState()
            LSPTheme(
                themeMode = themeMode,
                dynamicColor = dynamicColor,
                seed = seed,
                amoled = amoled,
            ) {
                // The chosen language re-resolves every string below, and the localizer the shared
                // library reads is pointed at LSPatch's overlay so its sheets follow suit.
                LocalizedContent(LSPSettings) {
                    CompositionLocalProvider(
                        LocalDialogLocalizer provides
                            { content ->
                                LocalizedOverlay(LSPSettings, content)
                            },
                        LocalNavigator provides navigator,
                    ) {
                        val snackbarHostState = remember { SnackbarHostState() }
                        CompositionLocalProvider(LocalSnackbarHost provides snackbarHostState) {
                            LSPatchApp(navigator, snackbarHostState)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The app shell: the navigation container, the destinations, and what is drawn over both.
 *
 * [NavigationSuiteScaffold] picks the container from the window size -- a bottom bar on a phone, a rail when there is
 * width to spare -- and owns where it sits, so the destination below is laid out beside or above it rather than under
 * it. Which panels it holds, in which order, is the reader's, and there is a third arrangement with no container at
 * all: a ball floating over the content. Rearranging needs something to rearrange, so edit mode puts the container back
 * while it lasts.
 */
@Composable
private fun LSPatchApp(navigator: Navigator, snackbarHostState: SnackbarHostState) {
    val floating by LSPSettings.floatingNav.collectAsState()
    val editing = navigator.editingPanels
    // The container shows only at the root of a panel. On a detail screen none of the items is the
    // current destination, and a navigation bar highlighting nothing is worse than none.
    val atRoot = !navigator.canGoBack

    // Driving the scaffold's own state rather than dropping the items: hiding the items alone
    // leaves the container laid out, so a detail screen keeps a dead strip of bar-sized space.
    val suiteState = rememberNavigationSuiteScaffoldState()
    LaunchedEffect(atRoot) { if (atRoot) suiteState.show() else suiteState.hide() }
    // Leaving a root screen also cancels an in-progress panel edit.
    LaunchedEffect(atRoot) { if (!atRoot) navigator.editingPanels = false }

    // Floating overrules the adaptive bar/rail with None -- the type that actually removes the
    // container rather than hiding it -- except while editing panels, when there has to be a bar.
    val suiteType =
        if (floating && !editing) NavigationSuiteType.None
        else NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfo())

    NavigationSuiteScaffold(
        state = suiteState,
        navigationSuiteType = suiteType,
        navigationItems = {
            // Under None the NavigationSuite drops this slot with its container, so skipping it
            // says so rather than leaving a composable that never runs.
            if (suiteType != NavigationSuiteType.None) {
                PanelBar(
                    panels = navigator.panels,
                    current = navigator.currentTopLevel,
                    editing = editing,
                    suiteType = suiteType,
                    onSelect = { route -> navigator.switchTo(route) },
                    onEdit = { navigator.editingPanels = true },
                    onToggleHidden = { key, hidden -> navigator.setPanelHidden(key, hidden) },
                    onMove = { from, to -> navigator.movePanel(from, to) },
                )
            }
        },
        primaryActionContent = {
            if (editing) PanelEditDone(onDone = { navigator.editingPanels = false })
        },
    ) {
        // Single inset owner: each screen's own Scaffold consumes the status-bar inset
        // (edge-to-edge), so nothing here re-applies it. The snackbar is overlaid.
        Box(Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = navigator.backStack,
                onBack = { navigator.back() },
                // Naming any decorator replaces NavDisplay's default, which is the saveable-state
                // one alone, so it is repeated here. The ViewModel one is what this list is for: it
                // scopes a ViewModelStore per entry, so opening a second app's detail screen builds
                // a second ViewModel instead of reusing the first.
                entryDecorators =
                    listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                entryProvider = entryProvider { registerRoutes(navigator) },
            )
            // Last child so it draws over the destination, and only at a root panel (a detail
            // screen has its own back affordance) and not mid-edit.
            if (floating && !editing && atRoot) {
                FloatingPanelNav(
                    panels = navigator.panels,
                    current = navigator.currentTopLevel,
                    onSelect = { route -> navigator.switchTo(route) },
                    settings = LSPFloatingNavSettings,
                )
            }
            // The shared host, not Material's: a message here says whether something worked, and
            // the default bar is the same grey slab either way.
            SharedSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            )
            // Hosted here rather than on a screen: what needs Shizuku is spread across all of them,
            // and a failure has to be legible wherever it happens. Its trace goes to the same
            // screen the logs use, which is the only account of the problem when Shizuku -- and so
            // the log -- is down.
            ShizukuFailureDialog(onViewTrace = { trace -> navigator.go(LogTrace(trace)) })
        }
    }

    // After the scaffold on purpose. Back callbacks are dispatched last-registered-first and
    // BackHandler registers from an effect, which run in composition order, so this one outranks
    // the handler NavDisplay installs and edit mode ends before the stack is touched.
    BackHandler(enabled = editing) { navigator.editingPanels = false }
}

/**
 * Every destination, registered.
 *
 * All four panels keep their entry whether or not the reader has hidden them: a saved stack names its keys by class and
 * [entryProvider] throws for one it was never given, so dropping the registration of a hidden panel would turn a stale
 * saved stack into a crash.
 */
private fun EntryProviderScope<NavKey>.registerRoutes(navigator: Navigator) {
    entry<TopLevelRoute.Home> { HomeScreen(navigator) }
    entry<TopLevelRoute.Store> { RepoScreen(navigator) }
    entry<TopLevelRoute.Manage> { ManageScreen(navigator) }
    entry<TopLevelRoute.Logs> { LogsScreen(navigator) }

    entry<SelectPatchTarget> { SelectPatchTargetScreen(navigator) }
    entry<NewPatch> { route -> NewPatchScreen(navigator, token = route.token) }
    entry<SelectModules> { route ->
        SelectModulesScreen(
            navigator,
            requestedBy = route.requestedBy,
            initialSelected = route.initialSelected,
        )
    }
    entry<AppDetail> { route -> AppDetailScreen(navigator, packageName = route.packageName) }
    entry<RepoDetails> { route -> RepoDetailsScreen(navigator, packageName = route.packageName) }
    entry<LogTrace> { route -> LogTraceScreen(navigator, text = route.text) }
    entry<Update> { route -> UpdateScreen(navigator, prerelease = route.prerelease) }
}
