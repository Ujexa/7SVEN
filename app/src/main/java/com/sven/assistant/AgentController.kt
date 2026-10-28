package com.sven.assistant

import android.content.Context
import android.os.PowerManager

/**
 * Runs one task at a time: read screen -> ask local model for ONE action -> safety check ->
 * execute -> wait for the UI to settle -> repeat. Everything is local and every step is logged.
 */
internal object AgentController {
    interface Listener {
        fun onStep(text: String)
        fun onFinished(success: Boolean, summary: String)
    }

    private const val MAX_STEPS = 14

    /** [verified]: the goal's end-state was checked against the phone (null = no checkable state). */
    data class Outcome(val success: Boolean, val summary: String, val verified: Boolean? = null)

    @Volatile var listener: Listener? = null
    @Volatile var isRunning = false
        private set
    @Volatile private var cancelled = false
    @Volatile private var cancelReason = "Stopped."
    private val engine by lazy { NativeEngine() }
    private val lock = Any()

    /** Returns an error message, or null when the task has started. */
    fun start(appContext: Context, goal: String): String? {
        synchronized(lock) {
            if (isRunning) return "The agent is already working on a task. Press STOP first."
            if (SvenAccessibilityService.instance == null) {
                return "The accessibility service is off. Open SETUP and enable it."
            }
            isRunning = true
            cancelled = false
            cancelReason = "Stopped."
        }
        val ctx = appContext.applicationContext
        Thread({ runLoop(ctx, goal, startFromCurrent = false, notify = true) }, "7SVEN-agent").start()
        return null
    }

    /**
     * Blocking variant used by the task engine. Does not notify the UI listener (the engine reports
     * progress itself). With [startFromCurrent] the agent continues from whatever is on screen instead
     * of pressing Home first, so a step can build on the app the previous step opened.
     */
    fun runSync(appContext: Context, goal: String, startFromCurrent: Boolean): Outcome {
        synchronized(lock) {
            if (isRunning) return Outcome(false, "The agent is already working on something.")
            if (SvenAccessibilityService.instance == null) return Outcome(false, "The accessibility service is off. Open SETUP and enable it.")
            isRunning = true; cancelled = false; cancelReason = "Stopped."
        }
        return runLoop(appContext.applicationContext, goal, startFromCurrent, notify = false)
    }

    fun stop(reason: String) {
        if (!isRunning) return
        cancelReason = reason
        cancelled = true
        SvenAccessibilityService.instance?.cancelConfirm()
    }

    private fun report(text: String) {
        listener?.onStep(text)
    }

    private fun runLoop(ctx: Context, goal: String, startFromCurrent: Boolean, notify: Boolean): Outcome {
        Sven.init(ctx)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "7SVEN:agent")
        wake.setReferenceCounted(false)
        try { wake.acquire(10 * 60 * 1000L) } catch (_: Exception) {}

        AuditLog.add(ctx, "start", mapOf("goal" to goal))
        var success = false
        var summary = "I reached the step limit before finishing."
        val history = ArrayList<String>()
        val seen = HashMap<String, Int>()
        var invalid = 0
        var unchanged = 0
        var lastFingerprint = 0
        var ownScreenHops = 0

