package org.lsposed.lspatch.ui.page

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.lsposed.lspatch.data.repository.LSPStoreSettings
import org.lsposed.lspatch.data.repository.RepoRepository
import org.lsposed.lspatch.ui.appearance.LSPSettings
import org.lsposed.lspatch.ui.navigation.RepoDetails
import org.lsposed.lspatch.util.LSPNetwork
import org.matrix.vector.ui.LocalDialogLocalizer
import org.matrix.vector.ui.navigation.Navigator
import org.matrix.vector.ui.net.DohSettingSection

/**
 * The Store tab. A thin host around the shared Store list ([org.matrix.vector.ui.store.RepoScreen]): it wires LSPatch's
 * [RepoRepository] as the data source and [LSPStoreSettings] as the settings, and keeps this destination's nav identity
 * so `RepoScreenDestination` stays the tab target. Tapping a module opens LSPatch's own details screen.
 *
 * The header carries a menu button that opens the Network sheet — the DoH switch and what the last lookup did. It lives
 * here rather than in appearance because it is about reaching the Store, and this is where a user whose network cannot
 * reach it will look.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoScreen(navigator: Navigator) {
    val ctx = LocalContext.current
    var showNetwork by remember { mutableStateOf(false) }

    org.matrix.vector.ui.store.RepoScreen(
        onModuleClick = { navigator.go(RepoDetails(packageName = it)) },
        dataSource = RepoRepository.getInstance(ctx),
        settings = LSPStoreSettings,
        actions = {
            IconButton(onClick = { showNetwork = true }) {
                Icon(
                    Icons.Rounded.Tune,
                    contentDescription = stringResource(org.matrix.vector.ui.R.string.settings_network),
                )
            }
        },
    )

    if (showNetwork) {
        val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
        ModalBottomSheet(onDismissRequest = { showNetwork = false }, sheetState = sheetState) {
            // A sheet is its own window, which drops the in-app language override; re-apply it inside
            // so this speaks the reader's language rather than the phone's.
            LocalDialogLocalizer.current {
                // The whole manager's traffic goes through the one client, so this governs the Store,
                // the update check and store downloads alike.
                DohSettingSection(LSPSettings, LSPNetwork.dns.status, LSPNetwork.dns::retry)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
