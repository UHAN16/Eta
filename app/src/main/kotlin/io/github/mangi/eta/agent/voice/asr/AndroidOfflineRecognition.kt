package io.github.mangi.eta.agent.voice.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.audio.SpeechErrorCode
import io.github.mangi.eta.agent.voice.audio.SpeechFailure
import io.github.mangi.eta.core.AppStrings
import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred

/** Separate from the assistant's external-recognizer bridge. Never fall back to a generic recognizer. */
internal interface AndroidOfflineRecognition : Closeable {
    fun finish()
    suspend fun await(): String
}

internal class OfflineRecognitionEvents(
    val ready: () -> Unit,
    val speechStarted: () -> Unit,
    val speechEnded: () -> Unit,
    val preview: (String) -> Unit,
    val level: (Float) -> Unit,
)

internal fun offlineRecognitionIntent(language: String, pauseMs: Int): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        if (pauseMs > 0) putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, pauseMs.toLong())
    }

/** Android SpeechRecognizer start/stop, callbacks and destruction belong on the main thread. */
internal fun startAndroidOfflineRecognition(
    context: Context,
    language: String,
    pauseMs: Int,
    events: OfflineRecognitionEvents,
): AndroidOfflineRecognition {
    if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, AppStrings.get(
            R.string.offline_asr_unavailable, "此设备没有可用的 Android 本机语音识别服务",
        ))
    }
    val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context.applicationContext)
    val result = CompletableDeferred<String>()
    var closed = false

    fun text(bundle: Bundle?): String = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    try {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (!closed && !result.isCompleted) events.ready() }
            override fun onBeginningOfSpeech() { if (!closed && !result.isCompleted) events.speechStarted() }
            override fun onEndOfSpeech() { if (!closed && !result.isCompleted) events.speechEnded() }
            override fun onRmsChanged(rmsdB: Float) {
                if (!closed && !result.isCompleted && rmsdB.isFinite()) events.level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (!closed && !result.isCompleted) text(partialResults).takeIf { it.isNotBlank() }?.let(events.preview)
            }
            override fun onResults(results: Bundle?) { if (!closed) result.complete(text(results)) }
            override fun onError(error: Int) { if (!closed) result.completeExceptionally(offlineRecognitionError(error)) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(offlineRecognitionIntent(language, pauseMs))
    } catch (failure: Exception) {
        runCatching { recognizer.destroy() }
        throw failure
    }

    return object : AndroidOfflineRecognition {
        override fun finish() {
            if (!closed && !result.isCompleted) runCatching { recognizer.stopListening() }
                .onFailure { result.completeExceptionally(it) }
        }
        override suspend fun await(): String = result.await()
        override fun close() {
            if (closed) return
            closed = true
            result.cancel()
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
    }
}

internal fun offlineRecognitionError(code: Int): SpeechFailure = when (code) {
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechFailure(
        SpeechErrorCode.NO_SPEECH, AppStrings.get(R.string.voice_i18n_no_speech_recognized, "没有识别到语音，请重试"),
    )
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> SpeechFailure(
        SpeechErrorCode.CONFIGURATION, AppStrings.get(R.string.offline_asr_language_missing, "所选语言没有可用的离线识别包"),
    )
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure(
        SpeechErrorCode.PERMISSION, AppStrings.get(R.string.voice_i18n_mic_permission_required, "请允许 Eta 使用麦克风"),
    )
    else -> SpeechFailure(SpeechErrorCode.AUDIO, AppStrings.get(
        R.string.offline_asr_failed, "本机识别服务出错（%1\$d）", code,
    ))
}
