package io.github.mangi.eta.data.update

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import org.junit.Assert.*
import org.junit.Test

class GiteeUpdateTest {
    private val repository = "a962327460/eta"
    private val url = "https://gitee.com/$repository/releases/download/v3.4.6/Eta-v3.4.6.apk"
    private val sha = "a".repeat(64)
    private fun manifest() = JSONObject().put("schema", 1).put("name", "Eta-v3.4.6.apk")
        .put("size", 42).put("sha256", sha)
    private fun fence(json: JSONObject) = "```eta-update\n$json\n```"
    private fun release(body: String = fence(manifest())) = JSONObject().put("id", 123)
        .put("tag_name", "v3.4.6").put("body", body).put("html_url", "https://evil.example")
    private fun attachment() = JSONObject().put("id", 456).put("name", "Eta-v3.4.6.apk")
        .put("size", 42).put("browser_download_url", url)
    private fun parse(json: JSONObject = release(), vararg attachments: JSONObject) =
        AppUpdateChecker.parseGiteeRelease(json, JSONArray(attachments.toList()), repository)
    private fun asset() = UpdateAsset(url.toHttpUrl(), 42, sha, 456)

    @Test fun giteeEmptyReleaseResponseIsJsonNullNotAnUpdate() {
        assertNull(AppUpdateChecker.parseGiteeLatestMetadata("null"))
        assertNull(AppUpdateChecker.parseGiteeLatestMetadata(" \nnull\n"))
        assertEquals("v3.4.6", AppUpdateChecker.parseGiteeLatestMetadata(release().toString())?.optString("tag_name"))
        assertThrows(JSONException::class.java) { AppUpdateChecker.parseGiteeLatestMetadata("[]") }
    }

    @Test fun selectsExactGiteeAttachmentWithManifestAndConstructedPage() {
        val result = parse(release(), attachment())!!
        assertEquals("3.4.6", result.version)
        assertEquals("https://gitee.com/$repository/releases/tag/v3.4.6", result.url.toString())
        assertEquals(asset(), result.apk)
    }

    @Test fun supportsChineseNotesAndCrLfManifest() {
        assertNotNull(parse(release("更新说明\r\n\r\n" + fence(manifest()).replace("\n", "\r\n") + "\r\n"), attachment())?.apk)
    }

    @Test fun rejectsPrereleaseDraftAndInvalidTags() {
        assertNull(parse(release().put("prerelease", true), attachment()))
        assertNull(parse(release().put("draft", true), attachment()))
        for (tag in listOf("../evil", "v3.4.6-beta", "3.4.6", "v3.4.6/evil")) {
            assertThrows(IOException::class.java) { parse(release().put("tag_name", tag), attachment()) }
        }
    }

    @Test fun missingMalformedOrDuplicateManifestCannotEnableInstallation() {
        for (body in listOf("", "APK SHA-256: $sha", "```eta-update\nnot-json\n```",
            fence(manifest()) + "\n" + fence(manifest()),
            "```eta-update\nmalformed\n" + fence(manifest()))) {
            assertNull(body, parse(release(body), attachment())?.apk)
        }
    }

    @Test fun validatesEveryRequiredManifestField() {
        for (bad in listOf(manifest().put("schema", 2), manifest().put("size", 43),
            manifest().put("name", "wrong.apk"), manifest().put("sha256", "not-a-hash"))) {
            assertNull(parse(release(fence(bad)), attachment())?.apk)
        }
        for (key in listOf("schema", "size", "name", "sha256")) {
            val bad = manifest().also { it.remove(key) }
            assertNull(parse(release(fence(bad)), attachment())?.apk)
        }
    }

    @Test fun absentDuplicateOrUnverifiableAttachmentCannotInstall() {
        assertNull(parse()?.apk)
        assertNull(parse(release(), attachment(), attachment())?.apk)
        for (bad in listOf(attachment().put("id", 0), attachment().put("size", 0),
            attachment().put("size", AppUpdateChecker.MAX_APK_BYTES + 1),
            attachment().put("name", "Eta-v3.4.6-debug.apk"))) {
            assertNull(parse(release(), bad)?.apk)
        }
    }

    @Test fun neverUsesUntrustedReleaseAssetsForAttachmentIdentity() {
        val json = release().put("assets", JSONArray().put(attachment()))
        assertNull(parse(json)?.apk)
    }

    @Test fun rejectsWrongRepositoryHostSchemeQueryPortAndTag() {
        for (badUrl in listOf(url.replace(repository, "other/eta"), url.replace("gitee.com", "gitee.com.evil"),
            url.replace("https:", "http:"), "$url?token=secret", url.replace("gitee.com", "gitee.com:444"),
            url.replace("/v3.4.6/", "/v3.4.5/"))) {
            assertNull(parse(release(), attachment().put("browser_download_url", badUrl))?.apk)
        }
    }

    @Test fun failsClosedIfAttachmentPaginationCouldHideDuplicates() {
        val attachments = JSONArray().put(attachment())
        repeat(99) { attachments.put(attachment().put("name", "unrelated-$it")) }
        assertNull(AppUpdateChecker.parseGiteeRelease(release(), attachments, repository)?.apk)
    }

    @Test fun allowsOnlyExactGiteeAttachmentHopAndAttachmentCdn() {
        assertTrue(allowedAssetRedirect("https://gitee.com/$repository/attach_files/456/download/Eta-v3.4.6.apk".toHttpUrl(), asset()))
        assertTrue(allowedAssetRedirect("https://foruda.gitee.com/attach_file/123/Eta-v3.4.6.apk?token=opaque".toHttpUrl(), asset()))
        assertTrue(allowedAssetRedirect("https://foruda.gitee.com/attach_file/123/eta-v3.4.6.apk?token=opaque".toHttpUrl(), asset()))
        for (bad in listOf("https://gitee.com/other/eta/attach_files/456/download/Eta-v3.4.6.apk",
            "https://gitee.com/$repository/attach_files/999/download/Eta-v3.4.6.apk",
            "https://gitee.com/$repository/attach_files/456/download/Eta-v3.4.6.apk?token=secret",
            "https://foruda.gitee.com/other/file", "https://foruda.gitee.com.evil/attach_file/file",
            "http://foruda.gitee.com/attach_file/file", "https://foruda.gitee.com:444/attach_file/file",
            "https://user:password@foruda.gitee.com/attach_file/file", "https://127.0.0.1/attach_file/file",
            "https://objects.githubusercontent.com/file", "https://foruda.gitee.com/attach_file/file#fragment",
            "https://foruda.gitee.com/attach_file/123/ETA-v3.4.6.apk",
            "https://foruda.gitee.com/attach_file/123/eta-v3.4.5.apk")) {
            assertFalse(bad, allowedAssetRedirect(bad.toHttpUrl(), asset()))
        }
    }

    @Test fun giteeCannotExpandGitHubRedirectPolicyAndNeedsAttachmentId() {
        val cdn = "https://foruda.gitee.com/attach_file/123/file".toHttpUrl()
        assertFalse(allowedAssetRedirect(cdn))
        assertFalse(allowedAssetRedirect(cdn, asset().copy(attachmentId = null)))
    }
}
