package com.sven.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.exp
import kotlin.math.ln

// ===================================================================================
// v1.0 PERSONAL MEMORY
// Persistent (atomic JSON file), searchable (BM25 + synonyms + recency) and user-controlled
// (remember / forget / list / pin / export / auto-capture switch). Pure JVM, unit-tested.
// ===================================================================================

internal enum class MemoryKind { FACT, PREFERENCE, PERSON, NOTE, EVENT }

internal data class Memory(
    val id: Long,
    val text: String,
    val kind: MemoryKind,
    /** Canonical key (e.g. "name", "home", "person:mother"). A new fact with the same subject replaces the old one. */
    val subject: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long,
    val useCount: Int,
    val pinned: Boolean,
    /** explicit = user asked, auto = learned from a statement, legacy = imported v0.x note */
    val source: String
)

internal data class Candidate(
    val text: String,
    val kind: MemoryKind,
    val subject: String = "",
    val source: String = "explicit"
)

internal sealed class UpsertResult {
    data class Added(val memory: Memory) : UpsertResult()
    data class Updated(val old: Memory, val memory: Memory) : UpsertResult()
    data class Duplicate(val memory: Memory) : UpsertResult()
}

internal interface MemoryRepository {
    fun all(): List<Memory>
    fun get(id: Long): Memory?
    fun upsert(c: Candidate): UpsertResult
    fun delete(id: Long): Boolean
    fun deleteAll(): Int
    fun setPinned(id: Long, pinned: Boolean): Boolean
    fun touch(ids: Collection<Long>)
    fun legacyImported(): Boolean
    fun markLegacyImported()
}

/** JSON-file repository. Every mutation is written through atomically. */
internal class MemoryStore(dir: File, private val now: () -> Long = { System.currentTimeMillis() }) : MemoryRepository {
    private val store = JsonFileStore(File(dir, "sven_memory.json"))
    private val items = ArrayList<Memory>()
    private var nextId = 1L
    private var legacy = false

    init {
        store.read()?.let { root ->
            nextId = root.optLong("nextId", 1L)
            legacy = root.optBoolean("legacyImported", false)
            val arr = root.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                try {
                    items.add(
                        Memory(
                            id = o.getLong("id"), text = o.getString("text"),
                            kind = runCatching { MemoryKind.valueOf(o.optString("kind", "NOTE")) }.getOrDefault(MemoryKind.NOTE),
                            subject = o.optString("subject", ""), createdAt = o.optLong("createdAt"),
                            updatedAt = o.optLong("updatedAt"), lastUsedAt = o.optLong("lastUsedAt"),
                            useCount = o.optInt("useCount"), pinned = o.optBoolean("pinned"),
                            source = o.optString("source", "explicit")
                        )
                    )
                } catch (_: Exception) {
                }
            }
            if (items.isNotEmpty()) nextId = maxOf(nextId, items.maxOf { it.id } + 1)
        }
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("id", it.id).put("text", it.text).put("kind", it.kind.name)
                .put("subject", it.subject).put("createdAt", it.createdAt).put("updatedAt", it.updatedAt)
                .put("lastUsedAt", it.lastUsedAt).put("useCount", it.useCount).put("pinned", it.pinned)
                .put("source", it.source))
        }
        store.write(JSONObject().put("v", 1).put("nextId", nextId).put("legacyImported", legacy).put("items", arr))
    }

    @Synchronized override fun all(): List<Memory> = items.toList()
    @Synchronized override fun get(id: Long): Memory? = items.firstOrNull { it.id == id }

    @Synchronized
    override fun upsert(c: Candidate): UpsertResult {
        val t = now()
        // 1) Same subject: replace (a changed fact supersedes the old one).
        if (c.subject.isNotEmpty()) {
            val idx = items.indexOfFirst { it.subject == c.subject }
            if (idx >= 0) {
                val old = items[idx]
                if (TextUtil.norm(old.text) == TextUtil.norm(c.text)) {
                    items[idx] = old.copy(updatedAt = t); save(); return UpsertResult.Duplicate(items[idx])
                }
                val fresh = old.copy(text = c.text, kind = c.kind, updatedAt = t, source = c.source)
                items[idx] = fresh; save()
                return UpsertResult.Updated(old, fresh)
            }
        }
        // 2) Near-duplicate wording: refresh instead of storing twice.
        val toks = TextUtil.tokens(c.text)
        val dupIdx = items.indexOfFirst { TextUtil.jaccard(TextUtil.tokens(it.text), toks) >= 0.8 }
        if (dupIdx >= 0) {
            items[dupIdx] = items[dupIdx].copy(updatedAt = t); save()
            return UpsertResult.Duplicate(items[dupIdx])
        }
        val m = Memory(nextId++, c.text, c.kind, c.subject, t, t, 0L, 0, false, c.source)
        items.add(m); save()
        return UpsertResult.Added(m)
    }

    @Synchronized override fun delete(id: Long): Boolean {
        val removed = items.removeAll { it.id == id }
        if (removed) save()
        return removed
    }

    @Synchronized override fun deleteAll(): Int {
        val n = items.size; items.clear(); save(); return n
    }

    @Synchronized override fun setPinned(id: Long, pinned: Boolean): Boolean {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return false
        items[i] = items[i].copy(pinned = pinned); save(); return true
    }

    @Synchronized override fun touch(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val t = now()
        for (i in items.indices) if (items[i].id in ids) items[i] = items[i].copy(lastUsedAt = t, useCount = items[i].useCount + 1)
        save()
    }

    @Synchronized override fun legacyImported(): Boolean = legacy
    @Synchronized override fun markLegacyImported() { legacy = true; save() }
}

