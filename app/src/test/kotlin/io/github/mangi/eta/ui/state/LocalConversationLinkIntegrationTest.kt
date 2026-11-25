package io.github.mangi.eta.ui.state

import android.content.Context
import io.github.mangi.eta.agent.model.provider.AgentModelClient.ConversationMessage
import io.github.mangi.eta.agent.runtime.ipc.AgentRuntimeWire
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class LocalConversationLinkIntegrationTest {
    private lateinit var context: Context
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val id = "conv-12345678-1234-1234-1234-123456789abc"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(RuntimeEnvironment.getApplication()).declareActionUnbindable(AgentRuntimeWire.ACTION_BIND)
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }
    @After fun teardown() {
        scope.cancel()
        await { job.isCompleted }
        EtaDatabase.closeForTests()
    }
    private fun seed(failedTail: Boolean = false): AgentAppState {
        val history = listOf(ConversationMessage("user", "旧问题", messageId = "user-0"),
            ConversationMessage("assistant", "旧答案", messageId = "assistant-0"))
        val messages = listOf<AgentChatMessageUi>(UserMessageUi("user-0", "旧问题"), AgentMessageUi("assistant-0-0", "旧答案")) +
            if (failedTail) listOf(UserMessageUi("user-1", "未完成"), SystemNoticeMessageUi("failed", SystemNoticeCode.RuntimeFailed)) else emptyList()
        val journal = history + if (failedTail) listOf(ConversationMessage("user", "未完成", messageId = "user-1")) else emptyList()
        val source = AgentChatHomeUiState(
            messages = messages,
            history = journal, journal = journal, input = "", isStreaming = false, thinkingEnabled = false,
        )
        runBlocking { AgentConversationStore.save(context, id, mapOf(id to source), mapOf(id to "Original"), mapOf(id to 1L)) }
        return AgentAppState(context, scope)
    }
    @Test fun pastedLinkCreatesIndependentHistoryAndRetainsContinuationDraft() {
        val state = seed()
        state.createConversation()
        state.sendCurrentMessage(LocalConversationLink.encode(id) + "\n继续故事")
        await { !state.isBranchingCurrentConversation && state.conversationPaneState.selectedConversationId != null }
        val child = state.conversationPaneState.selectedConversationId!!
        assertNotEquals(id, child)
        assertEquals("继续故事", state.homeState.input)
        assertEquals(listOf("旧问题", "旧答案"), state.homeState.history.map { it.content })
        assertFalse(state.homeState.isStreaming)
        val source = runBlocking { AgentConversationStore.loadAllMessages(context, id) }
        assertEquals(listOf("user-0", "assistant-0-0"), source.map { it.id })
        assertTrue(state.homeState.messages.none { it.id in source.map { row -> row.id } })
    }
    @Test fun missingLinkRemainsDraftAndDoesNotStartModelRequest() {
        val state = seed()
        state.createConversation()
        val missing = "eta://conversation/conv-99999999-9999-9999-9999-999999999999"
        state.sendCurrentMessage(missing)
        assertEquals(missing, state.homeState.input)
        assertFalse(state.homeState.isStreaming)
        assertTrue(state.homeState.messages.isEmpty())
    }
    @Test fun rejectsImportIntoNonemptyChatWithoutChangingHistory() {
        val state = seed()
        state.sendCurrentMessage(LocalConversationLink.encode(id))
        assertEquals(id, state.conversationPaneState.selectedConversationId)
        assertEquals(2, state.homeState.messages.size)
        assertFalse(state.homeState.isStreaming)
    }
    @Test fun failedLastTurnIsNotSilentlyDropped() {
        val state = seed(failedTail = true)
        state.createConversation()
        val link = LocalConversationLink.encode(id)
        state.sendCurrentMessage(link)
        await { !state.isBranchingCurrentConversation }
        assertNull(state.conversationPaneState.selectedConversationId)
        assertEquals(link, state.homeState.input)
        assertTrue(state.homeState.messages.isEmpty())
        assertEquals(1, state.conversationPaneState.conversations.size)
    }
    @Test fun navigationDuringImportDoesNotStealCurrentConversation() {
        val state = seed()
        state.createConversation()
        state.sendCurrentMessage(LocalConversationLink.encode(id))
        state.createConversation()
        await { !state.isBranchingCurrentConversation && state.conversationPaneState.conversations.size == 2 }
        assertNull(state.conversationPaneState.selectedConversationId)
        assertTrue(state.homeState.messages.isEmpty())
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("Timed out waiting for local persistence", condition())
    }
}
