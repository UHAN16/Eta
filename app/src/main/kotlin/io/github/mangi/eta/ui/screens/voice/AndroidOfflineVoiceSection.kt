package io.github.mangi.eta.ui.screens.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.audio.SpeechFailure
import io.github.mangi.eta.agent.voice.tts.AndroidOfflineVoice
import io.github.mangi.eta.agent.voice.tts.listAndroidOfflineVoices
import io.github.mangi.eta.ui.components.GlassSpinnerPreference
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun AndroidOfflineVoiceSection(store: SpeechSettingsStore) {
    val context = LocalContext.current
    val displayLocale = LocalConfiguration.current.locales[0]
    var reload by remember { mutableIntStateOf(0) }
    var voices by remember { mutableStateOf<List<AndroidOfflineVoice>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val fallbackError = stringResource(R.string.offline_tts_unavailable)
    LaunchedEffect(context, reload, fallbackError) {
        voices = null
        error = null
        try {
            voices = listAndroidOfflineVoices(context)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error = (failure as? SpeechFailure)?.userMessage ?: fallbackError
        }
    }
    val choices = voices.orEmpty()
    val chosen = store.settings.offlineTtsVoice
    val chosenIndex = choices.indexOfFirst { it.id == chosen }
    val missing = chosen.isNotBlank() && chosenIndex < 0
    val items = listOf(DropdownItem(text = stringResource(R.string.offline_tts_auto_voice))) +
        choices.map { DropdownItem(text = it.locale.getDisplayName(displayLocale), summary = it.id) } +
        if (missing) listOf(DropdownItem(text = stringResource(R.string.offline_tts_voice_missing), summary = chosen))
        else emptyList()
    Column {
        SpeechGroupCard(title = stringResource(R.string.speech_group_voice)) {
            GlassSpinnerPreference(
                title = stringResource(R.string.speech_voice),
                items = items,
                selectedIndex = if (missing) items.lastIndex else chosenIndex + 1,
                onSelectedIndexChange = { index ->
                    val id = if (index == 0) "" else choices.getOrNull(index - 1)?.id
                    if (id != null) store.edit(store.settings.copy(offlineTtsVoice = id))
                },
            )
            ArrowPreference(
                title = stringResource(R.string.offline_tts_refresh),
                summary = error ?: when {
                    voices == null -> stringResource(R.string.speech_preview_loading)
                    choices.isEmpty() -> stringResource(R.string.offline_tts_no_voices)
                    else -> null
                },
                onClick = { store.stop(); reload++ },
            )
        }
        SpeechNote(stringResource(R.string.offline_tts_voice_note))
    }
}
