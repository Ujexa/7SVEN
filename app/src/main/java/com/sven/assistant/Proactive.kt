package com.sven.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// ===================================================================================
// v1.3 PROACTIVE ASSISTANT (pure logic)
//   InterruptionPolicy : decides deliver / defer / drop so background help stays quiet
//   RuleEngine         : event triggers (battery low, charging started, event soon)
//   HabitDetector      : suggests scheduling things the user keeps doing by hand
//   Briefing           : the "what's my day" summary
// ===================================================================================

internal enum class Urgency { URGENT, NORMAL, LOW }

internal data class ProactiveItem(
    /** "reminder" | "rule" | "suggestion" | "briefing" | "task" */
    val kind: String,
    /** Stable key used for cooldown and de-duplication. */
    val key: String,
    val urgency: Urgency,
    val text: String,
    /** Command to run if the user accepts a suggestion (null for plain notifications). */
    val acceptCommand: String? = null
)

internal data class PolicySettings(
    val enabled: Boolean = true,
    val quietStartMin: Int = 22 * 60,
    val quietEndMin: Int = 7 * 60,
    val maxSuggestionsPerDay: Int = 2,
    val maxRuleNotificationsPerDay: Int = 6,
    val cooldownMs: Long = 6 * 3_600_000L,
    val muteAfterDismissals: Int = 3,
    val muteDays: Int = 7
) {
    fun inQuietHours(minuteOfDay: Int): Boolean =
        if (quietStartMin == quietEndMin) false
        else if (quietStartMin < quietEndMin) minuteOfDay in quietStartMin until quietEndMin
        else minuteOfDay >= quietStartMin || minuteOfDay < quietEndMin
}

internal sealed class Decision {
    object Deliver : Decision()
    data class Defer(val untilMillis: Long, val why: String) : Decision()
    data class Drop(val why: String) : Decision()
}

/** Persisted counters so limits hold across process restarts. */
internal class PolicyStore(dir: File) {
    private val store = JsonFileStore(File(dir, "sven_policy.json"))
    var day: String = ""
    val counts = HashMap<String, Int>()            // "suggestion", "rule"
    val lastShown = HashMap<String, Long>()
    val dismissStreak = HashMap<String, Int>()
    val mutedUntil = HashMap<String, Long>()
    val deferred = ArrayList<Pair<Long, ProactiveItem>>()   // due time -> item

    init {
        store.read()?.let { o ->
            day = o.optString("day")
            o.optJSONObject("counts")?.let { c -> c.keyList().forEach { k -> counts[k] = c.optInt(k) } }
            o.optJSONObject("last")?.let { c -> c.keyList().forEach { k -> lastShown[k] = c.optLong(k) } }
            o.optJSONObject("streak")?.let { c -> c.keyList().forEach { k -> dismissStreak[k] = c.optInt(k) } }
            o.optJSONObject("muted")?.let { c -> c.keyList().forEach { k -> mutedUntil[k] = c.optLong(k) } }
            o.optJSONArray("deferred")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { d ->
                    deferred.add(d.optLong("due") to ProactiveItem(d.optString("kind"), d.optString("key"),
                        runCatching { Urgency.valueOf(d.optString("u")) }.getOrDefault(Urgency.NORMAL), d.optString("text"), d.optString("accept").ifEmpty { null }))
                }
            }
        }
    }

    @Synchronized fun save() {
        fun m(map: Map<String, Any>) = JSONObject().also { j -> map.forEach { (k, v) -> j.put(k, v) } }
        val d = JSONArray(); deferred.forEach { (due, i) -> d.put(JSONObject().put("due", due).put("kind", i.kind).put("key", i.key).put("u", i.urgency.name).put("text", i.text).put("accept", i.acceptCommand)) }
        store.write(JSONObject().put("day", day).put("counts", m(counts)).put("last", m(lastShown)).put("streak", m(dismissStreak)).put("muted", m(mutedUntil)).put("deferred", d))
    }
}

