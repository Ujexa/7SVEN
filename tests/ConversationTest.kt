package com.sven.assistant

import java.time.*

fun main() {
    val z = ZoneId.of("UTC")
    fun ms(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, d, h, m).atZone(z).toInstant().toEpochMilli()
    val now = ms(26, 10, 0)
    fun st(cmd: String): ConversationState = ConversationState().also {
        it.lastCall = CommandParser.parse(cmd, now, z); it.lastCommandText = cmd; it.lastReply = "ok"
    }
    fun rw(r: Resolution?) = (r as? Resolution.Rewrite)?.text

    // interruption + social
    T.check("stop", FollowUps.resolve("stop", ConversationState(), now, z) is Resolution.Interrupt)
    T.check("never mind", FollowUps.resolve("Never mind.", ConversationState(), now, z) is Resolution.Interrupt)
    T.eq("thanks", "You're welcome.", (FollowUps.resolve("thanks!", st("open maps"), now, z) as Resolution.Say).text)
    T.check("greeting", FollowUps.resolve("hello", ConversationState(), now, z) is Resolution.Say)

    // alarm adjustments
    var s = st("set alarm for 7:30 am")
    T.eq("make it 8", "set alarm for 08:00", rw(FollowUps.resolve("make it 8", s, now, z)))
    T.eq("no, 9 pm", "set alarm for 21:00", rw(FollowUps.resolve("no, 9 pm", s, now, z)))
    T.eq("bare 6:15", "set alarm for 06:15", rw(FollowUps.resolve("6:15", s, now, z)))
    T.eq("an hour later", "set alarm for 08:30", rw(FollowUps.resolve("an hour later", s, now, z)))
    T.eq("15 minutes earlier", "set alarm for 07:15", rw(FollowUps.resolve("15 minutes earlier", s, now, z)))
    T.check("alarm preface warns", (FollowUps.resolve("make it 8", s, now, z) as Resolution.Rewrite).preface!!.contains("earlier alarm"))
    s = st("set an alarm for 6 pm called gym")
    T.eq("label kept", "set alarm for 07:00 called gym", rw(FollowUps.resolve("change it to 7 am", s, now, z)))

    // reminder reschedule (in place, no duplicate)
    s = st("remind me to stretch at 5pm"); s.lastReminderId = 4; s.lastWhen = ms(26, 17, 0)
    val r1 = FollowUps.resolve("actually make it 6 pm", s, now, z) as Resolution.Reschedule
    T.eq("resched id", 4L, r1.reminderId); T.eq("resched when", ms(26, 18, 0), r1.whenMillis)
    val r2 = FollowUps.resolve("30 minutes later", s, now, z) as Resolution.Reschedule
    T.eq("resched +30", ms(26, 17, 30), r2.whenMillis)
    val r3 = FollowUps.resolve("make it tomorrow at 9", s, now, z) as Resolution.Reschedule
    T.eq("resched tomorrow", ms(27, 9, 0), r3.whenMillis)

    // time-adjust must not hijack unrelated text
    T.eq("unrelated stays null", null, FollowUps.resolve("make it a good one", s, now, z))

    // missing-time follow up
    s = ConversationState(); s.pendingTimeFor = "remind me to buy milk"
    T.eq("time answer", "remind me to buy milk in 20 minutes", rw(FollowUps.resolve("in 20 minutes", s, now, z)))
    T.eq("pending cleared", null, s.pendingTimeFor)
    s.pendingTimeFor = "remind me to buy milk"
    T.check("no -> say", FollowUps.resolve("no", s, now, z) is Resolution.Say)

    // choices
    s = ConversationState(); s.pendingChoice = listOf(Choice("Mom (mobile)", "call 5550101"), Choice("Mom (home)", "call 5550102"))
    T.eq("second", "call 5550102", rw(FollowUps.resolve("the second one", s, now, z)))
    s.pendingChoice = listOf(Choice("Mom (mobile)", "call 5550101"), Choice("Mom (home)", "call 5550102"))
    T.eq("by label", "call 5550102", rw(FollowUps.resolve("home", s, now, z)))
    T.eq("choice cleared", null, s.pendingChoice)

    // pronouns
    s = st("call mom"); s.lastEntity = "Mom"
    T.eq("text her", "text Mom hello", rw(FollowUps.resolve("text her hello", s, now, z)))
    T.eq("call her back", "call Mom back".replace(" back", ""), rw(FollowUps.resolve("call her", s, now, z)))
    T.eq("no entity -> null", null, FollowUps.resolve("text her hello", st("open maps"), now, z))

    // substitution
    T.eq("search what about", "search for paris hotels", rw(FollowUps.resolve("what about paris hotels", st("search for london hotels"), now, z)))
    T.eq("navigate and", "navigate to Boston", rw(FollowUps.resolve("and Boston", st("navigate to Chicago"), now, z)))
    T.eq("calendar tomorrow", "what's on my calendar tomorrow", rw(FollowUps.resolve("what about tomorrow", st("what's on my calendar"), now, z)))
    T.eq("no context -> null", null, FollowUps.resolve("what about tomorrow", ConversationState(), now, z))

    // repeat / undo / speak again
    s = st("flashlight on")
    T.eq("again", "flashlight on", rw(FollowUps.resolve("do that again", s, now, z)))
    T.check("undo", FollowUps.resolve("undo that", s, now, z) is Resolution.Undo)
    s.lastReply = "It is sunny."
    T.check("say again", FollowUps.resolve("say that again", s, now, z) is Resolution.SpeakLast)

    // suggestions
    s = ConversationState(); s.pendingSuggestion = ProactiveItem("suggestion", "k", Urgency.NORMAL, "t", "every day at 8:00 give me my briefing")
    T.check("yes accepts", FollowUps.resolve("yes please", s, now, z) is Resolution.AcceptSuggestion)
    T.check("no declines", FollowUps.resolve("not now", s, now, z) is Resolution.DeclineSuggestion)
    s.pendingSuggestion = null
    T.eq("yes without suggestion -> null", null, FollowUps.resolve("yes", s, now, z))

    // memory correction
    T.eq("correction", "remember that my name is Sam", rw(FollowUps.resolve("no, my name is Sam", ConversationState(), now, z)))
    T.eq("actually live", "remember that I live in Boston", rw(FollowUps.resolve("actually I live in Boston", ConversationState(), now, z)))

    // ---- prompt builder: always inside budget, newest turns kept, system never truncated
    val turns = ArrayList<Pair<String, String>>()
    for (i in 1..60) { turns.add("user" to "Question number $i about topic ${i % 7}: " + "word ".repeat(40)); turns.add("assistant" to "Answer $i. " + "detail ".repeat(120)) }
    val sys = "You are 7SVEN, a local-first personal assistant on Android. Be concise."
    val b = PromptBuilder.build(sys, listOf("The user's name is Sam", "The user is allergic to peanuts"), turns)
    T.check("within budget (${b.chars})", b.chars <= PromptBuilder.MAX_CHARS)
    T.check("starts with system", b.prompt.startsWith("<|im_start|>system\n$sys"))
    T.check("ends with assistant cue", b.prompt.endsWith("<|im_start|>assistant\n"))
    T.check("keeps newest user turn", b.prompt.contains("Question number 60"))
    T.check("drops oldest", !b.prompt.contains("Question number 1:"))
    T.check("has memory", b.prompt.contains("allergic to peanuts"))
    T.check("has summary of dropped", b.prompt.contains("Earlier in this conversation: ") && b.droppedTurns > 0)
    T.check("includes several turns", b.includedTurns >= 3, b.includedTurns.toString())
    val short = PromptBuilder.build(sys, emptyList(), listOf("user" to "hi"))
    T.check("short convo no summary", !short.prompt.contains("Earlier in this conversation"))
    val huge = PromptBuilder.build(sys, List(30) { "memory line number $it ".repeat(5) }, listOf("user" to "x".repeat(5000)))
    T.check("huge input still bounded (${huge.chars})", huge.chars <= PromptBuilder.MAX_CHARS + 900)

    // ---- speech
    T.eq("clean ids", "Saved number 12: The user's name is Sam.", Speech.clean("Saved #12: The user's name is Sam."))
    T.check("clean bullets", !Speech.clean("✓ Open Maps\n• Alarm 07:30").contains("✓"))
    T.check("chunks split", Speech.chunks("One. Two! Three?").size == 3)
    T.check("long chunk bounded", Speech.chunks("word ".repeat(200)).all { it.length <= 220 })
    // ---- voice turns
    val v = VoiceTurns(); v.onHeard(); T.check("continues", v.shouldContinue()); T.check("first silence ok", !v.onSilence()); T.check("second ends", v.onSilence())
    T.finish("ConversationTest")
}
