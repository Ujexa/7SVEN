package com.sven.assistant

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONObject
import java.time.ZoneId
import java.util.Locale

/**
 * Fast, reliable tools. Anything that would send, call or spend only PREPARES the action
 * (opens the composer or dialer); the person presses the final button.
 */
internal object ToolBox {
    /**
     * [needs]: a permission/capability that blocked the call. [pkg]: app that was launched.
     * [entity]: person the action referred to (for "text her" follow-ups). [choices]: ambiguity to resolve.
     */
    data class Result(
        val ok: Boolean, val message: String, val needs: Capability? = null, val pkg: String? = null,
        val entity: String? = null, val choices: List<Choice>? = null
    )

    private fun ok(m: String) = Result(true, m)
    private fun err(m: String) = Result(false, m)

    private fun launch(ctx: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.applicationContext.startActivity(intent)
    }

    fun run(ctx: Context, rawName: String, args: JSONObject?): Result {
        val a = args ?: JSONObject()
        fun s(key: String): String = a.optString(key).trim()
        val tool = rawName.lowercase(Locale.ROOT).trim()
        PermissionGate.missing(ctx, tool)?.let { return Result(false, PermissionGate.explain(it), needs = it) }
        return try {
            when (tool) {
                "open_app" -> openApp(ctx, s("name"))
                "web_search" -> {
                    val q = s("query")
                    if (q.isEmpty()) return err("No search query given.")
                    launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
                    ok("Opened a web search for \"$q\".")
                }
                "navigate" -> {
                    val p = s("place")
                    if (p.isEmpty()) return err("No place given.")
                    launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(p))))
                    ok("Opened navigation to \"$p\".")
                }
                "set_alarm" -> {
                    val h = a.optInt("hour", -1)
                    val m = a.optInt("minute", 0)
                    if (h !in 0..23 || m !in 0..59) return err("Alarm time must be hour 0-23 and minute 0-59.")
                    val i = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, h)
                        .putExtra(AlarmClock.EXTRA_MINUTES, m)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, s("label").ifEmpty { "7SVEN alarm" })
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    val days = a.optJSONArray("days")
                    if (days != null && days.length() > 0) {
                        val list = ArrayList<Int>(); for (k in 0 until days.length()) list.add(days.getInt(k))
                        i.putExtra(AlarmClock.EXTRA_DAYS, list)
                    }
                    launch(ctx, i)
                    ok("Alarm set for %02d:%02d%s.".format(h, m, if (days != null && days.length() > 0) " (repeating)" else ""))
                }
                "set_timer" -> {
                    val sec = a.optInt("seconds", -1)
                    if (sec !in 1..86400) return err("Timer must be between 1 second and 24 hours.")
                    val i = Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, sec)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, s("label").ifEmpty { "7SVEN timer" })
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    launch(ctx, i)
                    ok("Timer set for $sec seconds.")
                }
                "dial" -> {
                    val n = s("number").replace(Regex("[^0-9+*#]"), "")
                    if (n.length < 3) return err("That does not look like a phone number.")
                    launch(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))))
                    ok("Dialer opened for $n. Press the call button yourself.")
                }
                "compose_sms" -> {
                    val n = s("number").replace(Regex("[^0-9+]"), "")
                    if (n.length < 3) return err("That does not look like a phone number.")
                    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$n")).putExtra("sms_body", s("text"))
                    launch(ctx, i)
                    ok("Message drafted to $n. Press send yourself.")
                }
                "flashlight" -> flashlight(ctx, a.optBoolean("on", true))
                "volume" -> volume(ctx, s("direction").lowercase(Locale.ROOT), a.optInt("percent", -1))
                "settings" -> openSettings(ctx, s("page").lowercase(Locale.ROOT))
                "save_note" -> {
                    if (s("text").isEmpty()) err("The note was empty.")
                    else { Sven.init(ctx); ok(Sven.memory.handle("remember " + s("text"))?.text ?: "Note saved.") }
                }
                "remind" -> { Sven.init(ctx); val r = Sven.reminders.execute(a); Result(!(r.startsWith("When should") || r.startsWith("That time")), r) }
                "briefing" -> ok(briefing(ctx))
                "battery" -> {
                    val (pct, charging) = DeviceInfo.battery(ctx)
                    if (pct < 0) err("Android did not report a battery level.") else ok("Battery is at $pct%" + if (charging) ", charging." else ".")
                }
                "device_status" -> deviceStatus(ctx, s("what"))
                "call_contact" -> contactAction(ctx, s("name"), null)
                "text_contact" -> contactAction(ctx, s("name"), s("text"))
                "whatsapp" -> whatsapp(ctx, s("to"), s("text"))
                "compose_email" -> {
                    val to = s("to")
                    if (!to.contains("@")) return err("That does not look like an email address.")
                    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(to, "@,")))
                        .putExtra(Intent.EXTRA_SUBJECT, s("subject")).putExtra(Intent.EXTRA_TEXT, s("body"))
                    launch(ctx, i)
                    ok("Email drafted to $to. Press send yourself.")
                }
                "add_event" -> {
                    val title = s("title").ifEmpty { "Event" }
                    val begin = a.optLong("when", 0L)
                    if (begin <= 0L) return err("When is the event? Say it again with a time.")
                    val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, title)
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + a.optLong("durationMin", 60) * 60_000L)
                    launch(ctx, i)
                    ok("Calendar event \"$title\" is ready. Press save in your calendar app.")
                }
                "read_calendar" -> readCalendar(ctx, s("range").ifEmpty { "today" })
                "media_control" -> mediaControl(ctx, s("action"))
                "play_media" -> playMedia(ctx, s("query"), s("app"))
                "copy_text" -> {
                    val t = s("text"); if (t.isEmpty()) return err("Nothing to copy.")
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("7SVEN", t))
                    ok("Copied to the clipboard.")
                }
                "system_nav" -> {
                    val svc = SvenAccessibilityService.instance
                        ?: return err("Enable the accessibility service in SETUP first.")
                    val r = svc.global(s("to"))
                    if (r == "OK") ok("Done.") else err(r.removePrefix("ERROR: "))
                }
                else -> err("Unknown tool \"$rawName\".")
            }
        } catch (e: Exception) {
            err("Tool failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun openApp(ctx: Context, wanted: String): Result {
        if (wanted.isEmpty()) return err("No app name given.")
        val pm = ctx.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(query, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != ctx.packageName }
        val w = wanted.lowercase(Locale.ROOT)
        val match = apps.firstOrNull { it.second.equals(wanted, ignoreCase = true) }
            ?: apps.firstOrNull { it.second.lowercase(Locale.ROOT).contains(w) }
            ?: apps.firstOrNull { it.second.length > 2 && w.contains(it.second.lowercase(Locale.ROOT)) }
            ?: return err("I could not find an app called \"$wanted\".")
        val intent = pm.getLaunchIntentForPackage(match.first)
            ?: return err("${match.second} cannot be launched.")
        launch(ctx, intent)
        return Result(true, "Opened ${match.second}.", pkg = match.first)
    }

    private fun flashlight(ctx: Context, on: Boolean): Result {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return err("This device has no flashlight.")
        TorchState.ensure(ctx)
        cm.setTorchMode(id, on)
        return ok("Flashlight ${if (on) "on" else "off"}.")
    }

    private fun volume(ctx: Context, direction: String, percent: Int): Result {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        return try {
            when {
                percent in 0..100 -> {
                    val max = am.getStreamMaxVolume(stream)
                    am.setStreamVolume(stream, max * percent / 100, AudioManager.FLAG_SHOW_UI)
                    ok("Volume set to $percent%.")
                }
                direction == "up" -> { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); ok("Volume up.") }
                direction == "down" -> { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); ok("Volume down.") }
                direction == "mute" -> { am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI); ok("Muted.") }
                direction == "unmute" -> { am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI); ok("Unmuted.") }
                else -> err("Volume needs a direction (up, down, mute, unmute) or a percent.")
            }
        } catch (e: SecurityException) {
            err("Android blocked the volume change (Do Not Disturb may be on).")
        }
    }

    private fun openSettings(ctx: Context, page: String): Result {
        val action = when (page) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "data", "network" -> Settings.ACTION_WIRELESS_SETTINGS
            "", "settings" -> Settings.ACTION_SETTINGS
            else -> null
        }
        if (action == null) return err("Unknown settings page: $page")
        launch(ctx, Intent(action))
        return ok("Opened $page settings.")
    }

    private fun briefing(ctx: Context): String {
        Sven.init(ctx)
        val now = System.currentTimeMillis(); val zone = ZoneId.systemDefault()
        val (from, to) = DeviceInfo.dayBounds(now, zone, 0)
        val (pct, charging) = DeviceInfo.battery(ctx)
        val unfinished = Sven.tasks.store.all().lastOrNull { it.resumable && it.state != TaskState.CANCELLED }?.goal
        return Briefing.build(BriefingFacts(
            now, zone, Sven.memory.all().firstOrNull { it.subject == "name" }?.text?.removePrefix("The user's name is ")?.trimEnd('.'),
            Sven.reminders.active(), DeviceInfo.events(ctx, maxOf(from, now), to), pct, charging, unfinished,
            Sven.memory.all().filter { it.pinned }.map { it.text.removeSuffix(".") }
        ))
    }

    private fun deviceStatus(ctx: Context, what: String): Result {
        val key = when (what) { "wifi" -> "wifi"; "bluetooth" -> "bluetooth"; "airplanemode" -> "airplane"; else -> "torch" }
        if (key == "torch") TorchState.ensure(ctx)
        val on = listOf("on", "off").firstOrNull { StateVerifier.verifyExpect(ctx, "$key:$it", false) == Verify.VERIFIED }
            ?: return err("Android does not let me read the $what state.")
        return ok("${what.replaceFirstChar { it.uppercase() }} is $on.")
    }

    private fun contactAction(ctx: Context, name: String, text: String?): Result {
        if (name.isEmpty()) return err("Who? Give me a contact name.")
        val matches = DeviceInfo.contacts(ctx, name) ?: return Result(false, PermissionGate.explain(Capability.CONTACTS), needs = Capability.CONTACTS)
        if (matches.isEmpty()) return err("I found no contact matching \"$name\".")
        val people = matches.map { it.name }.distinct()
        if (people.size > 1) {
            val top = matches.take(4)
            val verb = if (text == null) "call" else "text"
            val choices = top.map { Choice("${it.name} (${it.type.ifEmpty { "phone" }})", if (text == null) "call ${it.number}" else "text ${it.number} saying $text") }
            return Result(false, "Which one? " + choices.mapIndexed { i, c -> "${i + 1}) ${c.label}" }.joinToString("  ") + "  — say \"the first one\", or name it.", choices = choices, entity = null)
                .also { verb.length }
        }
        // one person; several numbers -> ask which number
        if (matches.size > 1) {
            val choices = matches.take(4).map { Choice("${it.name} (${it.type.ifEmpty { "phone" }})", if (text == null) "call ${it.number}" else "text ${it.number} saying $text") }
            return Result(false, "${matches[0].name} has several numbers: " + choices.mapIndexed { i, c -> "${i + 1}) ${c.label.substringAfter('(').trimEnd(')')}" }.joinToString("  ") + "  — which one?", choices = choices, entity = matches[0].name)
        }
        val m = matches[0]
        val r = if (text == null) {
            launch(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(m.number.replace(Regex("[^0-9+*#]"), "")))))
            "Dialer opened for ${m.name}. Press the call button yourself."
        } else {
            launch(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + m.number.replace(Regex("[^0-9+]"), ""))).putExtra("sms_body", text))
            "Message to ${m.name} drafted. Press send yourself."
        }
        return Result(true, r, entity = m.name)
    }

    private fun whatsapp(ctx: Context, to: String, text: String): Result {
        var number = to.replace(Regex("[^0-9+]"), "")
        var entity: String? = null
        if (number.length < 6) {
            val m = DeviceInfo.contacts(ctx, to) ?: return Result(false, PermissionGate.explain(Capability.CONTACTS), needs = Capability.CONTACTS)
            val one = m.firstOrNull() ?: return err("I found no contact matching \"$to\".")
            number = one.number.replace(Regex("[^0-9+]"), ""); entity = one.name
        }
        number = number.removePrefix("+")
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number?text=" + Uri.encode(text))).setPackage("com.whatsapp")
        if (i.resolveActivity(ctx.packageManager) == null) return err("WhatsApp is not installed.")
        launch(ctx, i)
        return Result(true, "WhatsApp message drafted. Press send yourself.", entity = entity)
    }

    private fun readCalendar(ctx: Context, range: String): Result {
        val now = System.currentTimeMillis(); val zone = ZoneId.systemDefault()
        val (from, to) = when (range) {
            "tomorrow" -> DeviceInfo.dayBounds(now, zone, 1)
            "week" -> now to DeviceInfo.dayBounds(now, zone, 6).second
            "next" -> now to now + 14L * 86_400_000L
            else -> maxOf(DeviceInfo.dayBounds(now, zone, 0).first, now) to DeviceInfo.dayBounds(now, zone, 0).second
        }
        val ev = DeviceInfo.events(ctx, from, to, if (range == "next") 1 else 12)
            ?: return Result(false, PermissionGate.explain(Capability.CALENDAR), needs = Capability.CALENDAR)
        if (ev.isEmpty()) return ok(if (range == "next") "No upcoming events in the next two weeks." else "Nothing on your calendar ${if (range == "week") "this week" else range}.")
        return ok((if (range == "next") "Next: " else "Calendar $range:\n") + ev.joinToString("\n") { "${it.title} — ${TimeParser.formatWhen(it.startMillis, now, zone)}" })
    }

    private fun mediaControl(ctx: Context, action: String): Result {
        val code = when (action) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE; "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return err("Unknown media action \"$action\".")
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ok("Sent \"$action\" to the media player.")
    }

    private fun playMedia(ctx: Context, query: String, app: String): Result {
        if (query.isEmpty()) return err("Play what?")
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        when (app) { "spotify" -> i.setPackage("com.spotify.music"); "youtube music" -> i.setPackage("com.google.android.apps.youtube.music"); "youtube" -> i.setPackage("com.google.android.youtube") }
        if (i.resolveActivity(ctx.packageManager) == null) return err(if (app.isEmpty()) "No music app on this phone handles play requests." else "${app.replaceFirstChar { it.uppercase() }} is not installed.")
        launch(ctx, i)
        return ok("Asked ${if (app.isEmpty()) "your music app" else app} to play \"$query\".")
    }
}