        try {
            // Leave 7SVEN's own screen so the agent works on the real target app. A task step that
            // follows another step keeps the current screen; the loop below hops home only if it is ours.
            if (!startFromCurrent) SvenAccessibilityService.instance?.let {
                it.global("home")
                it.waitSettle(800, 3000)
            }
            val userInfo = try { Sven.memory.contextFor(goal, 220).joinToString("; ") } catch (_: Exception) { "" }

            for (step in 1..MAX_STEPS) {
                if (cancelled) { summary = cancelReason; break }
                val svc = SvenAccessibilityService.instance
                if (svc == null) { summary = "The accessibility service was turned off."; break }
                svc.showBar("7SVEN · step $step")

                val snap = svc.snapshot()
                if (snap == null) {
                    summary = "I cannot read this screen (it may be locked or protected)."
                    break
                }
                if (snap.pkg == ctx.packageName) {
                    ownScreenHops++
                    if (ownScreenHops > 3) { summary = "I could not leave the 7SVEN screen."; break }
                    svc.global("home")
                    svc.waitSettle(700, 3000)
                    continue
                }

                if (step > 1 && snap.fingerprint == lastFingerprint) unchanged++ else unchanged = 0
                lastFingerprint = snap.fingerprint
                if (unchanged >= 3) { summary = "The screen stopped changing, so I stopped."; break }

                val prompt = AgentPrompt.build(goal, history, snap, unchanged, userInfo)
                val raw = try { ModelGate.generate(engine, prompt, 140) } catch (t: Throwable) { "ERROR: ${t.message}" }
                if (cancelled) { summary = cancelReason; break }
                if (raw.startsWith("ERROR:")) {
                    summary = "Model error: " + raw.removePrefix("ERROR:").trim()
                    break
                }

                val action = AgentAction.parse(raw)
                if (action == null) {
                    invalid++
                    history.add("(my last reply was not valid; answer with one JSON action)")
                    if (invalid >= 3) { summary = "The local model could not produce a valid action."; break }
                    continue
                }
                invalid = 0

                val sig = action.signature() + "@" + snap.fingerprint
                val times = (seen[sig] ?: 0) + 1
                seen[sig] = times
                if (times >= 3) { summary = "I was repeating the same action, so I stopped."; break }

                val desc = action.describe(snap)
                val decision = SafetyPolicy.evaluate(ctx, action, snap, goal)
                AuditLog.add(ctx, "action", mapOf(
                    "step" to step, "action" to "$desc [${snap.pkg}]",
                    "decision" to decision.level.name, "reason" to decision.reason
                ))
                report("Step $step: $desc")

                if (decision.level == SafetyPolicy.Level.BLOCK) {
                    summary = "Blocked for safety: ${decision.reason}."
                    break
                }
                if (decision.level == SafetyPolicy.Level.CONFIRM) {
                    val approved = svc.confirm(
                        "7SVEN NEEDS YOUR APPROVAL",
                        "$desc\n\nApp: ${snap.pkg}\nWhy: ${decision.reason}"
                    )
                    AuditLog.add(ctx, "confirmation", mapOf("approved" to approved, "action" to desc))
                    if (cancelled) { summary = cancelReason; break }
                    if (!approved) { summary = "You declined that action, so I stopped."; break }
                }

                when (action.type) {
                    "done" -> { success = true; summary = action.message ?: "Done."; break }
                    "fail" -> { summary = "I could not finish: " + (action.message ?: "no reason given."); break }
                    "ask_user" -> { summary = action.message ?: "I need more information."; break }
                }

                val result = execute(ctx, svc, snap, action)
                AuditLog.add(ctx, "result", mapOf("step" to step, "result" to result))
                history.add("$step. $desc -> ${result.take(60)}")
                if (history.size > 6) history.removeAt(0)
                svc.waitSettle()
            }
        } catch (t: Throwable) {
            summary = "The agent crashed: ${t.message ?: t.javaClass.simpleName}"
        }

        // Verified completion: if the goal names a checkable end-state, look at the phone before believing "done".
        var verified: Boolean? = null
        if (success) {
            val expect = GoalExpectation.parse(goal)
            when (try { StateVerifier.verifyExpect(ctx, expect, wait = true) } catch (_: Throwable) { Verify.UNVERIFIED }) {
                Verify.VERIFIED -> { verified = true; summary = "$summary (verified)" }
                Verify.MISMATCH -> {
                    success = false; verified = false
                    summary = "I thought it was done, but Android shows it is not (expected ${expect?.replace(':', ' ')}). Original report: $summary"
                }
                else -> {}
            }
        }
        try {
            SvenAccessibilityService.instance?.hideBar()
            try { if (wake.isHeld) wake.release() } catch (_: Exception) {}
            isRunning = false
            AuditLog.add(ctx, "finish", mapOf("summary" to summary, "verified" to verified))
        } finally {
            if (notify) listener?.onFinished(success, summary)
        }
        return Outcome(success, summary, verified)
    }

    private fun execute(ctx: Context, svc: SvenAccessibilityService, snap: ScreenSnapshot, a: AgentAction): String {
        return try {
            when (a.type) {
                "tap" -> {
                    val n = a.id?.let { snap.find(it) } ?: return "ERROR: unknown element id"
                    svc.click(n)
                }
                "type" -> {
                    val n = a.id?.let { snap.find(it) } ?: return "ERROR: unknown element id"
                    if (!n.editable) return "ERROR: element ${n.id} is not a text field"
                    svc.setText(n, a.text.orEmpty())
                }
                "submit" -> {
                    val n = a.id?.let { snap.find(it) } ?: return "ERROR: unknown element id"
                    svc.submit(n)
                }
                "scroll" -> svc.scroll(snap, a.id, a.direction ?: "down")
                "back" -> svc.global("back")
                "home" -> svc.global("home")
                "tool" -> {
                    val r = ToolBox.run(ctx, a.name.orEmpty(), a.args)
                    (if (r.ok) "OK " else "ERROR ") + r.message
                }
                else -> "ERROR: unsupported action"
            }
        } catch (t: Throwable) {
            "ERROR: ${t.message ?: t.javaClass.simpleName}"
        }
    }
}
