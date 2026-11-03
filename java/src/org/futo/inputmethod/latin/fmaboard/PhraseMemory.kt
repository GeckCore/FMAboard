package org.futo.inputmethod.latin.fmaboard

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.mutableStateOf
import org.futo.inputmethod.latin.uix.PreferenceUtils
import java.util.concurrent.Executors

/**
 * Whole phrases the user keeps writing ("nos vemos mañana entonces", "cuando llegues avísame"),
 * learned on the phone from the typing log. When the start of one is typed, the suggestion
 * bar offers the rest of it, to finish with one tap. Long-pressing it stops suggesting it.
 *
 * Nothing leaves the device, and nothing is suggested in password, OTP or incognito fields.
 */
object PhraseMemory {
    private const val MIN_COUNT = 2
    private const val MIN_WORDS = 3
    private const val MAX_WORDS = 8
    // How many typed words, at most, are matched against the start of a phrase
    private const val MAX_CONTEXT = 6
    private const val DAYS = 60
    private const val REBUILD_MS = 10 * 60 * 1000L
    private const val PREF_BLOCKED = "fmaboard_phrase_blocked"

    private val SEGMENT_END = Regex("[.!?¡¿\\n]+")
    private val SPACES = Regex("\\s+")
    // Links, e-mails and long codes/keys are never part of a phrase
    private val NOT_A_WORD = Regex("https?://.*|.*@.*\\..*|.*\\d.*\\d.*\\d.*\\d.*|.{25,}")

    private class Phrase(val words: List<String>, val keys: List<String>, val count: Int) {
        val key get() = keys.joinToString(" ")
    }

    /** What tapping the suggestion does: take [replaceChars] back and type [insert]. */
    class Match(val replaceChars: Int, val insert: String, val key: String) {
        val label get() = insert.trim()
    }

