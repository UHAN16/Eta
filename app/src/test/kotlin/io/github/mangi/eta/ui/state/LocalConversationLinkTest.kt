package io.github.mangi.eta.ui.state

import org.junit.Assert.*
import org.junit.Test

class LocalConversationLinkTest {
    private val id = "conv-12345678-1234-1234-1234-123456789abc"

    @Test fun roundTripLocalId() {
        assertEquals(id, LocalConversationLink.parse(LocalConversationLink.encode(id))?.conversationId)
    }
    @Test fun preservesMultilineContinuationAsDraft() {
        val request = LocalConversationLink.parse(" \n${LocalConversationLink.encode(id)}\n继续这个故事\n但换个结局。 ")!!
        assertEquals(id, request.conversationId)
        assertEquals("继续这个故事\n但换个结局。", request.draft)
    }
    @Test fun rejectsRemoteUrlsPathsQueriesAndMalformedIds() {
        listOf(
            "https://example.org/$id", "eta://conversation/../../eta.db",
            "eta://conversation/$id?token=secret", "eta://conversation/$id/extra",
            "eta://conversation/not-a-conversation", "eta://conversation/%2e%2e",
            "eta://evil/$id", "eta://conversation/$id#fragment",
        ).forEach { assertNull(it, LocalConversationLink.parse(it)) }
    }
    @Test fun ordinaryMessagesAreNotLinks() {
        assertFalse(LocalConversationLink.containsLink("继续刚才的话题"))
        assertNull(LocalConversationLink.parse("普通文字"))
    }
    @Test fun rejectsUnrepresentableExternalArchiveIds() {
        assertTrue(runCatching { LocalConversationLink.encode("external-archive") }.isFailure)
    }
}
