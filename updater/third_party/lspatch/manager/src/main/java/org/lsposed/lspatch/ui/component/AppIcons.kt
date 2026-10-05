package org.lsposed.lspatch.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.LSPPackageManager.AppInfo

/**
 * One app's icon, rasterised on demand.
 *
 * Null until it has been. Icons are no longer decoded for every installed package during the scan --
 * that walked hundreds of drawables at the busiest moment there is and never gave the memory back --
 * so a screen asks for the one it is drawing and redraws when it arrives. Seeded from the shared
 * cache so a row scrolled back into view draws on its first frame instead of flickering.
 */
@Composable
fun rememberAppIcon(app: AppInfo): ImageBitmap? {
    val key = app.app.packageName
    var icon by remember(key) { mutableStateOf(LSPPackageManager.cachedIcon(app)) }
    LaunchedEffect(key) { if (icon == null) icon = LSPPackageManager.loadIcon(app) }
    return icon
}

/**
 * The icons of a set of apps, for the clusters that show *which* apps rather than how many.
 *
 * Loaded one at a time and published as they arrive, so a cluster fills in rather than waiting for
 * its slowest icon. Order follows [apps]; an icon that cannot be loaded is left out, exactly as the
 * eager map's `mapNotNull` did.
 */
@Composable
fun rememberAppIcons(apps: List<AppInfo>): List<ImageBitmap> {
    val keys = apps.map { it.app.packageName }
    var icons by remember(keys) { mutableStateOf(apps.mapNotNull { LSPPackageManager.cachedIcon(it) }) }
    LaunchedEffect(keys) {
        val loaded = mutableListOf<ImageBitmap>()
        apps.forEach { app ->
            LSPPackageManager.loadIcon(app)?.let {
                loaded += it
                // Published only once it is at least as long as what is already on screen. Each
                // load suspends, so a cluster seeded from the cache would otherwise shrink to one
                // icon and grow back as the list was rebuilt from its start.
                if (loaded.size >= icons.size) icons = loaded.toList()
            }
        }
    }
    return icons
}