internal class InterruptionPolicy(
    private val settings: () -> PolicySettings,
    private val state: PolicyStore,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** True while Android Do Not Disturb is filtering interruptions. */
    private val dndActive: () -> Boolean = { false }
) {
    private fun minuteOfDay(t: Long) = Instant.ofEpochMilli(t).atZone(zone()).let { it.hour * 60 + it.minute }
    private fun classOf(kind: String) = if (kind == "suggestion") "suggestion" else "rule"

    private fun rollDay() {
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(now()), zone()).toString()
        if (state.day != today) { state.day = today; state.counts.clear() }
    }

    @Synchronized
    fun decide(item: ProactiveItem): Decision {
        val s = settings()
        val t = now()
        rollDay()
        // Things the user explicitly asked for (reminders, timers) are never held back by this policy.
        if (item.urgency == Urgency.URGENT) return Decision.Deliver
        if (!s.enabled) return Decision.Drop("proactive help is off")
        if ((state.mutedUntil[item.kind] ?: 0L) > t) return Decision.Drop("muted after repeated dismissals")
        if (t - (state.lastShown[item.key] ?: 0L) < s.cooldownMs && state.lastShown.containsKey(item.key)) return Decision.Drop("shown recently")
        val cls = classOf(item.kind)
        val cap = if (cls == "suggestion") s.maxSuggestionsPerDay else s.maxRuleNotificationsPerDay
        if ((state.counts[cls] ?: 0) >= cap) return Decision.Drop("daily limit reached")
        val quiet = s.inQuietHours(minuteOfDay(t))
        if (quiet || dndActive()) {
            if (item.urgency == Urgency.LOW) return Decision.Drop("quiet time")
            val until = if (quiet) nextQuietEnd(t, s) else t + 30 * 60_000L
            return Decision.Defer(until, if (quiet) "quiet hours" else "Do Not Disturb")
        }
        return Decision.Deliver
    }

    private fun nextQuietEnd(t: Long, s: PolicySettings): Long {
        var d = Instant.ofEpochMilli(t).atZone(zone()).toLocalDate()
        val endToday = d.atTime(s.quietEndMin / 60, s.quietEndMin % 60).atZone(zone()).toInstant().toEpochMilli()
        if (endToday > t) return endToday
        d = d.plusDays(1)
        return d.atTime(s.quietEndMin / 60, s.quietEndMin % 60).atZone(zone()).toInstant().toEpochMilli()
    }

    @Synchronized
    fun defer(item: ProactiveItem, until: Long) {
        state.deferred.removeAll { it.second.key == item.key }
        state.deferred.add(until to item); state.save()
    }

    @Synchronized
    fun takeDue(): List<ProactiveItem> {
        val t = now()
        val due = state.deferred.filter { it.first <= t }.map { it.second }
        if (due.isNotEmpty()) { state.deferred.removeAll { it.first <= t }; state.save() }
        return due
    }

    @Synchronized
    fun recordShown(item: ProactiveItem) {
        rollDay()
        state.lastShown[item.key] = now()
        if (item.urgency != Urgency.URGENT) state.counts[classOf(item.kind)] = (state.counts[classOf(item.kind)] ?: 0) + 1
        state.save()
    }

    /** The notification was swiped away without being used. */
    @Synchronized
    fun recordDismissed(kind: String) {
        if (kind == "reminder") return
        val n = (state.dismissStreak[kind] ?: 0) + 1
        state.dismissStreak[kind] = n
        if (n >= settings().muteAfterDismissals) {
            state.mutedUntil[kind] = now() + settings().muteDays * 86_400_000L
            state.dismissStreak[kind] = 0
        }
        state.save()
    }

    /** The user tapped or acted on it: clear the streak. */
    @Synchronized
    fun recordEngaged(kind: String) { state.dismissStreak[kind] = 0; state.mutedUntil.remove(kind); state.save() }

    @Synchronized
    fun unmuteAll() { state.mutedUntil.clear(); state.dismissStreak.clear(); state.save() }
}

