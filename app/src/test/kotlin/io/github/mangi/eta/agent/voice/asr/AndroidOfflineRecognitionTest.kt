package io.github.mangi.eta.agent.voice.asr

import android.app.Application
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.mangi.eta.agent.voice.audio.SpeechErrorCode
import io.github.mangi.eta.agent.voice.ui.EtaSpeechPhase
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidOfflineRecognitionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val results = mutableListOf<String>()
    private val held = mutableListOf<String>()
    private val fake = FakeRecognition()
    private lateinit var events: OfflineRecognitionEvents
    private var requestedLanguage = ""
    private var requestedPause = -1
    private var credentialsReads = 0
    private val settings = SpeechSettings(
        asr = AsrProvider.ANDROID_OFFLINE, offlineAsrLanguage = "zh-TW", autoSendSilenceMs = 1500,
        refineTranscript = true, // Local path must still bypass any model refinement.
    )
    private val controller = SpeechInputController(
        context = RuntimeEnvironment.getApplication(),
        scope = scope,
        automaticEndpoint = true,
        loadCredentials = { credentialsReads++; error("Offline ASR must not read credentials") },
        createRecorder = { error("Android owns the microphone in local recognition") },
        recognizeStream = { _, _, _, _, _, _, _ -> error("Offline ASR must not call a cloud stream") },
        recognizeFile = { _, _, _, _, _ -> error("Offline ASR must not upload a file") },
        startOfflineRecognition = { _, language, pause, callbacks ->
            requestedLanguage = language
            requestedPause = pause
            events = callbacks
            callbacks.ready()
            fake
        },
        onHoldResult = held::add,
        onResult = results::add,
    )

    @After fun cleanup() { controller.cancel(); scope.cancel(); idle() }

    @Test fun usesAndroidFinalResultImmediatelyWithoutCredentialsOrExplicitFinish() {
        controller.start(settings)
        idle()
        assertEquals(EtaSpeechPhase.LISTENING, controller.state.value.phase)
        assertEquals("zh-TW", requestedLanguage)
        assertEquals(1500, requestedPause)
        events.preview("早")
        assertEquals("早", controller.state.value.preview)
        events.speechEnded()
        fake.completion.complete("早上好")
        idle()
        assertEquals(listOf("早上好"), results)
        assertEquals(0, credentialsReads)
        assertEquals(0, fake.finishCount)
        assertTrue(fake.closed)
        assertFalse(controller.state.value.active)
    }

    @Test fun nativeEarlyEndpointDoesNotSendAHeldMessageUntilRelease() {
        controller.start(settings, holdToTalk = true)
        idle()
        assertEquals(0, requestedPause)
        fake.completion.complete("按住说话")
        idle()
        assertTrue(held.isEmpty())
        assertEquals("按住说话", controller.state.value.preview)
        controller.finish()
        idle()
        assertEquals(listOf("按住说话"), held)
        assertTrue(results.isEmpty())
    }

    @Test fun lateNativeResultAfterCancellationIsNotDelivered() {
        controller.start(settings)
        idle()
        controller.cancel()
        events.preview("stale")
        fake.completion.complete("stale")
        idle()
        assertTrue(results.isEmpty())
        assertTrue(fake.closed)
        assertEquals("", controller.state.value.preview)
    }

    @Test fun marksLocalRecognitionIntentAndSeparatesMissingPacksFromSilence() {
        val request = offlineRecognitionIntent("zh-CN", 1500)
        assertTrue(request.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertTrue(request.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        assertEquals("zh-CN", request.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals(1500L, request.getLongExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 0))
        assertEquals(SpeechErrorCode.CONFIGURATION, offlineRecognitionError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE).code)
        assertEquals(SpeechErrorCode.NO_SPEECH, offlineRecognitionError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT).code)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private class FakeRecognition : AndroidOfflineRecognition {
        val completion = CompletableDeferred<String>()
        var finishCount = 0
        var closed = false
        override suspend fun await(): String = completion.await()
        override fun finish() { finishCount++ }
        override fun close() { closed = true }
    }
}
