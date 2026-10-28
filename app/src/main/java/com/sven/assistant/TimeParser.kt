package com.sven.assistant

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** How an item repeats. [mask] bit0 = Monday ... bit6 = Sunday (used by WEEKLY). */
internal data class Repeat(val type: Type = Type.NONE, val mask: Int = 0, val intervalMin: Int = 0) {
    enum class Type { NONE, DAILY, WEEKDAYS, WEEKENDS, WEEKLY, INTERVAL }

    val repeating get() = type != Type.NONE

    private fun allowed(d: DayOfWeek): Boolean = when (type) {
        Type.DAILY -> true
        Type.WEEKDAYS -> d.value <= 5
        Type.WEEKENDS -> d.value >= 6
        Type.WEEKLY -> (mask shr (d.value - 1)) and 1 == 1
        else -> false
    }

    /** First fire strictly after [after] at [minuteOfDay] local time (calendar repeats) or after + interval. */
    fun next(after: Long, minuteOfDay: Int, zone: ZoneId): Long? {
        if (type == Type.NONE) return null
        if (type == Type.INTERVAL) return after + intervalMin.coerceAtLeast(1) * 60_000L
        var date = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        repeat(9) {
            if (allowed(date.dayOfWeek)) {
                val t = date.atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(zone).toInstant().toEpochMilli()
                if (t > after) return t
            }
            date = date.plusDays(1)
        }
        return null
    }

    fun describe(minuteOfDay: Int): String {
        val at = "%d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
        return when (type) {
            Type.NONE -> ""
            Type.DAILY -> "every day at $at"
            Type.WEEKDAYS -> "every weekday at $at"
            Type.WEEKENDS -> "every weekend at $at"
            Type.WEEKLY -> "every " + (0..6).filter { (mask shr it) and 1 == 1 }
                .joinToString(", ") { DayOfWeek.of(it + 1).name.lowercase().replaceFirstChar { c -> c.uppercase() } } + " at $at"
            Type.INTERVAL -> if (intervalMin % 60 == 0) "every ${intervalMin / 60} hour(s)" else "every $intervalMin minutes"
        }
    }
}

internal data class ParsedTime(
    val whenMillis: Long,
    val repeat: Repeat,
    val minuteOfDay: Int,
    /** Character ranges of [text] consumed by the time phrase (for stripping from the remaining text). */
    val spans: List<IntRange>,
    /** True when the phrase fixed a clock time (as opposed to just a day or a relative offset). */
    val hasClock: Boolean
)

/** Extracts "in 20 minutes", "tomorrow at 9", "every weekday at 7:30", "next friday 5pm" ... from free text. */
internal object TimeParser {
    private val days = mapOf(
        "monday" to 1, "mon" to 1, "tuesday" to 2, "tue" to 2, "tues" to 2, "wednesday" to 3, "wed" to 3,
        "thursday" to 4, "thu" to 4, "thur" to 4, "thurs" to 4, "friday" to 5, "fri" to 5, "saturday" to 6, "sat" to 6, "sunday" to 7, "sun" to 7
    )
    private val dayNames = "monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues|tue|wed|thurs|thur|thu|fri|sat|sun"
    private val periods = mapOf("morning" to 9 * 60, "afternoon" to 15 * 60, "evening" to 18 * 60, "night" to 21 * 60, "tonight" to 20 * 60, "noon" to 12 * 60, "midnight" to 0)

    private val I = RegexOption.IGNORE_CASE
    private val reRel = Regex("\\bin (an?|half an?|\\d+(?:\\.\\d+)?) ?(second|sec|minute|min|hour|hr|day|week)s?\\b", I)
    private val reEveryInterval = Regex("\\bevery (\\d+) ?(minute|min|hour|hr)s?\\b", I)
    private val reEveryHour = Regex("\\b(?:every hour|hourly)\\b", I)
    private val reEveryDay = Regex("\\b(?:every ?day|daily|each day|every (morning|evening|night|afternoon))\\b", I)
    private val reEveryWeekday = Regex("\\b(?:every weekday|on weekdays|weekdays|each weekday)\\b", I)
    private val reEveryWeekend = Regex("\\b(?:every weekend|on weekends|weekends)\\b", I)
    private val reEveryDow = Regex("\\bevery ((?:(?:$dayNames)(?:,? ?(?:and )?)?)+)", I)
    private val reWeekly = Regex("\\b(?:weekly|every week)\\b", I)
    private val reDayAfter = Regex("\\bday after tomorrow\\b", I)
    private val reTomorrow = Regex("\\btomorrow\\b", I)
    private val reToday = Regex("\\btoday\\b", I)
    private val reDow = Regex("\\b(?:(next|this|on) )?($dayNames)\\b", I)
    private val rePeriod = Regex("\\b(?:(?:this|in the) )?(morning|afternoon|evening|night|tonight|noon|midnight)\\b", I)
    private val reClock = Regex("(?:\\b(?:at|by|@) ?|\\b)(\\d{1,2})(?::(\\d{2}))? ?(a\\.?m\\.?|p\\.?m\\.?)\\b|(?:\\b(?:at|by) ?|@ ?)(\\d{1,2})(?::(\\d{2}))?\\b|\\b(\\d{1,2}):(\\d{2})\\b", I)
    private val reOclock = Regex("\\b(\\d{1,2}) o'?clock\\b", I)

