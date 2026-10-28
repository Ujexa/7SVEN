package com.sven.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZoneId

// ===================================================================================
// v1.3 REMINDERS / TIMERS / SCHEDULED ACTIONS
// Everything is persisted, survives reboot (re-armed by BootReceiver) and is delivered even
// if the app is closed. Missed items (phone off at fire time) are delivered once on boot.
// ===================================================================================

internal data class Reminder(
    val id: Long,
    val text: String,
    /** "reminder" | "timer" | "briefing" */
    val kind: String,
    val whenMillis: Long,
    val repeat: Repeat,
    val minuteOfDay: Int,
    /** "active" | "done" | "cancelled" */
    val status: String,
    val createdAt: Long,
    val lastFiredAt: Long = 0L
)

internal interface AlarmArmer {
    fun arm(r: Reminder)
    fun disarm(id: Long)
}

internal class ReminderStore(dir: File) {
    private val store = JsonFileStore(File(dir, "sven_reminders.json"))
    private val items = ArrayList<Reminder>()
    private var nextId = 1L

    init {
        store.read()?.let { root ->
            nextId = root.optLong("nextId", 1L)
            val arr = root.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                runCatching {
                    items.add(Reminder(
                        o.getLong("id"), o.getString("text"), o.optString("kind", "reminder"), o.getLong("when"),
                        Repeat(runCatching { Repeat.Type.valueOf(o.optString("rt", "NONE")) }.getOrDefault(Repeat.Type.NONE), o.optInt("mask"), o.optInt("interval")),
                        o.optInt("mod"), o.optString("status", "active"), o.optLong("created"), o.optLong("fired")
                    ))
                }
            }
            if (items.isNotEmpty()) nextId = maxOf(nextId, items.maxOf { it.id } + 1)
        }
    }

    @Synchronized fun newId() = nextId++
    @Synchronized fun all(): List<Reminder> = items.toList()
    @Synchronized fun get(id: Long): Reminder? = items.firstOrNull { it.id == id }
    @Synchronized fun put(r: Reminder) {
        val i = items.indexOfFirst { it.id == r.id }
        if (i >= 0) items[i] = r else items.add(r)
        // keep the file small: drop finished items older than 30 days, cap history
        val cutoff = r.createdAt - 30L * 86_400_000L
        items.removeAll { it.status != "active" && maxOf(it.lastFiredAt, it.createdAt) < cutoff }
        while (items.count { it.status != "active" } > 40) items.remove(items.first { it.status != "active" })
        save()
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("id", it.id).put("text", it.text).put("kind", it.kind).put("when", it.whenMillis)
                .put("rt", it.repeat.type.name).put("mask", it.repeat.mask).put("interval", it.repeat.intervalMin)
                .put("mod", it.minuteOfDay).put("status", it.status).put("created", it.createdAt).put("fired", it.lastFiredAt))
        }
        store.write(JSONObject().put("v", 1).put("nextId", nextId).put("items", arr))
    }
}

internal data class AddResult(val reminder: Reminder, val duplicate: Boolean)
internal data class RearmReport(val missed: List<Reminder>, val armed: Int)

