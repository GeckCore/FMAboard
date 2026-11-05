package org.futo.inputmethod.latin.fmaboard

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.uix.settings.Ios
import org.futo.inputmethod.latin.uix.settings.IosFootnote
import org.futo.inputmethod.latin.uix.settings.IosGroup
import org.futo.inputmethod.latin.uix.settings.IosIconTile
import org.futo.inputmethod.latin.uix.settings.SettingItem
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Text shortcuts: type a short word ("dir") and it becomes a longer text (your address).
 * It is offered as the correction for that word, so the space bar puts it in and backspace
 * takes it back, like any autocorrection.
 *
 * Saved only on the phone (files/fmaboard_shortcuts.json, never in the Documents copy), and
 * whatever a shortcut writes is hidden from the typing log, since it is often personal data.
 */
object TextShortcuts {
    private const val FILE_NAME = "fmaboard_shortcuts.json"
    private const val REDACTED = "[atajo]"
    const val MAX_KEY = 20

    data class Shortcut(val key: String, val text: String)

    @Volatile private var file: File? = null
    @Volatile private var map: Map<String, String> = emptyMap()
    @Volatile private var list: List<Shortcut> = emptyList()
    // JSON-escaped forms of the texts, to hide them in typing log lines
    @Volatile private var redactions: List<String> = emptyList()
    @Volatile private var fieldAllowed = false
    @Volatile private var hasSymbols = false

    @JvmStatic
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, FILE_NAME)
        file = f
        load(f)
    }

    fun all(context: Context): List<Shortcut> { init(context); return list }

    /**
     * Letters and digits, optionally after one symbol: "dir", or "/mama" so that typing the
     * plain word "mama" is left alone.
     */
    fun isValidKey(key: String): Boolean {
        if (key.length !in 2..MAX_KEY || key.any { it.isWhitespace() }) return false
        val rest = if (hasSymbol(key)) key.substring(1) else key
        return rest.isNotEmpty() && rest.all { it.isLetterOrDigit() }
    }

    /** Starts with a symbol, like "/mama": swapped by the space bar, not as a correction. */
    fun hasSymbol(key: String) = key.isNotEmpty() && !key[0].isLetterOrDigit()

    /** The text typed since the last space or line break. */
    @JvmStatic
    fun lastToken(before: CharSequence?): String = before?.toString()?.takeLastWhile { !it.isWhitespace() } ?: ""

    /** The text of a symbol shortcut that was just typed (right before the cursor), or null. */
    @JvmStatic
    fun symbolExpansion(before: CharSequence?): String? {
        if (!fieldAllowed || !hasSymbols) return null
        val token = lastToken(before)
        if (token.length < 2 || !hasSymbol(token)) return null
        return map[token.lowercase(Locale.ROOT)]
    }

    fun put(context: Context, old: String?, shortcut: Shortcut) {
        init(context)
        val next = list.filter { it.key != old && it.key != shortcut.key } + shortcut
        apply(next.sortedBy { it.key }); save()
    }

    fun remove(context: Context, key: String) {
        init(context)
        apply(list.filter { it.key != key }); save()
    }

    private fun apply(items: List<Shortcut>) {
        list = items
        map = items.associate { it.key.lowercase(Locale.ROOT) to it.text }
        hasSymbols = items.any { hasSymbol(it.key) }
        redactions = items.map { it.text }.filter { it.length >= 4 }.distinct()
            .sortedByDescending { it.length }
            .map { JSONObject.quote(it).let { q -> q.substring(1, q.length - 1) } }
    }

    private fun load(f: File) {
        val items = try {
            if (!f.exists()) emptyList() else {
                val a = JSONArray(f.readText())
                (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    Shortcut(o.optString("atajo"), o.optString("texto")).takeIf { isValidKey(it.key) && it.text.isNotEmpty() }
                }
            }
        } catch (e: Exception) { emptyList() }
        apply(items)
    }

    private fun save() {
        val f = file ?: return
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("atajo", it.key).put("texto", it.text)) }
        val tmp = File(f.path + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(f)
    }

    // --- Typing --------------------------------------------------------------------------------

    /** Shortcuts work everywhere except passwords, codes and number fields. */
    @JvmStatic
    fun onStartField(editorInfo: EditorInfo?, packageName: String) {
        val reason = TypingLog.exclusionReason(editorInfo, packageName)
        fieldAllowed = reason == null || reason == "own_app" || reason == "incognito"
    }

    /** True if the typed word is a shortcut: it is put first, as the correction. Input thread. */
    @JvmStatic
    fun adjust(words: SuggestedWords): Boolean {
        if (!fieldAllowed || map.isEmpty() || words.isEmpty) return false
        if (words.mInputStyle != SuggestedWords.INPUT_STYLE_TYPING) return false
        val typedInfo = words.mTypedWordInfo ?: return false
        val typed = typedInfo.mWord
        val text = map[typed.lowercase(Locale.ROOT)] ?: return false
        // "Dir" at the start of a sentence: capital first letter
        val target = if (typed.firstOrNull()?.isUpperCase() == true) text.replaceFirstChar { it.titlecase(Locale.ROOT) } else text
        val list = words.mSuggestedWordInfoList
        list.removeAll { it.mWord == target }
        list.add(minOf(SuggestedWords.INDEX_OF_AUTO_CORRECTION, list.size), SuggestedWordInfo(
            target, "", SuggestedWordInfo.MAX_SCORE, SuggestedWordInfo.KIND_CORRECTION,
            typedInfo.mSourceDict, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE))
        words.mWillAutoCorrect = true
        return true
    }

    /** True if [text] is what a shortcut writes (so undoing it teaches the autocorrector nothing). */
    fun isExpansion(text: String): Boolean = map.values.any { it.equals(text, ignoreCase = true) }

    /** Hides what shortcuts write from a typing log line (JSON). */
    @JvmStatic
    fun redact(line: String): String {
        var out = line
        for (r in redactions) if (out.contains(r)) out = out.replace(r, REDACTED)
        return out
    }
}

