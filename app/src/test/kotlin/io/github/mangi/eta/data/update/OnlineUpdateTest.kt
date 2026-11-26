package io.github.mangi.eta.data.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OnlineUpdateTest {
    private val repository = "UHAN16/Eta"
    private val correctUrl = "https://github.com/UHAN16/Eta/releases/download/v4.0.1/Eta-v4.0.1.apk"
    private val body = "apk bytes for testing".encodeToByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(body).toHexString()
    private val installed = ApkIdentity("io.github.mangi.eta", "4.0.0", 10, setOf("cert-a"), 34)
    private val candidate = installed.copy(versionName = "4.0.1", versionCode = 11)

    private fun release(vararg assets: JSONObject) = JSONObject()
        .put("tag_name", "v4.0.1")
        .put("html_url", "https://irrelevant.example")
        .put("assets", JSONArray(assets.toList()))

    private fun asset() = JSONObject().put("name", "Eta-v4.0.1.apk")
        .put("browser_download_url", correctUrl).put("state", "uploaded")
        .put("size", body.size).put("digest", "sha256:$sha")

    @Test fun selectsExactlyTheStableAssetFromConfiguredFork() {
        val release = AppUpdateChecker.parseRelease(release(asset(), asset().put("name", "unrelated.apk")), repository)!!
        assertEquals("4.0.1", release.version)
        assertEquals("https://github.com/UHAN16/Eta/releases/tag/v4.0.1", release.url.toString())
        assertEquals(UpdateAsset(correctUrl.toHttpUrl(), body.size.toLong(), sha), release.apk)
    }

    @Test fun rejectsDraftPrereleaseAndPathTags() {
        assertNull(AppUpdateChecker.parseRelease(release(asset()).put("draft", true), repository))
        assertNull(AppUpdateChecker.parseRelease(release(asset()).put("prerelease", true), repository))
        assertThrows(IOException::class.java) {
            AppUpdateChecker.parseRelease(release(asset()).put("tag_name", "../evil"), repository)
        }
    }

    @Test fun cannotInstallWithMissingOrAmbiguousAssetMetadata() {
        val malformed = listOf(
            release(),
            release(asset(), asset()),
            release(asset().put("state", "new")),
            release(asset().put("size", 0)),
            release(asset().put("size", AppUpdateChecker.MAX_APK_BYTES + 1)),
            release(asset().put("digest", "")),
            release(asset().put("digest", "sha256:not-a-digest")),
            release(asset().put("name", "Eta-v4.0.1-debug.apk")),
        )
        for (metadata in malformed) assertNull(AppUpdateChecker.parseRelease(metadata, repository)?.apk)
    }

    @Test fun rejectsOtherRepositoryHostQueryAndPlaintext() {
        for (url in listOf(
            correctUrl.replace(repository, "Mangi-11/Eta"),
            correctUrl.replace("github.com", "github.com.example.org"),
            correctUrl.replace("https:", "http:"),
            "$correctUrl?token=other",
            correctUrl.replace("github.com", "github.com:444"),
        )) {
            assertNull(AppUpdateChecker.parseRelease(release(asset().put("browser_download_url", url)), repository)?.apk)
        }
    }

    @Test fun redirectsMustStayOnTrustedHttpsAssetHosts() {
        for (url in listOf(
            "https://release-assets.githubusercontent.com/asset?token=opaque",
            "https://objects.githubusercontent.com/asset",
        )) assertTrue(url, allowedAssetRedirect(url.toHttpUrl()))
        for (url in listOf(
            "https://release-assets.githubusercontent.com.example.org/",
            "http://release-assets.githubusercontent.com/",
            "https://release-assets.githubusercontent.com:555/",
            "https://user:password@release-assets.githubusercontent.com/",
            "https://github.com/other/repo",
            "https://127.0.0.1/",
        )) assertFalse(url, allowedAssetRedirect(url.toHttpUrl()))
    }

    @Test fun verifiesBytesAndReportsDownloadProgress() {
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<Int>()
        verifyCopy(body.inputStream(), out, UpdateAsset(correctUrl.toHttpUrl(), body.size.toLong(), sha), progress::add)
        assertArrayEquals(body, out.toByteArray())
        assertEquals(100, progress.last())
    }

    @Test fun rejectsTruncationExtraBytesOrTampering() {
        val expected = UpdateAsset(correctUrl.toHttpUrl(), body.size.toLong(), sha)
        for (bad in listOf(body.copyOf(body.size - 1), body.copyOf(body.size + 1), ByteArray(body.size))) {
            assertEquals(UpdateIssue.INTEGRITY, assertThrows(UpdateException::class.java) {
                verifyCopy(bad.inputStream(), ByteArrayOutputStream(), expected) {}
            }.issue)
        }
    }

    @Test fun requiresExactPackageNameIncreasedCodeAndMatchingReleaseVersion() {
        validateIdentity(installed, candidate, "4.0.1", 34)
        reject(UpdateIssue.PACKAGE, candidate.copy(packageName = "unrelated"))
        reject(UpdateIssue.VERSION, candidate.copy(versionCode = installed.versionCode))
        reject(UpdateIssue.VERSION, candidate.copy(versionCode = installed.versionCode - 1))
        reject(UpdateIssue.VERSION, candidate.copy(versionName = "4.0.0"))
    }

    @Test fun rejectsUnknownOrDifferentSignerSetsAndNewerSystemRequirements() {
        reject(UpdateIssue.SIGNATURE, candidate.copy(signers = emptySet()))
        reject(UpdateIssue.SIGNATURE, candidate.copy(signers = setOf("cert-b")))
        reject(UpdateIssue.SIGNATURE, candidate.copy(signers = setOf("cert-a", "cert-b")))
        reject(UpdateIssue.SYSTEM, candidate.copy(minSdk = 35))
    }

    private fun reject(issue: UpdateIssue, apk: ApkIdentity) {
        assertEquals(issue, assertThrows(UpdateException::class.java) {
            validateIdentity(installed, apk, "4.0.1", 34)
        }.issue)
    }
}
