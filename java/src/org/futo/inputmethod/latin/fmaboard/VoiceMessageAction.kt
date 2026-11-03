package org.futo.inputmethod.latin.fmaboard

import android.content.Intent
import android.media.MediaPlayer
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.LatinIME
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.settings.Tip
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

private class VoiceState(voice: VoiceMessage.Voice) {
    val voice = mutableStateOf(voice)
    val installed = mutableStateOf(false)
    val downloading = mutableStateOf(false)
    val downloadProgress = mutableFloatStateOf(0f)
    val generating = mutableStateOf(false)
    val playing = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    /** The last audio made, and for which voice: reused by "Enviar" after "Escuchar". */
    var file: File? = null
    var fileVoice: VoiceMessage.Voice? = null
    var job: Job? = null
    var player: MediaPlayer? = null
    @Volatile var cancelDownload = false

    fun stopPlayer() {
        player?.let { p -> try { p.stop() } catch (_: Exception) {}; p.release() }
        player = null
        playing.value = false
    }
}

private fun formatMb(bytes: Long) = "%d MB".format(bytes / 1_000_000)

@Composable
private fun VoiceContents(manager: KeyboardManagerForAction, target: RewriteTarget?, state: VoiceState) {
    val context = LocalContext.current
    val ime = manager.getLatinIMEForDebug()

    if (!state.installed.value) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Tip("Convierte lo que escribes en un audio con voz natural para enviarlo. La voz se genera en el móvil, sin internet y gratis. Hace falta descargarla una vez (${formatMb(VoiceMessage.downloadBytes)}, mejor con wifi).")
            Spacer(Modifier.height(8.dp))
            if (state.downloading.value) {
                LinearProgressIndicator(progress = { state.downloadProgress.floatValue }, modifier = Modifier.fillMaxWidth().padding(8.dp))
                Text("Descargando voz… ${(state.downloadProgress.floatValue * 100).toInt()} %", Modifier.padding(horizontal = 8.dp))
                OutlinedButton(onClick = { state.cancelDownload = true }, modifier = Modifier.padding(8.dp)) { Text("Cancelar") }
            } else {
                state.error.value?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
                Button(onClick = {
                    state.error.value = null
                    state.downloading.value = true
                    state.cancelDownload = false
                    state.job = manager.getLifecycleScope().launch {
                        try {
                            withContext(Dispatchers.IO) {
                                VoiceMessage.download(context, { state.cancelDownload }) { done, total ->
                                    state.downloadProgress.floatValue = done.toFloat() / total
                                }
                            }
                            state.installed.value = VoiceMessage.isInstalled(context)
                        } catch (e: CancellationException) {
                            // cancelled: the partial download is kept and resumed next time
                        } catch (e: VoiceMessage.VoiceException) {
                            state.error.value = e.message
                        } catch (e: Exception) {
                            state.error.value = "No se pudo descargar la voz. Comprueba la conexión y vuelve a intentarlo."
                        } finally {
                            state.downloading.value = false
                        }
                    }
                }, modifier = Modifier.padding(8.dp)) { Text("Descargar voz (${formatMb(VoiceMessage.downloadBytes)})") }
            }
        }
        return
    }

    if (target == null || target.text.isBlank()) {
        Tip("Escribe el mensaje primero (o selecciona el trozo que quieras convertir en audio).")
        return
    }

    /** Makes the audio for the current voice if it isn't made yet, then runs [then]. */
    fun withAudio(then: (File) -> Unit) {
        val ready = state.file
        if (ready != null && state.fileVoice == state.voice.value && ready.exists()) { then(ready); return }
        state.job?.cancel()
        state.stopPlayer()
        state.error.value = null
        state.generating.value = true
        val voice = state.voice.value
        state.job = manager.getLifecycleScope().launch {
            try {
                val file = withContext(Dispatchers.Default) {
                    val audio = VoiceMessage.synthesize(context, target.text, voice)
                    VoiceMessage.encode(context, audio)
                }
                state.file = file
                state.fileVoice = voice
                then(file)
            } catch (e: CancellationException) {
                VoiceMessage.stop()
            } catch (e: VoiceMessage.VoiceException) {
                state.error.value = e.message
            } catch (e: Exception) {
                state.error.value = "No se pudo generar el audio"
            } finally {
                state.generating.value = false
            }
        }
    }

    fun play(file: File) {
        state.stopPlayer()
        try {
            state.player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { state.stopPlayer() }
                prepare()
                start()
            }
            state.playing.value = true
        } catch (e: Exception) {
            state.stopPlayer()
            state.error.value = "No se pudo reproducir el audio"
        }
    }

    fun send(file: File) {
        state.stopPlayer()
        val uri = FileProvider.getUriForFile(ime, "${ime.packageName}.fmaboard.files", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(VoiceMessage.mimeOf(file))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // The text became the audio: take it out of the field so it isn't sent twice
        ime.currentInputConnection?.let { ic ->
            if (readTarget(ic)?.text == target.text) applyRewrite(ic, target, "")
        }
        manager.closeActionWindow()
        sendToCurrentApp(ime, intent)
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Voz:", style = MaterialTheme.typography.bodyMedium)
            VoiceMessage.Voice.entries.forEach { voice ->
                FilterChip(
                    selected = state.voice.value == voice,
                    onClick = {
                        state.voice.value = voice
                        VoiceMessage.setVoice(context, voice)
                    },
                    label = { Text(voice.label) },
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
                    state.generating.value -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text("Generando la voz…", Modifier.padding(top = 8.dp))
                    }
                    state.error.value != null -> Text(state.error.value!!, color = MaterialTheme.colorScheme.error)
                    else -> Text(
                        (if (target.isSelection) "Selección: " else "") + target.text,
                        Modifier.verticalScroll(rememberScrollState()),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { if (state.playing.value) state.stopPlayer() else withAudio(::play) },
                enabled = !state.generating.value,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (state.playing.value) "■ Parar" else "▶ Escuchar")
            }
            Button(
                onClick = { withAudio(::send) },
                enabled = !state.generating.value,
                modifier = Modifier.weight(1f)
            ) {
                Text("Enviar audio")
            }
        }
    }
}

