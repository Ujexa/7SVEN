package com.sven.assistant

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.sin

/**
 * 7SVEN visual system.
 * Direction: a quiet, private notebook rather than a control panel. The assistant speaks in a serif on the
 * page itself, you speak in a filled bubble, system events are small margin notes. One accent (mineral teal),
 * flat surfaces, no gradients, sentence-case labels. Follows the phone's light/dark setting.
 */
internal class Palette(
    val dark: Boolean,
    val surface: Int,   // screen
    val card: Int,      // inputs, sheets
    val raised: Int,    // quiet buttons, chips
    val ink: Int,       // primary text
    val muted: Int,     // secondary text
    val line: Int,      // hairlines and outlines
    val accent: Int,
    val onAccent: Int,
    val danger: Int
)

internal object Ui {
    private val LIGHT = Palette(
        false, Color.rgb(244, 246, 245), Color.rgb(255, 255, 255), Color.rgb(230, 235, 233), Color.rgb(24, 31, 30),
        Color.rgb(98, 112, 109), Color.rgb(217, 224, 222), Color.rgb(11, 122, 107), Color.WHITE, Color.rgb(190, 54, 66)
    )
    private val DARK = Palette(
        true, Color.rgb(24, 29, 33), Color.rgb(35, 42, 48), Color.rgb(46, 55, 62), Color.rgb(233, 238, 237),
        Color.rgb(145, 160, 158), Color.rgb(54, 64, 71), Color.rgb(79, 209, 181), Color.rgb(9, 28, 24), Color.rgb(255, 122, 136)
    )

