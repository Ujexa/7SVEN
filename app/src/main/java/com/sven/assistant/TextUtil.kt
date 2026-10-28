package com.sven.assistant

import java.util.Locale

/** Small text helpers shared by memory search, follow-up resolution and parsing. */
internal object TextUtil {
    private val STOP = setOf(
        "a", "an", "the", "is", "are", "was", "were", "am", "be", "been", "i", "me", "my", "mine", "you", "your",
        "do", "does", "did", "of", "to", "in", "on", "at", "for", "and", "or", "it", "its", "that", "this",
        "what", "whats", "who", "where", "when", "which", "how", "about", "with", "have", "has", "had", "tell",
        "know", "remember", "please", "can", "could", "would", "will", "s", "user", "users", "from", "by", "as"
    )

    private val SYNONYMS = mapOf(
        "mom" to listOf("mother", "mum", "mama"), "mum" to listOf("mother", "mom"), "mother" to listOf("mom", "mum"),
        "dad" to listOf("father", "papa"), "father" to listOf("dad"),
        "wife" to listOf("spouse", "partner"), "husband" to listOf("spouse", "partner"),
        "birthday" to listOf("born", "birth"), "born" to listOf("birthday"),
        "live" to listOf("home", "address", "city", "living"), "address" to listOf("home", "live"),
        "home" to listOf("live", "address"), "work" to listOf("job", "employer", "works"),
        "job" to listOf("work", "employer"), "employer" to listOf("work", "job"),
        "allergic" to listOf("allergy", "allergies"), "allergy" to listOf("allergic"),
        "like" to listOf("love", "favorite", "prefer", "likes"), "love" to listOf("like", "favorite"),
        "favorite" to listOf("favourite", "like", "love"), "favourite" to listOf("favorite", "like"),
        "prefer" to listOf("like", "preference"), "food" to listOf("eat", "diet"),
        "dislike" to listOf("hate", "dislikes"), "hate" to listOf("dislike", "dislikes"),
        "phone" to listOf("number", "mobile"), "number" to listOf("phone")
    )

    fun stem(w: String): String {
        var s = w
        if (s.length > 4 && s.endsWith("ies")) return s.dropLast(3) + "y"
        if (s.length > 5 && s.endsWith("ing")) s = s.dropLast(3)
        else if (s.length > 4 && s.endsWith("ed")) s = s.dropLast(2)
        else if (s.length > 4 && s.endsWith("es")) s = s.dropLast(2)
        else if (s.length > 3 && s.endsWith("s") && !s.endsWith("ss")) s = s.dropLast(1)
        return s
    }

    /** Lower-case alphanumeric tokens without stop words, lightly stemmed. */
    fun tokens(text: String): List<String> =
        text.lowercase(Locale.ROOT).replace("'", "").split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() && it !in STOP && (it.length > 1 || it[0].isDigit()) }
            .map { stem(it) }

    /** Query tokens plus synonym expansions (expansions get lower weight in the index). */
    fun expand(tokens: List<String>): List<Pair<String, Double>> {
        val out = LinkedHashMap<String, Double>()
        tokens.forEach { out[it] = 1.0 }
        tokens.forEach { t ->
            SYNONYMS[t]?.forEach { syn -> val s = stem(syn); if (s !in out) out[s] = 0.6 }
        }
        return out.map { it.key to it.value }
    }

    fun norm(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun jaccard(a: Collection<String>, b: Collection<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val sa = a.toSet(); val sb = b.toSet()
        val inter = sa.intersect(sb).size.toDouble()
        return inter / (sa.size + sb.size - inter)
    }

    fun capitalize(s: String): String = if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)

    fun cleanValue(s: String): String =
        s.trim().trimEnd('.', '!', ',', ';', ' ').trim().removeSurrounding("\"").removeSurrounding("'").trim()
}
