package org.futo.inputmethod.latin.fmaboard

import android.content.Intent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.LatinIME

/**
 * Select a link in the message (or paste one) and the suggestion bar offers "Vídeo" / "Audio":
 * it downloads it and sends it to the same app with the rest of the message as the caption.
 */
object LinkSend {
    private class Request(
        val url: String, val fullText: String, val caption: String,
        val audio: Boolean, val targetPackage: String?,
    )

    private val LINK_AT_CURSOR = Regex("(https?://\\S+)\\s?$")
    private const val PASTE_JUMP = 10

    /** The link the chips are offered for, or null. */
    val detected = mutableStateOf<String?>(null)
    /** Progress text while a link is being downloaded to send, or null. */
    val status = mutableStateOf<String?>(null)

    private var request: Request? = null
    private var job: Job? = null

    fun isVisible() = detected.value != null || status.value != null

    /**
     * Cheap on purpose: the editor is only asked for text when something was selected or the
     * cursor jumped forward (a paste), not on every keystroke.
     */
    @JvmStatic
    fun onSelectionUpdate(ic: InputConnection?, oldSelStart: Int, newSelStart: Int, newSelEnd: Int) {
        if (status.value != null) return
        if (ic == null) {
            detected.value = null
            return
        }
        if (newSelStart != newSelEnd) {
            val selected = ic.getSelectedText(0)?.toString()?.trim()
            val url = selected?.takeIf { it.length < 2000 }?.let { LinkDownload.findUrl(it) }
            detected.value = url?.takeIf { selected.length - it.length <= 2 }
            return
        }
        if (newSelStart - oldSelStart >= PASTE_JUMP) {
            val before = ic.getTextBeforeCursor(500, 0)?.toString() ?: ""
            detected.value = LINK_AT_CURSOR.find(before)?.groupValues?.get(1)?.let { LinkDownload.findUrl(it) }
            return
        }
        if (detected.value != null && newSelStart != oldSelStart) detected.value = null
    }

    @JvmStatic
    fun onFieldChanged() {
        detected.value = null
    }

    fun dismiss() {
        detected.value = null
    }

    fun cancel() {
        job?.cancel()
        request = null
        status.value = null
        LinkDownload.cancel()
    }

    fun start(ime: LatinIME, audio: Boolean) {
        val url = detected.value ?: return
        if (LinkDownload.isWorking()) {
            Toast.makeText(ime, "Ya hay una descarga en curso", Toast.LENGTH_SHORT).show()
            return
        }
        val full = readField(ime.currentInputConnection) ?: url
        val caption = full.replace(url, " ").replace(Regex("[ \\t]+"), " ").trim()
        request = Request(url, full, caption, audio, ime.currentInputEditorInfo?.packageName)
        detected.value = null
        status.value = "Preparando…"

        LinkDownload.reset()
        LinkDownload.start(ime, url, audio)
        job?.cancel()
        job = ime.lifecycleScope.launch {
            var seenWorking = false
            LinkDownload.state.collect { s ->
                when (s) {
                    is LinkDownload.State.Working -> {
                        seenWorking = true
                        status.value = s.message
                    }
                    is LinkDownload.State.Done -> if (seenWorking) {
                        send(ime, s)
                        job?.cancel()
                    }
                    is LinkDownload.State.Failed -> if (seenWorking) {
                        status.value = s.message
                        delay(5000)
                        status.value = null
                        request = null
                        job?.cancel()
                    }
                    LinkDownload.State.Idle -> if (seenWorking) {
                        status.value = null
                        job?.cancel()
                    }
                }
            }
        }
    }

    private fun readField(ic: InputConnection?): String? =
        ic?.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 10_000 }, 0)?.text?.toString()

    private fun send(ime: LatinIME, done: LinkDownload.State.Done) {
        val r = request ?: return
        request = null
        status.value = null

        // Take the link (and, for a video, the caption) out of the message so it isn't sent twice.
        // Only if the message is still exactly what it was when the chip was tapped.
        val ic = ime.currentInputConnection
        val sendCaption = !r.audio && r.caption.isNotEmpty()
        if (ic != null && readField(ic) == r.fullText) {
            val remaining = if (sendCaption) "" else r.fullText.replace(r.url, "").replace(Regex("[ \\t]+"), " ").trim()
            ic.beginBatchEdit()
            ic.finishComposingText()
            ic.setSelection(0, r.fullText.length)
            ic.commitText(remaining, 1)
            ic.endBatchEdit()
        }

        val uri = FileProvider.getUriForFile(ime, "${ime.packageName}.fmaboard.files", done.file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(done.mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (sendCaption) intent.putExtra(Intent.EXTRA_TEXT, r.caption)
        val target = r.targetPackage?.takeIf { it != ime.packageName }
        try {
            if (target != null) {
                ime.startActivity(Intent(intent).setPackage(target))
            } else {
                throw IllegalStateException()
            }
        } catch (e: Exception) {
            ime.startActivity(Intent.createChooser(intent, null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        LinkDownload.reset()
    }
}

/** Shown in the suggestion bar instead of word suggestions. */
@Composable
fun RowScope.LinkSendBar(ime: LatinIME?) {
    val progress = LinkSend.status.value
    Row(
        modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (progress != null) {
            Text(progress, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = { LinkSend.cancel() }) {
                Icon(Icons.Default.Close, contentDescription = "Cancelar", modifier = Modifier.size(18.dp))
            }
        } else {
            Text("Enviar:", style = MaterialTheme.typography.bodyMedium)
            AssistChip(onClick = { ime?.let { LinkSend.start(it, audio = false) } }, label = { Text("▶ Vídeo") })
            AssistChip(onClick = { ime?.let { LinkSend.start(it, audio = true) } }, label = { Text("♪ Audio") })
            IconButton(onClick = { LinkSend.dismiss() }) {
                Icon(Icons.Default.Close, contentDescription = "Ocultar", modifier = Modifier.size(18.dp))
            }
        }
    }
}
