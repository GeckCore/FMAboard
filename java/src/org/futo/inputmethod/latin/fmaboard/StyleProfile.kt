package org.futo.inputmethod.latin.fmaboard

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * A short description of how the user writes, computed on the phone from the typing log.
 *
 * Only this summary (lengths, habits, frequent words and emojis) is sent with a reply
 * request; the user's messages themselves never leave the device.
 */
object StyleProfile {
    private const val MIN_MESSAGES = 5
    private const val CACHE_MS = 60 * 60 * 1000L

    // Records known to be wrong (see SCHEMA.md, "Data notes").
    private val KNOWN_BAD_IDS = setOf("649ca71f", "69d0d285", "4583fd3a")

    private val STOPWORDS = setOf(
        "de", "la", "que", "el", "en", "y", "a", "los", "se", "del", "las", "un", "por", "con",
        "no", "una", "su", "para", "es", "al", "lo", "como", "más", "mas", "o", "pero", "sus",
        "le", "ha", "me", "si", "sí", "sin", "sobre", "este", "ya", "entre", "cuando", "todo",
        "esta", "ser", "son", "dos", "también", "fue", "había", "era", "muy", "hasta", "desde",
        "mi", "te", "tu", "yo", "eso", "esto", "ese", "hay", "qué", "nos", "les", "ni", "e",
        "the", "to", "and", "is", "it", "of", "i", "you", "in",
    )

    private val LAUGH = Regex("^(ja|je|ji|js|ks)+[jasdk]*$|^x+d+$", RegexOption.IGNORE_CASE)

    @Volatile private var cached: String? = null
    @Volatile private var cachedAt = 0L

    /** Blocking (reads the log files). Null if there isn't enough data yet. */
    fun describe(context: Context): String? {
        val now = System.currentTimeMillis()
        if (cached != null && now - cachedAt < CACHE_MS) return cached
        val profile = compute(context)
        cached = profile
        cachedAt = now
        return profile
    }

    /** What the user wrote themself, from the last [days] days of log. Blocking (reads files). */
    fun ownMessages(context: Context, days: Int = Int.MAX_VALUE): List<String> {
        TypingLogStore.flushBlocking()
        val own = context.packageName
        return TypingLogStore.logFiles(context)
            .filter { it.name.endsWith(TypingLogStore.Stream.MESSAGES.suffix) }
            .sortedBy { it.name }.takeLast(days)
            .flatMap { file -> file.readLines() }
            .mapNotNull { line ->
                val m = try { JSONObject(line) } catch (e: Exception) { return@mapNotNull null }
                if (m.optString("id") in KNOWN_BAD_IDS || m.has("ai_edits")) return@mapNotNull null
                if (m.optString("app") == own) return@mapNotNull null
                // Mostly dictated by voice or pasted: not how the user types
                val stats = m.optJSONObject("stats")
                val notTyped = (stats?.optInt("dictated_chars", 0) ?: 0) + (stats?.optInt("pasted_chars", 0) ?: 0)
                if (notTyped * 2 >= m.optString("text").length && notTyped > 0) return@mapNotNull null
                // Only what the user wrote, not text that was already in the field
                m.optString("text").removePrefix(m.optString("prefilled")).trim()
                    .takeIf { it.isNotEmpty() }
            }
    }

    private fun compute(context: Context): String? {
        val messages = ownMessages(context)
        if (messages.size < MIN_MESSAGES) return null

        val words = messages.flatMap { it.split(Regex("\\s+")) }.filter { it.isNotEmpty() }
        val avgWords = words.size.toDouble() / messages.size
        val letterStarts = messages.filter { it.first().isLetter() }
        val lowercaseStart = letterStarts.count { it.first().isLowerCase() }.toDouble() / maxOf(1, letterStarts.size)
        val endsWithPeriod = messages.count { it.endsWith(".") }.toDouble() / messages.size
        val questions = messages.filter { it.contains('?') }
        val invertedQuestion = questions.count { it.contains('¿') }.toDouble() / maxOf(1, questions.size)
        val withAccents = messages.count { m -> m.any { it in "áéíóúÁÉÍÓÚ" } }.toDouble() / messages.size

        val tokens = words.map { it.lowercase(Locale.ROOT).trim { c -> !c.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
        val laughs = tokens.filter { LAUGH.matches(it) }
        val frequent = tokens
            .filter { it !in STOPWORDS && !LAUGH.matches(it) && it.any { c -> c.isLetter() } }
            .groupingBy { it }.eachCount()
            .filter { it.value >= 2 }
            .entries.sortedByDescending { it.value }
            .take(20).map { it.key }
        val emojis = messages.flatMap { emojisIn(it) }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(8).map { it.key }

        fun pct(v: Double) = "${Math.round(v * 100)}%"
        return buildString {
            appendLine("- Mensajes analizados: ${messages.size}. Longitud media: ${"%.1f".format(Locale.US, avgWords)} palabras.")
            appendLine("- Empieza en minúscula: ${pct(lowercaseStart)} de los mensajes. Termina con punto: ${pct(endsWithPeriod)}.")
            if (questions.isNotEmpty()) appendLine("- Usa '¿' al preguntar: ${pct(invertedQuestion)} de las preguntas.")
            appendLine("- Mensajes con alguna tilde: ${pct(withAccents)}.")
            if (laughs.isNotEmpty()) {
                val top = laughs.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key }
                appendLine("- Se ríe escribiendo: ${top.joinToString(", ")}.")
            }
            if (frequent.isNotEmpty()) appendLine("- Palabras que repite mucho: ${frequent.joinToString(", ")}.")
            if (emojis.isNotEmpty()) appendLine("- Emojis que usa: ${emojis.joinToString(" ")}.") else appendLine("- Casi no usa emojis.")
        }.trim()
    }

    private fun emojisIn(text: String): List<String> {
        val result = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val type = Character.getType(cp)
            if (type == Character.OTHER_SYMBOL.toInt() || type == Character.SURROGATE.toInt()) {
                if (cp > 0x2000) result.add(String(Character.toChars(cp)))
            }
            i += Character.charCount(cp)
        }
        return result
    }
}
