package com.sven.assistant

import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import java.util.Locale

/**
 * Explicit allow-list of Android intents. 7SVEN never silently toggles protected
 * settings; it opens the relevant system panel for the user to confirm.
 */
internal object ToolRouter {
    data class Action(val label: String, val intent: Intent)

    fun resolve(raw: String): Action? {
        var command = raw.lowercase(Locale.ROOT)
            .trim()
            .replace(Regex("[.!?]+$"), "")
        for (prefix in listOf("please ", "can you ", "could you ", "would you ")) {
            if (command.startsWith(prefix)) {
                command = command.removePrefix(prefix).trim()
                break
            }
        }
        val allowed = command.startsWith("open ") ||
            command.startsWith("launch ") ||
            command.startsWith("show ") ||
            command.startsWith("go to ")
        if (!allowed) return null

        return when {
            command in listOf("open settings", "show settings", "go to settings", "open android settings") ->
                Action("Android Settings", Intent(Settings.ACTION_SETTINGS))
            command.contains("wifi settings") || command.contains("wi-fi settings") ||
                command.contains("wireless settings") ->
                Action("Wi-Fi settings", Intent(Settings.ACTION_WIFI_SETTINGS))
            command.contains("bluetooth settings") ->
                Action("Bluetooth settings", Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            command.contains("display settings") || command.contains("brightness settings") ->
                Action("Display settings", Intent(Settings.ACTION_DISPLAY_SETTINGS))
            command.contains("sound settings") || command.contains("volume settings") ->
                Action("Sound settings", Intent(Settings.ACTION_SOUND_SETTINGS))
            command.contains("app settings") || command.contains("application settings") ->
                Action("Application settings", Intent(Settings.ACTION_APPLICATION_SETTINGS))
            command in listOf("open my app settings", "open 7sven settings") ->
                Action("7SVEN app settings", Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:com.sven.assistant")))
            command in listOf("open camera", "launch camera", "show camera") ->
                Action("Camera", Intent("android.media.action.IMAGE_CAPTURE"))
            command in listOf("open browser", "launch browser", "open internet", "open web browser") ->
                Action("Browser", Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))
            command in listOf("open alarms", "show alarms", "launch alarms", "open clock") ->
                Action("Alarms", Intent(AlarmClock.ACTION_SHOW_ALARMS))
            command in listOf("open calendar", "show calendar", "launch calendar") ->
                Action("Calendar", Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI))
            command in listOf("open downloads", "show downloads") ->
                Action("Downloads", Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.providers.downloads.documents/root/downloads")))
            else -> null
        }
    }
}
