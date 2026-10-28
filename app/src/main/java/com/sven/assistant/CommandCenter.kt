package com.sven.assistant

import android.content.Context
import org.json.JSONObject
import java.time.ZoneId

/**
 * One front door for every typed or spoken request. Order matters:
 *   follow-ups -> memory -> rules/settings -> reminders -> task commands -> multi-step plans -> single tools.
 * Anything not recognised returns NotHandled so MainActivity can fall back to the agent or the chat model.
 * Blocking: call from a worker thread.
 */
internal object CommandCenter {

    sealed class UiAction {
        object ShowMemory : UiAction()
        data class ConfirmClearMemory(val count: Int) : UiAction()
        data class Share(val text: String) : UiAction()
        /** A capability is missing; [retry] is re-run after the person grants it. */
        data class RequestPermission(val cap: Capability, val retry: String) : UiAction()
    }

    sealed class Outcome {
        data class NotHandled(val notice: String?) : Outcome()
        data class Reply(val text: String, val ui: UiAction? = null, val preface: String? = null, val speak: Boolean = true) : Outcome()
        object Interrupt : Outcome()
    }

    class ToolRun(val result: ToolBox.Result, val verify: Verify)

    private fun reply(text: String, ui: UiAction? = null, preface: String? = null, speak: Boolean = true): Outcome.Reply {
        if (speak) Sven.conversation.lastReply = text
        return Outcome.Reply(text, ui, preface, speak)
    }

    fun stopAll(reason: String = "Stopped.") {
        AgentController.stop(reason)
        Sven.tasks.cancel()
    }

    fun handle(ctx: Context, raw: String, fromVoice: Boolean, progress: ((String) -> Unit)? = null): Outcome {
        Sven.init(ctx)
        val st = Sven.conversation
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        var text = raw.trim()
        var preface: String? = null
        st.lastActivityAt = now

        // ---------------------------------------------------------------- follow-ups and conversation control
        when (val r = FollowUps.resolve(text, st, now, zone)) {
            null -> {}
            Resolution.Interrupt -> return Outcome.Interrupt
            is Resolution.Say -> return reply(r.text)
            Resolution.SpeakLast -> return Outcome.Reply(st.lastReply, speak = true)
            Resolution.Undo -> {
                val u = st.undo; st.undo = null
                return reply(if (u == null) "There is nothing to undo." else u())
            }
            Resolution.AcceptSuggestion -> {
                val item = st.pendingSuggestion; st.pendingSuggestion = null
                val cmd = item?.acceptCommand ?: return reply("There is nothing pending.")
                Sven.policy.recordEngaged(item.kind)
                text = cmd
            }
            Resolution.DeclineSuggestion -> {
                val item = st.pendingSuggestion; st.pendingSuggestion = null
                item?.let { Sven.habits.markDeclined(it.key.removePrefix("habit:")); Sven.policy.recordDismissed(it.kind) }
                return reply("Okay, I won't suggest that again.")
            }
            is Resolution.Reschedule -> {
                val m = Sven.reminders.reschedule(r.reminderId, r.whenMillis, r.minuteOfDay) ?: return reply("I can't find that reminder any more.")
                st.lastWhen = m.whenMillis
                return reply("Moved: " + Sven.reminders.describe(m))
            }
            is Resolution.Rewrite -> { text = r.text; preface = r.preface }
        }

        // ---------------------------------------------------------------- help
        if (Regex("^(?:help|what can you do|capabilities|commands)\\??$", RegexOption.IGNORE_CASE).matches(text)) return reply(helpText())

        // ---------------------------------------------------------------- v1.0 memory
        Sven.memory.handle(text, st.lastReply)?.let { mr ->
            val ui = when (val a = mr.action) {
                MemoryAction.ShowList -> UiAction.ShowMemory
                is MemoryAction.ConfirmClearAll -> UiAction.ConfirmClearMemory(a.count)
                is MemoryAction.Share -> UiAction.Share(a.text)
                null -> null
            }
            if (mr.text.startsWith("Saved #")) {
                val id = Sven.memory.lastTouchedId
                st.undo = { if (id != null) Sven.memory.delete(id); "Removed that memory." }
            }
            return reply(mr.text, ui, preface)
        }

        // ---------------------------------------------------------------- v1.3 rules and proactive settings
        RuleCommands.handle(text, Sven.ruleStore, Sven.ruleEngine)?.let { return reply(it, preface = preface) }
        RuleCommands.policyCommand(text, { Sven.policySettings }, { Sven.policySettings = it }, Sven.policy)?.let { return reply(it, preface = preface) }

        // ---------------------------------------------------------------- v1.3 reminders
        Sven.reminders.handleCommand(text)?.let { return reply(it, preface = preface) }
        ScheduleCommands.parseBriefing(text, now, zone)?.let { args ->
            val msg = Sven.reminders.execute(args) + notificationAdvice(ctx)
            Sven.reminders.lastAddedId?.let { id -> st.lastReminderId = id; st.undo = { Sven.reminders.cancel(id); "Briefing schedule removed." } }
            return reply(msg, preface = preface)
        }

        // ---------------------------------------------------------------- v1.1 task commands
        taskCommand(ctx, text, progress)?.let { return it }

        // ---------------------------------------------------------------- v1.1 multi-step plans
        when (val plan = TaskPlanner.plan(text, now, zone, Sven.agentAvailable())) {
            is PlanResult.Plan -> {
                val task = Sven.tasks.create(text, plan.steps)
                progress?.invoke("Plan: " + plan.steps.joinToString(" → ") { it.title })
                return runTask(ctx, task, progress)
            }
            is PlanResult.NeedsInfo -> return reply(plan.question)
            is PlanResult.Unplannable -> return reply(plan.reason)
            PlanResult.NotCompound -> {}
        }

        // ---------------------------------------------------------------- v1.2 single verified tool call
        CommandParser.parse(text, now, zone)?.let { tc -> return runSingle(ctx, tc, text, preface) }

        // Not a command. Let the caller try legacy routers / agent / chat; learn from statements on the way.
        return Outcome.NotHandled(Sven.memory.autoLearn(text))
    }