/** BM25 over memory text + subject with synonym expansion, recency and pin boosts. */
internal object MemoryIndex {
    data class Hit(val memory: Memory, val score: Double)

    fun search(query: String, memories: List<Memory>, now: Long, limit: Int = 6, minScore: Double = 0.5): List<Hit> {
        val q = TextUtil.expand(TextUtil.tokens(query))
        if (q.isEmpty() || memories.isEmpty()) return emptyList()
        val docs = memories.map { m -> m to TextUtil.tokens(m.text + " " + m.subject.replace(':', ' ').replace('_', ' ')) }
        val n = docs.size.toDouble()
        val avg = docs.sumOf { it.second.size }.toDouble().coerceAtLeast(1.0) / n
        val df = HashMap<String, Int>()
        docs.forEach { (_, toks) -> toks.toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
        val k1 = 1.2; val b = 0.75
        val hits = ArrayList<Hit>()
        for ((m, toks) in docs) {
            val tf = HashMap<String, Int>()
            toks.forEach { tf[it] = (tf[it] ?: 0) + 1 }
            var score = 0.0
            for ((term, weight) in q) {
                val f = tf[term] ?: continue
                val d = df[term] ?: 1
                val idf = ln(1.0 + (n - d + 0.5) / (d + 0.5))
                score += weight * idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * toks.size / avg))
            }
            if (score <= 0.0) continue
            val ageDays = ((now - maxOf(m.updatedAt, m.lastUsedAt)).coerceAtLeast(0L)) / 86_400_000.0
            score *= 1.0 + 0.15 * exp(-ageDays / 60.0)
            if (m.pinned) score *= 1.3
            if (score >= minScore) hits.add(Hit(m, score))
        }
        return hits.sortedByDescending { it.score }.take(limit)
    }
}

/** Turns first-person statements into canonical memories. */
internal object MemoryExtractor {
    private val sensitive = Regex(
        "\\b(password|passcode|passphrase|pin(?: code| number)?|cvv|cvc|card number|credit card|debit card|ssn|social security|" +
            "one[- ]time (?:code|password)|otp|api key|secret key|private key|seed phrase|recovery phrase|iban|account number|routing number)\\b|\\b\\d{12,}\\b",
        RegexOption.IGNORE_CASE
    )