// --- Settings ------------------------------------------------------------------------------------

@Composable
private fun ShortcutDialog(initial: TextShortcuts.Shortcut?, onDismiss: () -> Unit, onSave: (TextShortcuts.Shortcut) -> Unit,
                           onDelete: (() -> Unit)?) {
    var key by remember { mutableStateOf(initial?.key ?: "") }
    var text by remember { mutableStateOf(initial?.text ?: "") }
    val keyOk = TextShortcuts.isValidKey(key)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nuevo atajo" else "Editar atajo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = key, onValueChange = { key = it.trim().take(TextShortcuts.MAX_KEY) },
                    label = { Text("Atajo (lo que escribes)") }, placeholder = { Text("/dir") },
                    singleLine = true, isError = key.isNotEmpty() && !keyOk,
                    supportingText = { Text(if (key.isNotEmpty() && !keyOk) "Letras y números, sin espacios; puede empezar por un símbolo (/mama)."
                        else "Empieza por un símbolo (/mama) para que solo salte con él.") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Se convierte en") }, placeholder = { Text("Calle Mayor 12, 3º B, Madrid") },
                    minLines = 2, maxLines = 6,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = keyOk && text.isNotBlank(), onClick = { onSave(TextShortcuts.Shortcut(key, text.trim())) }) { Text("Guardar") }
        },
        dismissButton = {
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
            else TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun ShortcutsList() {
    val context = LocalContext.current
    var items by remember { mutableStateOf(TextShortcuts.all(context)) }
    var editing by remember { mutableStateOf<TextShortcuts.Shortcut?>(null) }
    var adding by remember { mutableStateOf(false) }

    IosGroup {
        items.forEach { s ->
            SettingItem(title = s.key, subtitle = s.text.replace("\n", " · ").take(80), compact = true,
                onClick = { editing = s }) {}
        }
        SettingItem(title = "Añadir atajo", compact = true,
            icon = { IosIconTile(painterResource(R.drawable.fmaboard_caps), Ios.Blue) },
            onClick = { adding = true }) {
            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
    }
    IosFootnote("Escribe el atajo y pulsa espacio: se cambia por el texto. Con un símbolo delante (/mama, #casa) solo salta cuando lo escribes así, no con la palabra normal. Si un atajo sin símbolo saltó sin querer, pulsa borrar y vuelve a ser la palabra.")

    if (adding || editing != null) {
        val initial = editing
        ShortcutDialog(
            initial = initial,
            onDismiss = { adding = false; editing = null },
            onSave = { s ->
                TextShortcuts.put(context, initial?.key, s)
                items = TextShortcuts.all(context)
                adding = false; editing = null
            },
            onDelete = initial?.let { i -> {
                TextShortcuts.remove(context, i.key)
                items = TextShortcuts.all(context)
                editing = null
            } }
        )
    }
}

val TextShortcutsMenu = UserSettingsMenu(
    title = R.string.fmaboard_shortcuts_title,
    navPath = "fmaboardShortcuts", registerNavPath = true,
    settings = listOf(
        userSettingDecorationOnly {
            Tip("Guarda textos que escribes a menudo y ponlos con una palabra corta: «/dir» para tu dirección, «/cbu» para tus datos de cuenta, «/mama» para el número de tu madre. Se guardan solo en este móvil y nunca quedan en el registro de escritura.")
        },
        userSettingDecorationOnly { ShortcutsList() },
    )
)