    // ==================================================================== single tool

    /** ToolBox + verification, with one automatic retry when the effect provably did not happen. */
    fun execTool(ctx: Context, tc: ToolCall): ToolRun {
        var r = ToolBox.run(ctx, tc.tool, tc.args)
        if (!r.ok) return ToolRun(r, Verify.NA)
        var v = try { StateVerifier.verifyCall(ctx, tc, r) } catch (_: Throwable) { Verify.UNVERIFIED }
        if (v == Verify.MISMATCH && tc.idempotent) {
            r = ToolBox.run(ctx, tc.tool, tc.args)
            if (r.ok) v = try { StateVerifier.verifyCall(ctx, tc, r) } catch (_: Throwable) { Verify.UNVERIFIED }
        }
        return ToolRun(r, v)
    }

    private fun runSingle(ctx: Context, tc: ToolCall, text: String, preface: String?): Outcome {
        val st = Sven.conversation
        val run = execTool(ctx, tc)
        val r = run.result
        AuditLog.add(ctx, "tool", mapOf("tool" to tc.tool, "ok" to r.ok, "verify" to run.verify.name, "msg" to r.message))

        if (!r.ok) {
            r.choices?.let { st.pendingChoice = it }
            r.entity?.let { st.lastEntity = it }
            if (tc.args.optBoolean("missingTime")) st.pendingTimeFor = text
            val ui = r.needs?.let { UiAction.RequestPermission(it, text) }
            return reply(r.message, ui, preface)
        }
        if (run.verify == Verify.MISMATCH) {
            return reply("I tried, but Android does not show it took effect. ${r.message}", preface = preface)
        }

        st.lastCall = tc; st.lastCommandText = text
        r.entity?.let { st.lastEntity = it }
        when (tc.tool) {
            "remind" -> Sven.reminders.lastAddedId?.let { id ->
                st.lastReminderId = id; st.lastWhen = Sven.reminders.get(id)?.whenMillis
                st.undo = { Sven.reminders.cancel(id); "Reminder cancelled." }
            }
            "add_event" -> st.lastWhen = tc.args.optLong("when").takeIf { it > 0 }
            "set_alarm" -> st.undo = { "I can't delete alarms from the clock app. Open it to remove that alarm." }
        }
        HabitDetector.signature(tc)?.let { Sven.habits.add(System.currentTimeMillis(), it) }

        val suffix = when (run.verify) {
            Verify.VERIFIED -> " Confirmed."
            Verify.UNVERIFIED -> if (tc.expect != null && !tc.expect.startsWith("foreground")) " (I could not confirm it.)" else ""
            else -> ""
        }
        val advice = if (tc.tool == "remind" || tc.args.optString("kind") == "briefing") notificationAdvice(ctx) else ""
        return reply(r.message + suffix + advice, preface = preface)
    }

    private fun notificationAdvice(ctx: Context): String = when {
        !Capability.NOTIFICATIONS.isGranted(ctx) -> "\n⚠ " + PermissionGate.explain(Capability.NOTIFICATIONS)
        !Capability.EXACT_ALARMS.isGranted(ctx) -> "\n⚠ " + PermissionGate.explain(Capability.EXACT_ALARMS)
        else -> ""
    }

    // ==================================================================== tasks

