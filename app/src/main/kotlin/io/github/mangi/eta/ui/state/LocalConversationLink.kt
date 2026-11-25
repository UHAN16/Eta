package io.github.mangi.eta.ui.state

/** Local database reference only. Never dereference a URL or accept a filesystem path. */
internal object LocalConversationLink {
    private val id = Regex("conv-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val link = Regex("^eta://conversation/(${id.pattern})(?:\\s+([\\s\\S]*))?$")
    data class Request(val conversationId: String, val draft: String)

    fun encode(conversationId: String): String {
        require(id.matches(conversationId))
        return "eta://conversation/$conversationId"
    }

    fun parse(text: String): Request? = link.matchEntire(text.trim())?.let {
        Request(it.groupValues[1], it.groupValues[2].trim())
    }

    fun containsLink(text: String): Boolean = text.contains("eta://conversation/", ignoreCase = true)
}
