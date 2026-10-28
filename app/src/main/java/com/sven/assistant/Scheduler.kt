package com.sven.assistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/** Arms reminders with AlarmManager so they fire with the app closed and are re-armed at boot. */
internal class AndroidAlarmArmer(private val ctx: Context) : AlarmArmer {
    private val am get() = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun arm(r: Reminder) {
        val pi = pending(ctx, r.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.whenMillis, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.whenMillis, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.whenMillis, pi)
        }
    }

    override fun disarm(id: Long) {
        pending(ctx, id, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
    }

    companion object {
        fun pending(ctx: Context, id: Long, flag: Int): PendingIntent? {
            val i = Intent(ctx, ProactiveReceiver::class.java).setAction(ProactiveReceiver.ACTION_FIRE)
                .setData(Uri.parse("sven://reminder/$id")).putExtra(ProactiveReceiver.EXTRA_ID, id)
            return PendingIntent.getBroadcast(ctx, (id % Int.MAX_VALUE).toInt(), i, flag or PendingIntent.FLAG_IMMUTABLE)
        }

        fun isArmed(ctx: Context, id: Long): Boolean = pending(ctx, id, PendingIntent.FLAG_NO_CREATE) != null

        private const val TICK_MS = 15 * 60_000L

        /** Periodic evaluation of rules and suggestions. Inexact on purpose: cheap on battery. */
        fun scheduleTick(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val i = Intent(ctx, ProactiveReceiver::class.java).setAction(ProactiveReceiver.ACTION_TICK)
            val pi = PendingIntent.getBroadcast(ctx, 7, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + TICK_MS, pi)
        }
    }
}

/** All 7SVEN notifications: reminders (loud, actionable) and proactive suggestions (quiet, dismissible). */
internal object Notifications {
    private const val CH_REMINDERS = "sven_reminders"
    private const val CH_PROACTIVE = "sven_proactive"

    private fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH_REMINDERS) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_REMINDERS, "Reminders and timers", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Things you asked 7SVEN to remind you about"
            })
        }
        if (nm.getNotificationChannel(CH_PROACTIVE) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_PROACTIVE, "Suggestions and rules", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Optional suggestions, rule alerts and briefings. Limited per day and silent during quiet hours."
            })
        }
    }

    private fun open(ctx: Context, kind: String, req: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_ENGAGED_KIND, kind)
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun broadcast(ctx: Context, action: String, req: Int, configure: Intent.() -> Unit): PendingIntent {
        val i = Intent(ctx, ProactiveReceiver::class.java).setAction(action).setData(Uri.parse("sven://$action/$req")).apply(configure)
        return PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun notifId(key: String): Int = 30000 + (key.hashCode() and 0xFFFF)

    /** A reminder, timer or missed item the user asked for. */
    fun reminder(ctx: Context, r: Reminder, missed: Boolean, bodyOverride: String? = null) {
        ensureChannels(ctx)
        val title = when { missed -> "Missed"; r.kind == "timer" -> "Timer"; r.kind == "briefing" -> "Your briefing"; else -> "Reminder" }
        val body = bodyOverride ?: if (r.kind == "timer") "Timer finished" else r.text
        val id = 20000 + (r.id % 9000).toInt()
        val b = Notification.Builder(ctx, CH_REMINDERS).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title).setContentText(body.lineSequence().first()).setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER).setContentIntent(open(ctx, "reminder", id))
        if (r.kind != "briefing") {
            b.addAction(Notification.Action.Builder(null, "Snooze 10 min", broadcast(ctx, ProactiveReceiver.ACTION_SNOOZE, id) { putExtra(ProactiveReceiver.EXTRA_ID, r.id); putExtra(ProactiveReceiver.EXTRA_NOTIF, id) }).build())
            b.addAction(Notification.Action.Builder(null, "Done", broadcast(ctx, ProactiveReceiver.ACTION_DONE, id) { putExtra(ProactiveReceiver.EXTRA_NOTIF, id) }).build())
        }
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, b.build())
    }

    /** Rule alerts, suggestions and unfinished-task nudges (everything governed by the interruption policy). */
    fun proactive(ctx: Context, item: ProactiveItem) {
        ensureChannels(ctx)
        val id = notifId(item.key)
        val b = Notification.Builder(ctx, CH_PROACTIVE).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (item.kind == "suggestion") "Suggestion" else "7SVEN")
            .setContentText(item.text.lineSequence().first()).setStyle(Notification.BigTextStyle().bigText(item.text))
            .setAutoCancel(true).setContentIntent(open(ctx, item.kind, id))
            .setDeleteIntent(broadcast(ctx, ProactiveReceiver.ACTION_DISMISSED, id) { putExtra(ProactiveReceiver.EXTRA_KIND, item.kind) })
        if (item.acceptCommand != null) {
            b.addAction(Notification.Action.Builder(null, "Yes", broadcast(ctx, ProactiveReceiver.ACTION_ACCEPT, id) {
                putExtra(ProactiveReceiver.EXTRA_COMMAND, item.acceptCommand); putExtra(ProactiveReceiver.EXTRA_KEY, item.key); putExtra(ProactiveReceiver.EXTRA_NOTIF, id) }).build())
            b.addAction(Notification.Action.Builder(null, "No thanks", broadcast(ctx, ProactiveReceiver.ACTION_DECLINE, id) {
                putExtra(ProactiveReceiver.EXTRA_KEY, item.key); putExtra(ProactiveReceiver.EXTRA_NOTIF, id) }).build())
        }
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, b.build())
    }

    fun cancel(ctx: Context, id: Int) = (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
}
