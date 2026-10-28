package com.sven.assistant

import java.util.Locale

/** Decides whether free text looks like a phone task for the agent rather than chat. */
internal object AgentRouter {
    private val verbs = setOf(
        "open", "go", "tap", "click", "press", "turn", "switch", "enable", "disable", "toggle",
        "set", "change", "send", "reply", "find", "search", "play", "pause", "scroll", "type",
        "take", "add", "create", "check", "read", "download", "install", "uninstall", "call",
        "text", "message", "share", "post", "book", "order", "connect", "disconnect", "forget",
        "clear", "show", "navigate", "look", "start", "stop", "close", "delete", "remove"
    )

    fun looksLikeTask(text: String): Boolean {
        val t = text.trim().lowercase(Locale.ROOT).removePrefix("please ").removePrefix("can you ")
            .removePrefix("could you ").removePrefix("hey ")
        if (t.length < 4) return false
        val first = t.split(Regex("\\s+")).firstOrNull().orEmpty()
        if (t.endsWith("?") && first in setOf("what", "who", "why", "how", "when", "where", "is", "are")) return false
        return first in verbs
    }
}
