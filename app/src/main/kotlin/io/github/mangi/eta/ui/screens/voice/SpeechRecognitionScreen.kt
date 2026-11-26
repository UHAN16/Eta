package io.github.mangi.eta.ui.screens.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.audio.validateSpeechSettings
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.components.SettingsTextField
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.mangi.eta.ui.components.SpeechLifecycle
import io.github.mangi.eta.ui.components.rememberSpeechPermission
import androidx.compose.runtime.getValue

@Composable
internal fun SpeechRecognitionScreen(onBack: () -> Unit, onOpenOss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SpeechSettingsStore(context, scope) }
    SpeechLifecycle { store.stop() }
    val settings = store.settings
    val asrLabels = AsrProvider.entries.map { it.label() }
    val asrNoteRes = when (settings.asr) {
        AsrProvider.QWEN_REALTIME, AsrProvider.DOUBAO -> R.string.speech_asr_note_streaming
        AsrProvider.QWEN_FLASH -> R.string.speech_asr_note_flash
        AsrProvider.QWEN_FILE -> R.string.speech_asr_note_file
        AsrProvider.MIMO -> R.string.speech_asr_note_mimo
        AsrProvider.DEEPSEEK -> R.string.speech_asr_note_deepseek
        AsrProvider.ANDROID_OFFLINE -> R.string.offline_asr_note
    }
    val showQwenLanguage = when (settings.asr) {
        AsrProvider.QWEN_REALTIME, AsrProvider.QWEN_FLASH, AsrProvider.QWEN_FILE -> true
        AsrProvider.DOUBAO, AsrProvider.MIMO, AsrProvider.DEEPSEEK, AsrProvider.ANDROID_OFFLINE -> false
    }

    ScaffoldPage(
        title = stringResource(R.string.speech_recognition_title),
        onBack = onBack,
        modifier = Modifier.imePadding(),
    ) {
        if (!store.loaded) {
            item(key = "loading") { SpeechLoadingState(store.message) }
            return@ScaffoldPage
        }
        item(key = "recognition_enabled") {
            SpeechGroupCard {
                SwitchPreference(
                    title = stringResource(R.string.speech_recognition_enabled),
                    checked = settings.recognitionEnabled,
                    onCheckedChange = { store.edit(settings.copy(recognitionEnabled = it)) },
                )
            }
        }
        if (settings.recognitionEnabled) {
            item(key = "service") {
                Column {
                    SpeechGroupCard(title = stringResource(R.string.speech_group_service)) {
                        GlassDropdownPreference(
                            title = stringResource(R.string.speech_service_and_model),
                            items = asrLabels,
                            selectedIndex = settings.asr.ordinal,
                            backdrop = currentGlassOverlayBackdrop(),
                            onSelectedIndexChange = { index ->
                                AsrProvider.entries.getOrNull(index)?.let { store.edit(settings.copy(asr = it)) }
                            },
                        )
                    }
                    SpeechNote(stringResource(asrNoteRes))
                }
            }
            if (settings.asr == AsrProvider.ANDROID_OFFLINE) {
                item(key = "offline_language") { AndroidOfflineLanguagePreference(store) }
            } else {
                item(key = "connection") {
                    SpeechConnectionSection(store, synthesis = false)
                }
            }
            if (showQwenLanguage) {
                item(key = "language") {
                    SpeechGroupCard {
                        SpeechFieldColumn {
                            SettingsTextField(
                                label = stringResource(R.string.speech_language_hint),
                                value = settings.language,
                                onChange = { store.edit(settings.copy(language = it.trim())) },
                            )
                        }
                    }
                }
            }
            if (settings.asr != AsrProvider.ANDROID_OFFLINE) {
                item(key = "result") { SpeechResultSection(store) }
            }
            if (settings.asr == AsrProvider.QWEN_FILE) {
                item(key = "oss") {
                    SpeechGroupCard {
                        ArrowPreference(
                            title = stringResource(R.string.speech_oss_title),
                            summary = settings.oss.bucket.ifBlank { stringResource(R.string.speech_oss_configure) },
                            onClick = onOpenOss,
                        )
                    }
                }
            }
            item(key = "test") {
                SpeechRecognitionTestSection(store)
            }
        } else {
            item(key = "recognition_disabled_note") {
                SpeechNote(stringResource(R.string.speech_recognition_disabled_note))
            }
        }
        item(key = "save") {
            SpeechSaveBlock(store, persistCredentials = settings.asr != AsrProvider.ANDROID_OFFLINE) {
                validateSpeechSettings(store.settings, store.credentials, synthesis = false)
            }
        }
    }
}

