package io.github.mangi.eta.data.update

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AppUpdateInstallerIntentTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun handsAndroidOnlyTheSpecificCachedApkWithAReadGrant() {
        val authority = "${context.packageName}.update-files"
        // Match the AndroidX lookup and avoid Robolectric's unsupported ComponentInfoFlags overload.
        @Suppress("DEPRECATION")
        val provider = requireNotNull(context.packageManager.resolveContentProvider(
            authority, PackageManager.GET_META_DATA,
        ))
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        val roots = requireNotNull(provider.loadXmlMetaData(context.packageManager, "android.support.FILE_PROVIDER_PATHS")).use { xml ->
            buildList {
                while (xml.next() != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType == XmlPullParser.START_TAG && xml.name != "paths") {
                        add(Triple(xml.name, xml.getAttributeValue(null, "name"), xml.getAttributeValue(null, "path")))
                    }
                }
            }
        }
        assertEquals(listOf(Triple("cache-path", "eta_updates", "app_updates/")), roots)
        val directory = File(context.cacheDir, "app_updates").apply { mkdirs() }
        val file = File.createTempFile("eta-update-", ".apk", directory)
        try {
            // AndroidX FileProvider assumes Android's '/' paths; on a Windows Robolectric host,
            // verify the actual manifest above and test the same installer intent with a content URI.
            val intent = if (File.separatorChar == '/') AppUpdateInstaller.installIntent(context, file.name)
                else packageInstallerIntent(Uri.parse("content://$authority/eta_updates/${file.name}"))
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertEquals("content", intent.data?.scheme)
            assertEquals(authority, intent.data?.authority)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        } finally {
            file.delete()
        }
    }

    @Test fun rejectsPathTraversalAndMissingCacheEntry() {
        for (name in listOf("../eta-update-123.apk", "elsewhere.apk", "eta-update-9999.apk")) {
            assertThrows(IOException::class.java) { AppUpdateInstaller.installIntent(context, name) }
        }
    }

    @Test fun permissionRequestTargetsOnlyEta() {
        val intent = AppUpdateInstaller.settingsIntent(context)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }
}
