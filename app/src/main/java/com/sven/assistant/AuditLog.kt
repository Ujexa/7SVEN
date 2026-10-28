package com.sven.assistant

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Append-only local record of everything the agent did. Never leaves the device. */
internal object AuditLog {
    private const val FILE = "agent_audit.jsonl"
    private const val MAX_BYTES = 400_000L

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun add(ctx: Context, event: String, detail: String) = add(ctx, event, mapOf("detail" to detail))

    @Synchronized
    fun add(ctx: Context, event: String, fields: Map<String, Any?> = emptyMap()) {
        try {
            val obj = JSONObject()
            obj.put("ts", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            obj.put("event", event)
            fields.forEach { (k, v) -> obj.put(k, v?.toString()?.take(300)) }
            val f = file(ctx)
            if (f.exists() && f.length() > MAX_BYTES) {
                val keep = f.readLines().takeLast(300)
                f.writeText(keep.joinToString("\n") + "\n")
            }
            f.appendText(obj.toString() + "\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun tail(ctx: Context, maxLines: Int = 60): String {
        val f = file(ctx)
        if (!f.exists()) return "No agent activity recorded yet."
        val lines = f.readLines().takeLast(maxLines)
        if (lines.isEmpty()) return "No agent activity recorded yet."
        return lines.joinToString("\n\n") { raw ->
            try {
                val o = JSONObject(raw)
                val detail = listOf("goal", "action", "result", "summary", "decision", "reason")
                    .mapNotNull { k -> o.optString(k).takeIf { it.isNotEmpty() }?.let { "$k: $it" } }
                    .joinToString("\n")
                o.optString("ts").takeLast(8) + "  " + o.optString("event").uppercase(Locale.ROOT) +
                    (if (detail.isEmpty()) "" else "\n$detail")
            } catch (_: Exception) {
                raw
            }
        }
    }

    @Synchronized
    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (_: Exception) {}
    }
}
