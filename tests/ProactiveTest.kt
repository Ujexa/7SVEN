package com.sven.assistant

import java.io.File
import java.time.*

internal class FakeArmer : AlarmArmer {
    val armed = HashMap<Long, Long>()
    override fun arm(r: Reminder) { armed[r.id] = r.whenMillis }
    override fun disarm(id: Long) { armed.remove(id) }
}

fun main() {
    val z = ZoneId.of("UTC")
    fun ms(d: Int, h: Int, m: Int, mo: Int = 10) = LocalDateTime.of(2026, mo, d, h, m).atZone(z).toInstant().toEpochMilli()
    var clock = ms(26, 10, 0)
    val dir = File.createTempFile("rem", "").apply { delete(); mkdirs() }
    var armer = FakeArmer()
    var svc = ReminderService(ReminderStore(dir), armer, { clock }, { z })

    // ---- add / execute / dedupe
    val tc = CommandParser.parse("remind me in 20 minutes to stretch", clock, z)!!
    T.check("execute msg", svc.execute(tc.args).contains("Reminder set (#1)"))
    T.eq("armed", clock + 20 * 60_000, armer.armed[1L])
    T.check("dup", svc.execute(tc.args).startsWith("Already scheduled"))
    T.eq("one active", 1, svc.active().size)
    T.check("missing time prompt", svc.execute(CommandParser.parse("remind me to buy milk", clock, z)!!.args).contains("When should I"))

    // ---- one-off fires
    clock += 20 * 60_000
    val fired = svc.onFired(1)
    T.eq("fired text", "stretch", fired?.text); T.eq("one-off done", "done", svc.get(1)?.status)
    T.check("fire twice ignored", svc.onFired(1) == null)

    // ---- repeating advances
    svc.execute(CommandParser.parse("remind me to take vitamins every day at 8 am", clock, z)!!.args)
    val rid = svc.active().first { it.text == "take vitamins" }.id
    T.eq("first daily fire tomorrow 8", ms(27, 8, 0), svc.get(rid)!!.whenMillis)
    clock = ms(27, 8, 0)
    svc.onFired(rid)
    T.eq("advanced", ms(28, 8, 0), svc.get(rid)!!.whenMillis); T.eq("still active", "active", svc.get(rid)!!.status)
    T.eq("re-armed next", ms(28, 8, 0), armer.armed[rid])

    // ---- snooze / list / cancel by text
    svc.execute(CommandParser.parse("remind me to call the dentist tomorrow at 3pm", clock, z)!!.args)
    T.check("list", svc.handleCommand("what reminders do i have")!!.contains("dentist"))
    T.check("cancel by text", svc.handleCommand("cancel my reminder about the dentist")!!.startsWith("Cancelled"))
    T.check("gone from list", !svc.handleCommand("my reminders")!!.contains("dentist"))
    T.check("snooze", svc.handleCommand("snooze 15 minutes")!!.startsWith("Snoozed"))
    T.check("snooze armed in 15", armer.armed.values.any { it == clock + 15 * 60_000 })
    T.check("not a reminder cmd", svc.handleCommand("open maps") == null)

    // ---- reboot: persistence, re-arm, missed delivery
    armer = FakeArmer()
    clock = ms(28, 9, 0)     // phone was off past 8:00 daily vitamins and the snooze
    svc = ReminderService(ReminderStore(dir), armer, { clock }, { z })
    val rep = svc.rearmAll()
    T.check("missed vitamins delivered once", rep.missed.any { it.text == "take vitamins" }, rep.missed.toString())
    T.check("stale (>24h) one-off is not delivered late", rep.missed.none { it.text == "stretch" }, rep.missed.toString())
    T.eq("vitamins re-armed next day", ms(29, 8, 0), armer.armed[rid])
    T.eq("second rearm has no missed", 0, svc.rearmAll().missed.size)

    // ---- timer
    val tm = svc.execute(CommandParser.parse("timer 5 minutes", clock, z)!!.args)
    T.check("timer msg", tm.startsWith("Timer set"), tm)
    T.check("cancel the timer", svc.handleCommand("cancel the timer") == "Timer cancelled.")

    // ---- scheduled briefing
    val b = ScheduleCommands.parseBriefing("every weekday at 7:30 give me my briefing", clock, z)!!
    T.eq("briefing kind", "briefing", b.getString("kind")); T.eq("briefing repeat", "WEEKDAYS", b.getString("repeat"))
    T.check("briefing exec", svc.execute(b).startsWith("Briefing scheduled"))
    T.check("no time -> null", ScheduleCommands.parseBriefing("give me my briefing", clock, z) == null)

    // ---- interruption policy
    val pdir = File.createTempFile("pol", "").apply { delete(); mkdirs() }
    var settings = PolicySettings()
    var dnd = false
    clock = ms(26, 12, 0)
    var pol = InterruptionPolicy({ settings }, PolicyStore(pdir), { clock }, { z }, { dnd })
    fun item(kind: String, key: String, u: Urgency = Urgency.NORMAL) = ProactiveItem(kind, key, u, "t $key")
    T.eq("normal midday delivers", Decision.Deliver, pol.decide(item("rule", "a")))
    pol.recordShown(item("rule", "a"))
    T.check("cooldown", pol.decide(item("rule", "a")) is Decision.Drop)
    clock = ms(26, 23, 0)
    val dq = pol.decide(item("rule", "b"))
    T.check("quiet defers", dq is Decision.Defer && dq.untilMillis == ms(27, 7, 0), dq.toString())
    T.eq("urgent in quiet", Decision.Deliver, pol.decide(item("reminder", "r", Urgency.URGENT)))
    T.check("low dropped in quiet", pol.decide(item("suggestion", "s", Urgency.LOW)) is Decision.Drop)
    pol.defer(item("rule", "b"), ms(27, 7, 0))
    clock = ms(27, 7, 1)
    T.eq("deferred released", 1, pol.takeDue().size); T.eq("only once", 0, pol.takeDue().size)
    dnd = true
    T.check("dnd defers", pol.decide(item("rule", "c")) is Decision.Defer); dnd = false
    // daily suggestion cap
    for (i in 1..2) { val s = item("suggestion", "s$i"); T.eq("sugg $i ok", Decision.Deliver, pol.decide(s)); pol.recordShown(s) }
    T.check("3rd suggestion capped", pol.decide(item("suggestion", "s3")) is Decision.Drop)
    clock += 86_400_000L
    T.eq("cap resets next day", Decision.Deliver, pol.decide(item("suggestion", "s4")))
    // dismissal backoff
    for (i in 1..3) pol.recordDismissed("suggestion")
    T.check("muted after 3 dismissals", pol.decide(item("suggestion", "s5")) is Decision.Drop)
    T.eq("rules unaffected by suggestion mute", Decision.Deliver, pol.decide(item("rule", "z")))
    pol.recordEngaged("suggestion")
    T.eq("engagement unmutes", Decision.Deliver, pol.decide(item("suggestion", "s6")))
    // persistence across restart
    for (i in 1..3) pol.recordDismissed("suggestion")
    pol = InterruptionPolicy({ settings }, PolicyStore(pdir), { clock }, { z }, { dnd })
    T.check("mute persists", pol.decide(item("suggestion", "s7")) is Decision.Drop)
    settings = settings.copy(enabled = false)
    T.check("disabled drops", pol.decide(item("rule", "q")) is Decision.Drop)
    T.eq("disabled still allows urgent", Decision.Deliver, pol.decide(item("reminder", "q2", Urgency.URGENT)))

    // ---- rules
    val rs = RuleStore(File.createTempFile("rules", "").apply { delete(); mkdirs() })
    val re = RuleEngine(rs)
    T.check("battery rule", RuleCommands.handle("when battery is below 20% remind me to charge", rs, re)!!.startsWith("Rule saved"))
    T.check("charging rule", RuleCommands.handle("when I plug in my phone remind me to turn off wifi", rs, re)!!.startsWith("Rule saved"))
    T.check("event rule", RuleCommands.handle("remind me 15 minutes before my meetings", rs, re)!!.startsWith("Rule saved"))
    T.eq("3 rules", 3, rs.all().size)
    T.eq("no fire at 50%", 0, re.evaluate(Facts(50, false, emptyList()), clock).size)
    T.eq("fires at 19%", 1, re.evaluate(Facts(19, false, emptyList()), clock).count { it.text.contains("charge", true) })
    T.eq("edge: not again at 18%", 0, re.evaluate(Facts(18, false, emptyList()), clock).count { it.text.contains("charge", true) })
    re.evaluate(Facts(60, false, emptyList()), clock)
    T.eq("re-armed after recovery", 1, re.evaluate(Facts(15, false, emptyList()), clock).count { it.text.contains("charge", true) })
    re.evaluate(Facts(15, false, emptyList()), clock)
    T.eq("charging edge fires on plug-in", 1, re.evaluate(Facts(16, true, emptyList()), clock).count { it.text.contains("wifi", true) })
    val ev = listOf(EventFact("Standup", clock + 10 * 60_000))
    T.eq("event soon fires", 1, re.evaluate(Facts(80, true, ev), clock).count { it.text.contains("Standup") })
    T.eq("event soon only once", 0, re.evaluate(Facts(80, true, ev), clock).count { it.text.contains("Standup") })
    T.check("list rules", RuleCommands.handle("my rules", rs, re)!!.contains("battery"))
    T.check("delete rule", RuleCommands.handle("delete rule #1", rs, re) == "Rule removed.")
    T.check("policy cmd quiet", RuleCommands.policyCommand("quiet hours 23 to 6", { settings }, { settings = it }, pol)!!.contains("23:00") && settings.quietStartMin == 23 * 60)
    T.check("policy cmd off", RuleCommands.policyCommand("turn off suggestions", { settings }, { settings = it }, pol)!!.contains("off"))

    // ---- habits
    val log = ActionLog(File.createTempFile("hab", "").apply { delete(); mkdirs() })
    val t0 = ms(20, 8, 5)
    listOf(0, 1, 2, 3).forEach { d -> log.add(t0 + d * 86_400_000L + d * 600_000L, "briefing") }
    log.add(t0, "battery")
    val hs = HabitDetector.detect(log, ms(24, 12, 0), z) { false }
    T.check("habit suggested", hs != null && hs.sig == "briefing" && hs.acceptCommand.contains("give me my briefing"), hs.toString())
    T.check("suggested command parses", ScheduleCommands.parseBriefing(hs!!.acceptCommand, ms(24, 12, 0), z) != null)
    log.markSuggested("briefing", ms(24, 12, 0))
    T.check("not repeated", HabitDetector.detect(log, ms(25, 12, 0), z) { false } == null)
    log.markDeclined("x")
    T.check("signature safe-list", HabitDetector.signature(CommandParser.parse("call 5550100", clock, z)!!) == null)
    T.eq("signature alarm", "set_alarm:7:30", HabitDetector.signature(CommandParser.parse("set alarm for 7:30 am", clock, z)!!))

    // ---- briefing
    val facts = BriefingFacts(ms(26, 8, 0), z, "Sam", svc.active(), listOf(EventFact("Dentist", ms(26, 15, 0))), 18, false, "Open Maps and search", listOf("Name is Sam"))
    val bt = Briefing.build(facts)
    T.check("briefing greeting", bt.startsWith("Good morning, Sam."), bt)
    T.check("briefing parts", bt.contains("Dentist") && bt.contains("Battery is at 18%") && bt.contains("resume task"), bt)
    T.check("briefing no calendar", Briefing.build(facts.copy(events = null)).contains("not connected"))
    T.finish("ProactiveTest")
}