// ----------------------------------------------------------------------------- rules

internal data class EventFact(val title: String, val startMillis: Long)
internal data class Facts(val batteryPct: Int, val charging: Boolean, val events: List<EventFact>?)

internal data class Rule(
    val id: Long, val type: String, val param: Int, val text: String, val enabled: Boolean = true,
    val armed: Boolean = true, val lastKey: String = "", val lastCharging: Boolean? = null
)

internal class RuleStore(dir: File) {
    private val store = JsonFileStore(File(dir, "sven_rules.json"))
    private val items = ArrayList<Rule>()
    private var nextId = 1L

    init {
        store.read()?.let { r ->
            nextId = r.optLong("nextId", 1L)
            val a = r.optJSONArray("items") ?: JSONArray()
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
                items.add(Rule(o.getLong("id"), o.getString("type"), o.optInt("param"), o.optString("text"), o.optBoolean("enabled", true),
                    o.optBoolean("armed", true), o.optString("lastKey"), if (o.has("lastCharging")) o.optBoolean("lastCharging") else null))
            }
            if (items.isNotEmpty()) nextId = maxOf(nextId, items.maxOf { it.id } + 1)
        }
    }

    @Synchronized fun all(): List<Rule> = items.toList()
    @Synchronized fun add(type: String, param: Int, text: String): Rule {
        val r = Rule(nextId++, type, param, text); items.add(r); save(); return r
    }
    @Synchronized fun update(r: Rule) { val i = items.indexOfFirst { it.id == r.id }; if (i >= 0) { items[i] = r; save() } }
    @Synchronized fun remove(id: Long): Boolean { val ok = items.removeAll { it.id == id }; if (ok) save(); return ok }

    private fun save() {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("id", it.id).put("type", it.type).put("param", it.param).put("text", it.text).put("enabled", it.enabled).put("armed", it.armed).put("lastKey", it.lastKey).also { o -> it.lastCharging?.let { c -> o.put("lastCharging", c) } }) }
        store.write(JSONObject().put("v", 1).put("nextId", nextId).put("items", a))
    }
}

internal class RuleEngine(private val store: RuleStore) {
    /** Edge-triggered: a rule fires once when its condition becomes true and re-arms when it clears. */
    fun evaluate(f: Facts, nowMs: Long): List<ProactiveItem> {
        val out = ArrayList<ProactiveItem>()
        for (r in store.all()) {
            if (!r.enabled) continue
            when (r.type) {
                "battery_low" -> {
                    val low = f.batteryPct in 0..r.param && !f.charging
                    if (low && r.armed) {
                        out += ProactiveItem("rule", "rule:${r.id}", Urgency.NORMAL, r.text.ifBlank { "Battery is at ${f.batteryPct}%. Time to charge." }.replace("{battery}", "${f.batteryPct}%"))
                        store.update(r.copy(armed = false))
                    } else if (!r.armed && (f.charging || f.batteryPct > r.param + 5)) store.update(r.copy(armed = true))
                }
                "charging_start" -> {
                    val prev = r.lastCharging
                    if (prev == false && f.charging) out += ProactiveItem("rule", "rule:${r.id}", Urgency.NORMAL, r.text.ifBlank { "Charging started." })
                    if (prev != f.charging) store.update(r.copy(lastCharging = f.charging))
                }
                "event_soon" -> {
                    val evs = f.events ?: continue
                    for (e in evs) {
                        val mins = (e.startMillis - nowMs) / 60_000L
                        val key = "${e.title}@${e.startMillis}"
                        if (mins in 0..r.param.toLong() && r.lastKey != key) {
                            out += ProactiveItem("rule", "rule:${r.id}:$key", Urgency.NORMAL, "In $mins min: ${e.title}")
                            store.update(r.copy(lastKey = key)); break
                        }
                    }
                }
            }
        }
        return out
    }

