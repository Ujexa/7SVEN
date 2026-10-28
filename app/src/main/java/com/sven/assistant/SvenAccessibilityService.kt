package com.sven.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One actionable or readable element on the current screen. */
class UiNode(
    val id: Int,
    val info: AccessibilityNodeInfo,
    val kind: String,
    val label: String,
    val bounds: Rect,
    val interactive: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val password: Boolean,
    val state: String
)

/** A compact, model-friendly view of the screen. */
class ScreenSnapshot(val pkg: String, val nodes: List<UiNode>) {
    val fingerprint: Int = (pkg + nodes.joinToString("|") { it.kind + it.label + it.state }).hashCode()

    fun find(id: Int): UiNode? = nodes.firstOrNull { it.id == id }

    private fun line(n: UiNode): String {
        val sb = StringBuilder()
        sb.append('[').append(n.id).append("] ").append(n.kind)
        if (n.label.isNotEmpty()) sb.append(" \"").append(n.label).append('"')
        if (n.state.isNotEmpty()) sb.append(' ').append(n.state)
        return sb.toString()
    }

    /** Renders the screen, dropping plain text first when over budget. */
    fun compact(maxChars: Int): String {
        val keep = nodes.toMutableList()
        var text = keep.joinToString("\n") { line(it) }
        var i = keep.size - 1
        while (text.length > maxChars && i >= 0) {
            if (!keep[i].interactive) {
                keep.removeAt(i)
                text = keep.joinToString("\n") { line(it) }
            }
            i--
        }
        if (text.length > maxChars) text = text.take(maxChars)
        return if (text.isEmpty()) "(nothing readable on screen)" else text
    }
}

class SvenAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: SvenAccessibilityService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val wm: WindowManager get() = getSystemService(WINDOW_SERVICE) as WindowManager

    @Volatile private var lastEventAt = 0L
    private var bar: View? = null
    private var barText: TextView? = null
    private var confirmView: View? = null
    private val confirmResult = AtomicBoolean(false)
    @Volatile private var confirmLatch: CountDownLatch? = null
    private var volUp = false
    private var volDown = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventAt = SystemClock.uptimeMillis()
    }

    override fun onInterrupt() {}

    /** Hold volume up + down together to stop a running agent. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> volUp = down
            KeyEvent.KEYCODE_VOLUME_DOWN -> volDown = down
        }
        if (volUp && volDown && AgentController.isRunning) {
            AgentController.stop("Stopped with the volume-key shortcut.")
        }
        return false
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AgentController.stop("The accessibility service was turned off.")
        instance = null
        main.post { removeBar(); removeConfirmView() }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ reading

    fun snapshot(): ScreenSnapshot? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        val out = ArrayList<UiNode>()
        walk(root, out, 0, "")
        return ScreenSnapshot(pkg, out)
    }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<UiNode>, depth: Int, ancestorLabel: String) {
        if (n == null || depth > 45 || out.size >= 90) return
        if (!n.isVisibleToUser) return

        val text = n.text?.toString()?.trim().orEmpty()
        val desc = n.contentDescription?.toString()?.trim().orEmpty()
        val hint = n.hintText?.toString()?.trim().orEmpty()
        val interactive = n.isClickable || n.isLongClickable || n.isEditable ||
            n.isScrollable || n.isCheckable
        var label = when {
            text.isNotEmpty() -> text
            desc.isNotEmpty() -> desc
            hint.isNotEmpty() -> hint
            else -> ""
        }
        if (label.isEmpty() && interactive && !n.isScrollable) label = childText(n, 0)
        if (label.isEmpty() && interactive) {
            label = n.viewIdResourceName?.substringAfterLast('/')?.replace('_', ' ').orEmpty()
        }
        label = label.replace(Regex("\\s+"), " ").take(48)

        val duplicate = !interactive && label.isNotEmpty() &&
            ancestorLabel.isNotEmpty() && ancestorLabel.contains(label)
        val bounds = Rect()
        n.getBoundsInScreen(bounds)

        if ((interactive || label.isNotEmpty()) && !duplicate && !bounds.isEmpty) {
            val cls = n.className?.toString().orEmpty()
            val isSwitch = cls.contains("Switch") || cls.contains("Toggle")
            val isImage = cls.contains("Image")
            val kind = when {
                n.isPassword -> "password"
                n.isEditable -> "input"
                n.isCheckable && isSwitch -> "switch"
                n.isCheckable -> "checkbox"
                (n.isClickable || n.isLongClickable) && isImage -> "icon"
                n.isClickable || n.isLongClickable -> "button"
                n.isScrollable -> "list"
                else -> "text"
            }
            val state = buildString {
                if (n.isCheckable) append(if (n.isChecked) "on" else "off")
                if (!n.isEnabled) {
                    if (isNotEmpty()) append(' ')
                    append("disabled")
                }
            }
            out.add(
                UiNode(
                    id = out.size + 1, info = n, kind = kind,
                    label = if (n.isPassword) "" else label, bounds = bounds,
                    interactive = interactive, editable = n.isEditable,
                    scrollable = n.isScrollable, password = n.isPassword, state = state
                )
            )
        }

        val childAncestor = if (interactive && label.isNotEmpty()) label else ancestorLabel
        for (i in 0 until n.childCount) {
            walk(n.getChild(i), out, depth + 1, childAncestor)
        }
    }

    private fun childText(n: AccessibilityNodeInfo, depth: Int): String {
        if (depth > 3) return ""
        val parts = ArrayList<String>()
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            var t = c.text?.toString()?.trim().orEmpty()
            if (t.isEmpty()) t = c.contentDescription?.toString()?.trim().orEmpty()
            if (t.isEmpty()) t = childText(c, depth + 1)
            if (t.isNotEmpty()) parts.add(t)
            if (parts.size >= 3) break
        }
        return parts.joinToString(" · ")
    }

    // ------------------------------------------------------------------ acting

    /** Waits until the UI has stopped changing (or the timeout passes). */
    fun waitSettle(minMs: Long = 600, maxMs: Long = 3500) {
        val start = SystemClock.uptimeMillis()
        try {
            Thread.sleep(minMs)
            while (SystemClock.uptimeMillis() - start < maxMs &&
                SystemClock.uptimeMillis() - lastEventAt < 450
            ) {
                Thread.sleep(80)
            }
        } catch (_: InterruptedException) {
        }
    }

    fun click(n: UiNode): String {
        n.info.refresh()
        var cur: AccessibilityNodeInfo? = n.info
        var hops = 0
        while (cur != null && hops < 5) {
            if (cur.isClickable && cur.isEnabled) {
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "OK"
                break
            }
            cur = cur.parent
            hops++
        }
        return tapAt(n.bounds.centerX(), n.bounds.centerY())
    }

    fun setText(n: UiNode, text: String): String {
        n.info.refresh()
        n.info.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return if (n.info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "OK"
        else "ERROR: this field did not accept text"
    }

    fun submit(n: UiNode): String {
        n.info.refresh()
        if (Build.VERSION.SDK_INT >= 30) {
            if (n.info.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return "OK"
        }
        return "ERROR: no Enter action here; tap the on-screen search or send button instead"
    }

    fun scroll(snap: ScreenSnapshot, id: Int?, direction: String): String {
        val dir = direction.lowercase()
        val forward = dir == "down" || dir == "right"
        val target = (id?.let { snap.find(it) }?.takeIf { it.scrollable }
            ?: snap.nodes.firstOrNull { it.scrollable })?.info
        if (target != null) {
            target.refresh()
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (target.performAction(action)) return "OK"
        }
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        return when (dir) {
            "down" -> swipe(w / 2, (h * 0.72f).toInt(), w / 2, (h * 0.28f).toInt())
            "up" -> swipe(w / 2, (h * 0.28f).toInt(), w / 2, (h * 0.72f).toInt())
            "right" -> swipe((w * 0.8f).toInt(), h / 2, (w * 0.2f).toInt(), h / 2)
            "left" -> swipe((w * 0.2f).toInt(), h / 2, (w * 0.8f).toInt(), h / 2)
            else -> "ERROR: direction must be up, down, left or right"
        }
    }

    fun global(which: String): String {
        val action = when (which.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return "ERROR: unknown navigation target"
        }
        return if (performGlobalAction(action)) "OK" else "ERROR: Android refused the action"
    }

    fun tapAt(x: Int, y: Int): String {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return gesture(path, 60)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): String {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return gesture(path, 350)
    }

    /** Must be called from a background thread. */
    private fun gesture(path: Path, durationMs: Long): String {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val description = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val dispatched = dispatchGesture(
            description,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok.set(true)
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            },
            null
        )
        if (!dispatched) return "ERROR: Android rejected the gesture"
        latch.await(2, TimeUnit.SECONDS)
        return if (ok.get()) "OK" else "ERROR: the gesture was cancelled"
    }

    // ------------------------------------------------------------------ overlays

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Shows (or updates) the floating status pill with a STOP button. */
    fun showBar(text: String) {
        main.post {
            if (bar == null) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.argb(235, 8, 16, 28))
                    setPadding(dp(12), dp(6), dp(6), dp(6))
                }
                val label = TextView(this).apply {
                    setTextColor(Color.rgb(54, 224, 255))
                    textSize = 12f
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = dp(190)
                }
                val stop = Button(this).apply {
                    this.text = "■ STOP"
                    textSize = 12f
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.rgb(176, 32, 48))
                    minHeight = 0
                    minimumHeight = 0
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                    setOnClickListener { AgentController.stop("Stopped with the on-screen STOP button.") }
                }
                row.addView(label, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                row.addView(stop, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)).apply { leftMargin = dp(10) })
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = dp(8)
                    y = dp(48)
                }
                try {
                    wm.addView(row, lp)
                    bar = row
                    barText = label
                } catch (_: Exception) {
                }
            }
            barText?.text = text
        }
    }

    fun hideBar() {
        main.post { removeBar() }
    }

    private fun removeBar() {
        bar?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bar = null
        barText = null
    }

    /**
     * Shows an approve/deny card and blocks the calling (background) thread until the
     * person answers or the timeout passes. Timeout counts as DENY.
     */
    fun confirm(title: String, detail: String, timeoutSec: Long = 60): Boolean {
        val latch = CountDownLatch(1)
        confirmResult.set(false)
        confirmLatch = latch
        main.post { showConfirmView(title, detail) }
        val answered = try { latch.await(timeoutSec, TimeUnit.SECONDS) } catch (_: InterruptedException) { false }
        confirmLatch = null
        main.post { removeConfirmView() }
        return answered && confirmResult.get()
    }

    fun cancelConfirm() {
        confirmResult.set(false)
        confirmLatch?.countDown()
    }

    private fun answerConfirm(approved: Boolean) {
        confirmResult.set(approved)
        confirmLatch?.countDown()
    }

    private fun showConfirmView(title: String, detail: String) {
        removeConfirmView()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 23, 37))
            setPadding(dp(20), dp(18), dp(20), dp(16))
        }
        card.addView(TextView(this).apply {
            text = title
            setTextColor(Color.rgb(54, 224, 255))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = detail
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, dp(10), 0, dp(14))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val deny = Button(this).apply {
            text = "DENY"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(176, 32, 48))
            setOnClickListener { answerConfirm(false) }
        }
        val approve = Button(this).apply {
            text = "APPROVE"
            setTextColor(Color.rgb(3, 14, 24))
            setBackgroundColor(Color.rgb(54, 224, 255))
            setOnClickListener { answerConfirm(true) }
        }
        row.addView(deny, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(8) })
        row.addView(approve, LinearLayout.LayoutParams(0, dp(46), 1f))
        card.addView(row)

        val lp = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.6f
        }
        try {
            wm.addView(card, lp)
            confirmView = card
        } catch (_: Exception) {
            answerConfirm(false)
        }
    }

    private fun removeConfirmView() {
        confirmView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        confirmView = null
    }
}
