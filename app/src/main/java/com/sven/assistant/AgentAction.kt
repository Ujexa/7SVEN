package com.sven.assistant

import org.json.JSONObject

/** One step chosen by the local model. */
internal data class AgentAction(
    val type: String,
    val id: Int?,
    val text: String?,
    val direction: String?,
    val name: String?,
    val args: JSONObject?,
    val message: String?
) {
    fun signature(): String = "$type|$id|$text|$direction|$name|${args?.toString()}"

    fun describe(snap: ScreenSnapshot?): String {
        val label = id?.let { snap?.find(it)?.label }?.takeIf { it.isNotEmpty() }?.let { " \"$it\"" }.orEmpty()
        return when (type) {
            "tap" -> "tap #$id$label"
            "type" -> "type \"${text.orEmpty().take(40)}\" into #$id$label"
            "submit" -> "press Enter in #$id$label"
            "scroll" -> "scroll ${direction ?: "down"}"
            "tool" -> "tool $name(${args?.toString()?.take(70) ?: ""})"
            "ask_user" -> "ask you a question"
            else -> type
        }
    }

    companion object {
        private val TYPES = setOf(
            "tap", "type", "submit", "scroll", "back", "home", "tool", "ask_user", "done", "fail"
        )

        /** [modelOutput] is whatever the model wrote AFTER the forced `{"action":"` prefix. */
        fun parse(modelOutput: String): AgentAction? {
            val full = AgentPrompt.PREFILL + modelOutput
            val json = extractObject(full) ?: return null
            val obj = try { JSONObject(json) } catch (_: Exception) { return null }
            val type = obj.optString("action").trim().lowercase()
            if (type !in TYPES) return null

            fun str(key: String): String? =
                if (obj.has(key) && !obj.isNull(key)) obj.optString(key).trim().ifEmpty { null } else null

            val id = if (obj.has("id")) obj.optInt("id", -1).takeIf { it >= 0 } else null
            val message = str("summary") ?: str("question") ?: str("reason") ?: str("message")
            return AgentAction(
                type = type,
                id = id,
                text = str("text"),
                direction = str("direction")?.lowercase(),
                name = str("name"),
                args = obj.optJSONObject("args"),
                message = message
            )
        }

        /** Returns the first balanced {...} object, repairing a truncated tail when possible. */
        private fun extractObject(s: String): String? {
            val start = s.indexOf('{')
            if (start < 0) return null
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until s.length) {
                val c = s[i]
                if (inString) {
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == '"') inString = false
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return s.substring(start, i + 1)
                    }
                }
            }
            if (depth <= 0) return null
            val repaired = StringBuilder(s.substring(start).trimEnd())
            if (inString) repaired.append('"')
            repeat(depth) { repaired.append('}') }
            return repaired.toString()
        }
    }
}
