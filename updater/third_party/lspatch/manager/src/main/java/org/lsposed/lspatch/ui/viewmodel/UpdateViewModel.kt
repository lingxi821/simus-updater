package org.lsposed.lspatch.ui.viewmodel

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.share.LSPConfig
import org.lsposed.lspatch.util.LSPPackageManager
import org.matrix.vector.ui.update.VariantChoice

/**
 * Backs the full-screen self-update page: it fetches LSPatch's release history from GitHub, holds
 * the release the reader is looking at for the shared HTML renderer, and drives the
 * download-and-install the install bar starts.
 *
 * The whole release list is fetched, not only the newest, so the page can offer a version-history
 * switcher (the same shape Vector's framework updater has) and so a reader who is up to date and taps
 * for a look can be shown the newest canary. Which release the page opens on depends on why it was
 * opened: the newest stable normally, or the newest prerelease when the reader asked for one.
 *
 * Independent of [HomeViewModel] on purpose. Home keeps its own lightweight check only to mark the
 * version line, and that check stays on stable releases so the mark never lights for a canary; this
 * owns the screen's copy so the page can re-check, switch versions and install on its own.
 */
class UpdateViewModel : ViewModel() {

    /**
     * One LSPatch release: its tag, page, notes and manager apk assets, plus which channel it is on.
     *
     * [newer] is true only for a stable release above the installed build (a canary is never an
     * "update" to offer -- it is a build a reader asks for on purpose). [apks] are the manager apk
     * assets the release published, one per variant (Release, Debug), so the shared variant picker
     * can drive the choice.
     */
    data class Update(
        val version: String,
        val tag: String,
        val url: String,
        val notes: String,
        val apks: List<ApkAsset>,
        val prerelease: Boolean,
        val publishedEpoch: Long,
        val newer: Boolean,
    )

    /**
     * One manager apk a release published: which variant it is, where to fetch it, and how big.
     *
     * [key] is [VariantChoice.RELEASE] or [VariantChoice.DEBUG] -- the same keys the shared picker
     * labels and the running build is matched against. The two variants are signed with different
     * keys, so installing the one that does not match the running build is refused by the platform
     * as a signature mismatch; the picker still offers both, and the OS asks to uninstall first.
     */
    data class ApkAsset(
        val key: String,
        val name: String,
        val url: String,
        val sizeInBytes: Long,
    )

    /** Where the selected release sits relative to the running build -- what the install bar says. */
    enum class Relation {
        /** Nothing selected yet (still loading, or the check failed). */
        None,
        /** A stable release newer than the installed build: an update to offer. */
        UpdateAvailable,
        /** The build that is running: a reinstall, not an update. */
        Current,
        /** Anything else the reader navigated to -- an older stable, or a canary they want to try. */
        Other,
    }

    /** Progress of a self-update the user has started from the install bar. */
    sealed interface UpdateStage {
        data object Idle : UpdateStage
        /** [progress] is 0f..1f, or -1f while the total size is unknown. */
        data class Downloading(val progress: Float) : UpdateStage
        data object Installing : UpdateStage
        data class Failed(val message: String) : UpdateStage
    }

    /** Every release fetched, newest first, both channels -- the version-history list. */
    var history by mutableStateOf<List<Update>>(emptyList())
        private set

    /** The release the page is currently showing; its notes, apks and links drive the whole screen. */
    var selected by mutableStateOf<Update?>(null)
        private set

    var updateStage by mutableStateOf<UpdateStage>(UpdateStage.Idle)
        private set

    /** True while a release check is in flight, so the screen can say it is checking. */
    var checkingUpdate by mutableStateOf(false)
        private set

