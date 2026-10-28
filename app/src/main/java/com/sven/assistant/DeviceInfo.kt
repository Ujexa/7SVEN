package com.sven.assistant

import android.app.NotificationManager
import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.ContactsContract
import java.time.Instant
import java.time.ZoneId

internal data class ContactMatch(val name: String, val number: String, val type: String)

/** Read-only device facts: battery, calendar, contacts, Do Not Disturb, torch. */
internal object DeviceInfo {

    fun battery(ctx: Context): Pair<Int, Boolean> {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return pct to charging
    }

    fun dndActive(ctx: Context): Boolean = try {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.currentInterruptionFilter.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
    } catch (_: Exception) { false }

    /** Calendar instances in [from, to). Null when permission is missing (distinct from "no events"). */
    fun events(ctx: Context, from: Long, to: Long, limit: Int = 12): List<EventFact>? {
        if (!Capability.CALENDAR.isGranted(ctx)) return null
        val out = ArrayList<EventFact>()
        try {
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, from); ContentUris.appendId(b, to)
            ctx.contentResolver.query(
                b.build(), arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
                null, null, CalendarContract.Instances.BEGIN + " ASC"
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(no title)"
                    out.add(EventFact(title, c.getLong(1)))
                }
            }
        } catch (_: SecurityException) { return null } catch (_: Exception) { }
        return out
    }

    fun contacts(ctx: Context, name: String): List<ContactMatch>? {
        if (!Capability.CONTACTS.isGranted(ctx)) return null
        val out = LinkedHashMap<String, ContactMatch>()
        try {
            val p = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            ctx.contentResolver.query(
                p, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$name%"),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    val key = num.replace(Regex("[^0-9+]"), "")
                    if (key.isEmpty() || out.containsKey(key)) continue
                    val type = ContactsContract.CommonDataKinds.Phone.getTypeLabel(ctx.resources, c.getInt(2), "").toString().lowercase()
                    out[key] = ContactMatch(n, num, type)
                }
            }
        } catch (_: SecurityException) { return null } catch (_: Exception) { }
        val exact = out.values.filter { it.name.equals(name, true) }
        return if (exact.isNotEmpty()) exact else out.values.toList()
    }

    fun dayBounds(now: Long, zone: ZoneId, plusDays: Long): Pair<Long, Long> {
        val d = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(plusDays)
        return d.atStartOfDay(zone).toInstant().toEpochMilli() to d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

/** Remembers the torch state through the camera service callback (the only reliable way to read it). */
internal object TorchState {
    @Volatile var on: Boolean? = null
    private var registered = false

    @Synchronized fun ensure(ctx: Context) {
        if (registered) return
        try {
            val cm = ctx.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { on = enabled }
            }, Handler(Looper.getMainLooper()))
            registered = true
        } catch (_: Exception) { }
    }
}
