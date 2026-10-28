package com.sven.assistant

import android.app.AlarmManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.provider.Settings
import java.util.Calendar

/**
 * Turns "I sent the intent" into "Android shows the result". VERIFIED only when the state is
 * observable and matches; MISMATCH only when it is observable and does not; otherwise UNVERIFIED.
 */
internal object StateVerifier {

    /** Check a completed tool call. May wait a few seconds for the change to appear. */
    fun verifyCall(ctx: Context, tc: ToolCall, result: ToolBox.Result): Verify {
        when (tc.tool) {
            "media_control" -> {
                val want = when (tc.args.optString("action")) { "pause" -> false; "resume" -> true; else -> return Verify.NA }
                return poll(2500) { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive == want }
            }
            "play_media" -> {
                val v = poll(6000) { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive }
                return if (v == Verify.VERIFIED) v else Verify.UNVERIFIED
            }
            "remind" -> {
                val id = Sven.reminders.lastAddedId ?: return Verify.UNVERIFIED
                val r = Sven.reminders.get(id) ?: return Verify.MISMATCH
                if (r.status != "active") return Verify.MISMATCH
                return if (AndroidAlarmArmer.isArmed(ctx, id)) Verify.VERIFIED else Verify.MISMATCH
            }
        }
        val e = tc.expect ?: return Verify.NA
        if (e.startsWith("foreground:")) return foreground(ctx, e.removePrefix("foreground:"), result)
        return verifyExpect(ctx, e, wait = true)
    }

    /** Check an end-state expectation such as "wifi:on". With [wait] false it is a single instant look. */
    fun verifyExpect(ctx: Context, expect: String?, wait: Boolean): Verify {
        if (expect == null) return Verify.NA
        val check: () -> Boolean? = { observe(ctx, expect) }
        if (check() == null) return Verify.UNVERIFIED
        return if (wait) poll(3500, check) else (if (check() == true) Verify.VERIFIED else Verify.MISMATCH)
    }

    /** true / false when observable, null when it cannot be read. */
    private fun observe(ctx: Context, expect: String): Boolean? {
        val p = expect.split(':')
        return try {
            when (p[0]) {
                "wifi" -> (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled == (p[1] == "on")
                "bluetooth" -> BluetoothAdapter.getDefaultAdapter()?.let { it.isEnabled == (p[1] == "on") }
                "airplane" -> (Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) == (p[1] == "on")
                "torch" -> { TorchState.ensure(ctx); TorchState.on?.let { it == (p[1] == "on") } }
                "volume" -> {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val want = max * p[1].toInt() / 100
                    kotlin.math.abs(am.getStreamVolume(AudioManager.STREAM_MUSIC) - want) <= 1
                }
                "alarm" -> {
                    val next = (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock
                    if (next == null) false   // nothing at all is set: provable failure
                    else {
                        val c = Calendar.getInstance().apply { timeInMillis = next.triggerTime }
                        // The soonest alarm may be a different, earlier one; a match proves it, a non-match proves nothing.
                        if (c.get(Calendar.HOUR_OF_DAY) == p[1].toInt() && c.get(Calendar.MINUTE) == p[2].toInt()) true else null
                    }
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun foreground(ctx: Context, what: String, result: ToolBox.Result): Verify {
        val svc = SvenAccessibilityService.instance ?: return Verify.UNVERIFIED
        val deadline = System.currentTimeMillis() + 3500
        while (System.currentTimeMillis() < deadline) {
            val pkg = try { svc.snapshot()?.pkg } catch (_: Exception) { null }
            if (pkg != null && pkg != ctx.packageName) {
                val ok = when (what) {
                    "settings" -> pkg.contains("settings", true)
                    "app" -> result.pkg == null || pkg == result.pkg
                    else -> true
                }
                if (ok) return Verify.VERIFIED
            }
            Thread.sleep(250)
        }
        // The launcher may be slow or the screen may be secure; that is not proof of failure.
        return Verify.UNVERIFIED
    }

    private fun poll(maxMs: Long, check: () -> Boolean?): Verify {
        val end = System.currentTimeMillis() + maxMs
        var last: Boolean? = null
        while (true) {
            last = check()
            if (last == true) return Verify.VERIFIED
            if (last == null) return Verify.UNVERIFIED
            if (System.currentTimeMillis() >= end) return Verify.MISMATCH
            Thread.sleep(250)
        }
    }
}
