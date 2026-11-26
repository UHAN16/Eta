package io.github.mangi.eta.agent.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.audio.SpeechErrorCode
import io.github.mangi.eta.agent.voice.audio.SpeechFailure
import io.github.mangi.eta.core.AppStrings
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class AndroidOfflineVoice(val id: String, val locale: Locale)

internal fun isInstalledOfflineVoice(voice: Voice): Boolean =
    !voice.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()

internal fun selectAndroidOfflineVoice(voices: Collection<Voice>, requested: String, locale: Locale): Voice? {
    val installed = voices.filter(::isInstalledOfflineVoice)
    if (requested.isNotBlank()) return installed.firstOrNull { it.name == requested }
    return installed.filter { it.locale.language == locale.language }
        .sortedWith(compareByDescending<Voice> { it.locale == locale }
            .thenByDescending { it.locale.country.isNotBlank() && it.locale.country == locale.country }
            .thenByDescending { it.quality }.thenBy { it.name })
        .firstOrNull()
}

internal fun offlineVoiceLocale(appLocale: Locale, text: String): Locale {
    if (appLocale.language in setOf("ja", "ko", "zh")) return appLocale
    return if (text.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }) {
        Locale.SIMPLIFIED_CHINESE
    } else appLocale
}

internal suspend fun listAndroidOfflineVoices(context: Context): List<AndroidOfflineVoice> = useAndroidTts(context) { engine ->
    engine.voices.orEmpty().filter(::isInstalledOfflineVoice)
        .sortedWith(compareBy<Voice> { it.locale.language != "zh" }
            .thenBy { it.locale.toLanguageTag() }.thenByDescending { it.quality }.thenBy { it.name })
        .map { AndroidOfflineVoice(it.name, it.locale) }
}

internal suspend fun speakAndroidOffline(
    context: Context,
    text: String,
    voiceId: String,
    onStarted: () -> Unit,
) = useAndroidTts(context) { engine ->
    val locale = offlineVoiceLocale(context.resources.configuration.locales[0], text)
    val voice = selectAndroidOfflineVoice(engine.voices.orEmpty(), voiceId, locale)
        ?: throw missingAndroidVoice()
    if (engine.setVoice(voice) != TextToSpeech.SUCCESS ||
        engine.voice?.let { it.name == voice.name && isInstalledOfflineVoice(it) } != true
    ) throw missingAndroidVoice()
    engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())

    val limit = TextToSpeech.getMaxSpeechInputLength().coerceIn(2, 420)
    for (chunk in SpeechText.chunks(text, limit)) {
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { if (utteranceId == id) started.complete(Unit) }
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) { started.complete(Unit); done.complete(Unit) }
            }
            @Deprecated("Required by UtteranceProgressListener")
            override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == id) {
                    val error = if (errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET) missingAndroidVoice() else androidTtsFailure()
                    started.completeExceptionally(error)
                    done.completeExceptionally(error)
                }
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == id) {
                    val error = androidTtsFailure()
                    started.completeExceptionally(error)
                    done.completeExceptionally(error)
                }
            }
        })
        val result = engine.speak(chunk, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        if (result == TextToSpeech.ERROR_NOT_INSTALLED_YET) throw missingAndroidVoice()
        if (result != TextToSpeech.SUCCESS) throw androidTtsFailure()
        withTimeout(180_000) {
            started.await()
            onStarted()
            done.await()
        }
    }
}

private suspend fun <T> useAndroidTts(context: Context, block: suspend (TextToSpeech) -> T): T =
    withContext(Dispatchers.Main.immediate) {
        val init = CompletableDeferred<Int>()
        val engine = TextToSpeech(context.applicationContext) { init.complete(it) }
        try {
            val result = kotlinx.coroutines.withTimeoutOrNull(10_000) { init.await() }
            if (result != TextToSpeech.SUCCESS) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, AppStrings.get(
                R.string.offline_tts_unavailable, "无法启动 Android 系统朗读引擎",
            ))
            block(engine)
        } finally {
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }
    }

private fun missingAndroidVoice() = SpeechFailure(SpeechErrorCode.CONFIGURATION, AppStrings.get(
    R.string.offline_tts_voice_missing, "所选离线音色不可用；请安装语音数据，选择已安装的离线音色",
))

private fun androidTtsFailure() = SpeechFailure(SpeechErrorCode.AUDIO, AppStrings.get(
    R.string.offline_tts_failed, "系统离线朗读失败；请检查系统的文字转语音引擎和语音数据",
))