/** Opens the app the user is typing in with the audio, or the share sheet if it can't take it. */
private fun sendToCurrentApp(ime: LatinIME, intent: Intent) {
    val target = ime.currentInputEditorInfo?.packageName?.takeIf { it != ime.packageName }
    try {
        if (target == null) throw IllegalStateException()
        ime.startActivity(Intent(intent).setPackage(target))
    } catch (e: Exception) {
        ime.startActivity(Intent.createChooser(intent, null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

val VoiceMessageAction = Action(
    icon = R.drawable.fmaboard_voice,
    name = R.string.fmaboard_voice_title,
    simplePressImpl = null,
    windowImpl = { manager, _ ->
        val context = manager.getContext()
        val target = readTarget(manager.getLatinIMEForDebug().currentInputConnection)
        val state = VoiceState(VoiceMessage.voice(context))
        state.installed.value = VoiceMessage.isInstalled(context)
        object : ActionWindow() {
            @Composable
            override fun windowName(): String = stringResource(R.string.fmaboard_voice_title)

            @Composable
            override fun WindowContents(keyboardShown: Boolean) {
                VoiceContents(manager, target, state)
            }

            override fun close(): CloseResult {
                state.cancelDownload = true
                if (state.generating.value) VoiceMessage.stop()
                state.job?.cancel()
                state.stopPlayer()
                // Free the model's memory; the keyboard runs in the same process as everything else
                manager.getLifecycleScope().launch(NonCancellable) { VoiceMessage.release() }
                return CloseResult.Default
            }
        }
    }
)
