package com.sven.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Everything a tool may need, with a live check and the exact fix. Tools never fail silently. */
internal enum class Capability(val label: String, val runtime: Array<String>) {
    CONTACTS("Contacts", arrayOf(Manifest.permission.READ_CONTACTS)),
    CALENDAR("Calendar", arrayOf(Manifest.permission.READ_CALENDAR)),
    MICROPHONE("Microphone", arrayOf(Manifest.permission.RECORD_AUDIO)),
    ACCESSIBILITY("Accessibility service", emptyArray()),
    NOTIFICATIONS("Notifications", emptyArray()),
    EXACT_ALARMS("Exact alarms", emptyArray()),
    OVERLAY("Display over other apps", emptyArray());

    fun isGranted(ctx: Context): Boolean = when (this) {
        CONTACTS, CALENDAR, MICROPHONE ->
            Build.VERSION.SDK_INT < 23 || runtime.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        ACCESSIBILITY -> SvenAccessibilityService.instance != null
        NOTIFICATIONS -> try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled() } catch (_: Exception) { true }
        EXACT_ALARMS -> Build.VERSION.SDK_INT < 31 || (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        OVERLAY -> Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)
    }

    val needsRuntimePrompt get() = runtime.isNotEmpty()

    /** Settings screen that fixes a non-runtime capability. */
    fun settingsIntent(ctx: Context): Intent = when (this) {
        ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
    }
}

internal object PermissionGate {
    /** Capabilities a tool cannot work without. */
    fun required(tool: String): List<Capability> = when (tool) {
        "call_contact", "text_contact" -> listOf(Capability.CONTACTS)
        "whatsapp" -> emptyList()
        "read_calendar" -> listOf(Capability.CALENDAR)
        "system_nav" -> listOf(Capability.ACCESSIBILITY)
        else -> emptyList()
    }

    fun missing(ctx: Context, tool: String): Capability? = required(tool).firstOrNull { !it.isGranted(ctx) }

    fun explain(cap: Capability): String = when (cap) {
        Capability.CONTACTS -> "I need Contacts access to find people by name. Allow it and I will repeat the command, or use a phone number instead."
        Capability.CALENDAR -> "I need Calendar access to read your events. Allow it and I will repeat the command."
        Capability.ACCESSIBILITY -> "The accessibility service is off. Open SETUP and enable it."
        Capability.MICROPHONE -> "I need the microphone permission for voice."
        Capability.NOTIFICATIONS -> "Notifications are blocked for 7SVEN, so reminders cannot alert you. Enable them in SETUP."
        Capability.EXACT_ALARMS -> "Exact alarms are not allowed, so reminders may arrive a few minutes late. Enable it in SETUP."
        Capability.OVERLAY -> "Display over other apps is off."
    }
}