    fun describe(r: Rule): String = "#${r.id} " + when (r.type) {
        "battery_low" -> "when battery ≤ ${r.param}%: ${r.text.ifBlank { "notify" }}"
        "charging_start" -> "when charging starts: ${r.text.ifBlank { "notify" }}"
        else -> "${r.param} min before calendar events"
    }
}

internal object RuleCommands {
    private val I = RegexOption.IGNORE_CASE

    /** Returns a reply, or null if [raw] is not a rule command. */
    fun handle(raw: String, store: RuleStore, engine: RuleEngine): String? {
        val t = raw.trim().replace(Regex("[.!?]+$"), "")
        val l = t.lowercase()
        Regex("^(?:when|if|whenever) (?:my )?battery (?:is |gets |drops |falls |goes )?(?:below|under|lower than|at|reaches|less than)\\s*(\\d{1,2}) ?(?:%|percent)?,? ?(?:then )?(?:remind me|tell me|notify me|alert me|let me know|warn me)(?: to)? ?(.*)$", I).find(t)?.let {
            val pct = it.groupValues[1].toInt().coerceIn(1, 95)
            val msg = it.groupValues[2].trim().ifEmpty { "" }
            val r = store.add("battery_low", pct, if (msg.isEmpty()) "Battery is at {battery}. Time to charge." else TextUtil.capitalize(msg) + " (battery {battery})")
            return "Rule saved: ${engine.describe(r)}. I only notify once per drop, and stay quiet during quiet hours."
        }
        Regex("^(?:remind me|tell me|notify me|alert me)(?: to)? ?(.*?) when (?:my )?battery (?:is |gets |drops |falls )?(?:below|under|at|reaches) ?(\\d{1,2}) ?(?:%|percent)?$", I).find(t)?.let {
            val r = store.add("battery_low", it.groupValues[2].toInt().coerceIn(1, 95), if (it.groupValues[1].isBlank()) "Battery is at {battery}. Time to charge." else TextUtil.capitalize(it.groupValues[1]) + " (battery {battery})")
            return "Rule saved: ${engine.describe(r)}."
        }
        Regex("^(?:when|whenever) i (?:plug in|start charging|connect)(?: my)?(?: phone| charger| it)?,? ?(?:then )?(?:remind me|tell me|notify me)(?: to)? ?(.*)$", I).find(t)?.let {
            val r = store.add("charging_start", 0, TextUtil.capitalize(it.groupValues[1].trim()))
            return "Rule saved: ${engine.describe(r)}."
        }
        Regex("^(?:remind|notify|alert|warn) me (\\d{1,3}) ?(?:min|minutes?) before (?:my )?(?:events?|meetings?|appointments?|calendar events?)$", I).find(t)?.let {
            val r = store.add("event_soon", it.groupValues[1].toInt().coerceIn(1, 180), "")
            return "Rule saved: ${engine.describe(r)}. This needs calendar permission (see SETUP)."
        }
        if (l in setOf("my rules", "list rules", "show rules", "show my rules", "what rules do i have", "rules")) {
            val a = store.all()
            return if (a.isEmpty()) "No rules yet. Try: \"when battery is below 20% remind me to charge\"." else "Your rules:\n" + a.joinToString("\n") { engine.describe(it) }
        }
        Regex("^(?:delete|remove|cancel) rule ?#?(\\d+)$", I).find(t)?.let {
            return if (store.remove(it.groupValues[1].toLong())) "Rule removed." else "I have no rule #${it.groupValues[1]}."
        }
        if (l in setOf("delete all rules", "remove all rules", "clear rules")) { val n = store.all().size; store.all().forEach { r -> store.remove(r.id) }; return "Removed $n rule(s)." }
        return null
    }

