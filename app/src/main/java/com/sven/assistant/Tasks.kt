package com.sven.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

// ===================================================================================
// v1.1 TASK ENGINE
// Multi-step plans with a checkpoint after every step, cancellation, crash/restart recovery,
// retry-once, and *verified* completion: a step is only reported as verified when the
// phone state actually shows the expected result.
// ===================================================================================

internal enum class StepStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED, CANCELLED }
internal enum class TaskState { PLANNED, RUNNING, DONE, FAILED, CANCELLED, INTERRUPTED }
internal enum class Verify { VERIFIED, UNVERIFIED, MISMATCH, NA }

internal class Step(
    val index: Int,
    val kind: String,                 // "tool" | "agent"
    val title: String,
    val tool: String? = null,
    val args: JSONObject? = null,
    val goal: String? = null,
    val expect: String? = null,
    val idempotent: Boolean = true
) {
    var status: StepStatus = StepStatus.PENDING
    var result: String = ""
    var verify: Verify = Verify.NA
    var attempts: Int = 0
    var startedAt: Long = 0
    var endedAt: Long = 0

    fun toJson(): JSONObject = JSONObject().put("i", index).put("kind", kind).put("title", title)
        .put("tool", tool).put("args", args).put("goal", goal).put("expect", expect).put("idem", idempotent)
        .put("status", status.name).put("result", result).put("verify", verify.name)
        .put("attempts", attempts).put("start", startedAt).put("end", endedAt)

    companion object {
        fun fromJson(o: JSONObject): Step = Step(
            o.getInt("i"), o.getString("kind"), o.getString("title"),
            o.optString("tool").ifEmpty { null }, o.optJSONObject("args"), o.optString("goal").ifEmpty { null },
            o.optString("expect").ifEmpty { null }, o.optBoolean("idem", true)
        ).also {
            it.status = runCatching { StepStatus.valueOf(o.optString("status")) }.getOrDefault(StepStatus.PENDING)
            it.result = o.optString("result")
            it.verify = runCatching { Verify.valueOf(o.optString("verify")) }.getOrDefault(Verify.NA)
            it.attempts = o.optInt("attempts"); it.startedAt = o.optLong("start"); it.endedAt = o.optLong("end")
        }
    }
}

internal class Task(val id: Long, val goal: String, val steps: List<Step>, val createdAt: Long) {
    var state: TaskState = TaskState.PLANNED
    var updatedAt: Long = createdAt
    var summary: String = ""

    val doneCount get() = steps.count { it.status == StepStatus.DONE }
    val nextStep: Step? get() = steps.firstOrNull { it.status != StepStatus.DONE }
    val resumable get() = state == TaskState.INTERRUPTED || state == TaskState.FAILED || state == TaskState.CANCELLED

    fun toJson(): JSONObject = JSONObject().put("id", id).put("goal", goal).put("created", createdAt).put("updated", updatedAt)
        .put("state", state.name).put("summary", summary).put("steps", JSONArray().also { a -> steps.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Task {
            val arr = o.getJSONArray("steps")
            val steps = (0 until arr.length()).map { Step.fromJson(arr.getJSONObject(it)) }
            return Task(o.getLong("id"), o.getString("goal"), steps, o.optLong("created")).also {
                it.state = runCatching { TaskState.valueOf(o.optString("state")) }.getOrDefault(TaskState.PLANNED)
                it.updatedAt = o.optLong("updated"); it.summary = o.optString("summary")
            }
        }
    }
}

/** Persists the most recent tasks (checkpoint file). */
internal class TaskStore(dir: File, private val keep: Int = 20) {
    private val store = JsonFileStore(File(dir, "sven_tasks.json"))
    private val tasks = ArrayList<Task>()
    private var nextId = 1L

    init {
        store.read()?.let { root ->
            nextId = root.optLong("nextId", 1L)
            val arr = root.optJSONArray("tasks") ?: JSONArray()
            for (i in 0 until arr.length()) runCatching { tasks.add(Task.fromJson(arr.getJSONObject(i))) }
            if (tasks.isNotEmpty()) nextId = maxOf(nextId, tasks.maxOf { it.id } + 1)
        }
    }

