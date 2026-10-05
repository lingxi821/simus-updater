package org.lsposed.lspatch.ui.page

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date
import org.lsposed.lspatch.R
import org.lsposed.lspatch.share.LSPConfig
import org.lsposed.lspatch.ui.viewmodel.UpdateViewModel
import org.lsposed.lspatch.ui.viewmodel.UpdateViewModel.Relation
import org.matrix.vector.ui.locale.currentLocale
import org.matrix.vector.ui.navigation.Navigator
import org.matrix.vector.ui.store.StoreHtmlPane
import org.matrix.vector.ui.store.releaseMarkdownToHtml
import org.matrix.vector.ui.update.VariantChoice
import org.matrix.vector.ui.update.VariantPicker
import org.matrix.vector.ui.update.VersionHistoryItem
import org.matrix.vector.ui.update.VersionHistorySheet
import org.matrix.vector.ui.update.VersionStatus

/**
 * The full-screen self-update page, modelled on Vector's `FrameworkUpdateScreen` but for the manager apk: the top bar
 * names the selected build and its channel and carries a version-history switcher, the body renders that build's notes
 * through the shared `StoreHtmlPane`, and the bottom bar carries the whole download-and-install act. It is reachable
 * from the version line whether or not an update exists, so "up to date", a re-check, older builds and the newest canary
 * are all in reach.
 *
 * [prerelease] opens the page on the newest canary rather than the newest stable — what the home version line asks for
 * when it is tapped while no stable update is marked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(navigator: Navigator, prerelease: Boolean = false) {
    val vm = viewModel<UpdateViewModel>()
    val selected = vm.selected
    val history = vm.history
    val stage = vm.updateStage
    val checking = vm.checkingUpdate
    val context = LocalContext.current
    val locale = currentLocale()

    // Drive the first check from here rather than the ViewModel's init, so the channel the page was
    // opened for (stable, or canary when the reader went looking) picks which release it lands on.
    LaunchedEffect(prerelease) { vm.checkUpdate(preferPrerelease = prerelease) }

    var versionsOpen by remember { mutableStateOf(false) }

    val openUrl: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    val releasesUrl = selected?.url ?: "${UpdateViewModel.REPO_URL}/releases"

    if (versionsOpen) {
        // Resolved out of the map: stringResource is @Composable and cannot be called inside it.
        val canaryLabel = stringResource(R.string.update_channel_canary)
        val releaseLabel = stringResource(R.string.update_channel_release)
        val installedLabel = stringResource(R.string.update_installed)
        val olderLabel = stringResource(R.string.update_older)
        val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
        val items =
            history.map { entry ->
                val status =
                    when {
                        vm.isInstalled(entry) -> VersionStatus.Installed
                        vm.isOlder(entry) -> VersionStatus.Older
                        else -> VersionStatus.None
                    }
                VersionHistoryItem(
                    id = entry.tag,
                    title = "LSPatch ${entry.tag}",
                    subtitle =
                        listOfNotNull(
                                entry.publishedEpoch.takeIf { it > 0 }?.let { dateFormat.format(Date(it * 1000)) },
                                if (entry.prerelease) canaryLabel else releaseLabel,
                            )
                            .joinToString("  ·  "),
                    statusLabel =
                        when (status) {
                            VersionStatus.Installed -> installedLabel
                            VersionStatus.Older -> olderLabel
                            else -> null
                        },
                    status = status,
                    selected = entry.tag == selected?.tag,
                )
            }
        VersionHistorySheet(
            heading = stringResource(R.string.update_versions),
            items = items,
            onSelect = { tag -> vm.select(tag) },
            onDismiss = { versionsOpen = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "LSPatch " + (selected?.tag ?: "v${LSPConfig.instance.VERSION_NAME}"),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        // The channel of the build being shown, not the running one — it changes as
                        // the reader switches versions.
                        Text(
                            text =
                                stringResource(
                                    if (selected?.prerelease == true) R.string.update_channel_canary
                                    else R.string.update_channel_release
                                ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.update_back),
                        )
                    }
                },
                actions = {
                    // The version-history switcher, once there is more than one build to switch to.
                    if (history.size > 1) {
                        IconButton(onClick = { versionsOpen = true }) {
                            Icon(Icons.Rounded.History, contentDescription = stringResource(R.string.update_versions))
                        }
                    }
                    IconButton(onClick = { openUrl(releasesUrl) }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = stringResource(R.string.update_open_release),
                        )
                    }
                },
            )
        },
        bottomBar = {
            UpdateBar(
                relation = vm.relation,
                hasApk = vm.chosenApk != null,
                checking = checking,
                stage = stage,
                variantChoices = selected?.apks?.map { VariantChoice(it.key, it.sizeInBytes, it.name) }.orEmpty(),
                selectedVariant = vm.chosenVariant,
                onSelectVariant = vm::chooseVariant,
                onInstall = { vm.downloadAndInstall() },
                onOpenReleases = { openUrl(releasesUrl) },
                onCheck = { vm.checkUpdate() },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            val html =
                remember(selected?.tag, selected?.notes) {
                    selected
                        ?.notes
                        ?.takeIf { it.isNotBlank() }
                        ?.let { releaseMarkdownToHtml(it, "https://github.com/JingMatrix/LSPatch") }
                }
            when {
                // The selected build's notes, whichever build that is.
                html != null ->
                    StoreHtmlPane(
                        html = html,
                        modifier = Modifier.fillMaxSize(),
                        onOpenUrl = openUrl,
                        fetchSubresource = null,
                        contextForWebView = null,
                    )
                // Still fetching and nothing selected yet.
                checking && selected == null -> Empty(stringResource(R.string.update_checking))
                // A build is selected, but it published no notes body.
                selected != null -> Empty(stringResource(R.string.update_no_notes))
                // The check failed (network/parse) -- honest about it rather than claiming up to date.
                else -> Empty(stringResource(R.string.update_check_failed))
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The bottom install bar, mirroring Vector's `UpdateBar` shape: a running download or install shows progress and its
 * label, a failure offers a retry, and while idle the primary action follows the selected build's relation to the
 * running one — install an available update, reinstall the current build, install some other build the reader navigated
 * to, or (when nothing loaded) re-check.
 */
