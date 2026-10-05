package org.lsposed.lspatch.util

import android.util.Log
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object GithubReleaseDownloader {

    private const val TAG = "GithubReleaseDownloader"
    private const val RELEASES_API =
        "https://api.github.com/repos/JingMatrix/LSPatch/releases/latest"
    private const val USER_AGENT = "LSPatch-Manager"

    data class Result(val tagName: String, val assetName: String, val file: File)

    /**
     * Downloads the latest stable manager APK from GitHub Releases.
     *
     * `/releases/latest` resolves to the newest non-prerelease tag (canaries are published as
     * prereleases), so recovery always lands on a stable build. The published assets are named
     * `manager-v<ver>-<code>-release.apk` / `-debug.apk` (see the build's rename rule), so the match
     * is by that shape -- the earlier exact `manager.apk` / `manager-debug.apk` literals never existed
     * in a real release, which is why revert always failed with "No manager APK found". The release
     * variant is preferred; the debug one is the fallback.
     */
    fun downloadLatestManager(dest: File): Result {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()

        val releaseJson = httpGetString(RELEASES_API)
        val root = JSONObject(releaseJson)
        val tag = root.optString("tag_name", "unknown")
        val assets = root.getJSONArray("assets")

        var preferredUrl: String? = null
        var preferredName: String? = null
        var fallbackUrl: String? = null
        var fallbackName: String? = null

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            val url = asset.getString("browser_download_url")
            val isManagerApk =
                name.startsWith("manager", ignoreCase = true) && name.endsWith(".apk", ignoreCase = true)
            when {
                !isManagerApk -> {} // Skip the .jar and any non-manager asset attached to the release.
                name.contains("debug", ignoreCase = true) -> {
                    fallbackUrl = url
                    fallbackName = name
                }
                else -> {
                    preferredUrl = url
                    preferredName = name
                }
            }
        }

        val downloadUrl = preferredUrl ?: fallbackUrl
            ?: throw IllegalStateException("No manager APK found in latest GitHub release")
        val assetName = preferredName ?: fallbackName!!

        Log.i(TAG, "Downloading $assetName ($tag) from $downloadUrl")
        httpDownload(downloadUrl, dest)
        if (!dest.isFile || dest.length() == 0L) {
            throw IllegalStateException("Downloaded APK is empty")
        }
        return Result(tag, assetName, dest)
    }

    // Through the shared client ([LSPNetwork]) so the update check and download resolve the same way
    // as the rest of the manager, DoH included. OkHttp follows the CDN redirects itself, so the
    // manual redirect loop this used to keep is gone.
    private fun httpGetString(url: String): String {
        val request =
            Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .build()
        LSPNetwork.client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("GitHub API HTTP ${response.code}: $body")
            }
            return body
        }
    }

    private fun httpDownload(url: String, dest: File) {
        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/octet-stream")
                .build()
        LSPNetwork.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Download HTTP ${response.code}")
            }
            response.body.byteStream().use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
        }
    }
}