    @Synchronized fun newId() = nextId++
    @Synchronized fun add(t: Task) { tasks.add(t); while (tasks.size > keep) tasks.removeAt(0); save() }
    @Synchronized fun all(): List<Task> = tasks.toList()
    @Synchronized fun latest(): Task? = tasks.lastOrNull()
    @Synchronized fun get(id: Long): Task? = tasks.firstOrNull { it.id == id }
    @Synchronized fun save() {
        store.write(JSONObject().put("v", 1).put("nextId", nextId).put("tasks", JSONArray().also { a -> tasks.forEach { a.put(it.toJson()) } }))
    }
}

// ----------------------------------------------------------------------------- planner

internal sealed class PlanResult {
    object NotCompound : PlanResult()
    /** Looks like several actions but part of it is not something I can run. */
    data class Unplannable(val reason: String) : PlanResult()
    data class NeedsInfo(val question: String) : PlanResult()
    data class Plan(val steps: List<Step>) : PlanResult()
}

internal object TaskPlanner {
    const val MAX_STEPS = 6

    private val starters = setOf(
        "open", "launch", "start", "set", "create", "make", "add", "turn", "switch", "enable", "disable", "call", "dial", "phone",
        "text", "sms", "message", "whatsapp", "email", "search", "google", "look", "navigate", "take", "go", "remind", "play", "pause",
        "resume", "skip", "next", "previous", "volume", "mute", "unmute", "give", "show", "read", "check", "copy", "wake", "timer",
        "flashlight", "torch", "brief", "schedule", "book", "find", "tap", "scroll", "send", "post", "share", "tell", "say", "explain", "write", "ask"
    )
    private val sep = Regex("\\s*(?:;|,|\\band then\\b|\\bthen\\b|\\bafter that\\b|\\band\\b|\\balso\\b)\\s+", RegexOption.IGNORE_CASE)

    /** Splits only where the text after the separator starts like a command, so "salt and pepper" stays whole. */
    fun split(text: String): List<String> {
        val t = text.trim().replace(Regex("^(?:please|hey)[, ]+", RegexOption.IGNORE_CASE), "")
        val cuts = ArrayList<Pair<Int, Int>>()
        for (m in sep.findAll(t)) {
            val rest = t.substring(m.range.last + 1).trimStart().lowercase()
            val first = rest.substringBefore(' ').trim(',', '.')
            if (first in starters && rest.length > 2) cuts.add(m.range.first to m.range.last + 1)
        }
        if (cuts.isEmpty()) return listOf(t)
        val out = ArrayList<String>(); var prev = 0
        for ((a, b) in cuts) { out.add(t.substring(prev, a).trim()); prev = b }
        out.add(t.substring(prev).trim())
        return out.filter { it.isNotEmpty() }
    }