    fun isSensitive(text: String) = sensitive.containsMatchIn(text)

    private const val I = "(?:i|i'm|im|i am)"
    private val relations = "mom|mum|mother|dad|father|wife|husband|partner|girlfriend|boyfriend|brother|sister|son|daughter|boss|manager|friend|dog|cat|roommate"

    private data class Rule(val re: Regex, val build: (MatchResult) -> Candidate?)

    private fun fact(text: String, subject: String, kind: MemoryKind = MemoryKind.FACT, source: String) =
        Candidate(text, kind, subject, source)

    private fun rules(source: String): List<Rule> = listOf(
        Rule(Regex("^(?:that )?my name is (.+)$", RegexOption.IGNORE_CASE)) {
            fact("The user's name is ${TextUtil.cleanValue(it.groupValues[1])}.", "name", source = source)
        },
        Rule(Regex("^(?:that )?(?:you can |please )?call me (.+)$", RegexOption.IGNORE_CASE)) {
            fact("The user's name is ${TextUtil.cleanValue(it.groupValues[1])}.", "name", source = source)
        },
        Rule(Regex("^(?:that )?$I allergic to (.+)$", RegexOption.IGNORE_CASE)) {
            val v = TextUtil.cleanValue(it.groupValues[1])
            fact("The user is allergic to $v.", "allergy:" + TextUtil.norm(v), source = source)
        },
        Rule(Regex("^(?:that )?(?:i live in|i'm living in|i am living in|i'm based in|i am based in) (.+)$", RegexOption.IGNORE_CASE)) {
            fact("The user lives in ${TextUtil.cleanValue(it.groupValues[1])}.", "home", source = source)
        },
        Rule(Regex("^(?:that )?my (?:home )?address is (.+)$", RegexOption.IGNORE_CASE)) {
            fact("The user's home address is ${TextUtil.cleanValue(it.groupValues[1])}.", "home", source = source)
        },
        Rule(Regex("^(?:that )?$I from (.+)$", RegexOption.IGNORE_CASE)) {
            fact("The user is from ${TextUtil.cleanValue(it.groupValues[1])}.", "hometown", source = source)
        },
        Rule(Regex("^(?:that )?i work (?:at|for) (.+)$", RegexOption.IGNORE_CASE)) {
            fact("The user works at ${TextUtil.cleanValue(it.groupValues[1])}.", "employer", source = source)
        },
        Rule(Regex("^(?:that )?i work as (?:a |an )?(.+)$", RegexOption.IGNORE_CASE)) {
            fact("The user works as a ${TextUtil.cleanValue(it.groupValues[1])}.", "job", source = source)
        },
        Rule(Regex("^(?:that )?(?:my birthday is|my birthday's|i was born (?:on|in)) (.+)$", RegexOption.IGNORE_CASE)) {
            fact("The user's birthday is ${TextUtil.cleanValue(it.groupValues[1])}.", "birthday", source = source)
        },
        Rule(Regex("^(?:that )?my ($relations)(?:'s name)? is (.+)$", RegexOption.IGNORE_CASE)) {
            val rel = it.groupValues[1].lowercase().let { r -> if (r == "mum") "mom" else if (r == "mother") "mom" else if (r == "father") "dad" else r }
            val v = TextUtil.cleanValue(it.groupValues[2])
            val subj = if (rel == "friend") "person:friend:" + TextUtil.norm(v) else "person:$rel"
            fact("The user's $rel is named $v.", subj, MemoryKind.PERSON, source)
        },
        Rule(Regex("^(?:that )?my favou?rite (.{2,30}?) (?:is|are) (.+)$", RegexOption.IGNORE_CASE)) {
            val what = TextUtil.cleanValue(it.groupValues[1]); val v = TextUtil.cleanValue(it.groupValues[2])
            fact("The user's favorite $what is $v.", "favorite:" + TextUtil.norm(what), MemoryKind.PREFERENCE, source)
        },
        Rule(Regex("^(?:that )?i (?:really |absolutely |just )?(like|love|enjoy|adore) (.+)$", RegexOption.IGNORE_CASE)) {
            val v = TextUtil.cleanValue(it.groupValues[2])
            fact("The user ${it.groupValues[1].lowercase()}s $v.", "likes:" + TextUtil.norm(v), MemoryKind.PREFERENCE, source)
        },
        Rule(Regex("^(?:that )?i (?:really )?prefer (.+)$", RegexOption.IGNORE_CASE)) {
            val v = TextUtil.cleanValue(it.groupValues[1])
            fact("The user prefers $v.", "prefers:" + TextUtil.norm(v), MemoryKind.PREFERENCE, source)
        },
        Rule(Regex("^(?:that )?i (?:really )?(?:hate|dislike|can't stand|cannot stand) (.+)$", RegexOption.IGNORE_CASE)) {
            val v = TextUtil.cleanValue(it.groupValues[1])
            fact("The user dislikes $v.", "dislikes:" + TextUtil.norm(v), MemoryKind.PREFERENCE, source)
        },
        Rule(Regex("^(?:that )?$I (vegetarian|vegan|pescatarian|diabetic|lactose intolerant|gluten[- ]free|left[- ]handed|right[- ]handed)$", RegexOption.IGNORE_CASE)) {
            val v = it.groupValues[1].lowercase()
            fact("The user is $v.", "trait:$v", source = source)
        },
        Rule(Regex("^(?:that )?i (?:usually |always )?(wake up|get up|go to bed|go to sleep) (?:at|around) (.+)$", RegexOption.IGNORE_CASE)) {
            val verb = it.groupValues[1].lowercase().replace("get up", "wake up").replace("go to sleep", "go to bed")
            fact("The user usually ${verb}s at ${TextUtil.cleanValue(it.groupValues[2])}.".replace("wake ups", "wakes up").replace("go to beds", "goes to bed"), "routine:" + verb.replace(' ', '_'), source = source)
        }
    )

