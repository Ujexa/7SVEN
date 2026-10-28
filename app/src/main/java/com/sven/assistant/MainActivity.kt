package com.sven.assistant

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import android.view.WindowManager
import android.text.TextWatcher
import android.text.TextUtils
import android.text.InputType
import android.text.Editable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.app.Dialog
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val p by lazy { Ui.palette(this) }
    private val bg: Int get() = p.surface
    private val panel: Int get() = p.card
    private val cyan: Int get() = p.accent
    private val blue: Int get() = p.accent
    private val muted: Int get() = p.muted
    private val ink: Int get() = p.ink
    private val worker = Executors.newSingleThreadExecutor()
    private val engine by lazy { NativeEngine() }
    private val history = mutableListOf<Pair<String, String>>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private lateinit var status: TextView
    private lateinit var transcript: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var actionButton: IconButton
    private lateinit var modelButton: TextView
    private lateinit var core: Presence
    private lateinit var typingRow: LinearLayout
    private lateinit var typingLabel: TextView
    private var emptyState: View? = null
    private var speakingNow = false

    private var busy = false
    private var modelReady = false
        set(value) { field = value; Sven.modelReady = value; runOnUiThread { refreshModelChip() } }

    // v1.1-v1.4 state
    private val commandExecutor = Executors.newSingleThreadExecutor()
    private var voice: VoiceSession? = null
    private var voiceMode = false           // a hands-free conversation is in progress
    private var lastFromVoice = false       // the latest request was spoken, so reply aloud
    private var speakToken = 0              // invalidates the end-of-speech callback of a cut-off reply
    private var chatGeneration = 0          // invalidates a model answer the person interrupted
    private var focusRequest: AudioFocusRequest? = null
    private var pendingRetry: String? = null
    private var pendingCap: Capability? = null
    private var modelFd: ParcelFileDescriptor? = null
    private var modelUri: Uri? = null
    private lateinit var agentModeButton: TextView

    private val agentListener = object : AgentController.Listener {
        override fun onStep(text: String) {
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                addMessage("system", text)
                updateStatus("AGENT · " + text.uppercase(Locale.ROOT).take(38))
            }
        }

        override fun onFinished(success: Boolean, summary: String) {
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                history.add("assistant" to summary)
                addMessage("assistant", summary)
                persistHistory()
                core.mode = "ready"
                updateStatus(if (success) "AGENT · TASK COMPLETE" else "AGENT · STOPPED")
                busy = false
                setComposerEnabled(true)
                speak(summary)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sven.init(applicationContext)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (!p.dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        tts = TextToSpeech(this, this)
        buildUi()
        history.addAll(PersistenceStore.loadHistory(this))
        if (history.isEmpty()) showEmptyState() else history.forEach { (role, message) -> addMessage(role, message) }
        updateStatus("LOCAL CORE · RESTORING MODEL")
        restoreModelIfAvailable()
        AgentController.listener = agentListener
        onAppStartup()
        noteEngaged(intent)
        if (intent?.action == WakeWordService.ACTION_WAKE) {
            window.decorView.postDelayed({ startVoiceInput() }, 650)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        noteEngaged(intent)
        if (intent?.action == WakeWordService.ACTION_WAKE) {
            updateStatus("WAKE WORD DETECTED · LISTENING")
            window.decorView.postDelayed({ startVoiceInput() }, 450)
        }
    }

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) {
            ttsReady = (tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.LANG_MISSING_DATA) >= TextToSpeech.LANG_AVAILABLE
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { speechEnded(utteranceId) }
                @Deprecated("Required override") override fun onError(utteranceId: String?) { speechEnded(utteranceId) }
            })
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // ---- header: presence light, name, status, model chip, menu
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(8), dp(8))
        }
        core = Presence(this, p)
        header.addView(core, LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(12) })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = "7SVEN"
            textSize = 20f
            letterSpacing = 0.02f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ink)
        })
        status = TextView(this).apply {
            textSize = 12.5f
            setTextColor(muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titles.addView(status)
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))

        modelButton = object : TextView(this@MainActivity) {
            override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); post { refreshModelChip() } }
        }.apply {
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(7), dp(13), dp(7))
            setOnClickListener { chooseModel() }
        }
        header.addView(modelButton, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(4) })
        val menuButton = IconButton(this, p).apply {
            kind = IconButton.Kind.MORE
            setOnClickListener { showMenu() }
        }
        header.addView(menuButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header)
        root.addView(View(this).apply { setBackgroundColor(p.line) }, LinearLayout.LayoutParams(-1, dp(1)))

        // ---- conversation
        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        transcript = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
        }
        scroll.addView(transcript)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // "working" row, attached to the conversation only while busy
        typingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        typingRow.addView(TypingDots(this, p), LinearLayout.LayoutParams(dp(34), dp(14)).apply { rightMargin = dp(8) })
        typingLabel = TextView(this).apply { textSize = 13f; setTextColor(muted) }
        typingRow.addView(typingLabel)

        // ---- composer: one field, one adaptive button (voice / send / stop)
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        input = EditText(this).apply {
            hint = "Ask, or tell me what to do"
            textSize = 16f
            setTextColor(ink)
            setHintTextColor(muted)
            background = Ui.round(p.card, dp(24).toFloat(), p.line, dp(1))
            setPadding(dp(18), dp(12), dp(18), dp(12))
            minHeight = dp(48)
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { updateAction() }
            })
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        actionButton = IconButton(this, p).apply {
            setOnClickListener { onActionTapped() }
        }
        composer.addView(actionButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })
        root.addView(composer)

        setContentView(root)
        refreshModelChip()
        updateAction()
    }

    /** Header chip: invites loading a model until one is ready. */
    private fun refreshModelChip() {
        if (!::modelButton.isInitialized) return
        val loading = !modelButton.isEnabled && !busy
        when {
            loading -> {
                modelButton.text = "Loading…"
                modelButton.setTextColor(muted)
                modelButton.background = Ui.round(Color.TRANSPARENT, dp(16).toFloat(), p.line, dp(1))
            }
            modelReady -> {
                modelButton.text = "Model ready"
                modelButton.setTextColor(muted)
                modelButton.background = Ui.round(Color.TRANSPARENT, dp(16).toFloat(), p.line, dp(1))
            }
            else -> {
                modelButton.text = "Load model"
                modelButton.setTextColor(p.onAccent)
                modelButton.background = Ui.round(p.accent, dp(16).toFloat())
            }
        }
    }

    private fun onActionTapped() {
        when (actionButton.kind) {
            IconButton.Kind.STOP -> {
                // While a spoken reply plays in a conversation, one tap cuts it off and starts listening.
                if (speakingNow && voiceMode && !busy) { stopSpeaking(); voice?.replyFinished(150) }
                else stopEverything(announce = true)
            }
            IconButton.Kind.SEND -> sendMessage()
            else -> startVoiceInput()
        }
    }

    /** The one button changes meaning: stop while anything runs, send when there is text, otherwise speak. */
    private fun updateAction() {
        if (!::actionButton.isInitialized) return
        val active = busy || voiceMode || speakingNow || AgentController.isRunning
        actionButton.kind = when {
            active -> IconButton.Kind.STOP
            input.text.isNotBlank() -> IconButton.Kind.SEND
            else -> IconButton.Kind.MIC
        }
    }

    private fun showTyping(show: Boolean) {
        if (!::typingRow.isInitialized) return
        if (show && typingRow.parent == null) { transcript.addView(typingRow); scrollToEnd() }
        else if (!show && typingRow.parent != null) transcript.removeView(typingRow)
    }

    private fun scrollToEnd() { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }

    /** First-run / empty conversation: say what this is and offer four things to try. */
    private fun showEmptyState() {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(36), 0, dp(8))
        }
        box.addView(TextView(this).apply {
            text = when (hour) { in 5..11 -> "Good morning."; in 12..17 -> "Good afternoon."; else -> "Good evening." }
            textSize = 32f
            typeface = Typeface.SERIF
            setTextColor(ink)
        })
        box.addView(TextView(this).apply {
            text = "Everything here runs on this phone. Reminders, memory and phone actions work right away; load a model for open-ended chat."
            textSize = 15f
            setTextColor(muted)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(10), 0, dp(24))
        })
        fun suggestion(label: String, command: String, sendNow: Boolean) {
            box.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(ink)
                background = Ui.round(p.raised, dp(14).toFloat())
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setOnClickListener {
                    input.setText(command); input.setSelection(command.length)
                    if (sendNow) sendMessage() else { input.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0) }
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        suggestion("What does my day look like?", "briefing", true)
        suggestion("Remind me in 30 minutes to…", "remind me in 30 minutes to ", false)
        suggestion("Remember that…", "remember that ", false)
        suggestion("Open Maps and search for coffee", "open Maps and search for coffee", true)
        emptyState = box
        transcript.addView(box)
    }

    /** Bottom sheet with everything that used to crowd the main screen. */
    private fun showMenu() {
        val dialog = Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar)
        val r = dp(24).toFloat()
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(p.surface); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            setPadding(dp(16), dp(10), dp(16), dp(22))
        }
        sheet.addView(View(this).apply { background = Ui.round(p.line, dp(2).toFloat()) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14) })

        val missing = listOf(Capability.ACCESSIBILITY, Capability.NOTIFICATIONS, Capability.EXACT_ALARMS).count { !it.isGranted(this) }
        val memories = try { Sven.memory.count() } catch (_: Exception) { 0 }
        fun tile(title: String, sub: String, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(p.card, dp(16).toFloat(), p.line, dp(1))
            setPadding(dp(14), dp(13), dp(14), dp(13))
            addView(TextView(this@MainActivity).apply {
                text = title; textSize = 15.5f; setTextColor(ink)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            val s = TextView(this@MainActivity).apply { text = sub; textSize = 12.5f; setTextColor(muted); setPadding(0, dp(2), 0, 0) }
            addView(s)
            tag = s
            setOnClickListener { onClick() }
        }
        fun row(a: LinearLayout, b: LinearLayout) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(a, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(5) })
            row.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(5) })
            sheet.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        fun go(f: () -> Unit): () -> Unit = { dialog.dismiss(); window.decorView.post { f() } }

        row(tile("Today", "Reminders, calendar, battery", go { input.setText("briefing"); sendMessage() }),
            tile("Memory", if (memories == 0) "Nothing saved yet" else "$memories saved on this phone", go { showMemoryDialog() }))
        row(tile("Phone actions", "Apps, settings, camera", go { showPhoneActions() }),
            tile("Agent", agentModeLabel(), { cycleAgentMode() }).also { agentModeButton = it.tag as TextView })
        row(tile("Setup", if (missing == 0) "All set" else "$missing need attention", go { showSetupDialog() }),
            tile("Settings", "Voice, memory, suggestions", go { showAssistantSettings() }))
        row(tile("Activity log", "What I did, kept locally", go { showAuditLog() }),
            tile("Help", "What I can do", go { input.setText("help"); sendMessage() }))

        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.45f)
        }
        dialog.show()
    }

    private fun makeButton(label: String, color: Int): Button =
        Button(this).apply {
            text = Ui.sentence(label)
            textSize = 13f
            isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val primary = color == cyan
            val danger = color == p.danger
            setTextColor(if (primary) p.onAccent else if (danger) Color.WHITE else ink)
            background = Ui.round(if (primary) p.accent else if (danger) p.danger else p.raised, dp(12).toFloat())
            minHeight = dp(40); minimumHeight = dp(40)
            setPadding(dp(14), 0, dp(14), 0)
            stateListAnimator = null
        }

    private fun showAssistantSettings() {
        val prefs = getSharedPreferences("sven_local_state", MODE_PRIVATE)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        content.addView(TextView(this).apply {
            text = "LOCAL ASSISTANT CONTROLS"
            textSize = 10f
            letterSpacing = 0.08f
            setTextColor(cyan)
        })
        val wakeLabel = TextView(this).apply {
            text = "Active wake phrase (model-supported)"
            textSize = 12f
            setTextColor(ink)
            setPadding(0, dp(14), 0, dp(4))
        }
        content.addView(wakeLabel)
        val wakeInput = EditText(this).apply {
            setText("Light Up (supported by bundled English model)")
            isEnabled = false
            setTextColor(ink)
            setHintTextColor(muted)
            hint = "Hey 7SVEN"
            backgroundTintList = ColorStateList.valueOf(cyan)
        }
        content.addView(wakeInput)
        val wakeEnabled = android.widget.CheckBox(this).apply {
            text = "Enable local wake-word detection (Light Up)"
            setTextColor(ink)
            buttonTintList = ColorStateList.valueOf(cyan)
            isChecked = prefs.getBoolean("wake_enabled", false)
        }
        content.addView(wakeEnabled)
        val voiceEnabled = android.widget.CheckBox(this).apply {
            text = "Speak assistant replies aloud"
            setTextColor(ink)
            isChecked = prefs.getBoolean("tts_enabled", true)
            buttonTintList = ColorStateList.valueOf(cyan)
        }
        content.addView(voiceEnabled)
        val convoMode = android.widget.CheckBox(this).apply {
            text = "Hands-free conversation (keep listening after each reply)"
            setTextColor(ink); buttonTintList = ColorStateList.valueOf(cyan)
            isChecked = prefs.getBoolean("conversation_mode", true)
        }
        content.addView(convoMode)
        val memEnabled = android.widget.CheckBox(this).apply {
            text = "Personal memory on"
            setTextColor(ink); buttonTintList = ColorStateList.valueOf(cyan)
            isChecked = prefs.getBoolean("memory_enabled", true)
        }
        content.addView(memEnabled)
        val memAuto = android.widget.CheckBox(this).apply {
            text = "Learn clear facts automatically (\"my name is…\"); I tell you each time"
            setTextColor(ink); buttonTintList = ColorStateList.valueOf(cyan)
            isChecked = prefs.getBoolean("memory_auto", true)
        }
        content.addView(memAuto)
        val proactiveOn = android.widget.CheckBox(this).apply {
            text = "Proactive suggestions and rule alerts"
            setTextColor(ink); buttonTintList = ColorStateList.valueOf(cyan)
            isChecked = prefs.getBoolean("proactive_enabled", true)
        }
        content.addView(proactiveOn)
        val ps = Sven.policySettings
        content.addView(TextView(this).apply {
            text = "Quiet hours %d:%02d to %d:%02d (say \"quiet hours 22 to 7\" to change). At most %d suggestions a day; reminders you set always arrive.".format(
                ps.quietStartMin / 60, ps.quietStartMin % 60, ps.quietEndMin / 60, ps.quietEndMin % 60, ps.maxSuggestionsPerDay)
            textSize = 11f; setTextColor(muted); setPadding(0, dp(4), 0, dp(4))
        })
        content.addView(TextView(this).apply {
            text = "Wake detection uses the bundled English keyword model and listens for “Light Up”. The first setup downloads the local model/runtime. This build currently uses this supported phrase; arbitrary custom phrases need token generation before they can work."
            textSize = 11f
            setTextColor(muted)
            setPadding(0, dp(10), 0, dp(4))
        })
        Ui.dialog(this)
            .setTitle("Settings")
            .setView(content)
            .setPositiveButton("Save") { _, _ ->
                val phrase = "Light Up"
                prefs.edit()
                    .putString("wake_phrase", phrase)
                    .putBoolean("tts_enabled", voiceEnabled.isChecked)
                    .putBoolean("wake_enabled", wakeEnabled.isChecked)
                    .putBoolean("conversation_mode", convoMode.isChecked)
                    .putBoolean("memory_enabled", memEnabled.isChecked)
                    .putBoolean("memory_auto", memAuto.isChecked)
                    .putBoolean("proactive_enabled", proactiveOn.isChecked)
                    .apply()
                if (wakeEnabled.isChecked) {
                    if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_WAKE_PERMISSION)
                        addMessage("system", "Microphone permission is required. Grant it, then enable wake detection again.")
                    } else {
                        try {
                            val serviceIntent = Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_START)
                            if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
                            addMessage("system", "Local wake-word service starting. Say “Light Up” to activate voice capture.")
                        } catch (e: Exception) {
                            addMessage("system", "Could not start wake detection: ${e.message ?: "Android blocked the microphone service"}. Open 7SVEN while enabling it and check microphone permission.")
                        }
                    }
                } else {
                    stopService(Intent(this, WakeWordService::class.java))
                    addMessage("system", "Wake-word listening stopped.")
                }
            }
            .setNeutralButton("App permissions") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    addMessage("system", "Could not open application settings on this device.")
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private val prefsState get() = getSharedPreferences("sven_local_state", MODE_PRIVATE)

    /** Speaks a reply sentence by sentence (so it can be cut off) and continues a hands-free session afterwards. */
    private fun speak(text: String) {
        val token = ++speakToken
        val chunks = Speech.chunks(text)
        if (!(ttsReady && prefsState.getBoolean("tts_enabled", true)) || chunks.isEmpty()) {
            turnSpoken(token); return
        }
        speakingNow = true
        updateAction()
        requestAudioFocus()
        tts?.stop()
        chunks.forEachIndexed { i, c ->
            tts?.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null,
                if (i == chunks.lastIndex) "end:$token" else "c:$token:$i")
        }
    }

    private fun speechEnded(id: String?) {
        if (id == null || !id.startsWith("end:")) return
        val token = id.removePrefix("end:").toIntOrNull() ?: return
        runOnUiThread { if (!isDestroyed) turnSpoken(token) }
    }

    private fun turnSpoken(token: Int) {
        if (token != speakToken) return          // this reply was interrupted
        speakingNow = false
        updateAction()
        abandonAudioFocus()
        if (voiceMode) voice?.replyFinished()
    }

    private fun stopSpeaking() {
        speakToken++
        speakingNow = false
        try { tts?.stop() } catch (_: Exception) {}
        abandonAudioFocus()
        updateAction()
    }

    private fun requestAudioFocus() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } catch (_: Exception) {}
    }

    private fun abandonAudioFocus() {
        try { focusRequest?.let { (getSystemService(AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) } } catch (_: Exception) {}
        focusRequest = null
    }

    private fun agentModeLabel(): String = when (prefsState.getInt("agent_mode", 1)) {
        0 -> "Off"
        2 -> "Always on"
        else -> "Auto (task-like messages)"
    }

    private fun cycleAgentMode() {
        val next = (prefsState.getInt("agent_mode", 1) + 1) % 3
        prefsState.edit().putInt("agent_mode", next).apply()
        if (::agentModeButton.isInitialized) agentModeButton.text = agentModeLabel()
        addMessage(
            "system",
            when (next) {
                0 -> "Agent off: messages are chat only."
                2 -> "Agent always: every message is treated as a phone task."
                else -> "Agent auto: task-like messages operate the phone; questions are answered as chat."
            }
        )
    }

    private fun shouldRunAgent(text: String): Boolean = when (prefsState.getInt("agent_mode", 1)) {
        0 -> false
        2 -> true
        else -> SvenAccessibilityService.instance != null && modelReady && AgentRouter.looksLikeTask(text)
    }

    private fun runAgent(goal: String) {
        if (!modelReady) {
            replyLocally("Load your local model first. The agent needs it to plan each step.")
            return
        }
        val error = AgentController.start(applicationContext, goal)
        if (error != null) {
            replyLocally(error)
            return
        }
        busy = true
        setComposerEnabled(false)
        core.mode = "thinking"
        updateStatus("AGENT · WORKING")
        addMessage("system", "Agent started. Use STOP, the on-screen STOP pill, or hold both volume keys to cancel.")
    }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            addMessage("system", "Could not open that Android screen on this device.")
        }
    }

    private fun showSetupDialog() {
        val micOk = Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val overlayOk = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        fun line(label: String, ok: Boolean) = content.addView(TextView(this).apply {
            text = (if (ok) "●  " else "○  ") + label
            textSize = 13f
            setTextColor(if (ok) cyan else muted)
            setPadding(0, dp(4), 0, dp(4))
        })
        fun action(label: String, block: () -> Unit) = content.addView(
            makeButton(label, Color.rgb(19, 43, 61)).apply { setOnClickListener { block() } },
            LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) }
        )
        line("Local model loaded", modelReady)
        line("Accessibility service (the hands)", SvenAccessibilityService.instance != null)
        line("Display over other apps (wake word + background launches)", overlayOk)
        line("Microphone permission", micOk)
        line("Notifications (reminders can alert you)", Capability.NOTIFICATIONS.isGranted(this))
        line("Exact alarms (reminders arrive on time)", Capability.EXACT_ALARMS.isGranted(this))
        line("Contacts (call/text by name; optional)", Capability.CONTACTS.isGranted(this))
        line("Calendar (briefing, events; optional)", Capability.CALENDAR.isGranted(this))
        action("1 · enable accessibility") { safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        action("2 · allow display over apps") {
            safeStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        action("3 · microphone permission") {
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_WAKE_PERMISSION)
            }
        }
        action("5 · allow notifications") { requestCapability(Capability.NOTIFICATIONS) }
        action("6 · allow exact alarms") { requestCapability(Capability.EXACT_ALARMS) }
        action("7 · ALLOW CONTACTS (optional)") { requestCapability(Capability.CONTACTS) }
        action("8 · ALLOW CALENDAR (optional)") { requestCapability(Capability.CALENDAR) }
        action("4 · battery: set to unrestricted") {
            safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        content.addView(TextView(this).apply {
            text = "If Accessibility is greyed out: App info, three-dot menu, Allow restricted settings, then try again. " +
                "On Samsung, turn off Auto Blocker if installs are blocked. Battery: open Battery in App info and choose Unrestricted so the wake word keeps running."
            textSize = 11f
            setTextColor(muted)
            setPadding(0, dp(12), 0, dp(4))
        })
        Ui.dialog(this)
            .setTitle("Setup")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAuditLog() {
        val body = TextView(this).apply {
            text = AuditLog.tail(this@MainActivity)
            textSize = 11f
            setTextColor(ink)
            setTextIsSelectable(true)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        Ui.dialog(this)
            .setTitle("Activity log (kept on this phone)")
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton("Close", null)
            .setNeutralButton("Clear log") { _, _ -> AuditLog.clear(this) }
            .show()
    }

    private fun confirmClearConversation() {
        Ui.dialog(this)
            .setTitle("Clear conversation?")
            .setMessage("This removes the saved chat transcript from this device. Saved notes are kept.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                history.clear()
                transcript.removeAllViews()
                val confirmation = "Conversation cleared. Your saved notes remain available."
                history.add("assistant" to confirmation)
                addMessage("assistant", confirmation)
                persistHistory()
            }
            .show()
    }

    private val voiceCallbacks = object : VoiceSession.Callbacks {
        override fun onPartial(text: String) { input.setText(text) }

        override fun onFinalText(text: String) {
            input.setText("")
            lastFromVoice = true
            submit(text)
        }

        override fun onStateChanged(state: VoiceSession.State) {
            when (state) {
                VoiceSession.State.LISTENING -> { updateStatus("VOICE · LISTENING"); core.mode = "listening" }
                VoiceSession.State.WORKING -> updateStatus("VOICE · WORKING")
                VoiceSession.State.SPEAKING -> updateStatus("VOICE · SPEAKING")
                VoiceSession.State.IDLE -> if (!busy) {
                    updateStatus(if (modelReady) "LOCAL MODEL ONLINE · READY" else "LOCAL SYSTEM · READY")
                    core.mode = "ready"
                }
            }
        }

        override fun onEnded(reason: String) {
            voiceMode = false
            if (reason.isNotEmpty()) addMessage("system", reason)
        }
    }

    /** VOICE button: start a conversation; tap while I speak to interrupt and talk; tap while listening to stop. */
    private fun startVoiceInput() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_VOICE_PERMISSION)
            return
        }
        val v = voice ?: VoiceSession(this, voiceCallbacks).also { voice = it }
        if (!v.isAvailable()) { legacyVoiceInput(); return }
        if (v.state == VoiceSession.State.LISTENING) { v.stop("Stopped listening."); return }
        if (busy) { addMessage("system", "Still working. Press STOP to interrupt, or wait for the answer."); return }
        stopSpeaking()
        voiceMode = true
        v.start(prefsState.getBoolean("conversation_mode", true))
    }

    private fun legacyVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to your assistant")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_VOICE)
        } catch (_: ActivityNotFoundException) {
            addMessage("system", "No compatible speech-recognition service is available on this device.")
        }
    }

    /** Stop talking, listening, planning and agent work right now. */
    private fun stopEverything(announce: Boolean) {
        val wasActive = busy || AgentController.isRunning || Sven.tasks.isRunning || voiceMode || tts?.isSpeaking == true
        stopSpeaking()
        voiceMode = false
        voice?.stop("")
        chatGeneration++                       // a model answer still being computed is discarded
        CommandCenter.stopAll("Stopped from the app.")
        if (busy && !AgentController.isRunning && !Sven.tasks.isRunning) {
            busy = false; setComposerEnabled(true); core.mode = "ready"
            updateStatus(if (modelReady) "LOCAL MODEL ONLINE · READY" else "LOCAL SYSTEM · READY")
        }
        if (announce) addMessage("system", if (wasActive) "Stopped." else "Nothing was running.")
    }

    private fun showPhoneActions() {
        val items = arrayOf(
            "Open Android settings",
            "Open Wi-Fi settings",
            "Open Bluetooth settings",
            "Open display settings",
            "Open sound settings",
            "Open app settings",
            "Open camera",
            "Open browser",
            "Open alarms"
        )
        Ui.dialog(this)
            .setTitle("Phone actions")
            .setItems(items) { _, which ->
                val command = when (which) {
                    0 -> "open settings"
                    1 -> "open wifi settings"
                    2 -> "open bluetooth settings"
                    3 -> "open display settings"
                    4 -> "open sound settings"
                    5 -> "open app settings"
                    6 -> "open camera"
                    7 -> "open browser"
                    else -> "open alarms"
                }
                executePhoneAction(command)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun executePhoneAction(raw: String): Boolean {
        val action = ToolRouter.resolve(raw) ?: return false
        return try {
            action.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(action.intent)
            addMessage("system", "Opened ${action.label}. Android accepted the launch request.")
            updateStatus("ACTION DISPATCHED · ANDROID")
            true
        } catch (e: Exception) {
            addMessage(
                "system",
                "Could not open ${action.label}: ${e.message ?: "not supported on this device"}"
            )
            true
        }
    }

    private fun chooseModel() {
        if (busy) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_MODEL)
    }

    @Deprecated("Document-picker and speech-recognition result compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return

        if (requestCode == REQUEST_VOICE) {
            val spoken = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                lastFromVoice = true
                input.setText(spoken)
                sendMessage()
            }
            return
        }
        if (requestCode != REQUEST_MODEL) return

        val uri = data.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Some providers do not support persistent grants.
        }
        val newFd = try { contentResolver.openFileDescriptor(uri, "r") } catch (_: Exception) { null }
        if (newFd == null) {
            updateStatus("MODEL FILE ERROR")
            addMessage("assistant", "I couldn't open that file. Please select your GGUF model again.")
            return
        }

        val oldFd = modelFd
        modelReady = false
        modelButton.isEnabled = false
        updateStatus("LOADING LOCAL MODEL…")
        core.mode = "thinking"
        addMessage("system", "Loading the selected model. Initial loading may take a little while.")

        worker.execute {
            val result = try { engine.loadModel(newFd.fd) }
            catch (e: Throwable) { "ERROR: ${e.message ?: "Native model loading failed."}" }

            runOnUiThread {
                modelButton.isEnabled = true
                if (result == "OK") {
                    modelFd = newFd
                    modelUri = uri
                    try { oldFd?.close() } catch (_: Exception) {}
                    modelReady = true
                    PersistenceStore.saveModelUri(this@MainActivity, uri)
                    updateStatus("LOCAL MODEL ONLINE · READY")
                    core.mode = "ready"
                    addMessage("assistant", "Local model online. Ready when you are.")
                } else {
                    try { newFd.close() } catch (_: Exception) {}
                    updateStatus("MODEL LOAD FAILED")
                    core.mode = "idle"
                    addMessage("assistant", result.removePrefix("ERROR: "))
                }
            }
        }
    }

    private fun sendMessage() {
        if (busy) return
        val question = input.text.toString().trim()
        if (question.isEmpty()) return
        input.setText("")
        if (!voiceMode) lastFromVoice = false
        pendingRetry = null; pendingCap = null
        submit(question)
    }

    /** Every request (typed, spoken, retried) enters here. */
    private fun submit(question: String) {
        if (busy) return
        stopSpeaking()
        history.add("user" to question)
        addMessage("user", question)
        persistHistory()
        if (handleLocalAdmin(question)) return

        busy = true
        setComposerEnabled(false)
        updateStatus("WORKING…")
        core.mode = "thinking"
        voice?.working()
        val fromVoice = lastFromVoice
        commandExecutor.execute {
            val outcome = try {
                CommandCenter.handle(applicationContext, question, fromVoice) { p ->
                    runOnUiThread { if (!isDestroyed) { addMessage("system", p); updateStatus("TASK · " + p.uppercase(Locale.ROOT).take(38)) } }
                }
            } catch (t: Throwable) {
                CommandCenter.Outcome.Reply("Something went wrong: ${t.message ?: t.javaClass.simpleName}")
            }
            runOnUiThread { if (!isDestroyed) onOutcome(question, outcome) }
        }
    }

    private fun onOutcome(question: String, o: CommandCenter.Outcome) {
        busy = false
        setComposerEnabled(true)
        core.mode = "ready"
        updateStatus(if (modelReady) "LOCAL MODEL ONLINE · READY" else "LOCAL SYSTEM · READY")
        when (o) {
            is CommandCenter.Outcome.Interrupt -> {
                if (history.isNotEmpty() && history.last().first == "user") { history.removeAt(history.lastIndex); persistHistory() }
                stopEverything(announce = true)
            }
            is CommandCenter.Outcome.Reply -> {
                o.preface?.let { addMessage("system", it) }
                replyLocally(o.text)
                o.ui?.let { handleUi(it) }
            }
            is CommandCenter.Outcome.NotHandled -> {
                o.notice?.let { addMessage("system", it) }
                continueLegacy(question)
            }
        }
    }

    /** Things the command center does not know: legacy Android shortcuts, the phone agent, then chat. */
    private fun continueLegacy(question: String) {
        if (executePhoneAction(question)) { endVoiceTurnSilently(); return }
        if (shouldRunAgent(question)) {
            runAgent(question)
            return
        }

        if (!modelReady) {
            replyLocally("Load your local GGUF model first. Reminders, memory, phone actions and plans work without it.")
            return
        }

        busy = true
        setComposerEnabled(false)
        updateStatus("LOCAL INFERENCE · THINKING")
        core.mode = "thinking"
        val prompt = buildPrompt(question)
        val myGeneration = ++chatGeneration

        worker.execute {
            val answer = try { ModelGate.generate(engine, prompt, 256) }
            catch (e: Throwable) { "ERROR: ${e.message ?: "Generation failed."}" }

            runOnUiThread {
                if (isDestroyed || myGeneration != chatGeneration) return@runOnUiThread   // interrupted: drop the answer
                val cleaned = answer.replace(Regex("<\\|[^>]+\\|>"), "").trim()
                busy = false
                setComposerEnabled(true)
                core.mode = "ready"
                if (cleaned.startsWith("ERROR:")) {
                    addMessage("assistant", cleaned.removePrefix("ERROR:").trim())
                    updateStatus("GENERATION ERROR")
                    endVoiceTurnSilently()
                } else {
                    val finalAnswer = cleaned.ifEmpty { "I couldn't generate a response. Please try again." }
                    history.add("assistant" to finalAnswer)
                    addMessage("assistant", finalAnswer)
                    Sven.conversation.lastReply = finalAnswer
                    persistHistory()
                    updateStatus("LOCAL MODEL ONLINE · READY")
                    speak(finalAnswer)
                }
            }
        }
    }

    /** A turn ended without speech: keep a hands-free conversation going. */
    private fun endVoiceTurnSilently() { if (voiceMode) voice?.replyFinished(200) }

    /** Local-only admin phrases that need dialogs. */
    private fun handleLocalAdmin(raw: String): Boolean {
        val lower = raw.trim().lowercase(Locale.ROOT)
        if (lower in listOf("clear conversation", "clear chat", "delete chat history", "reset conversation")) {
            confirmClearConversation()
            return true
        }
        if (lower in listOf("what is my wake word", "what's my wake word", "show wake phrase")) {
            val phrase = getSharedPreferences("sven_local_state", MODE_PRIVATE).getString("wake_phrase", "Hey 7SVEN") ?: "Hey 7SVEN"
            replyLocally("Your saved wake-phrase preference is: \"$phrase\". Say it with wake detection enabled in CONTROL.")
            return true
        }
        return false
    }

    private fun persistHistory() {
        PersistenceStore.saveHistory(this, history)
    }

    private fun replyLocally(message: String) {
        history.add("assistant" to message)
        addMessage("assistant", message)
        persistHistory()
        if (lastFromVoice || voiceMode) speak(message) else endVoiceTurnSilently()
    }

    private fun restoreModelIfAvailable() {
        val uri = PersistenceStore.getModelUri(this)
        if (uri == null) {
            updateStatus("LOCAL SYSTEM · SELECT MODEL")
            core.mode = "idle"
            return
        }

        val fd = try { contentResolver.openFileDescriptor(uri, "r") }
        catch (_: Exception) { null }

        if (fd == null) {
            updateStatus("MODEL ACCESS EXPIRED · SELECT MODEL")
            core.mode = "idle"
            addMessage("system", "Saved model access is unavailable. Select the GGUF file again.")
            return
        }

        modelButton.isEnabled = false
        updateStatus("RESTORING LOCAL MODEL…")
        core.mode = "thinking"

        worker.execute {
            val result = try { engine.loadModel(fd.fd) }
            catch (e: Throwable) { "ERROR: ${e.message ?: "Model restoration failed."}" }

            runOnUiThread {
                modelButton.isEnabled = true
                if (result == "OK") {
                    modelFd = fd
                    modelUri = uri
                    modelReady = true
                    updateStatus("LOCAL MODEL ONLINE · RESTORED")
                    core.mode = "ready"
                    addMessage("system", "Saved local model restored successfully.")
                } else {
                    try { fd.close() } catch (_: Exception) {}
                    modelReady = false
                    updateStatus("MODEL RESTORE FAILED · SELECT MODEL")
                    core.mode = "idle"
                    addMessage("system", "Automatic model restoration failed. Select the model again.")
                }
            }
        }
    }

    private fun buildPrompt(question: String): String {
        val system = "You are 7SVEN, a local-first personal assistant on Android. " +
            "Be useful, concise, accurate, and honest about uncertainty. " +
            "You cannot directly control the phone unless the app explicitly reports an Android action was launched. " +
            "Never claim you changed a setting or performed an action unless the application confirms it."
        val memory = try { Sven.memory.contextFor(question, 650) } catch (_: Exception) { emptyList() }
        return PromptBuilder.build(system, memory, history.toList()).prompt
    }

    private var lastRole: String? = null

    /** You: a filled bubble. 7SVEN: serif text straight on the page. System events: small margin notes. */
    private fun addMessage(role: String, message: String) {
        emptyState?.let { transcript.removeView(it); emptyState = null }
        val gap = when {
            lastRole == null -> 0
            lastRole != role -> dp(18)
            role == "system" -> dp(6)
            else -> dp(10)
        }
        lastRole = role
        val view: View
        val lp: LinearLayout.LayoutParams
        when (role) {
            "user" -> {
                val r = dp(20).toFloat(); val s = dp(5).toFloat()
                view = TextView(this).apply {
                    text = message
                    textSize = 15.5f
                    setTextColor(p.onAccent)
                    setTextIsSelectable(true)
                    setLineSpacing(dp(2).toFloat(), 1f)
                    maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
                    background = GradientDrawable().apply { setColor(p.accent); cornerRadii = floatArrayOf(r, r, r, r, s, s, r, r) }
                    setPadding(dp(15), dp(10), dp(15), dp(11))
                }
                lp = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = gap }
            }
            "system" -> {
                view = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(View(this@MainActivity).apply { setBackgroundColor(p.line) }, LinearLayout.LayoutParams(dp(2), -1))
                    addView(TextView(this@MainActivity).apply {
                        text = message
                        textSize = 13f
                        setTextColor(muted)
                        setTextIsSelectable(true)
                        setLineSpacing(dp(2).toFloat(), 1f)
                        setPadding(dp(10), dp(1), 0, dp(1))
                    })
                }
                lp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap }
            }
            else -> {
                view = TextView(this).apply {
                    text = message
                    textSize = 16.5f
                    typeface = Typeface.SERIF
                    setTextColor(ink)
                    setTextIsSelectable(true)
                    setLineSpacing(dp(5).toFloat(), 1f)
                }
                lp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap }
            }
        }
        val at = if (::typingRow.isInitialized && typingRow.parent != null) transcript.childCount - 1 else -1
        transcript.addView(view, at, lp)
        scrollToEnd()
    }

    private fun updateStatus(value: String) {
        val text = Ui.friendly(value)
        status.text = text
        if (::typingLabel.isInitialized) typingLabel.text = text
        updateAction()
    }

    /** While work is running the field is locked, a "working" row shows, and the action button becomes Stop. */
    private fun setComposerEnabled(enabled: Boolean) {
        input.isEnabled = enabled
        input.hint = if (enabled) "Ask, or tell me what to do" else "Working… tap stop to interrupt"
        modelButton.isEnabled = enabled
        showTyping(!enabled)
        updateAction()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ================================================================= v1.0-v1.4 glue

    /** One-time work when the process starts: import old notes, recover cut-off tasks, re-arm reminders, start the tick. */
    private fun onAppStartup() {
        commandExecutor.execute {
            try {
                Sven.memory.importLegacy(PersistenceStore.getNotes(applicationContext))
                val recovered = if (Sven.tasks.isRunning) emptyList() else Sven.tasks.recoverInterrupted()
                val report = Sven.reminders.rearmAll()
                report.missed.forEach { Proactive.deliverReminder(applicationContext, it, true) }
                AndroidAlarmArmer.scheduleTick(applicationContext)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    recovered.forEach {
                        addMessage("system", "Task #${it.id} was interrupted (${it.doneCount} of ${it.steps.size} steps done): ${it.goal.take(60)}. Say \"resume task\" or \"cancel task\".")
                    }
                }
            } catch (t: Throwable) {
                AuditLog.add(applicationContext, "startup_error", t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** A notification of ours was tapped: that counts as engagement, so the policy does not back off. */
    private fun noteEngaged(intent: Intent?) {
        val kind = intent?.getStringExtra(EXTRA_ENGAGED_KIND) ?: return
        intent.removeExtra(EXTRA_ENGAGED_KIND)
        commandExecutor.execute { try { Sven.policy.recordEngaged(kind) } catch (_: Exception) {} }
    }

    override fun onStart() {
        super.onStart()
        ProactiveBus.listener = { text ->
            runOnUiThread {
                if (!isDestroyed) {
                    addMessage("assistant", text)
                    if (!busy && !voiceMode) speak(text)
                }
            }
        }
    }

    override fun onStop() {
        ProactiveBus.listener = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val cap = pendingCap
        val retry = pendingRetry
        if (cap != null && retry != null && cap.isGranted(this) && !busy) {
            pendingCap = null; pendingRetry = null
            addMessage("system", "Access granted. Retrying: $retry")
            submit(retry)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            REQUEST_VOICE_PERMISSION ->
                if (granted) startVoiceInput() else addMessage("system", "Microphone permission was denied, so voice cannot listen.")
            REQUEST_RUNTIME -> {
                val retry = pendingRetry
                pendingRetry = null; pendingCap = null
                if (granted && retry != null && !busy) { addMessage("system", "Access granted. Retrying: $retry"); submit(retry) }
                else if (!granted) addMessage("system", "Access denied. You can still use phone numbers instead of names, or grant it later in SETUP.")
            }
        }
    }

    /** Ask for a capability the right way: runtime dialog, or the exact Settings screen. */
    private fun requestCapability(cap: Capability) {
        if (cap.needsRuntimePrompt && Build.VERSION.SDK_INT >= 23) requestPermissions(cap.runtime, REQUEST_RUNTIME)
        else safeStart(cap.settingsIntent(this))
    }

    private fun handleUi(ui: CommandCenter.UiAction) {
        when (ui) {
            CommandCenter.UiAction.ShowMemory -> showMemoryDialog()
            is CommandCenter.UiAction.ConfirmClearMemory -> confirmClearMemory(ui.count)
            is CommandCenter.UiAction.Share -> try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "7SVEN memories").putExtra(Intent.EXTRA_TEXT, ui.text), "Share memories"))
            } catch (_: Exception) { addMessage("system", "No app can receive shared text on this device.") }
            is CommandCenter.UiAction.RequestPermission -> {
                pendingRetry = ui.retry; pendingCap = ui.cap
                addMessage("system", "Needs ${ui.cap.label} access. I will repeat your request once it is allowed.")
                requestCapability(ui.cap)
            }
        }
    }

    private fun confirmClearMemory(count: Int) {
        Ui.dialog(this)
            .setTitle("Erase all $count memories?")
            .setMessage("This permanently removes everything 7SVEN remembers about you from this device. Conversation history is not affected.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Erase") { _, _ ->
                commandExecutor.execute {
                    val n = Sven.memory.clearAll()
                    runOnUiThread { replyLocally("Erased $n memories from this device.") }
                }
            }
            .show()
    }

    /** Everything 7SVEN remembers, with per-item pin and delete. Stored only on this device. */
    private fun showMemoryDialog() {
        val items = Sven.memory.all().sortedWith(compareByDescending<Memory> { it.pinned }.thenByDescending { it.updatedAt })
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        content.addView(TextView(this).apply {
            text = if (items.isEmpty()) "Nothing saved yet. Say: \"remember that my name is Sam\"." else "${items.size} saved · stored only on this device"
            textSize = 11f; setTextColor(cyan); setPadding(0, 0, 0, dp(6))
        })
        val ref = arrayOfNulls<AlertDialog>(1)
        items.take(60).forEach { m ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            row.addView(TextView(this).apply { text = (if (m.pinned) "📌 " else "") + m.text; textSize = 13f; setTextColor(ink) })
            row.addView(TextView(this).apply { text = "#${m.id} · ${m.kind.name.lowercase()} · ${m.source}"; textSize = 10f; setTextColor(muted) })
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
            buttons.addView(makeButton(if (m.pinned) "UNPIN" else "PIN", panel).apply {
                textSize = 9f
                setOnClickListener { Sven.memory.setPinned(m.id, !m.pinned); ref[0]?.dismiss(); showMemoryDialog() }
            }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(5) })
            buttons.addView(makeButton("Forget", p.danger).apply {
                textSize = 9f
                setOnClickListener { Sven.memory.delete(m.id); ref[0]?.dismiss(); showMemoryDialog() }
            }, LinearLayout.LayoutParams(0, dp(34), 1f))
            row.addView(buttons)
            content.addView(row)
        }
        if (items.size > 60) content.addView(TextView(this).apply { text = "…and ${items.size - 60} older ones (say \"show memories about …\")."; textSize = 11f; setTextColor(muted) })
        val b = Ui.dialog(this).setTitle("Memory").setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Close", null)
        if (items.isNotEmpty()) {
            b.setNeutralButton("Share") { _, _ -> handleUi(CommandCenter.UiAction.Share(Sven.memory.exportText())) }
            b.setPositiveButton("Erase all") { _, _ -> confirmClearMemory(items.size) }
        }
        ref[0] = b.show()
    }

    override fun onDestroy() {
        try { voice?.destroy() } catch (_: Exception) {}
        ProactiveBus.listener = null
        commandExecutor.shutdown()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        if (AgentController.listener === agentListener) AgentController.listener = null
        val keepModel = AgentController.isRunning || prefsState.getBoolean("wake_enabled", false)
        worker.execute {
            if (!keepModel) {
                try { engine.unloadModel() } catch (_: Throwable) {}
            }
            try { modelFd?.close() } catch (_: Throwable) {}
        }
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_MODEL = 7107
        private const val REQUEST_VOICE = 7108
        private const val REQUEST_WAKE_PERMISSION = 7109
        private const val REQUEST_VOICE_PERMISSION = 7110
        private const val REQUEST_RUNTIME = 7111
        const val EXTRA_ENGAGED_KIND = "sven_engaged_kind"
    }
}