    fun policyCommand(raw: String, get: () -> PolicySettings, set: (PolicySettings) -> Unit, policy: InterruptionPolicy): String? {
        val t = raw.trim().replace(Regex("[.!?]+$"), "")
        Regex("^(?:turn|switch) (on|off) (?:proactive|suggestions|proactive help|proactive suggestions)$", I).find(t)?.let {
            val on = it.groupValues[1].lowercase() == "on"
            set(get().copy(enabled = on)); if (on) policy.unmuteAll()
            return if (on) "Proactive suggestions are on (at most ${get().maxSuggestionsPerDay} a day, never during quiet hours)."
            else "Proactive suggestions are off. Reminders you set still arrive."
        }
        Regex("^(?:set )?quiet hours (?:from |to |are )?(\\d{1,2})(?::(\\d{2}))? ?(am|pm)? (?:to|until|-|till) (\\d{1,2})(?::(\\d{2}))? ?(am|pm)?$", I).find(t)?.let {
            fun conv(h: String, m: String, ap: String): Int { var hh = h.toInt(); when (ap.lowercase()) { "pm" -> if (hh < 12) hh += 12; "am" -> if (hh == 12) hh = 0 }; return (hh % 24) * 60 + m.ifEmpty { "0" }.toInt() }
            val g = it.groupValues
            val a = conv(g[1], g[2], g[3]); val b = conv(g[4], g[5], g[6])
            set(get().copy(quietStartMin = a, quietEndMin = b))
            return "Quiet hours: %d:%02d to %d:%02d. Suggestions and rules wait until they end; reminders you set still arrive.".format(a / 60, a % 60, b / 60, b % 60)
        }
        if (Regex("^(?:turn off|disable|no) quiet hours$", I).matches(t)) { set(get().copy(quietStartMin = 0, quietEndMin = 0)); return "Quiet hours are off." }
        return null
    }
}

// ----------------------------------------------------------------------------- habits

internal class ActionLog(dir: File) {
    private val store = JsonFileStore(File(dir, "sven_habits.json"))
    private val entries = ArrayList<Pair<Long, String>>()
    private val suggested = HashMap<String, Long>()     // sig -> when suggested (or -1 if declined)

    init {
        store.read()?.let { o ->
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) a.optJSONArray(i)?.let { p -> entries.add(p.getLong(0) to p.getString(1)) } }
            o.optJSONObject("suggested")?.let { s -> s.keyList().forEach { k -> suggested[k] = s.optLong(k) } }
        }
    }

    @Synchronized fun add(ts: Long, sig: String) { entries.add(ts to sig); while (entries.size > 300) entries.removeAt(0); save() }
    @Synchronized fun entries(): List<Pair<Long, String>> = entries.toList()
    @Synchronized fun lastSuggested(sig: String): Long? = suggested[sig]
    @Synchronized fun markSuggested(sig: String, ts: Long) { suggested[sig] = ts; save() }
    @Synchronized fun markDeclined(sig: String) { suggested[sig] = -1L; save() }
    private fun save() {
        val a = JSONArray(); entries.forEach { a.put(JSONArray().put(it.first).put(it.second)) }
        val s = JSONObject(); suggested.forEach { (k, v) -> s.put(k, v) }
        store.write(JSONObject().put("log", a).put("suggested", s))
    }
}

internal data class HabitSuggestion(val sig: String, val text: String, val acceptCommand: String)

internal object HabitDetector {
    /** Only low-risk things can be suggested for scheduling: no calls, texts or sends. */
    fun signature(tc: ToolCall): String? = when (tc.tool) {
        "briefing" -> "briefing"
        "read_calendar" -> "read_calendar:" + tc.args.optString("range", "today")
        "battery" -> "battery"
        "set_alarm" -> if (tc.args.has("days")) null else "set_alarm:${tc.args.optInt("hour")}:${tc.args.optInt("minute")}"
        else -> null
    }