    private val pronounOnly = Regex("^(it|that|this|them|you|him|her|us|me|those|these|there|here|so|too|more|one|something|anything|everything)$", RegexOption.IGNORE_CASE)

    /** Statement -> candidate using the pattern table. */
    fun classify(body: String, source: String): Candidate? {
        val b = body.trim()
        for (r in rules(source)) {
            val m = r.re.find(b) ?: continue
            val c = r.build(m) ?: continue
            return c
        }
        return null
    }

    /** Conservative detection for implicit learning: narrow patterns, short statements, no questions. */
    fun autoCandidate(utterance: String): Candidate? {
        val t = utterance.trim().trimEnd('.', '!')
        if (t.length < 8 || t.length > 140 || t.contains('?')) return null
        if (isSensitive(t)) return null
        val first = t.substringBefore(' ').lowercase()
        if (first in setOf("what", "who", "why", "how", "when", "where", "is", "are", "do", "does", "can", "could", "would", "please", "remind", "set", "open", "call", "text")) return null
        val c = classify(t, "auto") ?: return null
        val value = c.text.substringAfterLast(' ').trim('.')
        if (pronounOnly.matches(value)) return null
        if (c.text.split(' ').size > 16) return null
        return c
    }

    /** Explicit "remember ..." body: structured if recognised, otherwise a free note. */
    fun explicit(body: String): Candidate {
        val b = body.trim().trimEnd('.')
        classify(b, "explicit")?.let { return it }
        Regex("^(?:that )?my (.{2,40}?) (?:is|are|was) (.+)$", RegexOption.IGNORE_CASE).find(b)?.let {
            val what = TextUtil.cleanValue(it.groupValues[1]); val v = TextUtil.cleanValue(it.groupValues[2])
            return Candidate("The user's $what is $v.", MemoryKind.FACT, "my:" + TextUtil.norm(what), "explicit")
        }
        return Candidate(TextUtil.capitalize(b.removePrefix("that ").trim()) + ".", MemoryKind.NOTE, "", "explicit")
    }
}

