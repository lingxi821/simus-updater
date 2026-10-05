package org.lsposed.lspatch.data.repository

import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lsposed.lspatch.data.model.PatchRequest
import org.lsposed.lspatch.data.model.PatchTarget
import org.lsposed.lspatch.lspApp

/**
 * Holds the [PatchRequest] a patch screen is working on, addressed by an opaque token.
 *
 * The screen takes a token, not the request itself, for two reasons. compose-destinations passes navigation arguments
 * through the back stack's `SavedStateHandle`, which is written into the saved instance state Bundle -- so a request
 * carrying apk paths and a module list would be serialised on every process save, and anything `Parcelable` in it would
 * have to stay parcelable forever. And a request on disk means the configure screen can be re-entered after the process
 * is killed, instead of coming back to a `lateinit` that was never initialised.
 */
object PatchRequestStore {

    private const val TAG = "PatchRequestStore"

    /** Written by Gson so [PatchTarget]'s subtype survives the round trip. */
    private const val TARGET_KIND = "kind"

    // Spelled out rather than read off the class. A class name is not an identity once the build
    // shrinker is allowed to rewrite it, and a discriminator that changes from one build to the
    // next reads every stored subtype back as whichever one the fallback names.
    private const val KIND_INSTALLED_APP = "InstalledApp"
    private const val KIND_APK_FILES = "ApkFiles"
    private const val KIND_RECOVERED_ORIGIN = "RecoveredOrigin"

    private fun kindOf(target: PatchTarget): String =
        when (target) {
            is PatchTarget.InstalledApp -> KIND_INSTALLED_APP
            is PatchTarget.ApkFiles -> KIND_APK_FILES
            is PatchTarget.RecoveredOrigin -> KIND_RECOVERED_ORIGIN
        }

    private val gson =
        GsonBuilder()
            .registerTypeAdapter(
                PatchTarget::class.java,
                JsonSerializer<PatchTarget> { src, _, context ->
                    context.serialize(src, src::class.java).asJsonObject.apply {
                        add(TARGET_KIND, JsonPrimitive(kindOf(src)))
                    }
                },
            )
            .registerTypeAdapter(
                PatchTarget::class.java,
                JsonDeserializer { json, _, context ->
                    val obj = json as JsonObject
                    val type =
                        when (obj.get(TARGET_KIND)?.asString) {
                            KIND_APK_FILES -> PatchTarget.ApkFiles::class.java
                            KIND_RECOVERED_ORIGIN -> PatchTarget.RecoveredOrigin::class.java
                            else -> PatchTarget.InstalledApp::class.java
                        }
                    context.deserialize<PatchTarget>(obj, type)
                },
            )
            .create()

    private val dir: File
        get() = lspApp.noBackupFilesDir.resolve("patch-requests").also { it.mkdirs() }

    private fun fileFor(token: String) = dir.resolve("$token.json")

    /** Persists [request] under a fresh token and returns it. */
    suspend fun put(request: PatchRequest): String =
        withContext(Dispatchers.IO) {
            val token = request.token.ifBlank { UUID.randomUUID().toString() }
            val stored = if (token == request.token) request else request.copy(token = token)
            fileFor(token).writeText(gson.toJson(stored))
            token
        }

    /** Overwrites the request already stored under its own token -- used as the draft is edited. */
    suspend fun update(request: PatchRequest) =
        withContext(Dispatchers.IO) {
            fileFor(request.token).writeText(gson.toJson(request))
            Unit
        }

    suspend fun get(token: String): PatchRequest? =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = fileFor(token)
                if (!file.exists()) null else gson.fromJson(file.readText(), PatchRequest::class.java)
            }
                .onFailure { Log.w(TAG, "Could not read request $token", it) }
                .getOrNull()
        }

    suspend fun drop(token: String) =
        withContext(Dispatchers.IO) {
            fileFor(token).delete()
            Unit
        }

    /**
     * Drops requests older than a day.
     *
     * A request is abandoned the moment its screen is left without patching, and nothing else ever deletes it. They are
     * tiny, so the window is generous -- long enough that a request survives the app being killed and reopened, short
     * enough that they do not accumulate for the life of the install.
     */
    suspend fun prune() =
        withContext(Dispatchers.IO) {
            runCatching {
                val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
                dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
            }
                .onFailure { Log.w(TAG, "Prune failed", it) }
            Unit
        }
}
