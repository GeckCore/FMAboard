package org.futo.inputmethod.latin.fmaboard

import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import android.text.InputType
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import org.futo.inputmethod.latin.InputAttributes
import org.futo.inputmethod.latin.RichInputMethodManager
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.uix.PreferenceUtils
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * FMAboard passive typing logger.
 *
 * Each time a text field is opened and the user types something, one "message" session is
 * recorded. Every raw event (touches, keys, gestures, suggestions, commits) goes to the
 * keystrokes stream, and when the field closes a single clean record with the final text
 * and typing statistics goes to the messages stream. See SCHEMA.md.
 *
 * Nothing is recorded in password, OTP/code, incognito (NO_PERSONALIZED_LEARNING) or
 * non-text (number, phone, date) fields, or while logging is turned off in settings.
 * Fields that are opened but never typed in leave no trace.
 */
object TypingLog {
    const val SCHEMA_VERSION = 2
    const val PREF_ENABLED = "fmaboard_logging_enabled"
    const val DEFAULT_ENABLED = true
    const val MAX_TEXT_CHARS = 4000

    @Volatile private var appContext: Context? = null
    @Volatile private var session: Session? = null

    // True while the keyboard types into one of its own boxes (clipboard search, notes…)
    // instead of the app's field: that text is not part of the message.
    @Volatile private var suspended = false

    /** The open session, or null while input goes to the keyboard's own boxes. */
    private fun liveSession(): Session? = if (suspended) null else session

    private class Session(
        val id: String,
        val app: String,
        val field: String,
        val lang: String,
        val header: JSONObject,
        val prefilled: String,
    ) {
        val startTime = System.currentTimeMillis()
        var started = false
        var sent = false

        // Best known text in the field: last snapshot read from the editor, with the
        // composing word that was part of it swapped for the current composing word.
        var editorText: String = prefilled
        var composingInEditor = 0
        var composing: String = ""
        fun currentText() = editorText.dropLast(composingInEditor) + composing

        var keystrokes = 0
        var deletes = 0
        var lastKeyWasDelete = false
        var swipes = 0
        var suggestionsUsed = 0
        var firstInputTime = 0L
        var lastInputTime = 0L

        val downTimes = HashMap<Int, Long>()
        var lastDownTime = 0L
        var dwellSum = 0L
        var dwellCount = 0
        var flightSum = 0L
        var flightCount = 0

        // Characters typed since the last commit, to spot autocorrections.
        val typedWord = StringBuilder()
        var wordFromSuggestionOrSwipe = false
        val corrections = JSONArray()

        // Text replaced by the AI rewrite action, so the dataset can tell it apart.
        val aiEdits = JSONArray()
        var dictatedChars = 0
        var pastedChars = 0
    }

