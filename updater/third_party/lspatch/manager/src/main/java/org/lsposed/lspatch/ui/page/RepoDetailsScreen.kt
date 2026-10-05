package org.lsposed.lspatch.ui.page

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.lsposed.lspatch.data.repository.LSPStoreInstallHost
import org.lsposed.lspatch.data.repository.LSPStoreSettings
import org.lsposed.lspatch.data.repository.RepoRepository
import org.matrix.vector.ui.navigation.Navigator

/**
 * The module details page. A thin host over the shared `org.matrix.vector.ui.store.RepoDetailsScreen`: it supplies an
 * [LSPStoreInstallHost] so a module can be installed straight from its page — the same install bar Vector shows — since
 * a store module is an ordinary APK the manager can install.
 */
@Composable
fun RepoDetailsScreen(navigator: Navigator, packageName: String) {
    val context = LocalContext.current
    val installHost = remember(packageName) { LSPStoreInstallHost(packageName) }
    org.matrix.vector.ui.store.RepoDetailsScreen(
        packageName = packageName,
        onNavigateBack = { navigator.back() },
        onOpenUrl = { url ->
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        },
        dataSource = RepoRepository.getInstance(context),
        settings = LSPStoreSettings,
        host = installHost,
    )
}