    fun extract(text: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): ParsedTime? {
        val spans = ArrayList<IntRange>()
        val nowDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)

        // ---- relative offset: "in 20 minutes", "in an hour", "in half an hour"
        reRel.find(text)?.let { m ->
            val q = m.groupValues[1].lowercase()
            val amount = when {
                q == "a" || q == "an" -> 1.0
                q.startsWith("half") -> 0.5
                else -> q.toDoubleOrNull() ?: return@let
            }
            val unitMs = when (m.groupValues[2].lowercase()) {
                "second", "sec" -> 1_000L
                "minute", "min" -> 60_000L
                "hour", "hr" -> 3_600_000L
                "day" -> 86_400_000L
                else -> 7 * 86_400_000L
            }
            val whenMs = now + (amount * unitMs).toLong()
            val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(whenMs), zone)
            return ParsedTime(whenMs, Repeat(), dt.hour * 60 + dt.minute, listOf(m.range), false)
        }

        // ---- repeat phrases
        var repeat = Repeat()
        var periodFromRepeat: Int? = null
        reEveryInterval.find(text)?.let { m ->
            val n = m.groupValues[1].toInt()
            val mins = if (m.groupValues[2].lowercase().startsWith("h")) n * 60 else n
            if (mins >= 5) { repeat = Repeat(Repeat.Type.INTERVAL, 0, mins); spans.add(m.range) }
        }
        if (!repeat.repeating) reEveryHour.find(text)?.let { repeat = Repeat(Repeat.Type.INTERVAL, 0, 60); spans.add(it.range) }
        if (!repeat.repeating) reEveryWeekday.find(text)?.let { repeat = Repeat(Repeat.Type.WEEKDAYS); spans.add(it.range) }
        if (!repeat.repeating) reEveryWeekend.find(text)?.let { repeat = Repeat(Repeat.Type.WEEKENDS); spans.add(it.range) }
        if (!repeat.repeating) reEveryDow.find(text)?.let { m ->
            var mask = 0
            Regex(dayNames, I).findAll(m.groupValues[1]).forEach { d -> days[d.value.lowercase()]?.let { mask = mask or (1 shl (it - 1)) } }
            if (mask != 0) { repeat = Repeat(Repeat.Type.WEEKLY, mask); spans.add(m.range) }
        }
        if (!repeat.repeating) reEveryDay.find(text)?.let { m ->
            repeat = Repeat(Repeat.Type.DAILY); spans.add(m.range)
            m.groupValues[1].lowercase().takeIf { it.isNotEmpty() }?.let { periodFromRepeat = periods[it] }
        }
        if (!repeat.repeating) reWeekly.find(text)?.let {
            repeat = Repeat(Repeat.Type.WEEKLY, 1 shl (nowDt.dayOfWeek.value - 1)); spans.add(it.range)
        }

        // ---- day
        var date: LocalDate? = null
        var explicitDay = false
        var periodMin: Int? = periodFromRepeat
        if (!repeat.repeating || repeat.type == Repeat.Type.INTERVAL) {
            reDayAfter.find(text)?.let { date = nowDt.toLocalDate().plusDays(2); explicitDay = true; spans.add(it.range) }
            if (date == null) reTomorrow.find(text)?.let { date = nowDt.toLocalDate().plusDays(1); explicitDay = true; spans.add(it.range) }
            if (date == null) reToday.find(text)?.let { date = nowDt.toLocalDate(); explicitDay = true; spans.add(it.range) }
            if (date == null) reDow.find(text)?.let { m ->
                val dow = days[m.groupValues[2].lowercase()] ?: return@let
                var delta = (dow - nowDt.dayOfWeek.value + 7) % 7
                if (delta == 0) delta = 7
                date = nowDt.toLocalDate().plusDays(delta.toLong()); explicitDay = true; spans.add(m.range)
            }
        }
        rePeriod.find(text)?.let { m ->
            val w = m.groupValues[1].lowercase()
            periodMin = periods[w]
            if (w == "tonight" && date == null) { date = nowDt.toLocalDate(); explicitDay = true }
            spans.add(m.range)
        }

        // ---- clock
        var clockMin: Int? = null
        var ampmGiven = false
        reClock.find(text)?.let { m ->
            val g = m.groupValues
            val (h, mi, ap) = when {
                g[1].isNotEmpty() -> Triple(g[1].toInt(), g[2].ifEmpty { "0" }.toInt(), g[3].lowercase())
                g[4].isNotEmpty() -> Triple(g[4].toInt(), g[5].ifEmpty { "0" }.toInt(), "")
                else -> Triple(g[6].toInt(), g[7].toInt(), "")
            }
            if (mi in 0..59 && h in 0..23) {
                var hour = h
                if (ap.startsWith("p")) { if (hour < 12) hour += 12; ampmGiven = true }
                else if (ap.startsWith("a")) { if (hour == 12) hour = 0; ampmGiven = true }
                if (!(ampmGiven && h > 12)) { clockMin = hour * 60 + mi; spans.add(m.range) }
            }
        }
        if (clockMin == null) reOclock.find(text)?.let { m ->
            val h = m.groupValues[1].toInt()
            if (h in 0..23) { clockMin = h * 60; spans.add(m.range) }
        }

        val hasClock = clockMin != null
        if (spans.isEmpty()) return null
        // A bare "morning/evening" with nothing else is not a schedule.
        if (!hasClock && date == null && !repeat.repeating && periodMin != null && spans.size == 1 && text.trim().split(' ').size > 6) return null

        var minute = clockMin ?: periodMin ?: (if (explicitDay || repeat.repeating) 9 * 60 else return null)
        // Hours 1..12 without am/pm: use the sensible half of the day.
        if (clockMin != null && !ampmGiven && clockMin!! / 60 in 1..12) {
            val h = clockMin!! / 60; val mi = clockMin!! % 60
            if (periodMin != null && periodMin!! >= 12 * 60 && h < 12) minute = (h + 12) * 60 + mi
            else if (repeat.repeating || (date != null && date != nowDt.toLocalDate()) || explicitDay) {
                minute = if (h in 1..5) (h + 12) * 60 + mi else h * 60 + mi
            } else {
                val nowMin = nowDt.hour * 60 + nowDt.minute
                val am = h % 12 * 60 + mi; val pm = am + 12 * 60
                minute = listOf(am, pm).firstOrNull { it > nowMin } ?: am
            }
        }

        val zoneId = zone
        val whenMs: Long = if (repeat.repeating) {
            if (repeat.type == Repeat.Type.INTERVAL) now + repeat.intervalMin * 60_000L
            else repeat.next(now, minute, zoneId) ?: return null
        } else {
            var d = date ?: nowDt.toLocalDate()
            var t = d.atTime(minute / 60, minute % 60).atZone(zoneId).toInstant().toEpochMilli()
            if (t <= now) {
                if (date == null || date == nowDt.toLocalDate() && !explicitDay) { d = d.plusDays(1); t = d.atTime(minute / 60, minute % 60).atZone(zoneId).toInstant().toEpochMilli() }
                else if (date == nowDt.toLocalDate()) return null // asked for a time earlier today
            }
            t
        }
        return ParsedTime(whenMs, repeat, minute, spans.sortedBy { it.first }, hasClock)
    }

    /** [text] with the time phrase removed and leftover connector words trimmed. */
    fun strip(text: String, parsed: ParsedTime): String {
        val sb = StringBuilder(text)
        parsed.spans.sortedByDescending { it.first }.forEach { r ->
            if (r.first >= 0 && r.last < sb.length) sb.delete(r.first, r.last + 1)
        }
        return sb.toString().replace(Regex("\\s+"), " ")
            .replace(Regex("\\b(?:at|on|by|for|in the|this)\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^\\s*(?:at|on|by|for)\\b", RegexOption.IGNORE_CASE), "")
            .trim().trim(',', '.', ' ')
    }

    fun formatWhen(ms: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone)
        val clock = "%d:%02d %s".format(if (dt.hour % 12 == 0) 12 else dt.hour % 12, dt.minute, if (dt.hour < 12) "am" else "pm")
        val diff = ms - now
        if (diff in 0..90 * 60_000L && dt.toLocalDate() == today) {
            val mins = (diff / 60_000L).toInt()
            return if (mins < 1) "in under a minute" else if (mins < 60) "in $mins min (at $clock)" else "in ${mins / 60} h ${mins % 60} min (at $clock)"
        }
        val dayWord = when (dt.toLocalDate()) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            else -> dt.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() } + " " + dt.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3) + " " + dt.dayOfMonth
        }
        return "$dayWord at $clock"
    }
}
