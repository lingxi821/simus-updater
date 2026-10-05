package org.lsposed.lspatch.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.share.LSPConfig

/**
 * Holds the LSPatch GitHub repository's public status (stars / forks / open issues / license) for the
 * home footer. Anonymous GitHub API, best-effort: it stays null until a request succeeds, so the
 * footer simply doesn't render rather than showing zeros.
 */
class HomeViewModel : ViewModel() {

    data class RepoStatus(val stars: Int, val forks: Int, val openIssues: Int, val license: String?)

    /**
     * A newer stable LSPatch release than the installed one -- the sole cue the version line's update
     * mark needs. Only its existence is read (the mark is on/off); the update page owns the notes,
     * assets and install, so nothing more is carried here and there is no asset name to match wrong.
     */
    data class Update(val version: String)

    var repo by mutableStateOf<RepoStatus?>(null)
        private set

    var update by mutableStateOf<Update?>(null)
        private set

    /** True while a release check is in flight, so callers can avoid overlapping requests. */
    var checkingUpdate by mutableStateOf(false)
        private set

    init {
        refresh()
        checkUpdate()
    }

    fun refresh() {
        viewModelScope.launch {
            val fetched = withContext(Dispatchers.IO) { fetch() }
            if (fetched != null) repo = fetched
        }
    }

    /**
     * Checks GitHub for a newer release than the installed build, so the header can mark the version
     * the way Vector marks its framework version. Best-effort and anonymous, like [refresh]: it
     * stays null on any failure, so the version simply shows unmarked rather than claiming to be
     * current when the check never ran.
     */
    fun checkUpdate() {
        if (checkingUpdate) return
        viewModelScope.launch {
            checkingUpdate = true
            update = withContext(Dispatchers.IO) { fetchLatest() }
            checkingUpdate = false
        }
    }

    private fun fetchLatest(): Update? = runCatching {
        val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "LSPatch-Manager")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val obj = JsonParser.parseString(json).asJsonObject
            val tag = obj.get("tag_name")?.takeIf { !it.isJsonNull }?.asString ?: return null
            val latest = tag.trimStart('v', 'V').trim()
            val current = LSPConfig.instance.VERSION_NAME.trimStart('v', 'V').trim()
            // Never mark anything at or below the current build, nor older than the v0.8 baseline
            // (this UI first shipped in v0.8; earlier releases predate it and must not be surfaced).
            // The endpoint is /releases/latest, which excludes prereleases, so the mark is stable-only.
            if (!isNewer(latest, current) || isNewer(MIN_VERSION, latest)) return null
            Update(tag)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

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

    private fun fetch(): RepoStatus? = runCatching {
        val connection = (URL(REPO_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "LSPatch-Manager")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val obj = JsonParser.parseString(json).asJsonObject
            val licenseEl = obj.get("license")
            val license = if (licenseEl != null && !licenseEl.isJsonNull) {
                licenseEl.asJsonObject.get("spdx_id")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it != "NOASSERTION" }
            } else null
            RepoStatus(
                stars = obj.get("stargazers_count")?.asInt ?: 0,
                forks = obj.get("forks_count")?.asInt ?: 0,
                openIssues = obj.get("open_issues_count")?.asInt ?: 0,
                license = license,
            )
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    companion object {
        const val OWNER_REPO = "JingMatrix/LSPatch"
        const val REPO_URL = "https://github.com/$OWNER_REPO"
        private const val REPO_API = "https://api.github.com/repos/$OWNER_REPO"
        private const val RELEASES_API = "https://api.github.com/repos/$OWNER_REPO/releases/latest"
        private const val MIN_VERSION = "0.8"
    }
}
