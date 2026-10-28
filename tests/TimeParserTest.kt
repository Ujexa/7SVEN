package com.sven.assistant

import java.time.*

fun main() {
    val z = ZoneId.of("UTC")
    // Monday 2026-10-26 10:00 UTC
    val now = LocalDateTime.of(2026, 10, 26, 10, 0).atZone(z).toInstant().toEpochMilli()
    fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(z).toInstant().toEpochMilli()
    fun p(s: String) = TimeParser.extract(s, now, z)

    T.eq("in 20 min", now + 20 * 60_000, p("remind me in 20 minutes to stretch")?.whenMillis)
    T.eq("in an hour", now + 3_600_000, p("in an hour")?.whenMillis)
    T.eq("in half an hour", now + 1_800_000, p("in half an hour")?.whenMillis)
    T.eq("at 5pm", at(2026, 10, 26, 17, 0), p("call mom at 5pm")?.whenMillis)
    T.eq("at 9:30 am passed -> tomorrow", at(2026, 10, 27, 9, 30), p("at 9:30 am")?.whenMillis)
    T.eq("at 5 (no suffix) -> next 5 = 5pm", at(2026, 10, 26, 17, 0), p("remind me to eat at 5")?.whenMillis)
    T.eq("at 7 (no suffix) -> 7pm today", at(2026, 10, 26, 19, 0), p("at 7")?.whenMillis)
    T.eq("tomorrow at 9", at(2026, 10, 27, 9, 0), p("tomorrow at 9")?.whenMillis)
    T.eq("tomorrow at 7 -> am", at(2026, 10, 27, 7, 0), p("tomorrow at 7")?.whenMillis)
    T.eq("tomorrow morning", at(2026, 10, 27, 9, 0), p("tomorrow morning")?.whenMillis)
    T.eq("tonight", at(2026, 10, 26, 20, 0), p("remind me tonight to call")?.whenMillis)
    T.eq("friday 3pm", at(2026, 10, 30, 15, 0), p("on friday at 3pm")?.whenMillis)
    T.eq("next monday", at(2026, 11, 2, 9, 0), p("next monday")?.whenMillis)
    T.eq("17:30", at(2026, 10, 26, 17, 30), p("at 17:30")?.whenMillis)
    val d = p("every day at 8 am")!!
    T.eq("daily type", Repeat.Type.DAILY, d.repeat.type); T.eq("daily next", at(2026, 10, 27, 8, 0), d.whenMillis); T.eq("daily minute", 480, d.minuteOfDay)
    val w = p("every weekday at 7:30")!!
    T.eq("weekday type", Repeat.Type.WEEKDAYS, w.repeat.type); T.eq("weekday next", at(2026, 10, 27, 7, 30), w.whenMillis)
    val m = p("every monday and wednesday at 6pm")!!
    T.eq("dow mask", 0b101, m.repeat.mask); T.eq("dow next (Mon 6pm today)", at(2026, 10, 26, 18, 0), m.whenMillis)
    val iv = p("every 2 hours")!!
    T.eq("interval", 120, iv.repeat.intervalMin)
    T.eq("weekend", at(2026, 10, 31, 10, 0), p("every weekend at 10am")?.whenMillis)
    T.check("no time -> null", p("buy milk") == null)
    T.check("random number not a time", p("open app 5 times") == null)
    T.eq("strip", "call mom", TimeParser.strip("call mom at 5pm", p("call mom at 5pm")!!))
    T.eq("strip 2", "to stretch", TimeParser.strip("to stretch in 20 minutes", p("to stretch in 20 minutes")!!))
    // repeat math
    T.eq("Repeat.next weekdays from Friday evening", at(2026, 11, 2, 9, 0), Repeat(Repeat.Type.WEEKDAYS).next(at(2026, 10, 30, 18, 0), 540, z))
    T.eq("format soon", "in 20 min (at 10:20 am)", TimeParser.formatWhen(now + 20 * 60_000, now, z))
    T.finish("TimeParserTest")
}
