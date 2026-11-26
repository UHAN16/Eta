package io.github.mangi.eta.agent.voice.asr

import android.app.Application
import android.os.Looper
import io.github.mangi.eta.agent.voice.audio.SpeechAudioCapture
import io.github.mangi.eta.agent.voice.audio.SpeechAudioLease
import io.github.mangi.eta.agent.voice.audio.SpeechDuplexSession
import io.github.mangi.eta.agent.voice.ui.EtaSpeechPhase
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceCallAudioTest {
    @Test fun callAllowsItsOwnMicAndPlayerButOrdinaryAudioStillTakesExclusiveOwnership() {
        val context = RuntimeEnvironment.getApplication()
        val call = SpeechDuplexSession()
        val interrupted = mutableListOf<String>()
        val mic = SpeechAudioLease(context, call = call, capture = true) { interrupted += "mic" }
        val tts = SpeechAudioLease(context, call = call) { interrupted += "tts" }
        val other = SpeechAudioLease(context) { interrupted += "other" }
        try {
            mic.acquire(playback = false)
            tts.acquire(playback = false)
            assertTrue(interrupted.isEmpty())
            other.acquire(playback = false)
            assertEquals(listOf("mic", "tts"), interrupted)
        } finally {
            mic.close()
            tts.close()
            other.close()
        }
    }

    @Test fun fileRecognitionSubmitsOnSilenceWithoutCallingFinishFromUi() {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val capture = SimulatedCallCapture()
        val results = mutableListOf<String>()
        val onsets = mutableListOf<Boolean>()
        val controller = SpeechInputController(
            context = context,
            scope = scope,
            automaticEndpoint = true,
            call = SpeechDuplexSession(),
            createRecorder = { capture },
            onCallSpeechStarted = onsets::add,
            recognizeFile = { _, _, _, wav, _ ->
                assertEquals("RIFF", wav.take(4).toByteArray().toString(Charsets.US_ASCII))
                "下一轮开始了"
            },
            onResult = results::add,
        )
        try {
            controller.start(SpeechSettings(asr = AsrProvider.MIMO, autoSendSilenceMs = 1000),
                SpeechCredentials(mimoAsr = "test-key"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(controller.state.value.error, EtaSpeechPhase.LISTENING, controller.state.value.phase)
            capture.level(0.15f)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(true), onsets)
            ShadowSystemClock.advanceBy(Duration.ofMillis(1100))
            capture.level(0.001f)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("下一轮开始了"), results)
            assertFalse(controller.state.value.active)
        } finally {
            controller.cancel()
            scope.cancel()
        }
    }

    private class SimulatedCallCapture : SpeechAudioCapture {
        override val frames = Channel<ByteArray>(Channel.UNLIMITED)
        override val echoCancellationEnabled = true
        lateinit var level: (Float) -> Unit
        override suspend fun record(onReady: () -> Unit, onLevel: (Float) -> Unit) {
            level = onLevel
            onReady()
            frames.send(byteArrayOf(1, 2, 3, 4))
            awaitCancellation()
        }
        override fun finish() { frames.close() }
        override fun cancel() { frames.cancel() }
    }
}
