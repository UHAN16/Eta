package io.github.mangi.eta.data.update

import io.github.mangi.eta.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

internal data class AppLatestRelease(val version: String, val url: HttpUrl, val apk: UpdateAsset?)
internal data class UpdateAsset(val url: HttpUrl, val size: Long, val sha256: String)

internal object AppUpdateChecker {
    const val MAX_APK_BYTES = 128L * 1024 * 1024
    private const val MAX_RELEASE_METADATA_BYTES = 2L * 1024 * 1024
    private val stableTag = Regex("v[0-9]{1,9}(\\.[0-9]{1,9}){2}")
    private val digestPattern = Regex("sha256:([A-Fa-f0-9]{64})")

    suspend fun fetchLatest(): AppLatestRelease? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Eta-Update-Checker")
            .build()
        return updateHttp.newCall(request).readCancellable { response ->
            if (response.code == 404) return@readCancellable null
            if (!response.isSuccessful) throw IOException("Release status: ${response.code}")
            val source = response.body.source()
            if (source.request(MAX_RELEASE_METADATA_BYTES + 1)) throw IOException("Oversized release metadata")
            parseRelease(JSONObject(source.readUtf8()), BuildConfig.UPDATE_REPOSITORY)
        }
    }

    internal fun parseRelease(json: JSONObject, repository: String): AppLatestRelease? {
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val tag = json.optString("tag_name")
        if (!stableTag.matches(tag)) throw IOException("Invalid stable release tag")
        val base = "https://github.com/$repository/releases"
        // Construct these URLs ourselves; a response cannot select another repository or arbitrary host.
        val page = "$base/tag/$tag".toHttpUrl()
        val name = "Eta-$tag.apk"
        val expected = "$base/download/$tag/$name".toHttpUrl()
        val assets = json.optJSONArray("assets")
        val matching = (0 until (assets?.length() ?: 0)).mapNotNull { assets?.optJSONObject(it) }
            .filter { it.optString("name") == name }
        val asset = matching.singleOrNull()?.let { item ->
            val size = item.optLong("size", -1)
            val digest = digestPattern.matchEntire(item.optString("digest"))?.groupValues?.get(1)?.lowercase()
            if (item.optString("state") == "uploaded" &&
                size in 1..MAX_APK_BYTES && digest != null &&
                item.optString("browser_download_url").toHttpUrlOrNull() == expected
            ) UpdateAsset(expected, size, digest) else null
        }
        return AppLatestRelease(tag.removePrefix("v"), page, asset)
    }

    fun isNewer(latest: String, current: String): Boolean {
        val latestParts = latest.removePrefix("v").split('.')
        val currentParts = current.removePrefix("v").substringBefore('+').substringBefore('-').split('.')
        for (index in 0 until maxOf(latestParts.size, currentParts.size)) {
            val latestPart = latestParts.getOrNull(index) ?: "0"
            val currentPart = currentParts.getOrNull(index) ?: "0"
            val compared = if (latestPart.toIntOrNull() != null && currentPart.toIntOrNull() != null) {
                latestPart.toInt().compareTo(currentPart.toInt())
            } else latestPart.compareTo(currentPart)
            if (compared != 0) return compared > 0
        }
        return false
    }
}

internal val updateHttp = OkHttpClient.Builder()
    .followRedirects(false)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(3, TimeUnit.MINUTES)
    .build()

/** Keep cancellation wired during the response-body read, not just while waiting for headers. */
internal suspend fun <T> Call.readCancellable(read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching { response.use(read) })
            }
        })
    }