    @JvmStatic
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        TypingLogStore.init(context)
    }

    @JvmStatic
    fun isFieldActive(): Boolean = session != null

    /** The keyboard starts (true) or stops (false) typing into one of its own text boxes. */
    @JvmStatic
    fun setSuspended(value: Boolean) {
        suspended = value
    }

    @JvmStatic
    fun isEnabled(context: Context): Boolean =
        PreferenceUtils.getDefaultSharedPreferences(context).getBoolean(PREF_ENABLED, DEFAULT_ENABLED)

    /** Returns why a field must not be logged, or null if it may be. */
    @JvmStatic
    fun exclusionReason(editorInfo: EditorInfo?, packageName: String): String? {
        if (editorInfo == null) return "no_editor"
        // The keyboard's own screens (settings, "Texto de imagen"…) are not the user's writing
        if (editorInfo.packageName != null && editorInfo.packageName == appContext?.packageName) return "own_app"
        val inputClass = editorInfo.inputType and InputType.TYPE_MASK_CLASS
        if (inputClass != InputType.TYPE_CLASS_TEXT) return "non_text_class"
        val attrs = InputAttributes(editorInfo, false, packageName)
        if (attrs.mIsPasswordField) return "password"
        if (attrs.mIsCodeField) return "code"
        if (attrs.mNoLearning) return "incognito"
        return null
    }

    /** [textBeforeCursor] is what the field already contained when it was opened. */
    @JvmStatic
    fun onStartField(editorInfo: EditorInfo?, packageName: String, textBeforeCursor: CharSequence?,
                     keyboardWidth: Int, keyboardHeight: Int) {
        val context = appContext ?: return

        // Chat apps clear the field after sending and restart input on the same field.
        val previous = session
        if (previous != null) {
            val cleared = editorInfo != null && editorInfo.packageName == previous.app
                    && editorInfo.initialSelStart == 0
            // Nothing left to send if the user had deleted everything themselves
            if (cleared && previous.currentText().isNotBlank()) previous.sent = true
            endSession(previous, previous.currentText(), if (cleared) "cleared" else "restart")
        }

        if (!isEnabled(context) || !isUserUnlocked(context)) return
        if (exclusionReason(editorInfo, packageName) != null) return

        val info = editorInfo!!
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val id = UUID.randomUUID().toString().substring(0, 8)
        val app = info.packageName ?: ""
        val field = fieldKind(variation)
        // Language of the active keyboard layout, not the system language.
        val lang = try {
            RichInputMethodManager.getInstance().currentSubtypeLocale.toLanguageTag()
        } catch (e: Exception) {
            Locale.getDefault().toLanguageTag()
        }
        val prefilled = textBeforeCursor?.toString()?.takeLast(MAX_TEXT_CHARS) ?: ""

        val header = JSONObject()
            .put("app", app)
            .put("field", field)
            .put("multiline", (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0)
            .put("input_type", info.inputType)
            .put("ime_action", info.imeOptions and EditorInfo.IME_MASK_ACTION)
            .put("lang", lang)
            .put("kb_w", keyboardWidth)
            .put("kb_h", keyboardHeight)

        session = Session(id, app, field, lang, header, prefilled)
    }

    /**
     * The app emptied the field (cursor back at 0, nothing after it). Unless the user just
     * deleted everything themselves, that is a chat app clearing the box after sending:
     * close this message as sent and keep logging the same field as a new message.
     */
    @JvmStatic
    fun onFieldCleared() {
        val s = liveSession() ?: return
        if (!s.started || s.lastKeyWasDelete) return
        val text = s.currentText()
        if (text.isBlank()) return
        s.sent = true
        endSession(s, text, "cleared")
        session = Session(UUID.randomUUID().toString().substring(0, 8),
            s.app, s.field, s.lang, s.header, "")
    }

    /** [finalText] is read from the editor if it is still open, otherwise null. */
    @JvmStatic
    fun onFinishField(finalText: CharSequence?) {
        val s = session ?: return
        // While suspended the editor is the keyboard's own box, not the app's field
        val text = if (suspended) null else finalText?.toString()
        endSession(s, text ?: s.currentText(), "closed")
    }

    // --- Input events ----------------------------------------------------------------------

    /** Raw finger down/up on the keyboard, with the key that was under the finger. */
    @JvmStatic
    fun onTouch(action: Int, pointerId: Int, x: Int, y: Int, pressure: Float, size: Float,
                eventTime: Long, keyLabel: String?, keyCenterX: Int, keyCenterY: Int,
                keyWidth: Int, keyHeight: Int) {
        val s = liveSession() ?: return
        val time = uptimeToWall(eventTime)
        val type = when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                s.downTimes[pointerId] = time
                if (s.lastDownTime != 0L) {
                    val flight = time - s.lastDownTime
                    if (flight in 1..3000) { s.flightSum += flight; s.flightCount++ }
                }
                s.lastDownTime = time
                "down"
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                s.downTimes.remove(pointerId)?.let { down ->
                    val dwell = time - down
                    if (dwell in 1..2000) { s.dwellSum += dwell; s.dwellCount++ }
                }
                "up"
            }
            else -> return
        }
        val e = event(s, "touch", time)
            .put("a", type)
            .put("p", pointerId)
            .put("x", x).put("y", y)
            .put("pr", round3(pressure))
            .put("sz", round3(size))
        if (keyLabel != null) {
            e.put("key", keyLabel)
                .put("kx", keyCenterX).put("ky", keyCenterY)
                .put("kw", keyWidth).put("kh", keyHeight)
        }
        emit(s, e)
    }

    /** A key produced input: a character (code > 0) or a function key (code <= 0). */
    @JvmStatic
    fun onKey(code: Int, x: Int, y: Int, isRepeat: Boolean) {
        val s = liveSession() ?: return
        markInput(s)
        s.keystrokes++
        s.lastKeyWasDelete = code == Constants.CODE_DELETE
        val e = event(s, "key")
        if (code > 0) {
            val ch = String(Character.toChars(code))
            e.put("ch", ch)
            s.typedWord.append(ch)
        } else {
            e.put("fn", Constants.printableCode(code))
            if (code == Constants.CODE_DELETE) {
                s.deletes++
                if (s.typedWord.isNotEmpty()) s.typedWord.setLength(s.typedWord.length - 1)
            }
        }
        if (x >= 0 && y >= 0) e.put("x", x).put("y", y)
        if (isRepeat) e.put("repeat", true)
        emit(s, e)
    }

    /** Multi-character output from a single key, e.g. ".com" or an emoji. */
    @JvmStatic
    fun onTextKey(text: String?) {
        val s = liveSession() ?: return
        if (text == null) return
        markInput(s)
        s.keystrokes++
        s.lastKeyWasDelete = false
        emit(s, event(s, "text_key").put("text", text))
    }

    @JvmStatic
    fun onGesture(phase: String, pointCount: Int) {
        val s = liveSession() ?: return
        markInput(s)
        if (phase == "end") {
            s.lastKeyWasDelete = false
            s.swipes++
            s.wordFromSuggestionOrSwipe = true
        }
        emit(s, event(s, "gesture").put("phase", phase).put("points", pointCount))
    }

    @JvmStatic
    fun onSuggestionPicked(word: String?, kind: Int) {
        val s = liveSession() ?: return
        if (word == null) return
        markInput(s)
        s.lastKeyWasDelete = false
        s.suggestionsUsed++
        s.wordFromSuggestionOrSwipe = true
        emit(s, event(s, "suggestion").put("word", word).put("kind", kind))
    }

    /** Text actually sent to the app, after autocorrection. */
    @JvmStatic
    fun onCommit(text: CharSequence?) {
        val s = liveSession() ?: return
        if (text.isNullOrEmpty()) return
        markInput(s)
        val committed = text.toString()
        if (committed.isNotBlank()) {
            val typed = s.typedWord.toString().trim().trimEnd { !it.isLetterOrDigit() }
            val final = committed.trim()
            if (typed.isNotEmpty() && !s.wordFromSuggestionOrSwipe
                    && final.any { it.isLetter() } && typed != final) {
                s.corrections.put(JSONObject().put("typed", typed).put("final", final))
            }
            s.typedWord.setLength(0)
            s.wordFromSuggestionOrSwipe = false
        }
        emit(s, event(s, "commit").put("text", committed))
    }

    @JvmStatic
    fun onDeleteBefore(count: Int) {
        val s = liveSession() ?: return
        if (count <= 0) return
        markInput(s)
        emit(s, event(s, "delete").put("n", count))
    }

    @JvmStatic
    fun onEditorAction(actionId: Int) {
        val s = liveSession() ?: return
        s.sent = true
        emit(s, event(s, "action").put("id", actionId))
    }

    /** Snapshot of the text before the cursor, read from the editor after an edit. */
    @JvmStatic
    fun onEditorText(textBeforeCursor: CharSequence?, composingLength: Int) {
        val s = liveSession() ?: return
        if (textBeforeCursor == null) return
        s.editorText = textBeforeCursor.toString().takeLast(MAX_TEXT_CHARS)
        s.composingInEditor = composingLength.coerceAtMost(s.editorText.length)
        s.composing = s.editorText.takeLast(s.composingInEditor)
    }

    /**
     * The keyboard reopened an already committed word for editing (e.g. backspacing into
     * it). Its letters count as typed for spotting autocorrections, and they are part of
     * the editor text read right after, so they must not be counted twice.
     */
    @JvmStatic
    fun onWordResumed(word: CharSequence) {
        val s = liveSession() ?: return
        s.typedWord.setLength(0)
        s.typedWord.append(word)
        s.wordFromSuggestionOrSwipe = false
    }

    /** [text] was typed by voice dictation, not by hand. */
    @JvmStatic
    fun onDictation(text: String) {
        val s = liveSession() ?: return
        markInput(s)
        s.lastKeyWasDelete = false
        s.dictatedChars += text.trim().length
        emit(s, event(s, "dictation").put("text", text))
    }

    /** [text] was pasted from the keyboard's clipboard history, not typed. */
    @JvmStatic
    fun onPaste(text: String) {
        val s = liveSession() ?: return
        s.pastedChars += text.length
        emit(s, event(s, "paste").put("text", text))
    }

    /** Backspace right after an autocorrection put back [typed] instead of [committed]. */
    @JvmStatic
    fun onAutocorrectReverted(typed: String, committed: String) {
        val s = liveSession() ?: return
        markInput(s)
        emit(s, event(s, "autocorrect_revert").put("typed", typed).put("committed", committed))
    }

    /** The AI rewrite action replaced [before] (what the user wrote) with [after]. */
    @JvmStatic
    fun onAiRewrite(style: String, before: String, after: String) {
        val s = liveSession() ?: return
        markInput(s)
        s.lastKeyWasDelete = false
        s.typedWord.setLength(0)
        s.wordFromSuggestionOrSwipe = false
        val edit = JSONObject().put("style", style).put("before", before).put("after", after)
        s.aiEdits.put(edit)
        val e = event(s, "ai_rewrite")
        edit.keys().forEach { e.put(it, edit.get(it)) }
        emit(s, e)
    }

    @JvmStatic
    fun onComposingText(composing: CharSequence) {
        val s = liveSession() ?: return
        s.composing = composing.toString()
    }

    // --- Session end ----------------------------------------------------------------------

    private fun endSession(s: Session, finalText: String, reason: String) {
        session = null
        if (!s.started) return

        val endTime = System.currentTimeMillis()
        val text = finalText.takeLast(MAX_TEXT_CHARS)
        emit(s, event(s, "field_end").put("reason", reason).put("text", text))

        val typingMs = s.lastInputTime - s.firstInputTime
        val newChars = maxOf(0, text.length - s.prefilled.length)
        val stats = JSONObject()
            .put("keystrokes", s.keystrokes)
            .put("deletes", s.deletes)
            .put("swipes", s.swipes)
            .put("suggestions_used", s.suggestionsUsed)
            .put("autocorrections", s.corrections.length())
            .put("chars", text.length)
            .put("words", text.trim().split(Regex("\\s+")).count { it.isNotEmpty() })
            .put("typing_ms", typingMs)
        // Too few characters give meaningless speeds ("Jajaja" at 140 wpm)
        if (typingMs > 0 && newChars >= 10) {
            stats.put("wpm", Math.round(newChars / 5.0 / (typingMs / 60000.0) * 10) / 10.0)
        }
        if (s.dictatedChars > 0) stats.put("dictated_chars", s.dictatedChars)
        if (s.pastedChars > 0) stats.put("pasted_chars", s.pastedChars)
        if (s.dwellCount > 0) stats.put("avg_dwell_ms", s.dwellSum / s.dwellCount)
        if (s.flightCount > 0) stats.put("avg_flight_ms", s.flightSum / s.flightCount)

        val message = JSONObject()
            .put("v", SCHEMA_VERSION)
            .put("id", s.id)
            .put("start", isoFormat().format(Date(s.startTime)))
            .put("end", isoFormat().format(Date(endTime)))
            .put("app", s.app)
            .put("field", s.field)
            .put("lang", s.lang)
            .put("text", text)
        if (s.prefilled.isNotEmpty()) message.put("prefilled", s.prefilled)
        message.put("sent", s.sent)
            .put("end_reason", reason)
            .put("stats", stats)
        if (s.corrections.length() > 0) message.put("corrections", s.corrections)
        if (s.aiEdits.length() > 0) message.put("ai_edits", s.aiEdits)

        TypingLogStore.append(TypingLogStore.Stream.MESSAGES, message.toString())
        TypingLogStore.onMessageEnded()
        PhraseMemory.onMessageSaved()
    }

    // --- Helpers ------------------------------------------------------------------------

    // The session's header is only written once the user actually types, so opening a
    // field without typing leaves nothing behind.
    private fun markInput(s: Session) {
        val now = System.currentTimeMillis()
        if (!s.started) {
            s.started = true
            s.firstInputTime = now
            val header = event(s, "field_start", s.startTime)
            s.header.keys().forEach { header.put(it, s.header.get(it)) }
            if (s.prefilled.isNotEmpty()) header.put("prefilled", s.prefilled)
            TypingLogStore.append(TypingLogStore.Stream.KEYSTROKES, header.toString())
        }
        s.lastInputTime = now
    }

    private fun emit(s: Session, e: JSONObject) {
        if (!s.started) markInput(s)
        TypingLogStore.append(TypingLogStore.Stream.KEYSTROKES, e.toString())
    }

    private fun event(s: Session, type: String, wallTime: Long = System.currentTimeMillis()): JSONObject =
        JSONObject()
            .put("v", SCHEMA_VERSION)
            .put("t", wallTime)
            .put("type", type)
            .put("s", s.id)

    private fun uptimeToWall(eventTime: Long): Long =
        System.currentTimeMillis() - (SystemClock.uptimeMillis() - eventTime)

    private fun round3(v: Float): Double = Math.round(v * 1000.0) / 1000.0

    private fun isoFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    private fun isUserUnlocked(context: Context): Boolean =
        (context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked

    private fun fieldKind(variation: Int): String = when (variation) {
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "email"
        InputType.TYPE_TEXT_VARIATION_URI -> "uri"
        InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT -> "email_subject"
        InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE -> "short_message"
        InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE -> "long_message"
        InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> "person_name"
        InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> "postal_address"
        InputType.TYPE_TEXT_VARIATION_FILTER -> "search"
        InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT -> "web"
        else -> "text"
    }
}
