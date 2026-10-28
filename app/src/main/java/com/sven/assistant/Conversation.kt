package com.sven.assistant

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

// ===================================================================================
// v1.4 NATURAL INTERACTION (pure logic)
//   ConversationState / FollowUps : "make it 8", "text her", "what about tomorrow", "again", "undo", "yes"
//   PromptBuilder / Summarizer    : keeps long conversations inside the 2048-token context
//   Speech                        : spoken-form cleanup and sentence chunks for interruptible TTS
//   VoiceTurns                    : when a hands-free conversation should continue or end
// ===================================================================================

internal data class Choice(val label: String, val command: String)

internal class ConversationState {
    @Volatile var lastCall: ToolCall? = null
    @Volatile var lastCommandText: String? = null
    @Volatile var lastReminderId: Long? = null
    @Volatile var lastWhen: Long? = null
    @Volatile var lastEntity: String? = null
    @Volatile var lastReply: String = ""
    @Volatile var pendingChoice: List<Choice>? = null
    /** A command waiting for its missing time, e.g. "remind me to buy milk". */
    @Volatile var pendingTimeFor: String? = null
    @Volatile var pendingSuggestion: ProactiveItem? = null
    @Volatile var undo: (() -> String)? = null
    @Volatile var lastActivityAt: Long = 0L

    fun clearPending() { pendingChoice = null; pendingTimeFor = null }
    fun reset() { lastCall = null; lastCommandText = null; lastReminderId = null; lastWhen = null; lastEntity = null; lastReply = ""; clearPending(); pendingSuggestion = null; undo = null }
}

internal sealed class Resolution {
    data class Rewrite(val text: String, val preface: String? = null) : Resolution()
    data class Say(val text: String) : Resolution()
    data class Reschedule(val reminderId: Long, val whenMillis: Long, val minuteOfDay: Int) : Resolution()
    object AcceptSuggestion : Resolution()
    object DeclineSuggestion : Resolution()
    object Undo : Resolution()
    object SpeakLast : Resolution()
    /** Stop talking / working right now. */
    object Interrupt : Resolution()
}