    /** The phrase offered in the suggestion bar right now, or null. */
    val current = mutableStateOf<Match?>(null)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "FMAboardPhrases") }
    @Volatile private var index: Map<String, List<Phrase>> = emptyMap()
    @Volatile private var appContext: Context? = null
    @Volatile private var builtAt = 0L
    @Volatile private var fieldAllowed = false

    @JvmStatic
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        rebuild()
    }

    @JvmStatic
    fun onStartField(editorInfo: EditorInfo?, packageName: String) {
        fieldAllowed = TypingLog.exclusionReason(editorInfo, packageName) == null
        current.value = null
    }

    /** A message was saved to the log: learn from it (at most every few minutes). */
    @JvmStatic
    fun onMessageSaved() {
        if (System.currentTimeMillis() - builtAt > REBUILD_MS) rebuild()
    }

    private fun rebuild() {
        val context = appContext ?: return
        builtAt = System.currentTimeMillis()
        executor.execute {
            try {
                index = build(StyleProfile.ownMessages(context, DAYS), blocked(context))
            } catch (e: Exception) {
                // A damaged log line must not break typing
            }
        }
    }

    private fun blocked(context: Context): Set<String> =
        PreferenceUtils.getDefaultSharedPreferences(context).getStringSet(PREF_BLOCKED, emptySet()) ?: emptySet()

    /** Long press on the suggestion: never offer this phrase again. */
    fun forget(match: Match) {
        current.value = null
        val context = appContext ?: return
        val prefs = PreferenceUtils.getDefaultSharedPreferences(context)
        prefs.edit().putStringSet(PREF_BLOCKED, blocked(context) + match.key).apply()
        index = index.mapValues { (_, list) -> list.filterNot { isBlocked(it.key, setOf(match.key)) } }
    }

    private fun isBlocked(key: String, blocked: Set<String>) =
        blocked.any { key == it || key.startsWith("$it ") }

    private fun keyOf(word: String): String =
        ClipboardNotes.normalize(word).trim { !it.isLetterOrDigit() }.ifEmpty { word }

    /** Each message as runs of words between sentence ends (and links/codes), with their keys. */
    private fun runs(message: String): List<Pair<List<String>, List<String>>> {
        val out = ArrayList<Pair<List<String>, List<String>>>()
        for (segment in message.split(SEGMENT_END)) {
            var words = ArrayList<String>()
            for (w in segment.split(SPACES)) {
                if (w.isEmpty()) continue
                if (NOT_A_WORD.matches(w)) {
                    if (words.size >= MIN_WORDS) out.add(words to words.map(::keyOf))
                    words = ArrayList()
                } else {
                    words.add(w)
                }
            }
            if (words.size >= MIN_WORDS) out.add(words to words.map(::keyOf))
        }
        return out
    }

    /**
     * Phrases written in at least [MIN_COUNT] different messages. Longer ones are only counted
     * when they extend a frequent shorter one, so building stays small even with months of log.
     */
    private fun build(messages: List<String>, blocked: Set<String>): Map<String, List<Phrase>> {
        val runs = messages.map(::runs)
        val result = ArrayList<Phrase>()
        var frequent: Set<String>? = null
        for (n in MIN_WORDS..MAX_WORDS) {
            val counts = HashMap<String, Int>()
            val forms = HashMap<String, List<String>>()
            for (messageRuns in runs) {
                val seen = HashSet<String>()
                for ((words, keys) in messageRuns) for (i in 0..keys.size - n) {
                    if (frequent != null && keys.subList(i, i + n - 1).joinToString(" ") !in frequent) continue
                    val key = keys.subList(i, i + n).joinToString(" ")
                    if (seen.add(key)) counts[key] = (counts[key] ?: 0) + 1
                    forms[key] = words.subList(i, i + n) // the latest way it was written
                }
            }
            val kept = counts.filterValues { it >= MIN_COUNT }.keys
            if (kept.isEmpty()) break
            for (key in kept) {
                if (!isBlocked(key, blocked)) result.add(Phrase(forms[key]!!, key.split(' '), counts[key]!!))
            }
            frequent = kept
        }
        return result.groupBy { it.keys[0] }.mapValues { (_, list) ->
            list.sortedWith(compareByDescending<Phrase> { it.count }.thenByDescending { it.keys.size })
        }
    }

    /** Called on every cursor move in a normal field; cheap when nothing has been learned. */
    @JvmStatic
    fun onSelectionUpdate(ic: InputConnection?, selStart: Int, selEnd: Int) {
        if (!fieldAllowed || ic == null || selStart != selEnd || index.isEmpty()) {
            current.value = null
            return
        }
        // Only at the end of what is being written, not in the middle of a sentence
        val after = ic.getTextAfterCursor(1, 0)
        if (!after.isNullOrEmpty() && !after[0].isWhitespace()) {
            current.value = null
            return
        }
        current.value = ic.getTextBeforeCursor(200, 0)?.let { find(it.toString()) }
    }

    private fun find(before: String): Match? {
        val segment = before.split(SEGMENT_END).last()
        val partial = segment.isNotEmpty() && !segment.last().isWhitespace()
        val tokens = segment.split(SPACES).filter { it.isNotEmpty() }
        if (tokens.size < 2) return null
        val keys = tokens.map(::keyOf)
        val index = index

        // The longest stretch of what was typed that starts a known phrase wins
        for (k in minOf(MAX_CONTEXT, tokens.size) downTo 2) {
            val typed = keys.subList(keys.size - k, keys.size)
            for (p in index[typed[0]] ?: continue) {
                if (p.keys.size <= k) continue
                val fits = typed.indices.all { j ->
                    if (partial && j == k - 1) p.keys[j].startsWith(typed[j]) else p.keys[j] == typed[j]
                }
                if (!fits) continue
                val rest = p.words.subList(k, p.words.size)
                val last = tokens.last()
                val finishesWord = partial && p.keys[k - 1] != typed[k - 1]
                // Worth a tap only if it writes at least two more words
                if (rest.size + (if (finishesWord) 1 else 0) < 2) continue
                val insert = if (partial) {
                    listOf(sameCase(p.words[k - 1], last)) + rest
                } else rest
                return Match(if (partial) last.length else 0, insert.joinToString(" ") + " ", p.key)
            }
        }
        return null
    }

    /** "Ma" typed at the start of a sentence stays capitalized when it becomes "Mañana". */
    private fun sameCase(word: String, typed: String): String = when {
        typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercaseChar() }
        typed.firstOrNull()?.isLowerCase() == true -> word.replaceFirstChar { it.lowercaseChar() }
        else -> word
    }

    /** Tap on the suggestion: write the rest of the phrase. */
    fun accept(ic: InputConnection?) {
        val match = current.value ?: return
        current.value = null
        ic ?: return
        ic.beginBatchEdit()
        ic.finishComposingText()
        if (match.replaceChars > 0) ic.deleteSurroundingText(match.replaceChars, 0)
        ic.commitText(match.insert, 1)
        ic.endBatchEdit()
        // Written by the keyboard, not typed: kept apart in the log like a paste
        TypingLog.onPaste(match.insert)
    }
}
