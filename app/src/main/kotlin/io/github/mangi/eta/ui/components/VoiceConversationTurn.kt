package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi

internal enum class VoiceConversationPhase { OFF, LISTENING, WAITING, SPEAKING }

/** Only a new, successful final answer may be spoken. Never replay a greeting/history. */
internal fun voiceConversationReply(
    messages: List<AgentChatMessageUi>,
    baselineIds: Set<String>,
): AgentMessageUi? {
    val newMessages = messages.filterNot { it.id in baselineIds }
    val userIndex = newMessages.indexOfLast { it is UserMessageUi }
    if (userIndex < 0) return null
    val turn = newMessages.drop(userIndex + 1)
    if (turn.any { it is SystemNoticeMessageUi && it.code in setOf(
        SystemNoticeCode.Stopped, SystemNoticeCode.RuntimeFailed,
        SystemNoticeCode.EmptyResult, SystemNoticeCode.Interrupted,
    ) }) return null
    return (turn.lastOrNull { it is AgentMessageUi } as? AgentMessageUi)
        ?.takeIf { !it.isStreaming && it.content.isNotBlank() }
}
