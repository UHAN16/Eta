package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.asr.SpeechInputController
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text

internal val LocalVoiceConversationActive = staticCompositionLocalOf { false }
internal val LocalVoiceConversationBar = staticCompositionLocalOf<(@Composable (Boolean) -> Unit)?> { null }

/** A foreground-only, half-duplex loop. It owns no model/tool settings and never persists 'on'. */
@Composable
internal fun RoleplayVoiceConversationHost(
    messages: List<AgentChatMessageUi>,
    isStreaming: Boolean,
    blocked: Boolean,
    onSubmit: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val playback = LocalSpeechPlayback.current
    val role = LocalRoleplayChat.current
    val settings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsStateWithLifecycle(initialValue = null)
    val currentMessages by rememberUpdatedState(messages)
    val currentSubmit by rememberUpdatedState(onSubmit)
    val noReplyText by rememberUpdatedState(stringResource(R.string.voice_conversation_no_reply))
    val playbackStoppedText by rememberUpdatedState(stringResource(R.string.voice_conversation_playback_stopped))
    var phase by remember { mutableStateOf(VoiceConversationPhase.OFF) }
    var baselineIds by remember { mutableStateOf(emptySet<String>()) }
    var sawRun by remember { mutableStateOf(false) }
    var inputStarted by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val input = remember(context, scope) {
        SpeechInputController(context, scope, automaticEndpoint = true, onResult = { text ->
            if (phase == VoiceConversationPhase.LISTENING && text.isNotBlank()) {
                inputStarted = false
                baselineIds = currentMessages.mapTo(mutableSetOf()) { it.id }
                sawRun = false
                phase = VoiceConversationPhase.WAITING
                currentSubmit(text.trim())
            }
        })
    }
    val inputState by input.state.collectAsStateWithLifecycle()
    val ready = role != null && playback != null && settings?.let {
        it.recognitionEnabled && it.tts != TtsProvider.NONE &&
            it.asr in setOf(AsrProvider.QWEN_REALTIME, AsrProvider.DOUBAO)
    } == true
    fun stop() {
        val wasActive = phase != VoiceConversationPhase.OFF
        phase = VoiceConversationPhase.OFF
        inputStarted = false
        input.cancel()
        // Do not change ordinary chat's existing background/manual read-aloud behavior.
        if (wasActive) playback?.stop()
    }
    SpeechLifecycle { stop() }
    LaunchedEffect(blocked, ready, settings) {
        stop()
    }
    LaunchedEffect(inputState.error) {
        inputState.error?.let { status = it; stop() }
    }
    LaunchedEffect(inputState.active, phase) {
        if (phase == VoiceConversationPhase.LISTENING && inputStarted && !inputState.active) stop()
    }
    LaunchedEffect(phase) {
        when (phase) {
            VoiceConversationPhase.LISTENING -> {
                // Let speaker buffers drain before opening the microphone; never capture our own reply.
                delay(500)
                if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                    context.getSystemService(android.os.PowerManager::class.java)?.isInteractive == false) {
                    stop()
                    return@LaunchedEffect
                }
                val config = settings ?: return@LaunchedEffect
                inputStarted = true
                input.start(settings = config.copy(autoSendSilenceMs = config.autoSendSilenceMs.takeIf { it > 0 }
                    ?: SpeechSettings.DEFAULT_AUTO_SEND_SILENCE_MS))
            }
            VoiceConversationPhase.WAITING -> {
                // A rejected submission (missing chat model, etc.) must not leave a live mode hanging.
                delay(30_000)
                if (!sawRun) { status = noReplyText; stop() }
            }
            else -> Unit
        }
    }
    LaunchedEffect(isStreaming, messages, phase) {
        if (phase != VoiceConversationPhase.WAITING) return@LaunchedEffect
        if (isStreaming) {
            sawRun = true
        } else {
            val answer = voiceConversationReply(messages, baselineIds)
            if (answer == null && !sawRun) return@LaunchedEffect
            if (answer == null || playback == null) {
                status = noReplyText
                stop()
            } else {
                phase = VoiceConversationPhase.SPEAKING
                playback.speak(answer.id, answer.content, onCompleted = {
                    if (phase == VoiceConversationPhase.SPEAKING) {
                        inputStarted = false
                        phase = VoiceConversationPhase.LISTENING
                    }
                })
            }
        }
    }
    // Interrupted/failed playback must not silently restart the microphone.
    LaunchedEffect(playback) {
        if (playback != null) playback.state.collect { state ->
            if (phase == VoiceConversationPhase.SPEAKING && state.messageId == null) {
                status = state.error ?: playbackStoppedText
                stop()
            }
        }
    }
    val requestStart = rememberSpeechPermission {
        if (ready && !blocked && !isStreaming && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            playback.stop()
            inputStarted = false
            status = null
            phase = VoiceConversationPhase.LISTENING
        }
    }
    val active = phase != VoiceConversationPhase.OFF
    val controls: @Composable (Boolean) -> Unit = { emptyDraft ->
        if (role != null) Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { if (active) stop() else if (!ready) openSpeechSettings(context) else requestStart() },
                    enabled = active || (!blocked && !isStreaming && emptyDraft),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(if (active) R.string.voice_conversation_stop else R.string.voice_conversation_start)) }
                if (active) Text(stringResource(when (phase) {
                    VoiceConversationPhase.LISTENING -> R.string.voice_conversation_listening
                    VoiceConversationPhase.WAITING -> R.string.voice_conversation_waiting
                    else -> R.string.voice_conversation_speaking
                }), modifier = Modifier.weight(1f).padding(start = 12.dp))
            }
            if (!ready) Text(stringResource(R.string.voice_conversation_configure))
            status?.let { Text(it) }
            if (active) SpeechInputFeedback(input)
        }
    }
    CompositionLocalProvider(
        LocalVoiceConversationActive provides active,
        LocalVoiceConversationBar provides controls,
        content = content,
    )
}
