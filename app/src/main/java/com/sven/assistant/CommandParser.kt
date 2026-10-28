package com.sven.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * One concrete phone action. [expect] is a machine-checkable post-condition ("alarm:7:30", "torch:on",
 * "reminder") that StateVerifier turns into verified / unverified / failed.
 */
internal data class ToolCall(
    val tool: String,
    val args: JSONObject,
    val label: String,
    val expect: String? = null,
    /** Re-running this call twice must not create a second visible effect. */
    val idempotent: Boolean = true
)

/** Expected end-state for free-form agent goals ("turn on wifi" -> "wifi:on"). */
internal object GoalExpectation {
    private val re = Regex(
        "\\b(?:turn|switch|toggle|enable|disable)\\s+(on|off)\\s+(?:the\\s+|my\\s+)?(wi-?fi|bluetooth|airplane mode|flight mode|flashlight|torch)\\b" +
            "|\\b(?:turn|switch)\\s+(?:the\\s+|my\\s+)?(wi-?fi|bluetooth|airplane mode|flight mode|flashlight|torch)\\s+(on|off)\\b" +
            "|\\b(enable|disable)\\s+(?:the\\s+|my\\s+)?(wi-?fi|bluetooth|airplane mode|flight mode)\\b",
        RegexOption.IGNORE_CASE
    )

    fun parse(goal: String): String? {
        val m = re.find(goal) ?: return null
        val g = m.groupValues
        val (onOff, what) = when {
            g[1].isNotEmpty() -> g[1] to g[2]
            g[3].isNotEmpty() -> g[4] to g[3]
            else -> (if (g[5].lowercase() == "enable") "on" else "off") to g[6]
        }
        val key = when (what.lowercase().replace("-", "")) {
            "wifi" -> "wifi"
            "bluetooth" -> "bluetooth"
            "airplane mode", "flight mode" -> "airplane"
            else -> "torch"
        }
        return "$key:${onOff.lowercase()}"
    }
}

internal object CommandParser {
    private val I = RegexOption.IGNORE_CASE

    private fun call(tool: String, label: String, expect: String? = null, idem: Boolean = true, build: JSONObject.() -> Unit = {}): ToolCall =
        ToolCall(tool, JSONObject().apply(build), label, expect, idem)

    /** Calendar-style day numbers (SUNDAY=1 .. SATURDAY=7) for the clock app's repeat days. */
    fun clockDays(r: Repeat): JSONArray {
        val out = JSONArray()
        fun add(dowMon1: Int) = out.put(if (dowMon1 == 7) 1 else dowMon1 + 1)
        when (r.type) {
            Repeat.Type.DAILY -> (1..7).forEach { add(it) }
            Repeat.Type.WEEKDAYS -> (1..5).forEach { add(it) }
            Repeat.Type.WEEKENDS -> { add(6); add(7) }
            Repeat.Type.WEEKLY -> (1..7).filter { (r.mask shr (it - 1)) and 1 == 1 }.forEach { add(it) }
            else -> {}
        }
        return out
    }