internal sealed class MemoryIntent {
    data class Remember(val body: String) : MemoryIntent()
    data class Forget(val target: String?) : MemoryIntent()      // null = the last memory touched
    data class ForgetId(val id: Long) : MemoryIntent()
    object ForgetAll : MemoryIntent()
    data class Show(val topic: String?) : MemoryIntent()
    data class Ask(val query: String, val explicitRecall: Boolean) : MemoryIntent()
    data class Pin(val id: Long?, val pinned: Boolean) : MemoryIntent()
    object Export : MemoryIntent()
    data class SetAuto(val on: Boolean) : MemoryIntent()
    object RememberLastReply : MemoryIntent()
}

internal object MemoryCommands {
    private val I = RegexOption.IGNORE_CASE

    fun parse(raw: String): MemoryIntent? {
        val t = raw.trim().replace(Regex("[.!]+$"), "")
        val l = t.lowercase()

        if (l in setOf("remember that", "remember this", "save that", "save this", "note that", "keep that")) return MemoryIntent.RememberLastReply

        Regex("^(?:please |pls )?(?:remember|memorize|memorise|keep in mind|don't forget|do not forget|make a note|note down|save note|note)(?: that| this)?(?:[:,]| ) *(.+)$", I).find(t)?.let {
            val body = it.groupValues[1].trim()
            if (body.isNotEmpty()) return MemoryIntent.Remember(body)
        }
        Regex("^note: *(.+)$", I).find(t)?.let { return MemoryIntent.Remember(it.groupValues[1].trim()) }

        if (Regex("^(?:forget|delete|erase|clear|wipe)(?: all| everything)?(?: (?:my|the|your))?(?: saved)? ?(?:notes|memories|memory)$", I).matches(t) ||
            Regex("^forget everything(?: you know)?(?: about me)?$", I).matches(t) ||
            Regex("^forget all(?: notes| memories)?$", I).matches(t) || l == "clear memory"
        ) return MemoryIntent.ForgetAll

        if (Regex("^(?:forget|delete|remove) (?:about )?(?:that|it|this|the last one)$", I).matches(t)) return MemoryIntent.Forget(null)
        Regex("^(?:forget|delete|remove|unpin)? ?(?:memory |note )?#(\\d+)$", I).find(t)?.let {
            if (l.startsWith("unpin")) return MemoryIntent.Pin(it.groupValues[1].toLong(), false)
            if (l.startsWith("forget") || l.startsWith("delete") || l.startsWith("remove")) return MemoryIntent.ForgetId(it.groupValues[1].toLong())
        }
        Regex("^pin (?:memory |note )?#?(\\d+)$", I).find(t)?.let { return MemoryIntent.Pin(it.groupValues[1].toLong(), true) }
        Regex("^unpin (?:memory |note )?#?(\\d+)$", I).find(t)?.let { return MemoryIntent.Pin(it.groupValues[1].toLong(), false) }
        if (l == "pin that" || l == "pin it") return MemoryIntent.Pin(null, true)
        Regex("^(?:forget|delete|remove|erase) (?:the )?(?:memory|note) (?:about |that |of |on )?(.+)$", I).find(t)?.let {
            return MemoryIntent.Forget(it.groupValues[1].trim())
        }
        Regex("^forget (?:about |that |what i (?:said|told you) about )?(.{2,80})$", I).find(t)?.let {
            val target = it.groupValues[1].trim()
            if (!Regex("^(?:the|my) (?:conversation|chat|history)$", I).matches(target)) return MemoryIntent.Forget(target)
        }

        if (l in setOf("show notes", "list notes", "my notes", "read my notes", "show memory", "show memories", "list memories", "my memories",
                "what is in my memory", "what's in my memory", "what do you know about me", "what do you remember about me",
                "what do you remember", "what do you know", "open memory", "show my memories", "memory"))
            return MemoryIntent.Show(null)
        Regex("^(?:show|list|read)(?: me)?(?: all)?(?: my)? (?:saved )?(?:notes|memories)(?: (?:about|on) (.+))?$", I).find(t)?.let {
            return MemoryIntent.Show(it.groupValues[1].ifBlank { null })
        }
        Regex("^what do you (?:know|remember) about (?!me$)(.+)$", I).find(t)?.let { return MemoryIntent.Show(it.groupValues[1].trim()) }
        Regex("^what did i (?:tell|say to) you about (.+)$", I).find(t)?.let { return MemoryIntent.Show(it.groupValues[1].trim()) }

        if (Regex("^(?:export|share|back ?up) (?:my |all )?(?:memories|notes|memory)$", I).matches(t)) return MemoryIntent.Export
        Regex("^(?:turn|switch) (on|off) (?:automatic |auto )?(?:memory|learning)(?: capture)?$", I).find(t)?.let {
            return MemoryIntent.SetAuto(it.groupValues[1].lowercase() == "on")
        }
        if (l == "stop learning about me" || l == "stop remembering things automatically") return MemoryIntent.SetAuto(false)

        Regex("^do you (?:remember|know) (.+)$", I).find(t)?.let { return MemoryIntent.Ask(it.groupValues[1].trim(), true) }
        Regex("^(?:what(?:'s| is| was| are)|who(?:'s| is)|where(?:'s| is)|when(?:'s| is| was)) my (.+)$", I).find(t)?.let {
            return MemoryIntent.Ask(it.groupValues[1].trim(), false)
        }
        if (Regex("^where do i (?:live|work)$", I).matches(t)) return MemoryIntent.Ask(l.removePrefix("where do i "), false)
        if (l == "who am i" || l == "what is my name" || l == "what's my name") return MemoryIntent.Ask("name", false)
        if (Regex("^what am i allergic to$", I).matches(t)) return MemoryIntent.Ask("allergic", false)
        Regex("^what do i (like|love|prefer|hate|dislike)$", I).find(t)?.let { return MemoryIntent.Ask(it.groupValues[1], false) }
        return null
    }
}

