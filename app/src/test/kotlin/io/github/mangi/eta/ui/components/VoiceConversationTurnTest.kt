package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.*
import org.junit.Test

class VoiceConversationTurnTest {
    private val old = listOf<AgentChatMessageUi>(UserMessageUi("old-u", "旧问题"), AgentMessageUi("old-a", "旧回复"))
    private val baseline = old.mapTo(mutableSetOf()) { it.id }
    private val turn = old + listOf(UserMessageUi("new-u", "你好"), AgentMessageUi("new-a", "你好！"))

    @Test fun readsOnlyNewCompletedReply() {
        assertEquals("new-a", voiceConversationReply(turn, baseline)?.id)
    }
    @Test fun neverReadsHistoryOrGreeting() {
        assertNull(voiceConversationReply(old, baseline))
        assertNull(voiceConversationReply(listOf(AgentMessageUi("greeting", "欢迎")), emptySet()))
    }
    @Test fun neverReadsStreamingOrBlankReply() {
        assertNull(voiceConversationReply(turn.dropLast(1) + AgentMessageUi("new-a", "你好", isStreaming = true), baseline))
        assertNull(voiceConversationReply(turn.dropLast(1) + AgentMessageUi("new-a", " "), baseline))
    }
    @Test fun neverReadsStoppedFailedEmptyOrInterruptedTurn() {
        listOf(SystemNoticeCode.Stopped, SystemNoticeCode.RuntimeFailed, SystemNoticeCode.EmptyResult, SystemNoticeCode.Interrupted).forEach {
            assertNull(voiceConversationReply(turn + SystemNoticeMessageUi("notice", it), baseline))
        }
    }
    @Test fun readsFinalRatherThanIntermediateAnswer() {
        assertEquals("final", voiceConversationReply(turn + AgentMessageUi("final", "最终答案"), baseline)?.id)
    }
    @Test fun doesNotReplayPreviousReplyWhenNewTurnFails() {
        assertNull(voiceConversationReply(turn + UserMessageUi("later", "再问一次"), baseline))
    }
    @Test fun successfulRetryAndContextCompactionDoNotPreventReading() {
        assertNotNull(voiceConversationReply(turn + SystemNoticeMessageUi("retry", SystemNoticeCode.ModelRetry), baseline))
        assertNotNull(voiceConversationReply(turn + SystemNoticeMessageUi("compact", SystemNoticeCode.ContextCompaction), baseline))
    }
}
