package com.sven.assistant

import java.io.File
import java.time.*

internal class FakeRunner(val script: (Step, Int) -> StepOutcome, val satisfied: (Step) -> Boolean = { false }) : StepRunner {
    val calls = ArrayList<String>(); var cancelHook: (() -> Unit)? = null; var cancelCalled = false
    override fun run(task: Task, step: Step, isFirst: Boolean): StepOutcome {
        calls.add(step.title); cancelHook?.invoke()
        return script(step, calls.count { it == step.title })
    }
    override fun alreadySatisfied(step: Step) = satisfied(step)
    override fun cancelCurrent() { cancelCalled = true }
}

fun main() {
    val z = ZoneId.of("UTC")
    val now = LocalDateTime.of(2026, 10, 26, 10, 0).atZone(z).toInstant().toEpochMilli()
    fun plan(s: String, agent: Boolean = true) = TaskPlanner.plan(s, now, z, agent)

    // ---- planner
    T.eq("split simple", listOf("open maps", "search for pizza"), TaskPlanner.split("open maps and search for pizza"))
    T.eq("no split inside noun", listOf("search for salt and pepper"), TaskPlanner.split("search for salt and pepper"))
    T.eq("then", 3, TaskPlanner.split("open settings, turn on flashlight then set volume to 30").size)
    T.eq("message body keeps and", 2, TaskPlanner.split("text mom saying bread and milk please and call dad").size)
    T.check("single is not compound", plan("open maps") is PlanResult.NotCompound)
    val p = plan("set alarm for 7 am and turn on the flashlight and open Maps") as PlanResult.Plan
    T.eq("plan size", 3, p.steps.size)
    T.eq("plan kinds", listOf("tool", "tool", "tool"), p.steps.map { it.kind })
    val p2 = plan("open settings and turn on wifi") as PlanResult.Plan
    T.eq("agent step", "agent", p2.steps[1].kind); T.eq("agent expect", "wifi:on", p2.steps[1].expect)
    T.check("agent disallowed", plan("open settings and turn on wifi", false) is PlanResult.Unplannable)
    T.check("mixed phone+chat is unplannable", plan("open maps and tell me a joke") is PlanResult.Unplannable)
    T.check("pure chat falls through", plan("tell me about cats and then explain dogs") is PlanResult.NotCompound)
    T.check("needs time", plan("open maps and remind me to buy milk") is PlanResult.NeedsInfo)
    T.check("too many", plan("open a and open b and open c and open d and open e and open f and open g") is PlanResult.Unplannable)

    val dir = File.createTempFile("task", "").apply { delete(); mkdirs() }
    fun steps(vararg t: String) = t.mapIndexed { i, s -> Step(i, "tool", s, "x", null, null, "e$i", true) }

    // ---- success path, verified accounting
    var eng = TaskEngine(TaskStore(dir), { now })
    val progress = ArrayList<String>()
    eng.listener = object : TaskEngine.Listener { override fun onProgress(task: Task, text: String) { progress.add(text) }; override fun onFinished(task: Task) {} }
    var t = eng.create("g1", steps("A", "B", "C"))
    eng.run(t, FakeRunner({ s, _ -> StepOutcome(true, "ok", if (s.title == "C") Verify.UNVERIFIED else Verify.VERIFIED) }))
    T.eq("done state", TaskState.DONE, t.state)
    T.check("report mentions verified count", t.summary.contains("2 verified"), t.summary)
    T.eq("progress events", 3, progress.count { it.startsWith("Step") })

    // ---- failure stops, reports accurately, skips rest
    t = eng.create("g2", steps("A", "B", "C"))
    val fr = FakeRunner({ s, _ -> if (s.title == "B") StepOutcome(false, "no such app") else StepOutcome(true, "ok", Verify.VERIFIED) })
    eng.run(t, fr)
    T.eq("failed state", TaskState.FAILED, t.state)
    T.eq("statuses", listOf(StepStatus.DONE, StepStatus.FAILED, StepStatus.SKIPPED), t.steps.map { it.status })
    T.eq("B retried once (idempotent)", 2, fr.calls.count { it == "B" })
    T.check("report says 1 of 3", t.summary.contains("1 of 3") && t.summary.contains("no such app") && t.summary.contains("resume"), t.summary)

    // ---- mismatch counts as failure, non-idempotent not retried
    val nonIdem = listOf(Step(0, "tool", "Alarm", "set_alarm", null, null, "alarm:7:0", false))
    t = eng.create("g3", nonIdem)
    val mr = FakeRunner({ _, _ -> StepOutcome(true, "launched", Verify.MISMATCH) })
    eng.run(t, mr)
    T.eq("mismatch fails", TaskState.FAILED, t.state); T.eq("no retry for non-idempotent", 1, mr.calls.size)

    // ---- cancellation mid-run
    t = eng.create("g4", steps("A", "B", "C"))
    val cr = FakeRunner({ _, _ -> StepOutcome(true, "ok", Verify.VERIFIED) })
    cr.cancelHook = { if (cr.calls.size == 2) eng.cancel() }
    eng.run(t, cr)
    T.eq("cancel state", TaskState.CANCELLED, t.state)
    T.eq("cancel: C never ran", 2, cr.calls.size); T.check("cancel called runner", cr.cancelCalled)
    T.check("cancel report", t.summary.contains("Stopped"), t.summary)

    // ---- resume after cancel continues from first unfinished
    val rr = FakeRunner({ _, _ -> StepOutcome(true, "ok", Verify.VERIFIED) })
    eng.run(t, rr)
    T.eq("resumed done", TaskState.DONE, t.state)
    T.check("resume did not redo A", !rr.calls.contains("A"), rr.calls.toString())

    // ---- crash/restart recovery (new engine + new store from same file)
    val crashDir = File.createTempFile("crash", "").apply { delete(); mkdirs() }
    var e1 = TaskEngine(TaskStore(crashDir), { now })
    val ct = e1.create("g5", steps("A", "B", "C"))
    val killer = FakeRunner({ s, _ -> if (s.title == "B") throw OutOfMemoryError("simulated kill") else StepOutcome(true, "ok", Verify.VERIFIED) })
    // simulate process death during B: persist state exactly as the engine does mid-step
    ct.state = TaskState.RUNNING; ct.steps[0].status = StepStatus.DONE; ct.steps[1].status = StepStatus.RUNNING; ct.steps[1].attempts = 1
    e1.store.save()
    val e2 = TaskEngine(TaskStore(crashDir), { now })
    val rec = e2.recoverInterrupted()
    T.eq("recovered one", 1, rec.size)
    val rt = e2.store.get(ct.id)!!
    T.eq("interrupted", TaskState.INTERRUPTED, rt.state); T.eq("B back to pending", StepStatus.PENDING, rt.steps[1].status)
    T.eq("A stayed done", StepStatus.DONE, rt.steps[0].status)
    val rr2 = FakeRunner({ _, _ -> StepOutcome(true, "ok", Verify.VERIFIED) }, satisfied = { it.title == "B" })
    e2.run(rt, rr2)
    T.eq("resume finished", TaskState.DONE, rt.state)
    T.eq("B skipped because already satisfied; only C ran", listOf("C"), rr2.calls)

    // ---- runner throwing is reported, not propagated
    t = eng.create("g6", steps("A"))
    eng.run(t, FakeRunner({ _, _ -> throw IllegalStateException("boom") }))
    T.eq("throw -> failed", TaskState.FAILED, t.state); T.check("crash message", t.summary.contains("boom"), t.summary)

    // ---- concurrency guard
    T.check("not running after finish", !eng.isRunning)
    T.finish("TasksTest")
}