    /** Whether the running build is the debug variant -- the default the variant picker opens on. */
    private val runningDebuggable =
        (lspApp.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * The variant the reader has chosen, defaulting to the one they are running: a debug build
     * self-installs from the debug apk, since the two variants are signed differently.
     */
    var chosenVariant by mutableStateOf(
        if (runningDebuggable) VariantChoice.DEBUG else VariantChoice.RELEASE
    )
        private set

    /** The apk that will be installed: the chosen variant of the selected release, or whatever it published. */
    val chosenApk: ApkAsset?
        get() = selected?.apks?.let { apks ->
            apks.firstOrNull { it.key == chosenVariant } ?: apks.firstOrNull()
        }

    /** Where the selected release stands relative to the running build. */
    val relation: Relation
        get() {
            val sel = selected ?: return Relation.None
            val installed = LSPConfig.instance.VERSION_NAME.trimStart('v', 'V').trim()
            return when {
                sel.newer -> Relation.UpdateAvailable
                !sel.prerelease && sel.version.trimStart('v', 'V').trim() == installed -> Relation.Current
                else -> Relation.Other
            }
        }

    fun chooseVariant(key: String) {
        chosenVariant = key
    }

    /** Switch the page to a specific release from the history list. */
    fun select(tag: String) {
        history.firstOrNull { it.tag == tag }?.let { selected = it }
    }

    private val installedVersion
        get() = LSPConfig.instance.VERSION_NAME.trimStart('v', 'V').trim()

    /** Whether [update] is the build currently running (a stable release matching this version name). */
    fun isInstalled(update: Update): Boolean =
        !update.prerelease && update.version.trimStart('v', 'V').trim() == installedVersion

    /** Whether [update] is an older stable release than the one running. */
    fun isOlder(update: Update): Boolean =
        !update.prerelease && isNewer(installedVersion, update.version.trimStart('v', 'V').trim())

    /**
     * Fetches the release history from GitHub and opens the page on a sensible release.
     *
     * Best-effort and anonymous: on any failure the history stays as it was and [selected] stays null,
     * so the screen reports it could not check rather than claiming an update it never confirmed.
     *
     * @param preferPrerelease open on the newest prerelease (canary) rather than the newest stable --
     *   what a reader asks for by tapping the version line when no stable update is marked.
     */
    fun checkUpdate(preferPrerelease: Boolean = false) {
        if (checkingUpdate) return
        viewModelScope.launch {
            checkingUpdate = true
            val fetched = withContext(Dispatchers.IO) { fetchReleases() }
            if (fetched != null && fetched.isNotEmpty()) {
                history = fetched
                // Keep the reader on the release they were looking at across a re-check; otherwise
                // open on the newest prerelease when that is what was asked for, else the newest stable.
                val keep = selected?.let { prev -> fetched.firstOrNull { it.tag == prev.tag } }
                selected = keep
                    ?: if (preferPrerelease) fetched.firstOrNull { it.prerelease } else null
                        ?: fetched.firstOrNull { !it.prerelease }
                        ?: fetched.first()
            }
            checkingUpdate = false
        }
    }

    private fun fetchReleases(): List<Update>? = runCatching {
        val connection = (URL(RELEASES_LIST_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "LSPatch-Manager")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val array = JsonParser.parseString(json).asJsonArray
            val current = LSPConfig.instance.VERSION_NAME.trimStart('v', 'V').trim()
            array.mapNotNull { element ->
                val obj = element.asJsonObject
                if (obj.get("draft")?.takeIf { !it.isJsonNull }?.asBoolean == true) return@mapNotNull null
                val tag = obj.get("tag_name")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                val url = obj.get("html_url")?.takeIf { !it.isJsonNull }?.asString ?: "$REPO_URL/releases"
                val notes = obj.get("body")?.takeIf { !it.isJsonNull }?.asString.orEmpty().trim()
                val prerelease = obj.get("prerelease")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                val published = obj.get("published_at")?.takeIf { !it.isJsonNull }?.asString
                val epoch = published?.let { runCatching { java.time.Instant.parse(it).epochSecond }.getOrNull() } ?: 0L
                val version = tag.trimStart('v', 'V').trim()
                // Everything before v0.8 predates this UI and is never shown: a stable release older
                // than the baseline is dropped outright. Canaries carry no comparable version in their
                // tag (canary-<n>) and are the current state of master, so they are always kept.
                if (!prerelease && isNewer(MIN_VERSION, version)) return@mapNotNull null
                // Only a stable release above the current build is an update to *offer*. A canary is
                // not: it is a build the reader chooses on purpose, never one pushed at them.
                val newer = !prerelease && isNewer(version, current)
                val apks = parseApkAssets(obj.getAsJsonArray("assets"))
                Update(version, tag, url, notes, apks, prerelease, epoch, newer)
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /**
     * Downloads the selected release's apk and hands it to the platform installer. Progress is
     * surfaced through [updateStage]; the install shows the OS confirmation dialog, so on success this
     * process is replaced and nothing further runs here.
     */
    fun downloadAndInstall() {
        val target = chosenApk?.url ?: return
        if (updateStage is UpdateStage.Downloading || updateStage is UpdateStage.Installing) return
        viewModelScope.launch {
            updateStage = UpdateStage.Downloading(-1f)
            val apk = withContext(Dispatchers.IO) { runCatching { download(target) }.getOrNull() }
            if (apk == null) {
                updateStage = UpdateStage.Failed("Download failed")
                return@launch
            }
            updateStage = UpdateStage.Installing
            val (status, message) = LSPPackageManager.installApk(apk)
            if (status != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                updateStage = UpdateStage.Failed(message ?: "Install failed")
            } else {
                updateStage = UpdateStage.Idle
            }
        }
    }

    fun dismissUpdate() {
        if (updateStage !is UpdateStage.Downloading) updateStage = UpdateStage.Idle
    }

    private fun download(fileUrl: String): File {
        val out = File(lspApp.cacheDir, "update.apk")
        val connection = (URL(fileUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LSPatch-Manager")
        }
        try {
            if (connection.responseCode !in 200..299) throw java.io.IOException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong
            var read = 0L
            connection.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        updateStage = UpdateStage.Downloading(if (total > 0) read.toFloat() / total else -1f)
                    }
                }
            }
            return out
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The manager apks a release published, at most one per variant.
     *
     * Every `.apk` asset is read and sorted into Debug (its name carries the `debug` marker) or
     * Release (it does not), so the real published names -- `manager-v<ver>-<code>-release.apk` /
     * `-debug.apk` -- parse correctly. Assets without a download URL are dropped, as is the `.jar`.
     */
    private fun parseApkAssets(assets: com.google.gson.JsonArray?): List<ApkAsset> {
        val apks = assets
            ?.map { it.asJsonObject }
            ?.filter { it.get("name")?.asString?.endsWith(".apk", ignoreCase = true) == true }
            .orEmpty()
        fun asset(a: com.google.gson.JsonObject): ApkAsset? {
            val name = a.get("name")?.asString ?: return null
            val dl = a.get("browser_download_url")?.takeIf { !it.isJsonNull }?.asString ?: return null
            val size = a.get("size")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            val key =
                if (name.contains("debug", ignoreCase = true)) VariantChoice.DEBUG
                else VariantChoice.RELEASE
            return ApkAsset(key, name, dl, size)
        }
        // One per variant; when several match, the first wins (releases publish one apk per variant).
        return apks.mapNotNull { asset(it) }
            .groupBy { it.key }
            .mapNotNull { (_, group) -> group.firstOrNull() }
    }

    /** Dotted numeric compare, tolerant of suffixes; a non-numeric part sorts as 0. */
    private fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split('.', '-')
        val c = current.split('.', '-')
        for (i in 0 until maxOf(l.size, c.size)) {
            val li = l.getOrNull(i)?.toIntOrNull() ?: 0
            val ci = c.getOrNull(i)?.toIntOrNull() ?: 0
            if (li != ci) return li > ci
        }
        return false
    }

    companion object {
        const val OWNER_REPO = "JingMatrix/LSPatch"
        const val REPO_URL = "https://github.com/$OWNER_REPO"
        // The list endpoint, newest first, both channels -- so the history switcher and the
        // prerelease-on-demand open can see canaries, which /releases/latest deliberately hides.
        private const val RELEASES_LIST_API = "https://api.github.com/repos/$OWNER_REPO/releases?per_page=20"
        private const val MIN_VERSION = "0.8"
    }
}
