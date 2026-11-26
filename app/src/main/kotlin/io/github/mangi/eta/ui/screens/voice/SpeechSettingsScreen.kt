package io.github.mangi.eta.ui.screens.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import androidx.compose.runtime.getValue

@Composable
internal fun SpeechSettingsScreen(onBack: () -> Unit, onNavigate: (AppRoute) -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by SpeechSettingsRepository.settingsFlow().collectAsStateWithLifecycle(initialValue = null)
    val current = settings
    var applyingOffline by remember { mutableStateOf(false) }
    var offlineSaveFailed by remember { mutableStateOf(false) }

    ScaffoldPage(
        title = stringResource(R.string.speech_settings_title),
        onBack = onBack,
    ) {
        if (current == null) {
            item(key = "loading") { SpeechLoadingState(failure = null) }
            return@ScaffoldPage
        }
        item(key = "offline_quick_setup") {
            val selected = current.recognitionEnabled && current.asr == AsrProvider.ANDROID_OFFLINE &&
                current.tts == TtsProvider.ANDROID_OFFLINE
            SpeechGroupCard {
                ArrowPreference(
                    title = stringResource(if (selected) R.string.offline_selected else R.string.offline_enable_both),
                    summary = stringResource(if (offlineSaveFailed) R.string.offline_save_failed else R.string.offline_setup_summary),
                    enabled = !applyingOffline,
                    onClick = {
                        if (selected) onNavigate(AppRoute.SpeechSynthesis) else if (!applyingOffline) {
                            applyingOffline = true
                            offlineSaveFailed = false
                            scope.launch {
                                try {
                                    SpeechSettingsRepository.save(current.copy(
                                        asr = AsrProvider.ANDROID_OFFLINE,
                                        tts = TtsProvider.ANDROID_OFFLINE,
                                        recognitionEnabled = true,
                                    ))
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    offlineSaveFailed = true
                                } finally {
                                    applyingOffline = false
                                }
                            }
                        }
                    },
                )
            }
        }
        item(key = "service") {
            SpeechGroupCard(title = stringResource(R.string.speech_group_service)) {
                ArrowPreference(
                    title = stringResource(R.string.speech_recognition_title),
                    summary = if (current.recognitionEnabled) current.asr.label()
                    else stringResource(R.string.speech_recognition_disabled),
                    onClick = { onNavigate(AppRoute.SpeechRecognition) },
                )
                ArrowPreference(
                    title = stringResource(R.string.speech_synthesis_title),
                    summary = current.tts.label(),
                    onClick = { onNavigate(AppRoute.SpeechSynthesis) },
                )
            }
        }
        item(key = "assistant") {
            Column {
                SpeechGroupCard(title = stringResource(R.string.speech_group_assistant)) {
                    SwitchPreference(
                        title = stringResource(R.string.speech_auto_speak),
                        summary = stringResource(R.string.speech_auto_speak_summary),
                        checked = current.autoSpeak,
                        enabled = current.tts != TtsProvider.NONE && !applyingOffline,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                SpeechSettingsRepository.save(current.copy(autoSpeak = enabled))
                            }
                        },
                    )
                }
                SpeechNote(stringResource(R.string.speech_overview_note))
            }
        }
    }
}
