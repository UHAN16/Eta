package io.github.mangi.eta.agent.voice.tts

import android.app.Application
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidOfflineSpeechTest {
    @Test fun onlyInstalledNonNetworkVoicesAreEligibleEvenWhenExplicitlySelected() {
        val local = voice("installed")
        val network = voice("network", network = true)
        val missing = voice("missing", features = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
        val voices = listOf(network, missing, local)
        assertSame(local, selectAndroidOfflineVoice(voices, "", Locale.SIMPLIFIED_CHINESE))
        assertSame(local, selectAndroidOfflineVoice(voices, "installed", Locale.US))
        assertNull(selectAndroidOfflineVoice(voices, "network", Locale.SIMPLIFIED_CHINESE))
        assertNull(selectAndroidOfflineVoice(voices, "missing", Locale.SIMPLIFIED_CHINESE))
        assertNull(selectAndroidOfflineVoice(voices, "not-present", Locale.SIMPLIFIED_CHINESE))
    }

    @Test fun autoVoicePrefersMatchingLocaleAndNeverFallsBackToAnotherLanguage() {
        val mainland = voice("zh-mainland", locale = Locale.SIMPLIFIED_CHINESE)
        val taiwan = voice("zh-taiwan", locale = Locale.TRADITIONAL_CHINESE)
        val english = voice("en", locale = Locale.US)
        assertSame(taiwan, selectAndroidOfflineVoice(listOf(mainland, english, taiwan), "", Locale.TRADITIONAL_CHINESE))
        assertNull(selectAndroidOfflineVoice(listOf(english), "", Locale.SIMPLIFIED_CHINESE))
        assertEquals(Locale.SIMPLIFIED_CHINESE, offlineVoiceLocale(Locale.US, "你好！"))
        assertEquals(Locale.US, offlineVoiceLocale(Locale.US, "Hello"))
    }

    @Test fun controllerUsesSelectedAndroidVoiceAndDoesNotLoadCredentialsOrCloudAudio() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var capturedText = ""
        var capturedVoice = ""
        var credentialReads = 0
        var completed = false
        val playback = SpeechPlaybackController(
            RuntimeEnvironment.getApplication(), scope,
            loadCredentials = { credentialReads++; error("Offline playback must not read cloud credentials") },
            synthesize = { _, _, _, _ -> error("Offline playback must not synthesize in cloud") },
            createOutput = { error("Android TTS owns the playback output") },
            speakOffline = { _, text, voice, started ->
                capturedText = text
                capturedVoice = voice
                started()
            },
        )
        try {
            playback.speak("local", "**你好**", SpeechSettings(tts = TtsProvider.ANDROID_OFFLINE, offlineTtsVoice = "zh-test"),
                onCompleted = { completed = true })
            await { completed || playback.state.value.error != null }
            assertNull(playback.state.value.error)
            assertTrue(completed)
            assertEquals("你好", capturedText)
            assertEquals("zh-test", capturedVoice)
            assertEquals(0, credentialReads)
        } finally {
            playback.stop()
            scope.cancel()
        }
    }

    @Test fun cancellationStopsSystemSpeechWithoutSignalingSuccess() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var released = false
        var completed = false
        val playback = SpeechPlaybackController(
            RuntimeEnvironment.getApplication(), scope,
            speakOffline = { _, _, _, started ->
                started()
                try { awaitCancellation() } finally { released = true }
            },
        )
        try {
            playback.speak("local", "Interruptible", SpeechSettings(tts = TtsProvider.ANDROID_OFFLINE),
                onCompleted = { completed = true })
            await { !playback.state.value.loading }
            assertEquals("local", playback.state.value.messageId)
            playback.stop()
            await { released }
            assertFalse(completed)
            assertNull(playback.state.value.messageId)
        } finally {
            playback.stop()
            scope.cancel()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        assertTrue("Timed out waiting for Android speech state", condition())
    }

    private fun voice(name: String, locale: Locale = Locale.SIMPLIFIED_CHINESE,
        network: Boolean = false, features: Set<String> = emptySet()): Voice =
        Voice(name, locale, Voice.QUALITY_HIGH, Voice.LATENCY_LOW, network, features)
}