@Composable
private fun AndroidOfflineLanguagePreference(store: SpeechSettingsStore) {
    val tags = listOf("", "zh-CN", "zh-TW", "en-US")
    val labels = listOf(
        stringResource(R.string.offline_use_current_language), "简体中文（中国）", "繁體中文（台灣）", "English (US)",
    )
    SpeechGroupCard {
        GlassDropdownPreference(
            title = stringResource(R.string.offline_asr_language),
            items = labels,
            selectedIndex = tags.indexOf(store.settings.offlineAsrLanguage).coerceAtLeast(0),
            backdrop = currentGlassOverlayBackdrop(),
            onSelectedIndexChange = { index ->
                tags.getOrNull(index)?.let { store.edit(store.settings.copy(offlineAsrLanguage = it)) }
            },
        )
    }
}

@Composable
private fun SpeechResultSection(store: SpeechSettingsStore) {
    val settings = store.settings
    val streaming = settings.asr == AsrProvider.QWEN_REALTIME || settings.asr == AsrProvider.DOUBAO
    val options = SpeechSettings.AUTO_SEND_SILENCE_OPTIONS
    val optionLabels = options.map { millis ->
        if (millis == 0) {
            stringResource(R.string.speech_auto_send_off)
        } else {
            stringResource(R.string.speech_auto_send_seconds, (millis / 1_000f).toString().removeSuffix(".0"))
        }
    }
    val selectedOptionIndex = options.indexOf(settings.autoSendSilenceMs)
        .takeIf { it >= 0 }
        ?: options.indexOf(SpeechSettings.DEFAULT_AUTO_SEND_SILENCE_MS)

    Column {
        SpeechGroupCard(title = stringResource(R.string.speech_group_result)) {
            if (streaming) {
                GlassDropdownPreference(
                    title = stringResource(R.string.speech_auto_send_title),
                    items = optionLabels,
                    selectedIndex = selectedOptionIndex,
                    backdrop = currentGlassOverlayBackdrop(),
                    onSelectedIndexChange = { index ->
                        options.getOrNull(index)?.let { store.edit(settings.copy(autoSendSilenceMs = it)) }
                    },
                )
            }
            SwitchPreference(
                title = stringResource(R.string.speech_refine_title),
                summary = stringResource(R.string.speech_refine_summary),
                checked = settings.refineTranscript,
                onCheckedChange = { store.edit(settings.copy(refineTranscript = it)) },
            )
        }
        if (streaming) SpeechNote(stringResource(R.string.speech_auto_send_note))
    }
}

@Composable
private fun SpeechRecognitionTestSection(store: SpeechSettingsStore) {
    val state by store.recognition.state.collectAsStateWithLifecycle()
    val request = rememberSpeechPermission { store.recognition.start(store.settings, store.credentials) }
    val summary = state.error
        ?: state.preview.take(200).ifBlank { state.progress }.ifBlank { null }
    val title = stringResource(if (state.active) R.string.speech_test_finish else R.string.speech_test_recognition)

    Column {
        SpeechGroupCard(title = stringResource(R.string.speech_test_recognition)) {
            BasicComponent(
                title = title,
                summary = summary,
                summaryColor = BasicComponentDefaults.summaryColor(
                    color = if (state.error != null) StatusError else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                ),
                enabled = !store.saving,
                onClick = { if (state.active) store.recognition.finish() else request() },
                endActions = if (state.active) {
                    {
                        TextButton(
                            text = stringResource(R.string.action_cancel),
                            onClick = { store.recognition.cancel() },
                            minWidth = 0.dp,
                            minHeight = 32.dp,
                            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                } else null,
            )
        }
        SpeechNote(stringResource(if (store.settings.asr == AsrProvider.ANDROID_OFFLINE)
            R.string.offline_asr_test_note else R.string.speech_test_note))
    }
}