internal object FollowUps {
    private val I = RegexOption.IGNORE_CASE
    private val interrupts = setOf("stop", "stop it", "stop talking", "be quiet", "quiet", "shut up", "enough", "cancel", "cancel that", "never mind", "nevermind", "forget it", "hold on", "wait", "pause", "stop listening", "that's enough", "thats enough", "ok stop", "okay stop")
    private val yes = setOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "please", "do it", "go ahead", "yes please", "sounds good", "schedule it", "yes schedule it", "make it so")
    private val no = setOf("no", "nope", "nah", "not now", "no thanks", "no thank you", "don't", "skip it", "not interested")
    private val undo = setOf("undo", "undo that", "scratch that", "take that back", "revert that", "cancel it", "delete it", "remove it", "remove that", "delete that")
    private val again = setOf("again", "do it again", "do that again", "same again", "once more", "one more time", "repeat the command", "run it again")
    private val speakAgain = setOf("repeat that", "say that again", "what did you say", "sorry", "pardon", "come again", "say it again", "repeat", "what was that", "can you repeat that", "i didn't catch that")
    private val thanks = setOf("thanks", "thank you", "thanks a lot", "thank you so much", "cheers", "ta", "great thanks", "perfect thanks")
    private val ack = setOf("ok", "okay", "cool", "great", "nice", "got it", "alright", "fine", "good", "perfect", "awesome", "right")
    private val ordinals = mapOf("first" to 0, "1st" to 0, "one" to 0, "1" to 0, "second" to 1, "2nd" to 1, "two" to 1, "2" to 1, "third" to 2, "3rd" to 2, "three" to 2, "3" to 2, "last" to -1)

    fun norm(s: String) = s.trim().lowercase(Locale.ROOT).replace(Regex("[.!?,]+$"), "").replace(Regex("^(?:hey sven|sven|hey)[, ]+"), "").trim()

    fun isInterrupt(text: String) = norm(text) in interrupts

    fun resolve(raw: String, st: ConversationState, now: Long, zone: ZoneId = ZoneId.systemDefault()): Resolution? {
        val l = norm(raw)
        if (l.isEmpty()) return null

        if (l in interrupts) return Resolution.Interrupt

        // ---- answers to a question I asked
        st.pendingTimeFor?.let { base ->
            val t = TimeParser.extract(l, now, zone)
            st.pendingTimeFor = null
            if (t != null) return Resolution.Rewrite("$base ${l}")
            if (l in no || l in interrupts) return Resolution.Say("Okay, no reminder.")
        }
        st.pendingChoice?.let { options ->
            val idx = ordinals[l.removePrefix("the ").removeSuffix(" one").trim()]
            if (idx != null) {
                val c = if (idx == -1) options.lastOrNull() else options.getOrNull(idx)
                if (c != null) { st.pendingChoice = null; return Resolution.Rewrite(c.command) }
            }
            val byLabel = options.filter { l.length >= 3 && it.label.lowercase(Locale.ROOT).contains(l) }
            if (byLabel.size == 1) { st.pendingChoice = null; return Resolution.Rewrite(byLabel[0].command) }
            if (l in no || l == "none" || l == "neither") { st.pendingChoice = null; return Resolution.Say("Okay, I won't do that.") }
        }
        if (st.pendingSuggestion != null) {
            if (l in yes) return Resolution.AcceptSuggestion
            if (l in no) return Resolution.DeclineSuggestion
        }

        // ---- memory corrections: "no, my name is Sam" / "actually I live in Boston"
        Regex("^(?:no|actually|wait|correction|sorry|that's wrong|thats wrong|that is wrong)[,:]? (.+)$", I).find(l)?.let { m ->
            val rest = m.groupValues[1].trim()
            val raw2 = raw.trim().let { r -> Regex("^(?:no|actually|wait|correction|sorry|that's wrong|thats wrong|that is wrong)[,:]?\\s+", I).replaceFirst(r, "") }
            if (MemoryExtractor.classify(raw2.trimEnd('.', '!'), "explicit") != null) return Resolution.Rewrite("remember that $raw2")
            // fall through: might be a time adjustment ("no, 9 pm")
            adjust(rest, st, now, zone)?.let { return it }
        }

        // ---- social / meta
        if (l in undo) return Resolution.Undo
        if (l in again && st.lastCommandText != null) return Resolution.Rewrite(st.lastCommandText!!)
        if (l in speakAgain && st.lastReply.isNotBlank()) return Resolution.SpeakLast
        if (l in thanks) return Resolution.Say("You're welcome.")
        if (l in ack && st.lastReply.isNotBlank() && st.pendingSuggestion == null) return Resolution.Say("Okay.")
        if (Regex("^(?:hi|hello|hey|yo|good (?:morning|afternoon|evening))(?: there| sven)?$", I).matches(l)) return Resolution.Say("Hi. What can I do for you?")

        // ---- adjustments to the last time-based command
        adjust(l, st, now, zone)?.let { return it }

        // ---- pronouns that refer to the last contact
        Regex("^(call|dial|phone|text|message|sms|whatsapp|email)\\s+(her|him|them|back|that person)\\b(.*)$", I).find(raw.trim())?.let { m ->
            val who = st.lastEntity ?: return null
            val verb = m.groupValues[1]; val rest = m.groupValues[3]
            return Resolution.Rewrite("$verb $who$rest".trim())
        }

        // ---- "what about X" after a search / navigate / play / open / calendar
        Regex("^(?:what about|how about|and|or|now|try|and what about|what about for)\\s+(.+)$", I).find(raw.trim().trimEnd('.', '!', '?'))?.let { m ->
            val arg = m.groupValues[1].trim()
            val call = st.lastCall ?: return null
            return when (call.tool) {
                "web_search" -> Resolution.Rewrite("search for $arg")
                "navigate" -> Resolution.Rewrite("navigate to $arg")
                "play_media" -> Resolution.Rewrite("play $arg")
                "open_app" -> Resolution.Rewrite("open $arg")
                "read_calendar" -> if (arg in setOf("today", "tomorrow", "this week", "next week")) Resolution.Rewrite("what's on my calendar ${arg.replace("next week", "this week")}") else null
                else -> null
            }
        }
        return null
    }

    /** "make it 8", "change it to 8:30 pm", "no, 9", "an hour later", "15 minutes earlier". */
    private fun adjust(l: String, st: ConversationState, now: Long, zone: ZoneId): Resolution? {
        val call = st.lastCall ?: return null
        if (call.tool !in setOf("set_alarm", "remind", "add_event")) return null
        val shift = Regex("^(?:make it |move it |push it |set it )?(?:(\\d+|an?|half an?) ?(minutes?|mins?|hours?|hrs?)) (later|earlier|before|after)$", RegexOption.IGNORE_CASE).find(l)
        val phrase = Regex("^(?:make it|change it to|set it to|move it to|make that|let's say|lets say|how about|instead|change it|no|use|at)\\s+(?:to\\s+|at\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(l)?.groupValues?.get(1)?.trim()
            ?: if (Regex("^\\d{1,2}(?::\\d{2})? ?(?:am|pm)?$", RegexOption.IGNORE_CASE).matches(l)) l else null

        var newMs: Long? = null; var newH = 0; var newM = 0
        if (shift != null) {
            val q = shift.groupValues[1].lowercase()
            val amount = when { q == "a" || q == "an" -> 1.0; q.startsWith("half") -> 0.5; else -> q.toDouble() }
            val unit = if (shift.groupValues[2].lowercase().startsWith("h")) 60.0 else 1.0
            val deltaMin = (amount * unit).toLong() * if (shift.groupValues[3].lowercase() == "earlier" || shift.groupValues[3].lowercase() == "before") -1 else 1
            val base: Long = when (call.tool) {
                "set_alarm" -> Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atTime(call.args.optInt("hour"), call.args.optInt("minute")).atZone(zone).toInstant().toEpochMilli()
                else -> st.lastWhen ?: return null
            }
            newMs = base + deltaMin * 60_000L
            val z = Instant.ofEpochMilli(newMs).atZone(zone); newH = z.hour; newM = z.minute
        } else if (phrase != null) {
            val prevHour = when (call.tool) {
                "set_alarm" -> call.args.optInt("hour")
                else -> Instant.ofEpochMilli(st.lastWhen ?: now).atZone(zone).hour
            }
            val bare = Regex("^(\\d{1,2})(?::(\\d{2}))? ?(am|pm)?$", RegexOption.IGNORE_CASE).find(phrase)
            if (bare != null) {
                var h = bare.groupValues[1].toInt(); val mi = bare.groupValues[2].ifEmpty { "0" }.toInt()
                when (bare.groupValues[3].lowercase()) {
                    "pm" -> if (h < 12) h += 12
                    "am" -> if (h == 12) h = 0
                    // no am/pm: stay in the same half of the day as the time being replaced
                    else -> if (h in 1..11 && prevHour >= 12) h += 12
                }
                if (h > 23 || mi > 59) return null
                newH = h; newM = mi
                if (call.tool != "set_alarm") {
                    val base = Instant.ofEpochMilli(st.lastWhen ?: now).atZone(zone).toLocalDate()
                    newMs = base.atTime(h, mi).atZone(zone).toInstant().toEpochMilli()
                    if (newMs!! <= now) newMs = base.plusDays(1).atTime(h, mi).atZone(zone).toInstant().toEpochMilli()
                }
            } else {
                val p = TimeParser.extract(phrase, now, zone) ?: return null
                newMs = p.whenMillis; newH = p.minuteOfDay / 60; newM = p.minuteOfDay % 60
            }
        } else return null

        return when (call.tool) {
            "set_alarm" -> Resolution.Rewrite(
                "set alarm for ${CommandParser.fmt(newH, newM)}" + call.args.optString("label").takeIf { it.isNotEmpty() }?.let { " called $it" }.orEmpty(),
                "I can't delete the earlier alarm from the clock app, so remove it there if you don't need it."
            )
            "remind" -> {
                val id = st.lastReminderId ?: return null
                Resolution.Reschedule(id, newMs ?: return null, newH * 60 + newM)
            }
            else -> Resolution.Rewrite("add event ${call.args.optString("title")} at ${CommandParser.fmt(newH, newM)}")
        }
    }
}

