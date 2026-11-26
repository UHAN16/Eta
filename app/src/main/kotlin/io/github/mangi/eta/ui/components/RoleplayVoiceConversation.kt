package io.github.mangi.eta.ui.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.asr.SpeechInputController
import io.github.mangi.eta.agent.voice.audio.SpeechDuplexSession
import io.github.mangi.eta.agent.voice.audio.SpeechErrorCode
import io.github.mangi.eta.agent.voice.tts.SpeechPlaybackController
import io.github.mangi.eta.agent.voice.tts.SpeechText
import io.github.mangi.eta.agent.voice.ui.EtaSpeechPhase
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text

internal val LocalVoiceConversationActive = staticCompositionLocalOf { false }
internal val LocalVoiceConversationBar = staticCompositionLocalOf<(@Composable (Boolean) -> Unit)?> { null }

/** Foreground call. ASR and sentence-by-sentence TTS share only this call's audio lease. */
@Composable
internal fun RoleplayVoiceConversationHost(
    messages: List<AgentChatMessageUi>,
    isStreaming: Boolean,
    blocked: Boolean,
    canStart: Boolean,
    onSubmit: (String) -> Unit,
    onStopResponse: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val manualPlayback = LocalSpeechPlayback.current
    val role = LocalRoleplayChat.current
    val settings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsStateWithLifecycle(initialValue = null)
    val currentMessages by rememberUpdatedState(messages)
    val currentStreaming by rememberUpdatedState(isStreaming)
    val currentSubmit by rememberUpdatedState(onSubmit)
    val stopResponse by rememberUpdatedState(onStopResponse)
    val currentSettings by rememberUpdatedState(settings)
    var active by remember { mutableStateOf(false) }
    var startRequested by remember { mutableStateOf(false) }
    var turn by remember { mutableStateOf<VoiceCallTurn?>(null) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var noReply by remember { mutableStateOf(false) }
    val call = remember { SpeechDuplexSession() }
    val echoWindow = remember { VoiceCallEchoWindow() }
    val playback = remember(context, scope, call) {
        SpeechPlaybackController(context, scope, call = call)
    }
    val playbackState by playback.state.collectAsStateWithLifecycle()
    fun isEcho(text: String): Boolean =
        echoWindow.isEcho(text, SystemClock.elapsedRealtime(), playback.state.value.messageId != null)
    val clearReply: () -> Unit = {
        if (turn != null && currentStreaming) stopResponse()
        turn = null
        if (playback.state.value.messageId != null) echoWindow.stopped(SystemClock.elapsedRealtime())
        playback.stop()
    }
    val currentClearReply by rememberUpdatedState(clearReply)
    val input = remember(context, scope, call) {
        SpeechInputController(
            context, scope, automaticEndpoint = true, call = call,
            onCallSpeechStarted = { echoCancellation ->
                val realtime = currentSettings?.asr in setOf(AsrProvider.QWEN_REALTIME, AsrProvider.DOUBAO)
                // For realtime ASR wait for a human-looking partial when sound is playing.
                // File ASR has no partials and may use local onset when AEC is active.
                if (active && !realtime && (playback.state.value.messageId == null || echoCancellation)) {
                    currentClearReply()
                }
            },
            onCallAudioInterrupted = { active = false; turn = null; playback.stop() },
            onResult = { spoken ->
                val text = spoken.trim()
                if (active && text.any(Char::isLetterOrDigit) && !isEcho(text)) {
                    currentClearReply()
                    val baseline = currentMessages.mapTo(mutableSetOf()) { it.id }
                    turn = VoiceCallTurn(baseline)
                    noReply = false
                    echoWindow.clear()
                    // If cancellation is still settling, normal submit queues behind the stopped run.
                    currentSubmit(text)
                }
            },
        )
    }
    val inputState by input.state.collectAsStateWithLifecycle()
    val ready = role != null && settings?.recognitionEnabled == true
    fun stop() {
        startRequested = false
        active = false
        turn = null
        input.cancel()
        playback.stop()
    }
    SpeechLifecycle { stop() }
    LaunchedEffect(blocked, ready, settings) {
        if (blocked || !ready) stop()
    }
    LaunchedEffect(startRequested, lifecycle, ready, blocked, isStreaming, canStart) {
        if (startRequested && lifecycle == Lifecycle.State.RESUMED && ready && !blocked && !isStreaming && canStart) {
            startRequested = false
            manualPlayback?.stop()
            keyboard?.hide()
            inputError = null
            playbackError = null
            noReply = false
            echoWindow.clear()
            active = true
        }
    }
    val requestStart = rememberSpeechPermission { if (ready && !blocked && canStart) startRequested = true }

    LaunchedEffect(active, settings) {
        val config = settings ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        while (active) {
            input.start(settings = config.copy(autoSendSilenceMs = config.autoSendSilenceMs.takeIf { it > 0 }
                ?: SpeechSettings.DEFAULT_AUTO_SEND_SILENCE_MS))
            input.state.first { !it.active }
            // A controller can dispatch its terminal error directly after releasing the microphone.
            delay(150)
            val ended = input.state.value
            if (!active) break
            if (ended.error != null && ended.errorCode != SpeechErrorCode.NO_SPEECH) {
                inputError = ended.error
                stop()
                break
            }
        }
    }

    LaunchedEffect(active, inputState.preview) {
        val preview = input.state.value.preview.trim()
        if (active && preview.any(Char::isLetterOrDigit) && !isEcho(preview)) {
            currentClearReply()
        }
    }
    val voice = turn
    // The transcript itself stays in the existing streaming chat renderer. This loop owns audio only.
    LaunchedEffect(active, voice) {
        if (!active || voice == null) return@LaunchedEffect
        var ticksWithoutUser = 0
        var sequence = 0
        var lastAnswer: AgentMessageUi? = null
        var readableText = ""
        while (active && turn === voice) {
            val turnMessages = voiceTurnMessages(currentMessages, voice.baselineIds)
            val busy = currentStreaming
            if (turnMessages == null) {
                if (++ticksWithoutUser >= 300) {
                    noReply = true
                    turn = null
                }
            } else if (turnMessages.voiceTurnFailed()) {
                noReply = true
                turn = null
            } else {
                val answer = turnMessages.filterIsInstance<AgentMessageUi>().lastOrNull()
                val final = !busy && answer?.isStreaming != true
                if (answer != null && currentSettings?.tts != TtsProvider.NONE && playbackError == null) {
                    if (answer != lastAnswer) {
                        readableText = withContext(Dispatchers.Default) { SpeechText.readable(answer.content.take(30_000)) }
                        lastAnswer = answer
                    }
                    if (turn !== voice || !active) break
                    val part = voice.speech.next(answer.id, readableText, final)
                    if (part != null) {
                        if (!part.any(Char::isLetterOrDigit)) continue
                        echoWindow.play(part, SystemClock.elapsedRealtime())
                        val id = "voice-call-${answer.id}-${++sequence}"
                        playback.speak(id, part, settings = currentSettings)
                        val ended = playback.state.first { it.messageId != id }
                        if (turn !== voice || !active) break
                        echoWindow.stopped(SystemClock.elapsedRealtime())
                        playbackError = ended.error
                        continue
                    }
                }
                if (final) {
                    if (answer == null) noReply = true
                    turn = null
                }
            }
            delay(100)
        }
    }

    val controls: @Composable (Boolean) -> Unit = { emptyDraft ->
        if (role != null) Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        if (active) { clearReply(); stop() }
                        else if (!ready) openSpeechSettings(context) else requestStart()
                    },
                    enabled = active || (!blocked && !isStreaming && canStart && emptyDraft),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(if (active) R.string.voice_conversation_stop else R.string.voice_conversation_start)) }
                if (active && turn != null) Button(onClick = clearReply, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.voice_call_interrupt))
                }
            }
            if (!ready) Text(stringResource(R.string.voice_conversation_configure))
            if (ready && !active && !emptyDraft) Text(stringResource(R.string.voice_call_clear_draft))
            if (ready && settings?.tts == TtsProvider.NONE) Text(stringResource(R.string.voice_call_no_tts))
            if (active) {
                Text(stringResource(when {
                    playbackState.messageId != null -> R.string.voice_conversation_speaking
                    inputState.phase == EtaSpeechPhase.RECOGNIZING -> R.string.voice_call_recognizing
                    turn != null -> R.string.voice_conversation_waiting
                    else -> R.string.voice_conversation_listening
                }))
                inputState.preview.takeIf { it.isNotBlank() && !isEcho(it) }?.let { Text(it) }
            }
            if (noReply) Text(stringResource(R.string.voice_conversation_no_reply))
            inputError?.let { Text(it) }
            playbackError?.let { Text(stringResource(R.string.voice_call_tts_failed, it)) }
        }
    }
    CompositionLocalProvider(
        LocalVoiceConversationActive provides active,
        LocalVoiceConversationBar provides controls,
        content = content,
    )
}