    fun parse(raw: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): ToolCall? {
        val text = raw.trim().replace(Regex("[.!]+$"), "").replace(Regex("^(?:please|hey|ok|okay|and|then)[, ]+", I), "").trim()
        val lower = text.lowercase(Locale.ROOT)
        if (text.isEmpty()) return null

        // ---- reminders & timers (persisted by the reminder service)
        Regex("^(?:start |set |create )?(?:a |an )?timer(?: for)?\\s+(.+)$", I).find(text)?.let { m ->
            val t = TimeParser.extract("in " + m.groupValues[1].removePrefix("for ").trim(), now, zone)
            if (t != null) return call("remind", "Timer ${m.groupValues[1].trim()}", "reminder", false) {
                put("text", "Timer finished"); put("when", t.whenMillis); put("kind", "timer")
            }
        }
        Regex("^(?:remind me|reminder|set (?:a )?reminder)\\s*(.*)$", I).find(text)?.let { m ->
            var body = m.groupValues[1].trim()
            val t = TimeParser.extract(body, now, zone)
            if (t != null) body = TimeParser.strip(body, t)
            body = body.replace(Regex("^(?:to|about|that)\\s+", I), "").trim()
            return call("remind", "Reminder: $body", "reminder", false) {
                put("text", body.ifEmpty { "Reminder" })
                if (t != null) {
                    put("when", t.whenMillis); put("repeat", t.repeat.type.name); put("mask", t.repeat.mask)
                    put("interval", t.repeat.intervalMin); put("minuteOfDay", t.minuteOfDay)
                } else put("missingTime", true)
            }
        }

        // ---- alarms (system clock app)
        Regex("^(?:set|create|make|add)\\s+(?:an?\\s+)?alarm(?:\\s+(?:for|at))?\\s+(.+)$", I).find(text)?.let { m -> return alarm(m.groupValues[1], now, zone) }
        Regex("^wake me(?: up)?\\s+(?:at\\s+|by\\s+)?(.+)$", I).find(text)?.let { m -> return alarm(m.groupValues[1], now, zone) }

        // ---- calls, texts, mail
        Regex("^(?:call|dial|phone|ring)\\s+(\\+?[\\d][\\d \\-()]{2,})$", I).find(text)?.let {
            return call("dial", "Call ${it.groupValues[1].trim()}") { put("number", it.groupValues[1]) }
        }
        Regex("^(?:call|phone|ring|dial)\\s+(?!back$)(?:my\\s+)?(.{2,40})$", I).find(text)?.let {
            val name = it.groupValues[1].trim()
            if (!name.contains(Regex("\\b(?:and|then|to|the)\\b", I))) return call("call_contact", "Call $name") { put("name", name) }
        }
        Regex("^(?:text|sms|message|send a text to|send (?:a )?message to)\\s+(\\+?\\d[\\d\\-() ]{2,}?)\\s*(?:saying|that says|that|:)?\\s+(.+)$", I).find(text)?.let {
            return call("compose_sms", "Text ${it.groupValues[1].trim()}") { put("number", it.groupValues[1].trim()); put("text", it.groupValues[2].trim()) }
        }
        Regex("^(?:text|sms|message|send a text to|send (?:a )?message to)\\s+(.+?)\\s+(?:saying|that says|that|:)\\s+(.+)$", I).find(text)?.let {
            return call("text_contact", "Text ${it.groupValues[1].trim()}") { put("name", it.groupValues[1].trim()); put("text", it.groupValues[2].trim()) }
        }
        Regex("^(?:text|sms|message)\\s+(\\S+)\\s+(.+)$", I).find(text)?.let {
            return call("text_contact", "Text ${it.groupValues[1]}") { put("name", it.groupValues[1]); put("text", it.groupValues[2].trim()) }
        }
        Regex("^whatsapp\\s+(\\+?\\d[\\d\\-() ]{5,}?|\\S+)\\s*(?:saying|that|:)?\\s+(.+)$", I).find(text)?.let {
            return call("whatsapp", "WhatsApp ${it.groupValues[1]}") { put("to", it.groupValues[1].trim()); put("text", it.groupValues[2].trim()) }
        }
        Regex("^(?:email|e-mail|send an email to|compose an email to|write an email to)\\s+(\\S+@\\S+)(?:\\s+(?:about|subject)\\s+(.+?))?(?:\\s+(?:saying|body|that says)\\s+(.+))?$", I).find(text)?.let {
            return call("compose_email", "Email ${it.groupValues[1]}") { put("to", it.groupValues[1]); put("subject", it.groupValues[2]); put("body", it.groupValues[3]) }
        }

        // ---- calendar
        Regex("^(?:add|create|schedule|put|book)\\s+(?:an?\\s+)?(?:event|meeting|appointment)\\s+(.+)$", I).find(text)?.let { m ->
            var body = m.groupValues[1]
            val t = TimeParser.extract(body, now, zone)
            if (t != null) body = TimeParser.strip(body, t)
            body = body.replace(Regex("^(?:called|named|titled|for|about)\\s+", I), "").trim()
            return call("add_event", "Event: $body", null, false) {
                put("title", body.ifEmpty { "Event" }); if (t != null) put("when", t.whenMillis) else put("missingTime", true)
            }
        }
        if (Regex("^(?:what(?:'s| is)(?: on)? (?:my )?(?:calendar|schedule|agenda)(?: (?:today|tomorrow|this week))?|what do i have (?:today|tomorrow|this week|on)|show (?:me )?(?:my )?(?:calendar|schedule|agenda)|(?:what(?:'s| is) )?(?:my )?(?:next|upcoming) (?:event|meeting|appointment)s?|what(?:'s| is) next on my calendar)$", I).containsMatchIn(text)) {
            val range = when { lower.contains("tomorrow") -> "tomorrow"; lower.contains("week") -> "week"; lower.contains("next") -> "next"; else -> "today" }
            return call("read_calendar", "Calendar ($range)") { put("range", range) }
        }

        // ---- briefing / status
        if (Regex("^(?:give me |read |show |tell me )?(?:my )?(?:morning |daily |day )?(?:briefing|brief)(?: me)?$|^brief me$|^what(?:'s| is) my day (?:look like|like)$|^summary of my day$", I).containsMatchIn(text))
            return call("briefing", "Briefing")
        if (Regex("^(?:what(?:'s| is) )?(?:my |the )?battery(?: level| percentage| percent| status| life)?$|^how much battery(?: do i have| is left| left)?$|^battery$", I).containsMatchIn(text))
            return call("battery", "Battery")
        Regex("^is (?:my |the )?(wi-?fi|bluetooth|airplane mode|flashlight|torch) (on|off|enabled|disabled)$", I).find(text)?.let {
            return call("device_status", "Check ${it.groupValues[1]}") { put("what", it.groupValues[1].lowercase().replace("-", "")) }
        }

        // ---- media
        Regex("^(?:(pause|resume|next|previous|prev|skip)(?: the)?(?: music| song| track| media| playback| this song| this track)?|(play|pause|resume)(?: the)? (?:music|song|media|playback)|(next|previous|skip) (?:song|track))$", I).find(text)?.let {
            val a = it.groupValues.drop(1).firstOrNull { g -> g.isNotEmpty() }!!.lowercase()
            val action = when (a) { "skip" -> "next"; "prev" -> "previous"; "play" -> "resume"; else -> a }
            return call("media_control", "Media: $action") { put("action", action) }
        }
        Regex("^play (?!the (?:music|song|media)$)(.+?)(?: on (spotify|youtube music|youtube))?$", I).find(text)?.let {
            return call("play_media", "Play ${it.groupValues[1]}") { put("query", it.groupValues[1].trim()); put("app", it.groupValues[2].lowercase()) }
        }

        // ---- settings pages and system navigation
        Regex("^(?:open|show|go to)\\s+(?:the\\s+)?(?:(wi-?fi|bluetooth|display|sound|battery|location|accessibility|apps|data|network|brightness|volume)\\s+)?settings$", I).find(text)?.let {
            val page = it.groupValues[1].lowercase().replace("-", "")
            return call("settings", "Settings ${page.ifEmpty { "" }}".trim(), "foreground:settings") { put("page", page) }
        }
        Regex("^go (back|home)$", I).find(text)?.let { return call("system_nav", "Go ${it.groupValues[1]}") { put("to", it.groupValues[1].lowercase()) } }
        if (lower == "show recents" || lower == "open recents") return call("system_nav", "Recents") { put("to", "recents") }
        if (lower == "show notifications" || lower == "open notifications") return call("system_nav", "Notifications") { put("to", "notifications") }

        // ---- lights, sound
        Regex("^(?:turn )?(on|off) (?:the )?(?:flashlight|torch)$|^(?:flashlight|torch) (on|off)$", I).find(text)?.let {
            val on = (it.groupValues[1].ifEmpty { it.groupValues[2] }).lowercase() == "on"
            return call("flashlight", "Flashlight ${if (on) "on" else "off"}", "torch:${if (on) "on" else "off"}") { put("on", on) }
        }
        Regex("^volume (up|down)$", I).find(text)?.let { return call("volume", "Volume ${it.groupValues[1]}") { put("direction", it.groupValues[1].lowercase()) } }
        Regex("^(?:set )?volume (?:to )?(\\d{1,3}) ?%?$", I).find(text)?.let {
            val p = it.groupValues[1].toInt().coerceIn(0, 100)
            return call("volume", "Volume $p%", "volume:$p") { put("percent", p) }
        }
        if (lower == "mute" || lower == "unmute") return call("volume", lower.replaceFirstChar { c -> c.uppercase() }) { put("direction", lower) }

        // ---- search / navigate / clipboard / open app
        Regex("^(?:search(?: the web)? for|google|look up)\\s+(.+)$", I).find(text)?.let {
            return call("web_search", "Search ${it.groupValues[1]}", "foreground:other") { put("query", it.groupValues[1].trim()) }
        }
        Regex("^(?:navigate to|directions to|take me to)\\s+(.+)$", I).find(text)?.let {
            return call("navigate", "Navigate to ${it.groupValues[1]}", "foreground:other") { put("place", it.groupValues[1].trim()) }
        }
        Regex("^copy (.+?) to (?:the )?clipboard$", I).find(text)?.let { return call("copy_text", "Copy to clipboard") { put("text", it.groupValues[1].trim()) } }
        Regex("^(?:open|launch|start)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$", I).find(text)?.let {
            val name = it.groupValues[1].trim()
            val l = name.lowercase()
            val compound = l.contains(" and ") || l.contains(" then ") || l.contains(" to ")
            if (!compound && name.split(" ").size <= 3) return call("open_app", "Open $name", "foreground:app") { put("name", name) }
        }
        return null
    }

