package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi

internal fun voiceTurnMessages(messages: List<AgentChatMessageUi>, baselineIds: Set<String>): List<AgentChatMessageUi>? {
    val user = messages.indexOfLast { it is UserMessageUi && it.id !in baselineIds }
    return if (user < 0) null else messages.drop(user + 1).filterNot { it.id in baselineIds }
}

internal fun List<AgentChatMessageUi>.voiceTurnFailed() = any {
    it is SystemNoticeMessageUi && it.code in setOf(
        SystemNoticeCode.Stopped, SystemNoticeCode.RuntimeFailed, SystemNoticeCode.EmptyResult, SystemNoticeCode.Interrupted,
    )
}

internal class VoiceCallTurn(baseline: Set<String>) {
    val baselineIds: Set<String> = baseline
    val speech = StreamingVoiceReply()
}

/** Only a new, successful final answer may be spoken. Never replay a greeting/history. */
internal fun voiceConversationReply(
    messages: List<AgentChatMessageUi>,
    baselineIds: Set<String>,
): AgentMessageUi? {
    val turn = voiceTurnMessages(messages, baselineIds) ?: return null
    if (turn.voiceTurnFailed()) return null
    return (turn.lastOrNull { it is AgentMessageUi } as? AgentMessageUi)
        ?.takeIf { !it.isStreaming && it.content.isNotBlank() }
}

/** Consume only new sentence-sized slices. Provider revisions must not cause a reread. */
internal class StreamingVoiceReply {
    private val sent = mutableMapOf<String, String>()

    fun next(messageId: String, text: String, final: Boolean): String? {
        val previous = sent[messageId].orEmpty()
        val start = if (text.startsWith(previous)) previous.length else minOf(previous.length, text.length)
        val rest = text.substring(start).trimStart()
        if (rest.isBlank()) return null
        val offset = text.length - rest.length
        val end = rest.indices.take(160).firstOrNull { index ->
            val ch = rest[index]
            ch in "。！？!?；;\n" ||
                (ch == '.' && (index + 1 < rest.length && rest[index + 1].isWhitespace() || final && index == rest.lastIndex))
        }?.plus(1) ?: when {
            rest.length > 160 -> rest.take(160).indexOfLast { it.isWhitespace() || it in "，、：" }
                .takeIf { it >= 24 }?.plus(1) ?: 120
            final -> rest.length
            else -> return null
        }
        val safeEnd = if (rest[end - 1].isHighSurrogate()) end - 1 else end
        if (safeEnd == 0) return null
        sent[messageId] = text.take(offset + safeEnd)
        return rest.take(safeEnd).trim()
    }
}

internal fun soundsLikePlayback(recognized: String, recentPlayback: List<String>): Boolean {
    fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
    val actual = normalize(recognized)
    return actual.isNotEmpty() && normalize(recentPlayback.joinToString(" ")).contains(actual)
}

internal class VoiceCallEchoWindow {
    private var recent = emptyList<String>()
    private var lastPlayedAt: Long? = null

    fun play(text: String, now: Long) {
        if (lastPlayedAt?.let { now - it > TAIL_MS } != false) recent = emptyList()
        recent = (recent + text).takeLast(3)
        lastPlayedAt = now
    }

    fun stopped(now: Long) { if (lastPlayedAt != null) lastPlayedAt = now }

    fun isEcho(text: String, now: Long, playing: Boolean): Boolean =
        (playing || lastPlayedAt?.let { now - it <= TAIL_MS } == true) && soundsLikePlayback(text, recent)

    fun clear() { recent = emptyList(); lastPlayedAt = null }

    private companion object { const val TAIL_MS = 4_000 }
}

/** Do not depend on a transient streaming placeholder: very fast replies may never render one. */
internal class ChatAutoReadTracker(private val initialIds: Set<String>) {
    private val watched = mutableSetOf<String>()
    private val completed = mutableSetOf<String>()

    fun update(messages: List<AgentChatMessageUi>, streaming: Boolean): AgentMessageUi? {
        messages.filterIsInstance<AgentMessageUi>().filter { it.isStreaming }
            .forEach { watched.add(it.id) }
        if (streaming) return null
        val newest = messages.filterIsInstance<AgentMessageUi>().lastOrNull() ?: return null
        if (newest.isStreaming || newest.content.isBlank() || newest.id in completed) return null
        if (newest.id in initialIds && newest.id !in watched) return null
        val afterNewUser = voiceConversationReply(messages, initialIds)?.id == newest.id
        val tail = messages.drop(messages.indexOfLast { it.id == newest.id } + 1)
        if (tail.voiceTurnFailed() || !(afterNewUser || newest.id in watched)) return null
        completed.add(newest.id)
        return newest
    }
}