@Composable
private fun UpdateBar(
    relation: Relation,
    hasApk: Boolean,
    checking: Boolean,
    stage: UpdateViewModel.UpdateStage,
    variantChoices: List<VariantChoice>,
    selectedVariant: String,
    onSelectVariant: (String) -> Unit,
    onInstall: () -> Unit,
    onOpenReleases: () -> Unit,
    onCheck: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(colors.surfaceContainer)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        when (stage) {
            is UpdateViewModel.UpdateStage.Downloading -> {
                if (stage.progress >= 0f) {
                    LinearProgressIndicator(progress = { stage.progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.update_downloading),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            is UpdateViewModel.UpdateStage.Installing ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.update_installing), style = MaterialTheme.typography.labelLarge)
                }
            is UpdateViewModel.UpdateStage.Failed ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stage.message,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onInstall) { Text(stringResource(R.string.update_retry)) }
                }
            UpdateViewModel.UpdateStage.Idle ->
                Column {
                    // The Release/Debug choice sits above the button it answers, whenever the selected
                    // build has an apk. The shared picker draws nothing when only one variant exists,
                    // so this costs a blank row in no case.
                    if (hasApk) {
                        VariantPicker(
                            choices = variantChoices,
                            selectedKey = selectedVariant,
                            onSelect = onSelectVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    when (relation) {
                        // A newer stable release: the plain "download & install".
                        Relation.UpdateAvailable ->
                            Button(
                                onClick = { if (hasApk) onInstall() else onOpenReleases() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    stringResource(
                                        if (hasApk) R.string.update_install else R.string.update_open_release
                                    )
                                )
                            }
                        // The build that is running: say so, keep a re-check in reach, and still offer
                        // to reinstall it -- a legitimate want (repair, or re-apply the current build).
                        Relation.Current ->
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = colors.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = stringResource(R.string.update_up_to_date),
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = onCheck, enabled = !checking) {
                                        Text(stringResource(R.string.update_check))
                                    }
                                }
                                if (hasApk) {
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.update_reinstall))
                                    }
                                }
                            }
                        // Some other build the reader navigated to -- an older stable, or a canary they
                        // want to try. Installing it is the point; the label says so plainly.
                        Relation.Other ->
                            Button(
                                onClick = { if (hasApk) onInstall() else onOpenReleases() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    stringResource(
                                        if (hasApk) R.string.update_install_version else R.string.update_open_release
                                    )
                                )
                            }
                        // Nothing loaded (still checking, or the check failed): a re-check.
                        Relation.None ->
                            Button(onClick = onCheck, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.update_check))
                            }
                    }
                }
        }
    }
}
