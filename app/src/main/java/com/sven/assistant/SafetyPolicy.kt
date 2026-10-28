package com.sven.assistant

import android.content.Context

/** Decides whether an agent action runs, needs a human tap, or is refused. */
internal object SafetyPolicy {
    enum class Level { ALLOW, CONFIRM, BLOCK }

    data class Decision(val level: Level, val reason: String = "")

    private val hardBlock = Regex(
        "factory data reset|erase all data|reset phone|reset all settings|wipe|format (sd|storage)",
        RegexOption.IGNORE_CASE
    )

    private val risky = Regex(
        """\b(send|pay|payment|purchase|buy|checkout|order|delete|remove|erase|clear data|uninstall|install|transfer|withdraw|confirm|submit|post|publish|share|sign out|log out|logout|reset|subscribe|donate|accept|agree|allow|grant|unlock|disable|forget|unpair|call|dial)\b""",
        RegexOption.IGNORE_CASE
    )

    private val sensitivePackages = Regex(
        "bank|paypal|wallet|finance|crypto|coinbase|binance|venmo|upi|gpay|spay|authenticator|lastpass|1password|bitwarden|keepass|dashlane|packageinstaller|com\\.android\\.vending",
        RegexOption.IGNORE_CASE
    )

    private val injection = Regex(
        "ignore (all |any )?(previous|prior|above) (instructions|prompts)|disregard (the |your )?(instructions|rules)|new instructions|system prompt|you are now|reveal your",
        RegexOption.IGNORE_CASE
    )

    fun evaluate(ctx: Context, a: AgentAction, snap: ScreenSnapshot, @Suppress("UNUSED_PARAMETER") goal: String): Decision {
        if (a.type in setOf("done", "fail", "ask_user", "back", "home")) return Decision(Level.ALLOW)

        if (snap.pkg == ctx.packageName && a.type != "tool") {
            return Decision(Level.BLOCK, "the agent may not operate 7SVEN's own screens")
        }

        val node = a.id?.let { snap.find(it) }
        val label = node?.label.orEmpty()

        if (a.type == "type" && node?.password == true) {
            return Decision(Level.BLOCK, "typing into password fields is not allowed")
        }
        if ((a.type == "tap" || a.type == "submit") && hardBlock.containsMatchIn(label)) {
            return Decision(Level.BLOCK, "destructive control \"$label\"")
        }
        if (a.type == "tool") {
            // Tools that read personal data into the model's context need the person's approval.
            if (a.name.orEmpty().lowercase() in setOf("read_calendar", "briefing")) {
                return Decision(Level.CONFIRM, "reads your calendar and reminders into the local model")
            }
            return Decision(Level.ALLOW)
        }

        if (sensitivePackages.containsMatchIn(snap.pkg)) {
            return Decision(Level.CONFIRM, "sensitive app (${snap.pkg})")
        }
        if ((a.type == "tap" || a.type == "submit") && risky.containsMatchIn(label)) {
            return Decision(Level.CONFIRM, "\"$label\" may be hard to undo")
        }
        val screenText = snap.nodes.joinToString(" ") { it.label }
        if (injection.containsMatchIn(screenText)) {
            return Decision(Level.CONFIRM, "the screen contains text that looks like instructions to an AI")
        }
        return Decision(Level.ALLOW)
    }
}