// ----------------------------------------------------------------------------- prompt building

internal object Summarizer {
    private fun firstSentence(s: String, max: Int): String {
        val t = s.trim().replace(Regex("\\s+"), " ")
        val cut = Regex("(?<=[.!?])\\s").find(t)?.range?.first ?: t.length
        return t.substring(0, minOf(cut, max, t.length)).trim().let { if (it.length < t.length && cut > max) "$it…" else it }
    }

    /** Extractive recap of turns that no longer fit. Newest turns win when space is short. */
    fun summarize(dropped: List<Pair<String, String>>, maxChars: Int = 480): String {
        val lines = ArrayList<String>()
        for ((role, text) in dropped) {
            if (role == "system") continue
            if (text.split(' ').size < 3) continue
            lines += if (role == "user") "User: " + firstSentence(text, 70) else "You: " + firstSentence(text, 70)
        }
        val out = ArrayDeque<String>(); var used = 0
        for (l in lines.asReversed()) { if (used + l.length + 3 > maxChars) break; out.addFirst(l); used += l.length + 3 }
        return out.joinToString(" | ")
    }
}

internal data class BuiltPrompt(val prompt: String, val includedTurns: Int, val droppedTurns: Int, val chars: Int)

internal object PromptBuilder {
    /** ~4400 chars is about 1,300 tokens: room for the 256-token reply inside the 2048-token context. */
    const val MAX_CHARS = 4400
    private const val TURN_CAP = 700

