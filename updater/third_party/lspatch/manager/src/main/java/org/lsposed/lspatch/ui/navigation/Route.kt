package org.lsposed.lspatch.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import org.matrix.vector.ui.R as UiR
import org.matrix.vector.ui.navigation.TopLevelDestination

/**
 * Every destination, as a type.
 *
 * The back stack is a plain list of these, so an argument -- a package name, a request token -- is a constructor
 * parameter and cannot be mis-parsed out of a URL-shaped route, and no generated code stands between a screen and the
 * thing that opens it.
 */
@Serializable sealed interface Route : NavKey

/**
 * Every panel that exists, and the order a fresh install starts with.
 *
 * Not the order on screen: the reader's own order, and which panels they have hidden, are persisted by
 * [org.lsposed.lspatch.ui.appearance.LSPNavPanelStore] and modelled by NavPanels. What is declared here is the
 * catalogue and the default.
 *
 * Every panel is an object, never a class with an argument. A panel is identified by equality -- the bar highlights the
 * root it matches, and switching to the panel already open is a no-op -- so a route carrying "which tab to open this
 * time" would name a different panel on every visit. What a panel opens *on* is state the panel owns, not an argument
 * to reaching it.
 */
@Serializable
sealed interface TopLevelRoute : Route {
    @Serializable data object Home : TopLevelRoute

    @Serializable data object Store : TopLevelRoute

    @Serializable data object Manage : TopLevelRoute

    @Serializable data object Logs : TopLevelRoute
}

/** Pick what to patch: an installed app, or apks from storage. */
@Serializable data object SelectPatchTarget : Route

/**
 * Configure and run one patch.
 *
 * Carries the token of a persisted request rather than the request itself, so the screen survives the process being
 * killed mid-decision -- see PatchRequestStore.
 */
@Serializable data class NewPatch(val token: String) : Route

/**
 * Pick installed modules to embed, starting from [initialSelected].
 *
 * [requestedBy] is the identity of the screen that opened the picker -- a request token, or a
 * package name -- so what the picker chooses goes back to the screen that asked and to no other.
 */
@Serializable
data class SelectModules(val requestedBy: String, val initialSelected: List<String>) : Route

/** One patched app: what it was built with, and everything that can be done to it. */
@Serializable data class AppDetail(val packageName: String) : Route

/** One module in the store. */
@Serializable data class RepoDetails(val packageName: String) : Route

/**
 * A stack trace found in the log, on a screen of its own.
 *
 * Carries the text rather than a position, because the log window it came from is paged and filtered and may have moved
 * on by the time this is opened.
 */
@Serializable data class LogTrace(val text: String) : Route

/**
 * The self-update / version-history page.
 *
 * [prerelease] opens it on the newest canary rather than the newest stable -- what the home version
 * line asks for when it is tapped while no stable update is marked, reading the tap as a wish to see
 * what is brewing rather than a mistaken check.
 */
@Serializable data class Update(val prerelease: Boolean = false) : Route

/** The catalogue, as shared [TopLevelDestination]s -- labels come from the shared library. */
val TOP_LEVEL_DESTINATIONS: List<TopLevelDestination> =
    listOf(
        TopLevelDestination(
            "home",
            TopLevelRoute.Home,
            UiR.string.nav_home,
            Icons.Outlined.Home,
            Icons.Rounded.Home,
        ),
        TopLevelDestination(
            "store",
            TopLevelRoute.Store,
            UiR.string.nav_store,
            Icons.Outlined.Download,
            Icons.Rounded.Download,
        ),
        TopLevelDestination(
            "manage",
            TopLevelRoute.Manage,
            UiR.string.nav_manage,
            Icons.Outlined.Dashboard,
            Icons.Rounded.Dashboard,
        ),
        TopLevelDestination(
            "logs",
            TopLevelRoute.Logs,
            UiR.string.nav_logs,
            Icons.Outlined.Article,
            Icons.Rounded.Article,
        ),
    )
