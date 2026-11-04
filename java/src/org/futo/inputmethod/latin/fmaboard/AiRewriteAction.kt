package org.futo.inputmethod.latin.fmaboard

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.settings.SettingsActivity
import org.futo.inputmethod.latin.uix.settings.Tip

/** What will be rewritten: the selection if there is one, otherwise the whole field. */
internal class RewriteTarget(val text: String, val start: Int, val end: Int, val isSelection: Boolean)

private const val MAX_FIELD_CHARS = 10_000

internal fun readTarget(ic: InputConnection?): RewriteTarget? {
    ic ?: return null
    val extracted = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = MAX_FIELD_CHARS }, 0)
    if (extracted?.text != null) {
        val full = extracted.text.toString()
        val selStart = minOf(extracted.selectionStart, extracted.selectionEnd).coerceIn(0, full.length)
        val selEnd = maxOf(extracted.selectionStart, extracted.selectionEnd).coerceIn(0, full.length)
        val offset = extracted.startOffset
        return if (selEnd > selStart) {
            RewriteTarget(full.substring(selStart, selEnd), offset + selStart, offset + selEnd, true)
        } else {
            RewriteTarget(full, offset, offset + full.length, false)
        }
    }
    // Some editors don't support extracted text
    val before = ic.getTextBeforeCursor(MAX_FIELD_CHARS, 0)?.toString() ?: return null
    val after = ic.getTextAfterCursor(MAX_FIELD_CHARS, 0)?.toString() ?: ""
    return RewriteTarget(before + after, -1, -1, false)
}

internal fun applyRewrite(ic: InputConnection, target: RewriteTarget, result: String) {
    ic.beginBatchEdit()
    ic.finishComposingText()
    if (target.start >= 0) {
        ic.setSelection(target.start, target.end)
    } else {
        val before = ic.getTextBeforeCursor(MAX_FIELD_CHARS, 0)?.length ?: 0
        val after = ic.getTextAfterCursor(MAX_FIELD_CHARS, 0)?.length ?: 0
        ic.deleteSurroundingText(before, after)
    }
    ic.commitText(result, 1)
    ic.endBatchEdit()
}

private class RewriteState {
    val style = mutableStateOf<AiRewrite.Style?>(null)
    val loading = mutableStateOf(false)
    val result = mutableStateOf<String?>(null)
    val error = mutableStateOf<String?>(null)
    var job: Job? = null
}

@Composable
private fun RewriteContents(manager: KeyboardManagerForAction, target: RewriteTarget?, state: RewriteState) {
    val context = LocalContext.current

    if (!AiRewrite.hasApiKey(context)) {
        Column(Modifier.padding(8.dp)) {
            Tip("Para reescribir con IA pega tu clave de OpenRouter o de Gemini en Ajustes → FMAboard.")
            Button(onClick = {
                manager.closeActionWindow()
                SettingsActivity.openToNavDest(context, "fmaboard")
            }, modifier = Modifier.padding(8.dp)) { Text("Abrir ajustes") }
        }
        return
    }

    if (target == null || target.text.isBlank()) {
        Tip("Escribe algo primero (o selecciona el trozo que quieras reescribir).")
        return
    }

    fun run(style: AiRewrite.Style) {
        state.job?.cancel()
        state.style.value = style
        state.loading.value = true
        state.result.value = null
        state.error.value = null
        state.job = manager.getLifecycleScope().launch {
            try {
                state.result.value = withContext(Dispatchers.IO) {
                    AiRewrite.rewrite(context, target.text, style)
                }
            } catch (e: AiRewrite.RewriteException) {
                state.error.value = e.message
            } finally {
                state.loading.value = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AiRewrite.Style.entries.forEach { style ->
                FilterChip(
                    selected = state.style.value == style,
                    onClick = { run(style) },
                    label = { Text(style.label) },
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            Box(Modifier.padding(10.dp)) {
                when {
                    state.loading.value -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.error.value != null -> Text(state.error.value!!, color = MaterialTheme.colorScheme.error)
                    state.result.value != null -> Text(state.result.value!!,
                        Modifier.verticalScroll(rememberScrollState()))
                    else -> Text(
                        (if (target.isSelection) "Selección: " else "") + target.text,
                        Modifier.verticalScroll(rememberScrollState()),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { manager.closeActionWindow() }, modifier = Modifier.weight(1f)) {
                Text("Cancelar")
            }
            Button(
                onClick = {
                    val result = state.result.value ?: return@Button
                    val ic = manager.getLatinIMEForDebug().currentInputConnection ?: return@Button
                    applyRewrite(ic, target, result)
                    TypingLog.onAiRewrite(state.style.value!!.id, target.text, result)
                    TypingLog.onEditorText(ic.getTextBeforeCursor(TypingLog.MAX_TEXT_CHARS, 0), 0)
                    manager.closeActionWindow()
                },
                enabled = state.result.value != null && !state.loading.value,
                modifier = Modifier.weight(1f)
            ) {
                Text("Reemplazar")
            }
        }
    }
}

val AiRewriteAction = Action(
    icon = R.drawable.fmaboard_sparkle,
    name = R.string.fmaboard_rewrite_title,
    simplePressImpl = null,
    windowImpl = { manager, _ ->
        val target = readTarget(manager.getLatinIMEForDebug().currentInputConnection)
        val state = RewriteState()
        object : ActionWindow() {
            @Composable
            override fun windowName(): String = stringResource(R.string.fmaboard_rewrite_title)

            @Composable
            override fun WindowContents(keyboardShown: Boolean) {
                RewriteContents(manager, target, state)
            }

            override fun close(): CloseResult {
                state.job?.cancel()
                return CloseResult.Default
            }
        }
    }
)
