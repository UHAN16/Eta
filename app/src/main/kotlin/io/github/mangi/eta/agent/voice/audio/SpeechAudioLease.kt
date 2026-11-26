package io.github.mangi.eta.agent.voice.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import java.io.Closeable
import kotlinx.coroutines.CancellationException

/** 只协调本进程的录音和播放；所有入口共用一个租约，资源仍由入口生命周期持有。 */
/**
 * [interruptOnScreenOff] 为 false 时锁屏不打断：聊天朗读需要在锁屏和后台继续，
 * 录音与助手浮窗仍随熄屏结束。
 */
internal class SpeechAudioLease(
    private val context: Context,
    private val interruptOnScreenOff: Boolean = true,
    private val call: SpeechDuplexSession? = null,
    private val capture: Boolean = false,
    private val interrupt: () -> Unit,
) : Closeable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener({ change -> if (change < 0 && this in current) interrupt() }, Handler(Looper.getMainLooper()))
        .build()
    private var registered = false
    private var focused = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (this@SpeechAudioLease in current) interrupt() }
    }

    fun acquire(playback: Boolean, beforeFocus: () -> Unit = {}) {
        current.toList().filterNot { call != null && it.call === call && it.capture != capture }
            .forEach { old -> old.interrupt(); old.close() }
        current.add(this)
        try {
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                if (interruptOnScreenOff) addAction(Intent.ACTION_SCREEN_OFF)
            }, Context.RECEIVER_NOT_EXPORTED)
            registered = true
            if (playback) requestPlaybackFocus(beforeFocus)
        } catch (error: RuntimeException) {
            close()
            throw error
        }
    }

    fun requestPlaybackFocus(beforeFocus: () -> Unit = {}) {
        if (this !in current) throw CancellationException("Speech owner replaced")
        beforeFocus()
        focused = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focused) {
            throw SpeechFailure(
                SpeechErrorCode.AUDIO,
                AppStrings.get(R.string.voice_i18n_audio_focus_unavailable, "当前无法获得音频焦点，请稍后重试"),
            )
        }
    }

    override fun close() {
        if (registered) context.unregisterReceiver(receiver)
        registered = false
        if (focused) manager.abandonAudioFocusRequest(focus)
        focused = false
        current.remove(this)
    }

    private companion object { val current = mutableSetOf<SpeechAudioLease>() }
}

/** Opt-in: one microphone and one player owned by the same foreground call may overlap. */
internal class SpeechDuplexSession