internal sealed class MemoryAction {
    object ShowList : MemoryAction()
    data class ConfirmClearAll(val count: Int) : MemoryAction()
    data class Share(val text: String) : MemoryAction()
}

internal data class MemoryReply(val text: String, val action: MemoryAction? = null)

/** Orchestrates commands, auto-learning and retrieval for prompts. */
internal class MemoryService(
    private val repo: MemoryRepository,
    private val autoCapture: () -> Boolean,
    private val setAutoCapture: (Boolean) -> Unit,
    private val enabled: () -> Boolean = { true },
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile var lastTouchedId: Long? = null
        private set

    fun count() = repo.all().size
    fun all() = repo.all()
    fun delete(id: Long) = repo.delete(id)
    fun setPinned(id: Long, p: Boolean) = repo.setPinned(id, p)
    fun clearAll() = repo.deleteAll().also { lastTouchedId = null }

    fun importLegacy(notes: List<String>) {
        if (repo.legacyImported()) return
        notes.map { it.trim() }.filter { it.isNotEmpty() }.forEach {
            repo.upsert(Candidate(TextUtil.capitalize(it.removeSuffix(".")) + ".", MemoryKind.NOTE, "", "legacy"))
        }
        repo.markLegacyImported()
    }

    fun describe(m: Memory): String = "#${m.id} ${m.text}" + if (m.pinned) " 📌" else ""

    fun exportText(): String = repo.all().joinToString("\n") { "- ${it.text}" }

    /** Returns a reply when [text] is a memory command, else null (so other handlers can try). */
    fun handle(text: String, lastAssistantReply: String? = null): MemoryReply? {
        val intent = MemoryCommands.parse(text) ?: return null
        if (!enabled() && intent !is MemoryIntent.SetAuto) {
            return if (intent is MemoryIntent.Ask && !intent.explicitRecall) null
            else MemoryReply("Memory is turned off. Say \"turn on memory\" or enable it in CONTROL.")
        }
        return when (intent) {
            is MemoryIntent.Remember -> remember(intent.body)
            MemoryIntent.RememberLastReply -> {
                val r = lastAssistantReply?.trim().orEmpty()
                if (r.isEmpty()) MemoryReply("There is nothing recent to save.") else remember(r.take(240))
            }
            is MemoryIntent.Forget -> forget(intent.target)
            is MemoryIntent.ForgetId -> {
                val m = repo.get(intent.id)
                if (m == null) MemoryReply("I have no memory #${intent.id}.")
                else { repo.delete(m.id); MemoryReply("Forgot: ${m.text}") }
            }
            MemoryIntent.ForgetAll -> {
                val n = repo.all().size
                if (n == 0) MemoryReply("There is nothing saved to forget.") else MemoryReply("This will erase all $n saved memories.", MemoryAction.ConfirmClearAll(n))
            }
            is MemoryIntent.Show -> show(intent.topic)
            is MemoryIntent.Ask -> ask(intent)
            is MemoryIntent.Pin -> {
                val id = intent.id ?: lastTouchedId
                if (id == null || !repo.setPinned(id, intent.pinned)) MemoryReply("Tell me which memory, e.g. \"pin #3\".")
                else MemoryReply(if (intent.pinned) "Pinned #$id. Pinned memories are always available to me." else "Unpinned #$id.")
            }
            MemoryIntent.Export -> {
                val s = exportText()
                if (s.isEmpty()) MemoryReply("There is nothing saved to export.") else MemoryReply("Opening share sheet with ${repo.all().size} memories.", MemoryAction.Share(s))
            }
            is MemoryIntent.SetAuto -> {
                setAutoCapture(intent.on)
                MemoryReply(if (intent.on) "Automatic learning is on. I save only clear statements like \"my name is …\" and tell you each time."
                else "Automatic learning is off. I will only remember what you tell me to.")
            }
        }
    }

    private fun remember(body: String): MemoryReply {
        if (MemoryExtractor.isSensitive(body)) {
            return MemoryReply("I don't store passwords, PINs, card numbers or similar secrets. Use a password manager for those.")
        }
        val cand = MemoryExtractor.explicit(body)
        return when (val r = repo.upsert(cand)) {
            is UpsertResult.Added -> { lastTouchedId = r.memory.id; MemoryReply("Saved #${r.memory.id}: ${r.memory.text}") }
            is UpsertResult.Updated -> { lastTouchedId = r.memory.id; MemoryReply("Updated #${r.memory.id}. Before: ${r.old.text} Now: ${r.memory.text}") }
            is UpsertResult.Duplicate -> { lastTouchedId = r.memory.id; MemoryReply("I already had that (#${r.memory.id}).") }
        }
    }

    private fun forget(target: String?): MemoryReply {
        if (target == null) {
            val id = lastTouchedId ?: return MemoryReply("Which one? Say \"forget #3\" or \"forget my …\", or open MEMORY.")
            val m = repo.get(id) ?: return MemoryReply("That memory is already gone.")
            repo.delete(id); lastTouchedId = null
            return MemoryReply("Forgot: ${m.text}")
        }
        val hits = MemoryIndex.search(target.replace(Regex("^(?:my|the)\\s+"), ""), repo.all(), now(), 4, 0.4)
        if (hits.isEmpty()) return MemoryReply("I have nothing saved about \"$target\".")
        val top = hits[0]
        val clear = hits.size == 1 || top.score >= hits[1].score * 1.6
        if (clear) {
            repo.delete(top.memory.id); lastTouchedId = null
            return MemoryReply("Forgot #${top.memory.id}: ${top.memory.text}")
        }
        return MemoryReply("More than one match. Say \"forget #id\" for the one you mean:\n" + hits.joinToString("\n") { describe(it.memory) })
    }

    private fun show(topic: String?): MemoryReply {
        if (topic == null) {
            val all = repo.all()
            if (all.isEmpty()) return MemoryReply("I have nothing saved yet. Try: \"remember that my name is Sam\".")
            val sorted = all.sortedWith(compareByDescending<Memory> { it.pinned }.thenByDescending { it.updatedAt })
            return MemoryReply("I remember ${all.size} things (newest first):\n" + sorted.take(12).joinToString("\n") { describe(it) } +
                (if (all.size > 12) "\n…and ${all.size - 12} more in MEMORY." else ""), MemoryAction.ShowList)
        }
        val hits = MemoryIndex.search(topic, repo.all(), now(), 8, 0.4)
        if (hits.isEmpty()) return MemoryReply("I have nothing saved about \"$topic\".")
        repo.touch(hits.map { it.memory.id }); lastTouchedId = hits[0].memory.id
        return MemoryReply("About \"$topic\":\n" + hits.joinToString("\n") { describe(it.memory) })
    }

    private fun ask(i: MemoryIntent.Ask): MemoryReply? {
        val hits = MemoryIndex.search(i.query, repo.all(), now(), 3, 0.6)
        if (hits.isEmpty()) {
            // Not a memory question (e.g. "what's my battery") unless the user explicitly asked "do you remember".
            return if (i.explicitRecall) MemoryReply("I don't have that saved. Tell me and I'll remember it.") else null
        }
        repo.touch(hits.map { it.memory.id }); lastTouchedId = hits[0].memory.id
        val best = hits[0]
        val extra = hits.drop(1).filter { it.score >= best.score * 0.7 }
        return MemoryReply((listOf(best) + extra).joinToString(" ") { it.memory.text.replace("The user's", "Your").replace("The user is", "You are").replace("The user", "You") })
    }

    /** Statement-level learning. Returns a notice for the chat, or null if nothing was stored. */
    fun autoLearn(utterance: String): String? {
        if (!enabled() || !autoCapture()) return null
        val c = MemoryExtractor.autoCandidate(utterance) ?: return null
        return when (val r = repo.upsert(c)) {
            is UpsertResult.Added -> { lastTouchedId = r.memory.id; "Remembered #${r.memory.id}: ${r.memory.text} (say \"forget that\" to undo)" }
            is UpsertResult.Updated -> { lastTouchedId = r.memory.id; "Updated #${r.memory.id}: ${r.memory.text} (was: ${r.old.text})" }
            is UpsertResult.Duplicate -> null
        }
    }

    /** Short lines relevant to [query] for prompts, within [maxChars]. Pinned items and the user's name always qualify. */
    fun contextFor(query: String, maxChars: Int): List<String> {
        if (!enabled() || maxChars < 40) return emptyList()
        val all = repo.all()
        if (all.isEmpty()) return emptyList()
        val chosen = LinkedHashMap<Long, Memory>()
        all.filter { it.subject == "name" }.forEach { chosen[it.id] = it }
        all.filter { it.pinned }.take(3).forEach { chosen[it.id] = it }
        MemoryIndex.search(query, all, now(), 6, 0.9).forEach { if (!chosen.containsKey(it.memory.id)) chosen[it.memory.id] = it.memory }
        val out = ArrayList<String>(); var used = 0
        for (m in chosen.values) {
            val line = m.text.removeSuffix(".").take(160)
            if (used + line.length + 3 > maxChars) break
            out.add(line); used += line.length + 3
        }
        repo.touch(chosen.values.filter { it.text.removeSuffix(".").take(160) in out }.map { it.id })
        return out
    }
}
