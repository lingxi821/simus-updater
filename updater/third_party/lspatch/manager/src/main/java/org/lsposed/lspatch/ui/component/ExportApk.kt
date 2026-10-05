package org.lsposed.lspatch.ui.component

import org.matrix.vector.ui.show
import org.matrix.vector.ui.SnackbarTone
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.lspatch.R
import org.lsposed.lspatch.ui.util.LocalSnackbarHost
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Handed back by [rememberExportApk]; call [export] with the apks to save. */
class ExportApkLauncher internal constructor(
    private val onExport: (String, List<File>) -> Unit,
) {
    fun export(label: String, files: List<File>) = onExport(label, files)
}

/**
 * Saves patched apks wherever the user asks, once, on demand.
 *
 * Exporting is the only thing the old storage-directory grant was really for, and it was demanded
 * up front of everyone whether or not they ever wanted a copy -- which is what made patching depend
 * on a persisted permission and fail outright when one entry point never asked for it. A one-shot
 * grant at the moment of asking needs no permission to persist and nothing to go stale.
 *
 * One apk is written as an apk. Several are written as a zip, because a single grant yields a single
 * file, and silently exporting only the base of a split app would produce something that cannot be
 * installed.
 */
@Composable
fun rememberExportApk(): ExportApkLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalSnackbarHost.current
    val running = stringResource(R.string.patch_export_running)
    val done = stringResource(R.string.patch_export_done)
    val failed = stringResource(R.string.patch_export_failed)

    var pending by remember { mutableStateOf<List<File>>(emptyList()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val files = pending
        pending = emptyList()
        if (uri == null || files.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            // Shown from a coroutine of its own, because showing a message suspends until it is
            // dismissed: awaited here, the copy would not begin until the message saying it had
            // begun was already gone.
            launch { snackbarHost.show(running, SnackbarTone.Working) }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        if (files.size == 1) {
                            files.first().inputStream().use { it.copyTo(output) }
                        } else {
                            ZipOutputStream(output).use { zip ->
                                files.forEach { file ->
                                    zip.putNextEntry(ZipEntry(file.name))
                                    file.inputStream().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                            }
                        }
                    } ?: throw java.io.IOException("Cannot write to the chosen location")
                }.isSuccess
            }
            snackbarHost.show(
                if (ok) done else failed,
                if (ok) SnackbarTone.Success else SnackbarTone.Failure,
            )
        }
    }

    return remember {
        ExportApkLauncher { label, files ->
            if (files.isEmpty()) return@ExportApkLauncher
            pending = files
            val safe = label.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "patched" }
            launcher.launch(if (files.size == 1) "$safe.apk" else "$safe-apks.zip")
        }
    }
}