    fun detect(log: ActionLog, nowMs: Long, zone: ZoneId, alreadyScheduled: (String) -> Boolean): HabitSuggestion? {
        val cutoff = nowMs - 14L * 86_400_000L
        val bySig = log.entries().filter { it.first >= cutoff }.groupBy { it.second }
        for ((sig, list) in bySig) {
            val last = log.lastSuggested(sig)
            if (last != null && (last < 0 || nowMs - last < 30L * 86_400_000L)) continue
            if (alreadyScheduled(sig)) continue
            val days = list.map { Instant.ofEpochMilli(it.first).atZone(zone).toLocalDate() }.toSet()
            if (days.size < 3) continue
            val mins = list.map { Instant.ofEpochMilli(it.first).atZone(zone).let { z -> z.hour * 60 + z.minute } }.sorted()
            val median = mins[mins.size / 2]
            val tight = mins.count { kotlin.math.abs(it - median) <= 45 } >= 3
            if (!tight && !sig.startsWith("set_alarm")) continue
            val at = "%d:%02d".format(median / 60, median % 60)
            return when {
                sig == "briefing" -> HabitSuggestion(sig, "You ask for your briefing around $at most days. Want it automatically every day at $at?", "every day at $at give me my briefing")
                sig.startsWith("read_calendar") -> HabitSuggestion(sig, "You check your calendar around $at most days. Want a daily briefing at $at instead?", "every day at $at give me my briefing")
                sig.startsWith("set_alarm:") -> {
                    val p = sig.split(':'); val h = p[1].toInt(); val m = p[2].toInt()
                    HabitSuggestion(sig, "You set a %d:%02d alarm on several days. Want a repeating alarm for weekdays?".format(h, m), "set alarm for %d:%02d every weekday".format(h, m))
                }
                else -> null
            }
        }
        return null
    }
}

// ----------------------------------------------------------------------------- briefing

internal data class BriefingFacts(
    val nowMs: Long, val zone: ZoneId, val name: String?, val reminders: List<Reminder>,
    val events: List<EventFact>?, val batteryPct: Int, val charging: Boolean, val unfinishedTask: String?, val pinned: List<String>
)

internal object Briefing {
    fun build(f: BriefingFacts): String {
        val z = Instant.ofEpochMilli(f.nowMs).atZone(f.zone)
        val greet = when (z.hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }
        val sb = StringBuilder(greet + (f.name?.let { ", $it" } ?: "") + ".")
        val endOfDay = z.toLocalDate().plusDays(1).atStartOfDay(f.zone).toInstant().toEpochMilli()
        val todays = f.reminders.filter { it.status == "active" && it.kind != "briefing" && it.whenMillis < endOfDay }.sortedBy { it.whenMillis }
        if (todays.isEmpty()) sb.append("\nNo reminders left today.")
        else {
            sb.append("\nReminders today (${todays.size}):")
            todays.take(5).forEach { sb.append("\n• ").append(if (it.kind == "timer") "Timer" else it.text).append(" — ").append(TimeParser.formatWhen(it.whenMillis, f.nowMs, f.zone)) }
        }
        when {
            f.events == null -> sb.append("\nCalendar: not connected (grant it in SETUP to include events).")
            f.events.isEmpty() -> sb.append("\nCalendar: nothing scheduled today.")
            else -> {
                sb.append("\nCalendar (${f.events.size}):")
                f.events.take(5).forEach { sb.append("\n• ").append(it.title).append(" — ").append(TimeParser.formatWhen(it.startMillis, f.nowMs, f.zone)) }
            }
        }
        if (f.batteryPct in 0..30 && !f.charging) sb.append("\nBattery is at ${f.batteryPct}% — worth charging soon.")
        f.unfinishedTask?.let { sb.append("\nUnfinished task: $it (say \"resume task\").") }
        if (f.pinned.isNotEmpty()) sb.append("\nPinned: ").append(f.pinned.take(2).joinToString("; "))
        return sb.toString()
    }
}
