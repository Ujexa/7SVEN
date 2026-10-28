package com.sven.assistant

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Process-wide holder for the assistant's persistent services. Safe to call [init] from any entry point. */
internal object Sven {
    @Volatile private var appCtx: Context? = null

    /** Set by MainActivity once the local model is loaded; the agent and agent steps need it. */
    @Volatile var modelReady = false

    fun init(ctx: Context) {
        if (appCtx == null) synchronized(this) { if (appCtx == null) appCtx = ctx.applicationContext }
    }

    private val ctx: Context get() = appCtx ?: error("Sven.init(context) was not called")
    val prefs: SharedPreferences get() = ctx.getSharedPreferences("sven_local_state", Context.MODE_PRIVATE)
    private val dir: File get() = ctx.filesDir

    val memory: MemoryService by lazy {
        MemoryService(
            MemoryStore(dir),
            autoCapture = { prefs.getBoolean("memory_auto", true) },
            setAutoCapture = { prefs.edit().putBoolean("memory_auto", it).apply() },
            enabled = { prefs.getBoolean("memory_enabled", true) }
        )
    }
    val reminders: ReminderService by lazy { ReminderService(ReminderStore(dir), AndroidAlarmArmer(ctx)) }
    val ruleStore: RuleStore by lazy { RuleStore(dir) }
    val ruleEngine: RuleEngine by lazy { RuleEngine(ruleStore) }
    val habits: ActionLog by lazy { ActionLog(dir) }
    val tasks: TaskEngine by lazy { TaskEngine(TaskStore(dir)) }
    val conversation = ConversationState()

    var policySettings: PolicySettings
        get() = PolicySettings(
            enabled = prefs.getBoolean("proactive_enabled", true),
            quietStartMin = prefs.getInt("quiet_start", 22 * 60),
            quietEndMin = prefs.getInt("quiet_end", 7 * 60)
        )
        set(v) { prefs.edit().putBoolean("proactive_enabled", v.enabled).putInt("quiet_start", v.quietStartMin).putInt("quiet_end", v.quietEndMin).apply() }

    val policy: InterruptionPolicy by lazy {
        InterruptionPolicy({ policySettings }, PolicyStore(dir), dndActive = { DeviceInfo.dndActive(ctx) })
    }

    /** Agent steps and agent tasks need the accessibility service, the model, and the agent not switched off. */
    fun agentAvailable(): Boolean =
        SvenAccessibilityService.instance != null && modelReady && prefs.getInt("agent_mode", 1) != 0
}

/** One native-engine call at a time, whoever asks (chat, agent, background). */
internal object ModelGate {
    private val lock = ReentrantLock()
    fun generate(engine: NativeEngine, prompt: String, maxTokens: Int): String = lock.withLock { engine.generate(prompt, maxTokens) }
}