internal class ReminderService(
    private val store: ReminderStore,
    private val armer: AlarmArmer,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {
    @Volatile var lastFiredId: Long? = null
    @Volatile var lastAddedId: Long? = null

    fun active(): List<Reminder> = store.all().filter { it.status == "active" }.sortedBy { it.whenMillis }
    fun get(id: Long) = store.get(id)

    fun add(text: String, whenMs: Long, repeat: Repeat, minuteOfDay: Int, kind: String = "reminder"): AddResult {
        store.all().firstOrNull { it.status == "active" && it.kind == kind && it.text.equals(text, true) && kotlin.math.abs(it.whenMillis - whenMs) < 60_000 }
            ?.let { lastAddedId = it.id; return AddResult(it, true) }
        val r = Reminder(store.newId(), text, kind, whenMs, repeat, minuteOfDay, "active", now())
        store.put(r); armer.arm(r); lastAddedId = r.id
        return AddResult(r, false)
    }

    fun cancel(id: Long): Reminder? {
        val r = store.get(id) ?: return null
        if (r.status != "active") return null
        val c = r.copy(status = "cancelled"); store.put(c); armer.disarm(id); return c
    }

    fun cancelAll(kind: String? = null): Int {
        val list = active().filter { kind == null || it.kind == kind }
        list.forEach { cancel(it.id) }
        return list.size
    }

    fun find(text: String): List<Reminder> {
        val q = TextUtil.tokens(text)
        if (q.isEmpty()) return emptyList()
        return active().filter { r -> TextUtil.tokens(r.text).any { it in q } || (r.kind in q) }
    }

    fun reschedule(id: Long, whenMs: Long, minuteOfDay: Int): Reminder? {
        val r = store.get(id) ?: return null
        val moved = r.copy(whenMillis = whenMs, minuteOfDay = minuteOfDay, status = "active")
        store.put(moved); armer.arm(moved); return moved
    }

    fun snooze(id: Long, minutes: Int): Reminder? {
        val r = store.get(id) ?: return null
        val s = Reminder(store.newId(), r.text, if (r.kind == "timer") "reminder" else r.kind, now() + minutes * 60_000L, Repeat(), r.minuteOfDay, "active", now())
        store.put(s); armer.arm(s); lastAddedId = s.id
        return s
    }

    /** Called when the alarm fires. Advances repeats; one-offs become done. Returns the fired reminder. */
    fun onFired(id: Long): Reminder? {
        val r = store.get(id) ?: return null
        if (r.status != "active") return null
        val t = now()
        lastFiredId = id
        if (r.repeat.repeating) {
            val base = maxOf(t, r.whenMillis)
            val next = r.repeat.next(base, r.minuteOfDay, zone())
            if (next != null) {
                val adv = r.copy(whenMillis = next, lastFiredAt = t); store.put(adv); armer.arm(adv); return r
            }
        }
        store.put(r.copy(status = "done", lastFiredAt = t))
        return r
    }

    /** Boot / package update / time change: re-arm everything, collect what was missed while off. */
    fun rearmAll(): RearmReport {
        val t = now(); val missed = ArrayList<Reminder>(); var armed = 0
        for (r in active()) {
            if (r.whenMillis > t) { armer.arm(r); armed++; continue }
            val late = t - r.whenMillis
            if (r.repeat.repeating) {
                if (late < 6 * 3_600_000L && r.kind != "briefing") missed.add(r)
                val next = r.repeat.next(t, r.minuteOfDay, zone())
                if (next != null) { val adv = r.copy(whenMillis = next, lastFiredAt = t); store.put(adv); armer.arm(adv); armed++ } else store.put(r.copy(status = "done"))
            } else {
                if (late < 24 * 3_600_000L) missed.add(r)
                store.put(r.copy(status = "done", lastFiredAt = t))
            }
        }
        return RearmReport(missed, armed)
    }

    fun describe(r: Reminder): String {
        val whenTxt = TimeParser.formatWhen(r.whenMillis, now(), zone())
        val rep = if (r.repeat.repeating) " · " + r.repeat.describe(r.minuteOfDay) else ""
        val label = when (r.kind) { "timer" -> "Timer"; "briefing" -> "Briefing"; else -> r.text }
        return "#${r.id} $label — $whenTxt$rep"
    }

    // ------------------------------------------------------------------ text commands

    /** list / cancel / snooze / reschedule. Returns a reply or null if [raw] is not about reminders. */
    fun handleCommand(raw: String): String? {
        val t = raw.trim().replace(Regex("[.!?]+$"), "")
        val l = t.lowercase()
        val I = RegexOption.IGNORE_CASE

        if (Regex("^(?:show|list|what(?:'s| are| is)|see|read)?(?: me)?(?: all)?(?: my| the)? (?:active |upcoming |pending )?(?:reminders|timers|alarms i set|schedule)(?: do i have| i have| are set)?$", I).matches(t) ||
            l in setOf("reminders", "my reminders", "what reminders do i have", "do i have any reminders")) {
            val a = active()
            return if (a.isEmpty()) "You have no active reminders or timers." else "Active reminders:\n" + a.take(12).joinToString("\n") { describe(it) } + (if (a.size > 12) "\n…and ${a.size - 12} more." else "")
        }
        if (Regex("^(?:cancel|delete|remove|clear) (?:all )?(?:my )?(?:reminders|timers)$", I).matches(t)) {
            val n = cancelAll(if (l.contains("timer")) "timer" else null)
            return if (n == 0) "There was nothing to cancel." else "Cancelled $n item(s)."
        }
        Regex("^(?:cancel|delete|remove) (?:the |my )?(?:reminder|timer) ?#(\\d+)$", I).find(t)?.let {
            val c = cancel(it.groupValues[1].toLong()); return if (c == null) "I have no active reminder #${it.groupValues[1]}." else "Cancelled: ${describe(c)}"
        }
        if (l in setOf("cancel the timer", "stop the timer", "cancel my timer", "cancel timer")) {
            val tm = active().filter { it.kind == "timer" }
            return when (tm.size) { 0 -> "No timer is running."; 1 -> { cancel(tm[0].id); "Timer cancelled." }; else -> "Several timers are running; say \"cancel reminder #id\":\n" + tm.joinToString("\n") { describe(it) } }
        }
        Regex("^(?:cancel|delete|remove) (?:the |my )?reminder (?:about |to |for )?(.+)$", I).find(t)?.let {
            val hits = find(it.groupValues[1])
            return when (hits.size) {
                0 -> "I found no active reminder about \"${it.groupValues[1]}\"."
                1 -> { cancel(hits[0].id); "Cancelled: ${describe(hits[0])}" }
                else -> "More than one match; say \"cancel reminder #id\":\n" + hits.joinToString("\n") { describe(it) }
            }
        }
        Regex("^snooze(?: (?:it|that))?(?: for)?(?: (\\d+) ?(?:min|minutes?|m))?$", I).find(t)?.let {
            val id = lastFiredId ?: return "Nothing has gone off recently to snooze."
            val mins = it.groupValues[1].ifEmpty { "10" }.toInt().coerceIn(1, 24 * 60)
            val s = snooze(id, mins) ?: return "I cannot find that reminder any more."
            return "Snoozed. I will remind you again ${TimeParser.formatWhen(s.whenMillis, now(), zone())}."
        }
        Regex("^(?:reschedule|move|change) (?:the )?reminder ?#(\\d+) (?:to|for) (.+)$", I).find(t)?.let {
            val id = it.groupValues[1].toLong()
            val pt = TimeParser.extract(it.groupValues[2], now(), zone()) ?: return "I did not understand that time."
            val moved = reschedule(id, pt.whenMillis, pt.minuteOfDay) ?: return "I have no reminder #$id."
            return "Moved: ${describe(moved)}"
        }
        return null
    }

    /** Executes a parsed "remind" tool call. Returns the user-facing message. */
    fun execute(args: JSONObject): String {
        if (args.optBoolean("missingTime")) return "When should I remind you? Say e.g. \"in 20 minutes\" or \"tomorrow at 9\"."
        val whenMs = args.optLong("when", 0L)
        if (whenMs <= now()) return "That time has already passed."
        val kind = args.optString("kind", "reminder").ifEmpty { "reminder" }
        val rep = Repeat(runCatching { Repeat.Type.valueOf(args.optString("repeat", "NONE")) }.getOrDefault(Repeat.Type.NONE), args.optInt("mask"), args.optInt("interval"))
        val mod = args.optInt("minuteOfDay", -1).let { if (it >= 0) it else java.time.Instant.ofEpochMilli(whenMs).atZone(zone()).let { z -> z.hour * 60 + z.minute } }
        val text = args.optString("text", "Reminder").ifBlank { "Reminder" }
        val res = add(text, whenMs, rep, mod, kind)
        val r = res.reminder
        val whenTxt = TimeParser.formatWhen(r.whenMillis, now(), zone())
        val head = if (kind == "timer") "Timer set" else if (kind == "briefing") "Briefing scheduled" else "Reminder set"
        return (if (res.duplicate) "Already scheduled: " else "$head (#${r.id}): ") + (if (kind == "timer") "" else "\"${r.text}\" ") +
            whenTxt + (if (r.repeat.repeating) " · " + r.repeat.describe(r.minuteOfDay) else "") + "."
    }
}

/** "every day at 8 give me my briefing" -> a scheduled briefing. */
internal object ScheduleCommands {
    private val re = Regex("(?:give me|read|show|send me|do|run)\\s+(?:me\\s+)?(?:my\\s+|a\\s+|the\\s+)?(?:morning\\s+|daily\\s+|day\\s+)?briefing", RegexOption.IGNORE_CASE)

    fun parseBriefing(text: String, now: Long, zone: ZoneId): JSONObject? {
        if (!re.containsMatchIn(text)) return null
        val t = TimeParser.extract(text, now, zone) ?: return null
        return JSONObject().put("text", "Briefing").put("kind", "briefing").put("when", t.whenMillis).put("repeat", t.repeat.type.name)
            .put("mask", t.repeat.mask).put("interval", t.repeat.intervalMin).put("minuteOfDay", t.minuteOfDay)
    }
}