    private fun taskCommand(ctx: Context, text: String, progress: ((String) -> Unit)?): Outcome? {
        val I = RegexOption.IGNORE_CASE
        val eng = Sven.tasks
        if (Regex("^(?:task status|status of (?:the |my )?task|what(?:'s| is) the task status|how(?:'s| is) the task going)$", I).matches(text))
            return reply(TaskEngine.status(eng.current ?: eng.store.latest(), eng.isRunning))
        if (Regex("^(?:task history|my tasks|list tasks|recent tasks)$", I).matches(text)) {
            val all = eng.store.all().takeLast(6).reversed()
            return reply(if (all.isEmpty()) "No tasks yet." else all.joinToString("\n") { "#${it.id} ${it.state.name.lowercase()} — ${it.goal.take(50)} (${it.doneCount}/${it.steps.size})" })
        }
        if (Regex("^(?:cancel|stop|abort) (?:the |my |this )?task$", I).matches(text))
            return reply(if (eng.cancel()) "Stopping the task after the current step." else "No task is running.")
        if (Regex("^(?:resume|continue|retry|finish)(?: the| my)?(?: last| unfinished| interrupted| failed)?(?: task)?$", I).matches(text) && text.lowercase().contains("task").or(text.lowercase() == "resume")) {
            val t = eng.store.all().lastOrNull { it.resumable } ?: return reply("There is no unfinished task to resume.")
            if (t.steps.any { it.kind == "agent" } && !Sven.agentAvailable())
                return reply("That task needs the phone agent, which is off or unavailable (check SETUP and the AGENT button).")
            progress?.invoke("Resuming task #${t.id} from step ${(t.nextStep?.index ?: 0) + 1}")
            return runTask(ctx, t, progress)
        }
        return null
    }

    private fun runTask(ctx: Context, task: Task, progress: ((String) -> Unit)?): Outcome {
        val eng = Sven.tasks
        if (eng.isRunning) return reply("A task is already running. Say \"cancel task\" or press STOP first.")
        eng.listener = object : TaskEngine.Listener {
            override fun onProgress(task: Task, text: String) { progress?.invoke(text) }
            override fun onFinished(task: Task) {}
        }
        AuditLog.add(ctx, "task_start", mapOf("id" to task.id, "goal" to task.goal))
        val done = eng.run(task, AndroidStepRunner(ctx))
        AuditLog.add(ctx, "task_end", mapOf("id" to done.id, "state" to done.state.name))
        Sven.conversation.undo = null
        return reply(done.summary)
    }

    fun helpText(): String = """
Things I can do (all on this phone):
• Memory: "remember that my name is Sam", "what's my name", "forget that", "show memories"
• Reminders: "remind me in 20 minutes to stretch", "every weekday at 8 remind me to take vitamins", "my reminders", "snooze"
• Timers & alarms: "timer 10 minutes", "set alarm for 7:30 every weekday"
• Plans: "open Maps and search for coffee, then set a timer for 5 minutes", "task status", "resume task"
• Phone: call/text a contact, email, WhatsApp draft, calendar ("what's on my calendar", "add event dentist tomorrow at 3pm"), music, battery, flashlight, volume, settings
• Briefing: "briefing", or "every day at 8 give me my briefing"
• Rules: "when battery is below 20% remind me to charge", "quiet hours 22 to 7"
• Talk naturally: "make it 8", "text her hello", "what about tomorrow", "undo", "again"
I only prepare calls, texts and emails; you press send.
""".trim()
}

/** Executes plan steps against the real phone and checks the results. */
internal class AndroidStepRunner(private val ctx: Context) : StepRunner {
    override fun run(task: Task, step: Step, isFirst: Boolean): StepOutcome {
        if (!isFirst) try { Thread.sleep(500) } catch (_: InterruptedException) { }   // let the previous app settle
        if (step.kind == "tool") {
            val tc = ToolCall(step.tool.orEmpty(), step.args ?: JSONObject(), step.title, step.expect, step.idempotent)
            val run = CommandCenter.execTool(ctx, tc)
            val r = run.result
            if (!r.ok) return StepOutcome(false, r.message, Verify.NA)
            if (run.verify == Verify.MISMATCH) return StepOutcome(false, "Android does not show it took effect. ${r.message}", Verify.MISMATCH)
            return StepOutcome(true, r.message, run.verify)
        }
        val out = AgentController.runSync(ctx, step.goal.orEmpty(), startFromCurrent = !isFirst)
        val verify = when (out.verified) { true -> Verify.VERIFIED; false -> Verify.MISMATCH; null -> Verify.UNVERIFIED }
        return StepOutcome(out.success, out.summary, verify)
    }

    override fun alreadySatisfied(step: Step): Boolean {
        val e = step.expect ?: return false
        if (e.startsWith("foreground")) return false
        return try { StateVerifier.verifyExpect(ctx, e, wait = false) == Verify.VERIFIED } catch (_: Throwable) { false }
    }

    override fun cancelCurrent() { AgentController.stop("Stopped.") }
}