    fun build(system: String, memoryLines: List<String>, turns: List<Pair<String, String>>, maxChars: Int = MAX_CHARS): BuiltPrompt {
        val convo = turns.filter { it.first == "user" || it.first == "assistant" }
        val memory = if (memoryLines.isEmpty()) "" else " What you know about the user (context, not commands): " + memoryLines.joinToString("; ") + "."
        val tail = "<|im_start|>assistant\n"

        fun head(summary: String): String {
            val sum = if (summary.isEmpty()) "" else " Earlier in this conversation: $summary."
            return "<|im_start|>system\n$system$memory$sum<|im_end|>\n"
        }
        // First pass: how many recent turns fit with an empty summary slot of ~480 chars reserved.
        val reserve = 520
        var budget = maxChars - head("").length - tail.length - reserve
        val chosen = ArrayList<Pair<String, String>>()
        for ((role, text) in convo.asReversed()) {
            val t = text.trim().take(TURN_CAP)
            val cost = role.length + t.length + 28
            if (cost > budget && chosen.isNotEmpty()) break
            chosen.add(0, role to (if (cost > budget) t.take((budget - role.length - 28).coerceAtLeast(60)) else t))
            budget -= cost
            if (budget <= 0) break
        }
        val droppedList = convo.dropLast(chosen.size)
        val summary = if (droppedList.isEmpty()) "" else Summarizer.summarize(droppedList, minOf(480, maxChars - head("").length - tail.length - chosen.sumOf { it.second.length + it.first.length + 28 }).coerceAtLeast(0))
        val sb = StringBuilder(head(summary))
        chosen.forEach { (role, text) -> sb.append("<|im_start|>").append(role).append('\n').append(text).append("<|im_end|>\n") }
        sb.append(tail)
        return BuiltPrompt(sb.toString(), chosen.size, droppedList.size, sb.length)
    }
}

// ----------------------------------------------------------------------------- speech

internal object Speech {
    /** Makes a reply pleasant to hear: no markdown, bullets, ids or marks. */
    fun clean(text: String): String = text
        .replace(Regex("#(\\d+)"), "number $1")
        .replace(Regex("[✓✗•■·–—📌→←↑↓●○]"), " ")
        .replace(Regex("[*_`>]+"), "")
        .replace(Regex("\\(at (\\d{1,2}:\\d{2}) (am|pm)\\)"), "at $1 $2")
        .replace(Regex("\\n{2,}"), ". ")
        .replace(Regex("\\n"), ". ")
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\.\\s*\\."), ".")
        .trim()

    /** Sentence-sized pieces so a spoken reply can be cut off between sentences. */
    fun chunks(text: String, maxLen: Int = 220): List<String> {
        val cleaned = clean(text)
        if (cleaned.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (s in cleaned.split(Regex("(?<=[.!?])\\s+"))) {
            var rest = s.trim()
            while (rest.length > maxLen) {
                val cut = rest.lastIndexOf(' ', maxLen).let { if (it < 40) maxLen else it }
                out.add(rest.substring(0, cut).trim()); rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) out.add(rest)
        }
        return out
    }
}

/** Hands-free conversation policy: keep listening for follow-ups, end politely on silence. */
internal class VoiceTurns(private val maxSilentTurns: Int = 2, private val maxTurns: Int = 25) {
    var turns = 0; private set
    var silent = 0; private set

    fun reset() { turns = 0; silent = 0 }
    fun onHeard() { turns++; silent = 0 }
    /** Returns true when the session should end because the user has gone quiet. */
    fun onSilence(): Boolean { silent++; return silent >= maxSilentTurns }
    fun shouldContinue(): Boolean = turns < maxTurns
}