    fun plan(text: String, now: Long, zone: ZoneId = ZoneId.systemDefault(), agentAllowed: Boolean = true): PlanResult {
        val clauses = split(text)
        if (clauses.size < 2) return PlanResult.NotCompound
        if (clauses.size > MAX_STEPS) return PlanResult.Unplannable("That is ${clauses.size} actions; I can chain at most $MAX_STEPS at a time.")
        val steps = ArrayList<Step>()
        var firstBad: String? = null
        var needsAgent: String? = null
        for ((i, c) in clauses.withIndex()) {
            val tc = CommandParser.parse(c, now, zone)
            if (tc != null) {
                if (tc.args.optBoolean("missingTime")) return PlanResult.NeedsInfo("When should I do \"${c.trim()}\"? Say it again with a time.")
                steps += Step(i, "tool", tc.label, tc.tool, tc.args, null, tc.expect, tc.idempotent)
            } else if (AgentRouter.looksLikeTask(c)) {
                if (!agentAllowed && needsAgent == null) needsAgent = c
                steps += Step(i, "agent", c.trim().replaceFirstChar { it.uppercase() }, null, null, c.trim(), GoalExpectation.parse(c), true)
            } else if (firstBad == null) firstBad = c
        }
        // Nothing here is a phone action: it is ordinary chat ("tell me about cats and then dogs").
        if (steps.isEmpty()) return PlanResult.NotCompound
        if (firstBad != null) return PlanResult.Unplannable("I can chain phone actions, but I could not turn \"$firstBad\" into one. Say it as a separate message.")
        if (needsAgent != null) return PlanResult.Unplannable("\"$needsAgent\" needs the phone agent, which is off or unavailable (check SETUP and the AGENT button).")
        return PlanResult.Plan(steps)
    }
}

// ----------------------------------------------------------------------------- engine

internal data class StepOutcome(val ok: Boolean, val message: String, val verify: Verify = Verify.NA)

internal interface StepRunner {
    /** Blocking. [isFirst] is true for the first step actually run in this execution. */
    fun run(task: Task, step: Step, isFirst: Boolean): StepOutcome
    /** Is the effect of [step] already visible (used before re-running an interrupted step)? */
    fun alreadySatisfied(step: Step): Boolean
    fun cancelCurrent()
}

internal class TaskEngine(
    val store: TaskStore,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    interface Listener {
        fun onProgress(task: Task, text: String)
        fun onFinished(task: Task)
    }

    @Volatile var listener: Listener? = null
    private val cancelled = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    @Volatile private var runner: StepRunner? = null
    @Volatile var current: Task? = null
        private set

    val isRunning get() = running.get()

    fun create(goal: String, steps: List<Step>): Task {
        val t = Task(store.newId(), goal, steps, now())
        store.add(t)
        return t
    }

    /** App start: anything left RUNNING was cut off by a crash, kill, or reboot. */
    fun recoverInterrupted(): List<Task> {
        val out = ArrayList<Task>()
        for (t in store.all()) {
            if (t.state == TaskState.RUNNING) {
                t.state = TaskState.INTERRUPTED
                t.steps.filter { it.status == StepStatus.RUNNING }.forEach { it.status = StepStatus.PENDING; it.result = "interrupted" }
                t.summary = "Interrupted after ${t.doneCount} of ${t.steps.size} steps."
                t.updatedAt = now()
                out.add(t)
            }
        }
        if (out.isNotEmpty()) store.save()
        return out
    }

    fun cancel(): Boolean {
        if (!running.get()) return false
        cancelled.set(true)
        runner?.cancelCurrent()
        return true
    }

    /** Runs (or resumes) [task] to completion on the calling thread. */
    fun run(task: Task, stepRunner: StepRunner): Task {
        if (!running.compareAndSet(false, true)) {
            task.summary = "Another task is already running."; return task
        }
        cancelled.set(false)
        runner = stepRunner
        current = task
        var firstRun = true
        try {
            // A resumed task starts from a clean slate for failed/cancelled/skipped steps.
            task.steps.filter { it.status == StepStatus.FAILED || it.status == StepStatus.CANCELLED || it.status == StepStatus.SKIPPED }
                .forEach { it.status = StepStatus.PENDING; it.result = "" ; it.verify = Verify.NA }
            task.state = TaskState.RUNNING; task.updatedAt = now(); store.save()
            val total = task.steps.size

            for (step in task.steps) {
                if (step.status == StepStatus.DONE) continue
                if (cancelled.get()) { markRestCancelled(task); break }

                // Resuming an interrupted step: if its effect is already there, do not repeat it.
                if (step.attempts > 0 && step.expect != null && runCatching { stepRunner.alreadySatisfied(step) }.getOrDefault(false)) {
                    step.status = StepStatus.DONE; step.verify = Verify.VERIFIED; step.result = "already done before the interruption"
                    step.endedAt = now(); store.save()
                    listener?.onProgress(task, "Step ${step.index + 1}/$total already done: ${step.title}")
                    continue
                }

                var attemptsHere = 0
                while (true) {
                    attemptsHere++
                    step.status = StepStatus.RUNNING; step.attempts++; step.startedAt = now(); task.updatedAt = now()
                    store.save()                                   // checkpoint BEFORE acting
                    listener?.onProgress(task, "Step ${step.index + 1}/$total: ${step.title}")
                    val out = try { stepRunner.run(task, step, firstRun) }
                    catch (t: Throwable) { StepOutcome(false, "crashed: ${t.message ?: t.javaClass.simpleName}") }
                    firstRun = false
                    step.endedAt = now()

                    if (cancelled.get()) {
                        step.status = StepStatus.CANCELLED; step.result = "cancelled"; markRestCancelled(task); break
                    }
                    val failed = !out.ok || out.verify == Verify.MISMATCH
                    if (!failed) {
                        step.status = StepStatus.DONE; step.verify = out.verify; step.result = out.message
                        break
                    }
                    step.result = out.message; step.verify = out.verify
                    val retryable = step.idempotent && attemptsHere < 2
                    if (retryable) {
                        listener?.onProgress(task, "Step ${step.index + 1} did not work (${out.message.take(80)}). Trying once more.")
                        continue
                    }
                    step.status = StepStatus.FAILED
                    task.steps.filter { it.status == StepStatus.PENDING }.forEach { it.status = StepStatus.SKIPPED }
                    break
                }
                store.save()
                if (step.status == StepStatus.FAILED || step.status == StepStatus.CANCELLED) break
            }

            task.state = when {
                task.steps.all { it.status == StepStatus.DONE } -> TaskState.DONE
                task.steps.any { it.status == StepStatus.CANCELLED } || cancelled.get() -> TaskState.CANCELLED
                else -> TaskState.FAILED
            }
            task.summary = report(task)
            task.updatedAt = now(); store.save()
        } finally {
            running.set(false); runner = null; current = null
            listener?.onFinished(task)
        }
        return task
    }

    private fun markRestCancelled(task: Task) {
        task.steps.filter { it.status == StepStatus.PENDING || it.status == StepStatus.RUNNING }.forEach {
            it.status = if (it.status == StepStatus.RUNNING) StepStatus.CANCELLED else StepStatus.SKIPPED
        }
        if (task.steps.none { it.status == StepStatus.CANCELLED }) task.steps.firstOrNull { it.status == StepStatus.SKIPPED }?.status = StepStatus.CANCELLED
    }

    companion object {
        /** Honest, human-readable result. Never claims more than was verified. */
        fun report(t: Task): String {
            val n = t.steps.size
            val verified = t.steps.count { it.status == StepStatus.DONE && it.verify == Verify.VERIFIED }
            val head = when (t.state) {
                TaskState.DONE -> if (n == 1) "Done." else "All $n steps finished" +
                    (if (verified == n) " and each one was verified." else if (verified > 0) " ($verified verified, the rest could not be checked)." else " (I could not independently verify them).")
                TaskState.CANCELLED -> "Stopped after ${t.doneCount} of $n steps."
                TaskState.FAILED -> "I finished ${t.doneCount} of $n steps, then something failed."
                TaskState.INTERRUPTED -> "Interrupted after ${t.doneCount} of $n steps."
                else -> "Planned."
            }
            val lines = t.steps.joinToString("\n") { s ->
                val mark = when (s.status) {
                    StepStatus.DONE -> if (s.verify == Verify.VERIFIED) "✓" else "•"
                    StepStatus.FAILED -> "✗"
                    StepStatus.CANCELLED -> "■"
                    StepStatus.SKIPPED -> "–"
                    else -> "·"
                }
                val tail = when (s.status) {
                    StepStatus.DONE -> if (s.verify == Verify.UNVERIFIED) " (done, not verifiable)" else ""
                    StepStatus.FAILED -> " — ${s.result.take(120)}"
                    StepStatus.SKIPPED -> " (not attempted)"
                    StepStatus.CANCELLED -> " (cancelled)"
                    else -> ""
                }
                "$mark ${s.title}$tail"
            }
            val hint = if (t.state == TaskState.FAILED || t.state == TaskState.CANCELLED || t.state == TaskState.INTERRUPTED) "\nSay \"resume task\" to continue from the first unfinished step." else ""
            return if (n == 1) head + (if (t.state == TaskState.FAILED) " " + t.steps[0].result.take(160) else "") + hint else "$head\n$lines$hint"
        }

        fun status(t: Task?, running: Boolean): String {
            if (t == null) return "No tasks yet. Try: \"open Maps and search for coffee nearby\"."
            val state = if (running && t.state == TaskState.RUNNING) "running" else t.state.name.lowercase()
            return "Task #${t.id} ($state): ${t.goal.take(80)}\n" + t.steps.joinToString("\n") { "${it.index + 1}. ${it.title} [${it.status.name.lowercase()}]" }
        }
    }
}
