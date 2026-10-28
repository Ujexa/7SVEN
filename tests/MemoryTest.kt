package com.sven.assistant

import java.io.File

fun main() {
    val dir = File.createTempFile("mem", "").apply { delete(); mkdirs() }
    var clock = 1_000_000_000_000L
    val now = { clock }
    var auto = true
    fun svc(): MemoryService = MemoryService(MemoryStore(dir, now), { auto }, { auto = it }, { true }, now)

    var s = svc()
    // explicit remember + structured subject
    T.check("remember name", s.handle("remember that my name is Sam")!!.text.startsWith("Saved #1"))
    T.check("remember sensitive refused", s.handle("remember my wifi password is hunter2")!!.text.contains("don't store"))
    T.check("remember card refused", s.handle("remember my card number is 4111111111111111")!!.text.contains("don't store"))
    s.handle("remember my mom's name is Priya")
    s.handle("remember that I'm allergic to peanuts")
    s.handle("remember I live in Austin")
    s.handle("note: revise MOSFET circuits on Friday")
    s.handle("remember my favorite color is green")
    T.eq("count", 6, s.count())

    // restart persistence
    s = svc()
    T.eq("count after restart", 6, s.count())
    T.check("ask name after restart", s.handle("what's my name")!!.text.contains("Sam"))
    T.check("ask mom synonym", s.handle("who is my mother")!!.text.contains("Priya"))
    T.check("ask city", s.handle("where do I live")!!.text.contains("Austin"))
    T.check("ask allergy", s.handle("what am I allergic to")!!.text.contains("peanuts"))
    T.check("ask favorite", s.handle("what's my favorite colour")!!.text.contains("green"))
    T.check("ask unrelated falls through", s.handle("what's my battery level") == null)
    T.check("do you remember miss", s.handle("do you remember my passport")!!.text.contains("don't have"))
    T.check("note searchable", s.handle("what do you know about MOSFET")!!.text.contains("MOSFET"))

    // supersede
    val up = s.handle("remember that my name is Alex")!!.text
    T.check("updated name", up.startsWith("Updated #1"), up)
    T.eq("count unchanged after update", 6, s.count())
    T.check("name now Alex", s.handle("what's my name")!!.text.contains("Alex"))
    T.check("duplicate", s.handle("remember I live in Austin")!!.text.contains("already"))

    // auto learn
    T.check("auto: lives", s.autoLearn("I live in Denver")?.contains("Updated") == true)
    T.check("auto: ignores question", s.autoLearn("Do I like pizza?") == null)
    T.check("auto: ignores pronoun", s.autoLearn("I love it") == null)
    T.check("auto: ignores command", s.autoLearn("call my mom") == null)
    T.check("auto: pref", s.autoLearn("I really like jazz music")?.contains("jazz") == true)
    T.check("auto: password never", s.autoLearn("my password is abc12345 and my pin is 4455") == null)
    auto = false
    T.check("auto off", s.autoLearn("my birthday is March 3") == null)
    auto = true

    // forget
    T.check("forget target", s.handle("forget my favorite color")!!.text.startsWith("Forgot"))
    T.check("gone", s.handle("what's my favorite colour") == null)
    s.handle("remember that my dog is named Rex")
    T.check("forget that", s.handle("forget that")!!.text.startsWith("Forgot"))
    T.check("forget id", s.handle("forget #2")!!.text.startsWith("Forgot"))
    T.check("forget missing", s.handle("forget #999")!!.text.contains("no memory"))
    val all = s.handle("forget all notes")!!
    T.check("forget all confirm", all.action is MemoryAction.ConfirmClearAll)

    // retrieval for prompts
    s = svc()
    val ctx = s.contextFor("where should I go for dinner, any allergies?", 400)
    T.check("context has allergy", ctx.any { it.contains("peanuts") }, ctx.toString())
    T.check("context has name (always)", ctx.any { it.contains("name") }, ctx.toString())
    T.check("context budget", ctx.sumOf { it.length + 3 } <= 400)
    s.handle("pin #3")
    T.check("pinned persists", s.all().first { it.id == 3L }.pinned)

    // legacy import runs once
    val s2 = svc()
    s2.importLegacy(listOf("buy milk", "call plumber"))
    s2.importLegacy(listOf("buy milk", "call plumber"))
    T.check("legacy once", s2.all().count { it.source == "legacy" } == 2)

    // corruption recovery: damage the main file, backup must still load
    File(dir, "sven_memory.json").writeText("{ broken")
    T.check("recover from .bak", svc().count() > 0)

    // scale / latency
    val big = File.createTempFile("big", "").apply { delete(); mkdirs() }
    val bs = MemoryStore(big, now)
    for (i in 1..1500) bs.upsert(Candidate("The user bought item number $i from shop ${i % 37} with colour c${i % 11}.", MemoryKind.NOTE))
    bs.upsert(Candidate("The user's dentist is Dr. Okafor on Elm Street.", MemoryKind.FACT, "my:dentist"))
    val t0 = System.nanoTime()
    val hit = MemoryIndex.search("who is my dentist", bs.all(), clock, 3).firstOrNull()
    val ms = (System.nanoTime() - t0) / 1e6
    T.check("scale hit", hit?.memory?.text?.contains("Okafor") == true)
    T.check("scale latency <250ms (was ${ms}ms)", ms < 250)
    T.finish("MemoryTest")
}
