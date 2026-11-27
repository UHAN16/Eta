package io.github.mangi.eta.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Request

internal enum class UpdateIssue { INTEGRITY, PACKAGE, VERSION, SIGNATURE, SYSTEM }

internal class UpdateException(val issue: UpdateIssue) : IOException(issue.name)

internal data class ApkIdentity(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val signers: Set<String>,
    val minSdk: Int,
)

internal object AppUpdateInstaller {
    private const val CACHE_DIR = "app_updates"
    private val stagedName = Regex("eta-update-[0-9]+\\.apk")
    private val signingFlags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())

    suspend fun downloadAndVerify(
        context: Context,
        release: AppLatestRelease,
        onProgress: (Int) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val asset = release.apk ?: throw IOException("No verifiable release APK")
        val directory = File(context.cacheDir, CACHE_DIR)
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Cannot create update cache")
        val staleBefore = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L
        directory.listFiles()?.filter { stagedName.matches(it.name) && it.lastModified() < staleBefore }
            ?.forEach { it.delete() }
        val file = File.createTempFile("eta-update-", ".apk", directory)
        var verified = false
        try {
            // Open the file here so a cancelled network callback can never recreate a deleted file.
            file.outputStream().buffered().use { output -> download(asset, output, onProgress) }
            val manager = context.packageManager
            val installed = manager.getPackageInfo(context.packageName, signingFlags).identity()
            val candidate = manager.getPackageArchiveInfo(file.absolutePath, signingFlags)?.identity()
                ?: throw UpdateException(UpdateIssue.PACKAGE)
            validateIdentity(installed, candidate, release.version, Build.VERSION.SDK_INT)
            currentCoroutineContext().ensureActive()
            verified = true
            file.name
        } finally {
            if (!verified) file.delete()
        }
    }

    private suspend fun download(asset: UpdateAsset, output: OutputStream, onProgress: (Int) -> Unit) {
        var url = asset.url
        repeat(4) {
            val request = Request.Builder().url(url).header("Accept-Encoding", "identity").build()
            val next = updateHttp.newCall(request).readCancellable { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location")?.let(response.request.url::resolve)
                    if (location == null || !allowedAssetRedirect(location, asset)) {
                        throw IOException("Untrusted APK redirect")
                    }
                    return@readCancellable location
                }
                if (response.code != 200) throw IOException("APK status: ${response.code}")
                val length = response.body.contentLength()
                if (length >= 0L && length != asset.size) throw UpdateException(UpdateIssue.INTEGRITY)
                response.body.byteStream().use { input ->
                    verifyCopy(input, output, asset, onProgress)
                }
                null
            }
            if (next == null) return
            url = next
        }
        throw IOException("Too many APK redirects")
    }

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", context.packageName, null))

    fun installIntent(context: Context, name: String): Intent {
        val file = File(File(context.cacheDir, CACHE_DIR), name)
        if (!stagedName.matches(name) || !file.isFile) throw IOException("Verified update no longer cached")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.update-files", file)
        return packageInstallerIntent(uri)
    }

    private fun PackageInfo.identity() = ApkIdentity(
        packageName, versionName, longVersionCode,
        signingInfo?.apkContentsSigners.orEmpty().map { signature ->
            sha256().digest(signature.toByteArray()).toHex()
        }.toSet(),
        applicationInfo?.minSdkVersion ?: Int.MAX_VALUE,
    )
}

internal fun packageInstallerIntent(uri: Uri): Intent =
    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

internal fun allowedAssetRedirect(url: HttpUrl, asset: UpdateAsset? = null): Boolean {
    if (!url.isHttps || url.port != 443 || url.encodedUsername.isNotEmpty() ||
        url.encodedPassword.isNotEmpty() || url.fragment != null) return false
    if (asset?.url?.host != "gitee.com") {
        return url.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }
    // First hop stays on this exact repository/attachment. The second uses Gitee's attachment CDN.
    val segments = asset.url.pathSegments
    val id = asset.attachmentId ?: return false
    if (id <= 0 || segments.size != 6) return false
    val attachmentPath = "/${segments[0]}/${segments[1]}/attach_files/$id/download/${segments.last()}"
    val cdnSegments = url.pathSegments
    return (url.host == "gitee.com" && url.encodedPath == attachmentPath && url.query == null) ||
        (url.host == "foruda.gitee.com" && cdnSegments.size == 3 && cdnSegments[0] == "attach_file" &&
            cdnSegments[1].all(Char::isDigit) && cdnSegments[1].isNotEmpty() &&
            cdnSegments[2] in setOf(segments.last(), segments.last().lowercase()))
}

internal fun verifyCopy(input: InputStream, output: OutputStream, asset: UpdateAsset, onProgress: (Int) -> Unit) {
    if (asset.size !in 1..AppUpdateChecker.MAX_APK_BYTES) throw UpdateException(UpdateIssue.INTEGRITY)
    val digest = sha256()
    val buffer = ByteArray(64 * 1024)
    var count = 0L
    var lastPercent = -1
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        count += read
        if (count > asset.size) throw UpdateException(UpdateIssue.INTEGRITY)
        digest.update(buffer, 0, read)
        output.write(buffer, 0, read)
        val percent = (count * 100 / asset.size).toInt()
        if (percent != lastPercent) {
            onProgress(percent)
            lastPercent = percent
        }
    }
    if (count != asset.size || digest.digest().toHex() != asset.sha256) throw UpdateException(UpdateIssue.INTEGRITY)
}

internal fun validateIdentity(installed: ApkIdentity, candidate: ApkIdentity, releaseVersion: String, sdk: Int) {
    if (candidate.packageName != installed.packageName) throw UpdateException(UpdateIssue.PACKAGE)
    if (candidate.versionCode <= installed.versionCode || candidate.versionName != releaseVersion) {
        throw UpdateException(UpdateIssue.VERSION)
    }
    // Fail closed on unknown signers or signer rotation until both channels have an explicit rotation policy.
    if (candidate.signers.isEmpty() || candidate.signers != installed.signers) throw UpdateException(UpdateIssue.SIGNATURE)
    if (candidate.minSdk > sdk) throw UpdateException(UpdateIssue.SYSTEM)
}

private fun sha256() = MessageDigest.getInstance("SHA-256")
private fun ByteArray.toHex() = toHexString()