    fun palette(ctx: Context): Palette =
        if ((ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT

    fun round(color: Int, radiusPx: Float, strokeColor: Int? = null, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = radiusPx
            if (strokeColor != null && strokePx > 0) setStroke(strokePx, strokeColor)
        }

    /** "LOAD MODEL" -> "Load model". Leaves mixed-case text alone. */
    fun sentence(s: String): String {
        val letters = s.filter { it.isLetter() }
        if (letters.isEmpty() || letters != letters.uppercase(Locale.ROOT)) return s
        return s.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
    }

    /** Status strings used across the code base, in plain language. */
    fun friendly(raw: String): String {
        val r = raw.trim()
        return when {
            r.startsWith("LOCAL MODEL ONLINE") -> "Ready"
            r.startsWith("LOCAL SYSTEM") -> "Ready, no chat model loaded"
            r.startsWith("LOCAL CORE · RESTORING") -> "Restoring your model…"
            r.startsWith("LOCAL INFERENCE") -> "Thinking…"
            r.startsWith("VOICE · LISTENING") || r.startsWith("WAKE WORD") -> "Listening…"
            r.startsWith("VOICE · SPEAKING") -> "Speaking…"
            r.startsWith("VOICE · WORKING") || r == "WORKING…" -> "Working…"
            r.startsWith("AGENT · TASK COMPLETE") -> "Task complete"
            r.startsWith("AGENT · STOPPED") -> "Stopped"
            r.startsWith("AGENT · ") -> sentence(r.removePrefix("AGENT · ")).take(44)
            r.startsWith("TASK · ") -> sentence(r.removePrefix("TASK · ")).take(44)
            r.startsWith("GENERATION ERROR") -> "Something went wrong"
            else -> sentence(r.replace(" · ", ": "))
        }
    }

    fun dialog(ctx: Context): AlertDialog.Builder =
        AlertDialog.Builder(ctx, if (palette(ctx).dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)
}

/** Header presence light: still when idle, steady when ready, pulsing while the assistant works or listens. */
internal class Presence(ctx: Context, private val p: Palette) : View(ctx) {
    var mode = "idle"
        set(value) { if (field != value) { field = value; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) / 2f
        val core = r * 0.42f
        val animated = mode == "thinking" || mode == "listening"
        if (animated) {
            val period = if (mode == "listening") 1.1 else 1.8
            val t = ((SystemClock.uptimeMillis() / 1000.0) % period / period).toFloat()
            paint.style = Paint.Style.STROKE; paint.strokeWidth = r * 0.12f
            paint.color = p.accent; paint.alpha = (200 * (1f - t)).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, core + (r - core) * t, paint)
            paint.alpha = 255
        }
        paint.style = if (mode == "idle") Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeWidth = r * 0.16f
        paint.color = if (mode == "idle") p.muted else p.accent
        canvas.drawCircle(cx, cy, if (mode == "idle") core * 0.85f else core, paint)
        if (animated) postInvalidateOnAnimation()
    }
}

/** Flat icon button drawn in code (no assets): microphone, send, stop, menu. */
internal class IconButton(ctx: Context, private val p: Palette) : View(ctx) {
    enum class Kind { MIC, SEND, STOP, MORE }

    var kind = Kind.MIC
        set(value) {
            if (field != value) {
                field = value
                contentDescription = when (value) { Kind.MIC -> "Speak"; Kind.SEND -> "Send"; Kind.STOP -> "Stop"; Kind.MORE -> "Menu" }
                invalidate()
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        isClickable = true
        contentDescription = "Speak"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> alpha = 0.65f
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> alpha = 1f
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h / 2
        val d = minOf(w, h)
        val fillColor: Int; val iconColor: Int
        when (kind) {
            Kind.SEND -> { fillColor = p.accent; iconColor = p.onAccent }
            Kind.STOP -> { fillColor = p.ink; iconColor = p.surface }
            Kind.MIC -> { fillColor = p.raised; iconColor = p.ink }
            Kind.MORE -> { fillColor = Color.TRANSPARENT; iconColor = p.ink }
        }
        if (fillColor != Color.TRANSPARENT) {
            paint.style = Paint.Style.FILL; paint.color = fillColor
            canvas.drawCircle(cx, cy, d / 2f, paint)
        }
        val u = d / 48f
        paint.color = iconColor
        paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = 2.4f * u
        when (kind) {
            Kind.SEND -> {
                paint.style = Paint.Style.STROKE
                path.reset(); path.moveTo(cx, cy + 9 * u); path.lineTo(cx, cy - 9 * u)
                path.moveTo(cx - 8 * u, cy - 1 * u); path.lineTo(cx, cy - 9 * u); path.lineTo(cx + 8 * u, cy - 1 * u)
                canvas.drawPath(path, paint)
            }
            Kind.STOP -> {
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(RectF(cx - 7 * u, cy - 7 * u, cx + 7 * u, cy + 7 * u), 2.5f * u, 2.5f * u, paint)
            }
            Kind.MIC -> {
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(RectF(cx - 4.5f * u, cy - 11 * u, cx + 4.5f * u, cy + 3 * u), 4.5f * u, 4.5f * u, paint)
                paint.style = Paint.Style.STROKE
                canvas.drawArc(RectF(cx - 9 * u, cy - 6 * u, cx + 9 * u, cy + 9 * u), 0f, 180f, false, paint)
                canvas.drawLine(cx, cy + 9 * u, cx, cy + 13 * u, paint)
            }
            Kind.MORE -> {
                paint.style = Paint.Style.FILL
                for (i in -1..1) canvas.drawCircle(cx, cy + i * 7.5f * u, 2.2f * u, paint)
            }
        }
    }
}

/** Three softly pulsing dots for the "working" row in the conversation. */
internal class TypingDots(ctx: Context, private val p: Palette) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000.0
        val r = height / 5f
        for (i in 0..2) {
            val a = (0.35 + 0.65 * (0.5 + 0.5 * sin(t * 4.0 - i * 0.9))).toFloat()
            paint.color = p.muted; paint.alpha = (255 * a).toInt()
            canvas.drawCircle(r * 1.5f + i * r * 3.2f, height / 2f, r, paint)
        }
        postInvalidateOnAnimation()
    }
}