    private fun alarm(rest: String, now: Long, zone: ZoneId): ToolCall? {
        var body = rest.trim()
        var label = ""
        Regex("\\s+(?:called|named|labelled|labeled|titled)\\s+(.+)$", I).find(body)?.let { label = it.groupValues[1].trim(); body = body.removeRange(it.range).trim() }
        val t = TimeParser.extract(body, now, zone)
        val h: Int; val m: Int
        if (t != null && (t.hasClock || t.repeat.repeating)) { h = t.minuteOfDay / 60; m = t.minuteOfDay % 60 }
        else {
            val mm = Regex("^(\\d{1,2})(?::(\\d{2}))? ?(am|pm)?$", I).find(body.trim()) ?: return null
            var hh = mm.groupValues[1].toInt(); m = mm.groupValues[2].ifEmpty { "0" }.toInt()
            when (mm.groupValues[3].lowercase()) { "pm" -> if (hh < 12) hh += 12; "am" -> if (hh == 12) hh = 0 }
            if (hh !in 0..23 || m !in 0..59) return null
            h = hh
        }
        val repeat = t?.repeat ?: Repeat()
        val daysTxt = if (repeat.repeating && repeat.type != Repeat.Type.INTERVAL) " (" + repeat.describe(h * 60 + m).substringBefore(" at ") + ")" else ""
        return call("set_alarm", "Alarm %02d:%02d%s".format(h, m, daysTxt), "alarm:$h:$m", false) {
            put("hour", h); put("minute", m); put("label", label)
            if (repeat.repeating && repeat.type != Repeat.Type.INTERVAL) put("days", clockDays(repeat))
        }
    }

    /** Human-readable "HH:MM" used by follow-ups. */
    fun fmt(h: Int, m: Int) = "%02d:%02d".format(h, m)

    @Suppress("unused")
    fun zonedNow(now: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(now).atZone(zone)
}
