package org.lsposed.lspatch.ui.page

import org.matrix.vector.ui.AppIcon
import org.matrix.vector.ui.show
import org.matrix.vector.ui.SnackbarTone
import android.content.pm.PackageInstaller
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.SearchOff
import org.matrix.vector.ui.SharedAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch
import org.lsposed.lspatch.R
import org.lsposed.lspatch.data.model.PatchMode
import org.lsposed.lspatch.data.model.PatchOrigin
import org.lsposed.lspatch.data.model.PatchRequest
import org.lsposed.lspatch.data.model.PatchTarget
import org.lsposed.lspatch.data.repository.PatchRequestStore
import org.lsposed.lspatch.ui.navigation.AppDetail
import org.lsposed.lspatch.ui.navigation.NewPatch
import org.lsposed.lspatch.ui.util.LocalSnackbarHost
import org.lsposed.lspatch.ui.viewmodel.SelectPatchTargetViewModel
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.LSPPackageManager.AppInfo
import org.matrix.vector.ui.PackageRow
import org.matrix.vector.ui.PanelEmptyState
import org.matrix.vector.ui.SearchField
import org.matrix.vector.ui.navigation.Navigator

// Plain apks plus app bundles: .xapk/.apks/.apkm carry no registered mime, so most file providers
// report them as zip or octet-stream — both are allowed so a bundle can be picked and unzipped.
internal val APK_AND_BUNDLE_TYPES =
    arrayOf(
        "application/vnd.android.package-archive",
        "application/zip",
        "application/octet-stream",
    )

/**
 * What to patch -- the single entry into the patch flow, from Home and from Manage alike.
 *
 * The choice used to be made by a dialog offering "an installed app" or "apk from storage", which one entry point
 * showed and the other did not. There is nothing to choose between: the installed apps *are* the list, and a file from
 * storage is one more way to name a target, so it lives as an action in the bar rather than as a fork the user has to
 * take before they can see anything.
 *
 * Tapping a row is the commit. There is no confirm button, because the next screen is where the patch is configured and
 * where it can still be abandoned.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectPatchTargetScreen(navigator: Navigator) {
    val viewModel = viewModel<SelectPatchTargetViewModel>()
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalSnackbarHost.current
    val errorUnknown = stringResource(R.string.error_unknown)
    var query by remember { mutableStateOf("") }
    var reading by remember { mutableStateOf(false) }
    // An apk picked from storage that is itself already an LSPatch build: there is nothing to patch,
    // so it is offered for install instead, held here until the user confirms.
    var alreadyPatched by remember { mutableStateOf<AppInfo?>(null) }
    var installing by remember { mutableStateOf(false) }

    /**
     * Persists the request and hands over to the patch screen, replacing this one in the back stack: coming back from a
     * patch should return where the patch was started from, not to the picker that has already served its purpose.
     */
    fun commit(request: PatchRequest) {
        scope.launch {
            val token = PatchRequestStore.put(request)
            navigator.replace(NewPatch(token = token))
        }
    }

    /**
     * Installs an already-patched apk picked from storage, then opens its page. The apk and its splits already sit in
     * the temp dir from reading the manifest, so install is a plain session; on success the picker is left behind so
     * Back returns to where patching was started.
     */
    fun installAlreadyPatched(app: AppInfo) {
        scope.launch {
            installing = true
            val apkPaths = listOf(app.app.sourceDir) + (app.app.splitSourceDirs ?: emptyArray())
            val (status, message) = LSPPackageManager.installFiles(apkPaths.map(::File), useShizuku = true)
            installing = false
            alreadyPatched = null
            if (status == PackageInstaller.STATUS_SUCCESS) {
                navigator.replace(AppDetail(packageName = app.app.packageName))
            } else {
                snackbarHost.show(message ?: errorUnknown, SnackbarTone.Failure)
            }
        }
    }

    val storageLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { apks ->
            if (apks.isEmpty()) return@rememberLauncherForActivityResult
            scope.launch {
                reading = true
                LSPPackageManager.getAppInfoFromApks(apks)
                    .onSuccess { infos ->
                        reading = false
                        val primary = infos.first()
                        // An apk that is already an LSPatch build cannot be patched again from here;
                        // offer to install it and send the user to its page to manage loaders/modules.
                        if (primary.isLSPatched) {
                            alreadyPatched = primary
                            return@onSuccess
                        }
                        commit(
                            PatchRequest(
                                token = UUID.randomUUID().toString(),
                                target =
                                    PatchTarget.ApkFiles(
                                        packageName = primary.app.packageName,
                                        label = primary.label,
                                        apkPaths =
                                            listOf(primary.app.sourceDir) +
                                                (primary.app.splitSourceDirs ?: emptyArray()),
                                    ),
                                mode = PatchMode.Local,
                                origin = PatchOrigin.New,
                            )
                        )
                    }
                    .onFailure {
                        reading = false
                        snackbarHost.show(it.message ?: errorUnknown, SnackbarTone.Failure)
                    }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.patch_select_target)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { storageLauncher.launch(APK_AND_BUNDLE_TYPES) }) {
                        Icon(
                            Icons.Rounded.FolderOpen,
                            contentDescription = stringResource(R.string.patch_from_storage),
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.manage_search),
            )
            // Held until the load finishes rather than filled in as it goes: the list is sorted by
            // label, so a partial list re-sorts underneath a reader and moves the row they were
            // reaching for.
            if (viewModel.loading || reading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.patch_target_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
                return@Column
            }
            val shown = viewModel.filtered(query)
            if (shown.isEmpty()) {
                PanelEmptyState(
                    icon = Icons.Rounded.SearchOff,
                    text = stringResource(R.string.manage_no_match),
                )
                return@Column
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                items(items = shown, key = { it.app.packageName }) { app ->
                    // An app already patched by LSPatch has nothing to patch here: it is shown for
                    // context but dimmed and not selectable — it is managed from its own page instead.
                    val patched = app.isLSPatched
                    PackageRow(
                        icon = {
                            AppIcon(
                                applicationInfo = app.app,
                                contentDescription = app.label,
                                size = 24.dp,
                            )
                        },
                        label = app.label,
                        packageName = app.app.packageName,
                        modifier =
                            if (patched) Modifier.alpha(0.38f) else Modifier.clickable { commit(newRequestFor(app)) },
                        additionalContent =
                            if (patched) {
                                {
                                    Text(
                                        text = stringResource(R.string.patch_target_already_patched),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            } else null,
                    )
                }
            }
        }
    }

    alreadyPatched?.let { app ->
        SharedAlertDialog(
            onDismissRequest = { if (!installing) alreadyPatched = null },
            title = { Text(stringResource(R.string.patch_storage_patched_title)) },
            text = {
                if (installing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(
                            text = stringResource(R.string.patch_storage_installing),
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                } else {
                    Text(stringResource(R.string.patch_storage_patched_text, app.label))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { installAlreadyPatched(app) },
                    enabled = !installing,
                ) {
                    Text(stringResource(R.string.patch_action_install))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { alreadyPatched = null },
                    enabled = !installing,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
