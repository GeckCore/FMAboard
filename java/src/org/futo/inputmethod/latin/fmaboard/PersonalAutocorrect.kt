package org.futo.inputmethod.latin.fmaboard

import android.content.Context
import android.view.inputmethod.EditorInfo
import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Personal autocorrect rules learned on the phone from how the user corrects things.
 *
 * - Keep: the user undid an autocorrection with backspace ("mua" -> "MUA" -> "mua"), so that
 *   typed word is never autocorrected again.
 * - Fix: the user reopened a word that autocorrect left alone and fixed it by hand
 *   ("hecvo" -> "hecho"). After [FIX_MIN_COUNT] times it is autocorrected automatically.
 *
 * Nothing is learned in password, OTP, non-text or incognito fields. Rules stay on the
 * device in files/fmaboard_autocorrect.json.
 */
object PersonalAutocorrect {
    private const val FILE_NAME = "fmaboard_autocorrect.json"
    const val FIX_MIN_COUNT = 2
    // A typed word that is a real dictionary word needs more evidence before it is changed
    private const val FIX_MIN_COUNT_VALID_WORD = 4

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "FMAboardAutocorrect") }

    @Volatile private var file: File? = null
    private val keep = HashMap<String, Int>()
    private val fixes = HashMap<String, HashMap<String, Int>>()

    // Suggestions are adjusted on a worker thread
    @Volatile private var fieldAllowed = false
    private var resumedWord: String? = null

    @Volatile private var loaded = false

    @JvmStatic
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, FILE_NAME)
        file = f
        executor.execute { load(f) }
    }

    /** For the settings screen, which may open before the keyboard has loaded the rules. */
    fun rulesBlocking(context: Context): Rules {
        init(context)
        executor.submit {}.get()
        return rules()
    }

    @JvmStatic
    fun onStartField(editorInfo: EditorInfo?, packageName: String) {
        val allowed = TypingLog.exclusionReason(editorInfo, packageName) == null
        if (allowedBeforeSuspend != null) allowedBeforeSuspend = allowed else fieldAllowed = allowed
        resumedWord = null
    }

    // Saved while the keyboard types into one of its own boxes (clipboard search, notes…)
    @Volatile private var allowedBeforeSuspend: Boolean? = null

    @JvmStatic
    fun setSuspended(value: Boolean) {
        if (value) {
            if (allowedBeforeSuspend == null) allowedBeforeSuspend = fieldAllowed
            fieldAllowed = false
        } else {
            allowedBeforeSuspend?.let { fieldAllowed = it }
            allowedBeforeSuspend = null
        }
        resumedWord = null
    }

    // --- Learning ---------------------------------------------------------------------------

    /** Backspace right after an autocorrection put back what the user typed. */
    @JvmStatic
    fun onAutocorrectReverted(typed: String, committed: String) {
        if (!fieldAllowed) return
        // Case-only changes count too ("mua" -> "MUA")
        val key = typed.trim().lowercase(Locale.ROOT)
        if (key.isEmpty() || typed.trim() == committed.trim()) return
        // Undoing a text shortcut is not a correction to learn from
        if (TextShortcuts.isExpansion(committed.trim())) return
        synchronized(this) {
            keep[key] = (keep[key] ?: 0) + 1
            fixes.remove(key)
        }
        TypingLog.onAutocorrectReverted(typed, committed)
        save()
    }

    /** A committed word was reopened for editing. */
    @JvmStatic
    fun onWordResumed(word: CharSequence?) {
        resumedWord = if (fieldAllowed) word?.toString()?.trim()?.takeIf { it.isNotEmpty() } else null
    }

    /** Text committed to the editor: if it ends editing a reopened word, maybe learn a fix. */
    @JvmStatic
    fun onCommit(text: CharSequence?) {
        val before = resumedWord ?: return
        val after = text?.toString()?.trim() ?: return
        if (after.isEmpty()) return
        resumedWord = null
        if (!isPlausibleFix(before, after)) return
        val key = before.lowercase(Locale.ROOT)
        val fix = after.lowercase(Locale.ROOT)
        synchronized(this) {
            val counts = fixes.getOrPut(key) { HashMap() }
            counts[fix] = (counts[fix] ?: 0) + 1
            // Fixing a word by hand means the user does want it corrected
            keep.remove(key)
            keep.remove(fix)
        }
        save()
    }

    private fun isPlausibleFix(before: String, after: String): Boolean {
        val a = before.lowercase(Locale.ROOT)
        val b = after.lowercase(Locale.ROOT)
        if (a == b || a.length < 2 || b.length < 2) return false
        if (!a.all { it.isLetter() } || !b.all { it.isLetter() }) return false
        // Finishing or trimming a word is not fixing a typo
        if (b.startsWith(a) || a.startsWith(b)) return false
        val distance = damerau(a, b)
        return distance in 1..2 && distance <= maxOf(a.length, b.length) / 2
    }

    // --- Applying ---------------------------------------------------------------------------

    /** Adjusts the suggestions for the word being typed. Called on the input thread. */
    @JvmStatic
    fun adjust(words: SuggestedWords) {
        if (!fieldAllowed || words.isEmpty) return
        if (words.mInputStyle != SuggestedWords.INPUT_STYLE_TYPING) return
        val typedInfo = words.mTypedWordInfo ?: return
        val typed = typedInfo.mWord
        val key = typed.lowercase(Locale.ROOT)
        if (key.isEmpty()) return

        val fix: String?
        synchronized(this) {
            if (keep.containsKey(key)) {
                words.mWillAutoCorrect = false
                return
            }
            val needed = if (words.mTypedWordValid) FIX_MIN_COUNT_VALID_WORD else FIX_MIN_COUNT
            fix = fixes[key]?.maxByOrNull { it.value }?.takeIf { it.value >= needed }?.key
        }
        if (fix == null) return

        val target = matchCase(typed, fix)
        val list = words.mSuggestedWordInfoList
        val existing = list.indexOfFirst { it.mWord == target }
        val info = if (existing >= 0) list.removeAt(existing) else SuggestedWordInfo(
            target, "", SuggestedWordInfo.MAX_SCORE, SuggestedWordInfo.KIND_CORRECTION,
            typedInfo.mSourceDict, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
        list.add(minOf(SuggestedWords.INDEX_OF_AUTO_CORRECTION, list.size), info)
        words.mWillAutoCorrect = true
    }

    private fun matchCase(typed: String, word: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase(Locale.ROOT)
        typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.titlecase(Locale.ROOT) }
        else -> word
    }

    // --- Settings -----------------------------------------------------------------------------

    data class Rules(val keep: List<String>, val fixes: List<Triple<String, String, Int>>)

    fun rules(): Rules = synchronized(this) {
        Rules(
            keep.entries.sortedByDescending { it.value }.map { it.key },
            fixes.flatMap { (typo, counts) -> counts.map { Triple(typo, it.key, it.value) } }
                .sortedByDescending { it.third }
        )
    }

    fun clear() {
        synchronized(this) {
            keep.clear()
            fixes.clear()
        }
        save()
    }

    // --- Storage ------------------------------------------------------------------------------

    private fun load(f: File) {
        if (loaded) return
        loaded = true
        if (!f.exists()) return
        try {
            val json = JSONObject(f.readText())
            synchronized(this) {
                json.optJSONObject("keep")?.let { k -> k.keys().forEach { keep[it] = k.getInt(it) } }
                json.optJSONObject("fixes")?.let { fx ->
                    fx.keys().forEach { typo ->
                        val counts = fx.getJSONObject(typo)
                        fixes[typo] = HashMap<String, Int>().apply {
                            counts.keys().forEach { put(it, counts.getInt(it)) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Corrupt file: start over rather than break typing
        }
    }

    private fun save() {
        val f = file ?: return
        val json = synchronized(this) {
            JSONObject()
                .put("v", 1)
                .put("keep", JSONObject(keep as Map<*, *>))
                .put("fixes", JSONObject().apply {
                    fixes.forEach { (typo, counts) -> put(typo, JSONObject(counts as Map<*, *>)) }
                })
                .toString()
        }
        executor.execute {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json)
            tmp.renameTo(f)
        }
    }

    private fun damerau(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
        }
        return d[a.length][b.length]
    }
}
