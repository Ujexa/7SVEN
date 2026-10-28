package com.sven.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.ZoneId

/** Lets MainActivity show (and speak) things that arrive while the app is open. */
internal object ProactiveBus {
    @Volatile var listener: ((String) -> Unit)? = null
}

/** Background brain: delivers reminders, runs the periodic tick, applies the interruption policy. */
internal object Proactive {

    /** Run an item through the policy and deliver, defer or drop it. */
    fun offer(ctx: Context, item: ProactiveItem): Decision {
        val d = Sven.policy.decide(item)
        when (d) {
            is Decision.Deliver -> deliver(ctx, item)
            is Decision.Defer -> Sven.policy.defer(item, d.untilMillis)
            is Decision.Drop -> AuditLog.add(ctx, "proactive_drop", "${item.kind}:${item.key} (${d.why})")
        }
        return d
    }

    private fun deliver(ctx: Context, item: ProactiveItem) {
        Notifications.proactive(ctx, item)
        Sven.policy.recordShown(item)
        if (item.acceptCommand != null) {
            Sven.conversation.pendingSuggestion = item
            if (item.key.startsWith("habit:") || item.key.startsWith("task:")) Sven.habits.markSuggested(item.key.removePrefix("habit:"), System.currentTimeMillis())
        }
        ProactiveBus.listener?.invoke(item.text)
    }

    /** A reminder the user asked for: always delivered. */
    fun deliverReminder(ctx: Context, r: Reminder, missed: Boolean) {
        val body = if (r.kind == "briefing") ToolBox.run(ctx, "briefing", null).message else null
        Notifications.reminder(ctx, r, missed, body)
        val spoken = body ?: if (r.kind == "timer") "Timer finished." else (if (missed) "Missed reminder: " else "Reminder: ") + r.text
        ProactiveBus.listener?.invoke(spoken)
        AuditLog.add(ctx, "reminder_fired", r.kind + (if (missed) " (missed)" else ""))
    }

    fun tick(ctx: Context) {
        val now = System.currentTimeMillis()
        // 1) deferred items whose quiet period ended
        Sven.policy.takeDue().forEach { offer(ctx, it) }
        // 2) rule triggers
        val (pct, charging) = DeviceInfo.battery(ctx)
        val needsEvents = Sven.ruleStore.all().any { it.type == "event_soon" && it.enabled }
        val events = if (needsEvents) DeviceInfo.events(ctx, now, now + 3 * 3_600_000L, 6) else null
        if (pct >= 0) Sven.ruleEngine.evaluate(Facts(pct, charging, events), now).forEach { offer(ctx, it) }
        // 3) habit suggestion (at most one per tick)
        val zone = ZoneId.systemDefault()
        HabitDetector.detect(Sven.habits, now, zone) { sig ->
            (sig == "briefing" || sig.startsWith("read_calendar")) && Sven.reminders.active().any { it.kind == "briefing" }
        }?.let { h -> offer(ctx, ProactiveItem("suggestion", "habit:${h.sig}", Urgency.LOW, h.text, h.acceptCommand)) }
        // 4) one gentle nudge per unfinished task, never repeated
        Sven.tasks.store.all().lastOrNull { (it.state == TaskState.INTERRUPTED || it.state == TaskState.FAILED) && now - it.updatedAt < 24 * 3_600_000L }?.let { t ->
            val sig = "task:${t.id}"
            if (Sven.habits.lastSuggested(sig) == null)
                offer(ctx, ProactiveItem("suggestion", sig, Urgency.NORMAL, "Unfinished task: ${t.goal.take(60)} (${t.doneCount}/${t.steps.size} done). Resume it?", "resume task"))
        }
    }
}

class ProactiveReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_FIRE = "com.sven.assistant.FIRE"
        const val ACTION_TICK = "com.sven.assistant.TICK"
        const val ACTION_SNOOZE = "com.sven.assistant.SNOOZE"
        const val ACTION_DONE = "com.sven.assistant.DONE"
        const val ACTION_ACCEPT = "com.sven.assistant.ACCEPT"
        const val ACTION_DECLINE = "com.sven.assistant.DECLINE"
        const val ACTION_DISMISSED = "com.sven.assistant.DISMISSED"
        const val EXTRA_ID = "id"
        const val EXTRA_NOTIF = "notif"
        const val EXTRA_KIND = "kind"
        const val EXTRA_KEY = "key"
        const val EXTRA_COMMAND = "command"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        Sven.init(ctx)
        val action = intent.action ?: return
        val pending = goAsync()
        Thread {
            try { handle(ctx, intent, action) } catch (e: Throwable) { AuditLog.add(ctx, "receiver_error", "$action: ${e.message}") }
            finally { pending.finish() }
        }.start()
    }

    private fun handle(ctx: Context, intent: Intent, action: String) {
        when (action) {
            ACTION_FIRE -> {
                val r = Sven.reminders.onFired(intent.getLongExtra(EXTRA_ID, -1L)) ?: return
                Proactive.deliverReminder(ctx, r, false)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                val report = Sven.reminders.rearmAll()
                report.missed.forEach { Proactive.deliverReminder(ctx, it, true) }
                if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    Sven.tasks.recoverInterrupted()
                }
                AndroidAlarmArmer.scheduleTick(ctx)
                AuditLog.add(ctx, "rearm", "$action armed=${report.armed} missed=${report.missed.size}")
            }
            ACTION_TICK -> { try { Proactive.tick(ctx) } finally { AndroidAlarmArmer.scheduleTick(ctx) } }
            ACTION_SNOOZE -> {
                Sven.reminders.snooze(intent.getLongExtra(EXTRA_ID, -1L), 10)
                Notifications.cancel(ctx, intent.getIntExtra(EXTRA_NOTIF, 0))
            }
            ACTION_DONE -> Notifications.cancel(ctx, intent.getIntExtra(EXTRA_NOTIF, 0))
            ACTION_DISMISSED -> Sven.policy.recordDismissed(intent.getStringExtra(EXTRA_KIND) ?: "suggestion")
            ACTION_DECLINE -> {
                val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
                Sven.habits.markDeclined(key.removePrefix("habit:"))
                Sven.policy.recordDismissed("suggestion")
                Notifications.cancel(ctx, intent.getIntExtra(EXTRA_NOTIF, 0))
            }
            ACTION_ACCEPT -> {
                Sven.policy.recordEngaged("suggestion")
                Notifications.cancel(ctx, intent.getIntExtra(EXTRA_NOTIF, 0))
                val cmd = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
                if (cmd.isNotEmpty()) {
                    val out = CommandCenter.handle(ctx, cmd, fromVoice = false)
                    val text = (out as? CommandCenter.Outcome.Reply)?.text ?: "Could not run \"$cmd\"."
                    Notifications.proactive(ctx, ProactiveItem("ack", "ack:" + System.currentTimeMillis(), Urgency.URGENT, text))
                }
            }
        }
    }
}
