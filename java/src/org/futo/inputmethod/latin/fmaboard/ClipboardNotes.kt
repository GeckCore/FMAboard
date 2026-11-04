package org.futo.inputmethod.latin.fmaboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.uix.ActionTextEditor
import org.futo.inputmethod.latin.uix.theme.Typography
import java.text.Normalizer
import java.util.Locale

/**
 * Extras for FUTO's clipboard history: notes written by hand, picking one sentence or a few
 * words out of a long clip, and a search that ignores accents and word order.
 */
object ClipboardNotes {
    private const val MAX_WORDS = 600
    private val SENTENCE_END = Regex("(?<=[.!?…])\\s+|\\n+")
    private val MARKS = Regex("\\p{Mn}+")

    /** Lowercase without accents, so "camion" finds "camión". */
    fun normalize(text: String): String =
        MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    /** Every word of the query appears somewhere in the text, in any order. */
    fun matches(text: String?, query: String): Boolean {
        text ?: return false
        val haystack = normalize(text)
        return normalize(query).split(Regex("\\s+")).filter { it.isNotEmpty() }.all { it in haystack }
    }

    fun sentences(text: String): List<String> =
        text.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }

    fun words(text: String): List<String> =
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(MAX_WORDS)

    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("FMAboard", text))
    }
}

/** Writing a new note, or editing a clip. Typing goes to this box, not to the app. */
@Composable
fun ClipboardNoteEditor(text: MutableState<String>, onSave: (String) -> Unit, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            Box(modifier = Modifier.padding(8.dp)) {
                ActionTextEditor(
                    text = text,
                    multiline = true,
                    autocorrect = true,
                    placeholder = "Escribe la nota…",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancelar") }
            Button(
                onClick = { onSave(text.value.trim()) },
                enabled = text.value.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text("Guardar nota") }
        }
    }
}

/**
 * The whole clip, to paste or copy just part of it: tap a sentence, or tap the first and
 * the last word of the phrase you want.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClipboardFragmentPicker(
    text: String,
    onPaste: (String) -> Unit,
    onSaveNote: (String) -> Unit,
    onEdit: () -> Unit,
    onDownload: ((String) -> Unit)? = null,
    onOpenUri: ((uri: String, action: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val sentences = remember(text) { ClipboardNotes.sentences(text) }
    val words = remember(text) { ClipboardNotes.words(text) }
    var start by remember(text) { mutableIntStateOf(-1) }
    var end by remember(text) { mutableIntStateOf(-1) }
    val selection = if (start >= 0) {
        words.subList(minOf(start, end), maxOf(start, end) + 1).joinToString(" ").trimEnd(',', ';', ':')
    } else null

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (sentences.size > 1) {
                Text("Toca una frase para pegarla", style = Typography.SmallMl,
                    color = MaterialTheme.colorScheme.outline)
                sentences.forEach { sentence ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onPaste(sentence) }
                    ) {
                        Text(sentence, modifier = Modifier.padding(8.dp), style = Typography.SmallMl,
                            maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Text(if (sentences.size > 1) "O toca la primera y la última palabra" else "Toca la primera y la última palabra", style = Typography.SmallMl,
                color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                words.forEachIndexed { i, word ->
                    FilterChip(
                        selected = start >= 0 && i in minOf(start, end)..maxOf(start, end),
                        onClick = {
                            if (start >= 0 && start == end && i != start) {
                                end = i
                            } else {
                                start = i
                                end = i
                            }
                        },
                        label = { Text(word) },
                        modifier = Modifier.heightIn(min = 32.dp)
                    )
                }
            }
        }

        if (selection != null) {
            Text(selection, style = Typography.SmallMl, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp))
            Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ClipboardNotes.copyToClipboard(context, selection) },
                    modifier = Modifier.weight(1f)) { Text("Copiar") }
                OutlinedButton(onClick = { onSaveNote(selection) },
                    modifier = Modifier.weight(1f)) { Text("Nota") }
                Button(onClick = { onPaste(selection) }, modifier = Modifier.weight(1f)) { Text("Pegar") }
            }
        } else {
            val link = remember(text) { LinkDownload.findUrl(text) }
            // A link opens in its app; a phone number opens a WhatsApp chat without saving the contact
            val anyLink = remember(text) { ClipboardLinks.findUrl(text) }
            val cleanLink = remember(anyLink) { anyLink?.let { ClipboardLinks.cleanUrl(it) } }
            val phone = remember(text) { ClipboardLinks.findPhone(text) }
            if (onOpenUri != null && (anyLink != null || phone != null)) {
                Row(modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (anyLink != null) {
                        OutlinedButton(onClick = { onOpenUri(anyLink, android.content.Intent.ACTION_VIEW) },
                            modifier = Modifier.weight(1f)) { Text("Abrir enlace") }
                        if (cleanLink != null) {
                            OutlinedButton(onClick = {
                                val written = if (text.contains(anyLink)) anyLink else anyLink.removePrefix("https://")
                                onPaste(if (text.contains(written)) text.replaceFirst(written, cleanLink) else cleanLink)
                            }, modifier = Modifier.weight(1f)) { Text("Pegar sin rastreo") }
                        }
                    } else if (phone != null) {
                        OutlinedButton(onClick = {
                            onOpenUri(ClipboardLinks.whatsappUrl(context, phone), android.content.Intent.ACTION_VIEW)
                        }, modifier = Modifier.weight(1f)) { Text("Chat de WhatsApp") }
                        OutlinedButton(onClick = {
                            onOpenUri("tel:" + phone.filter { it.isDigit() || it == '+' }, android.content.Intent.ACTION_DIAL)
                        }, modifier = Modifier.weight(1f)) { Text("Llamar") }
                    }
                }
            }
            Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (link != null && onDownload != null) {
                    OutlinedButton(onClick = { onDownload(link) }, modifier = Modifier.weight(1f)) { Text("Descargar") }
                }
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("Editar") }
                Button(onClick = { onPaste(text) }, modifier = Modifier.weight(1f)) { Text("Pegar todo") }
            }
        }
    }
}
