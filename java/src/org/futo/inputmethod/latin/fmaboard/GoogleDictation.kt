package org.futo.inputmethod.latin.fmaboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.PreferenceUtils
import java.util.Locale

/**
 * Dictation with the phone's own speech recognition (Google's on most phones): the same engine
 * other keyboards use, far better than the small offline model, and free. Text appears while
 * speaking; it keeps listening through pauses until the person taps or stops talking for a while.
 * Then [AiDictation] polishes it with the person's style.
 */
object GoogleDictation {
    const val PREF_ENABLED = "fmaboard_google_voice"
    const val DEFAULT_ENABLED = true

    fun shouldUse(context: Context): Boolean =
        PreferenceUtils.getDefaultSharedPreferences(context).getBoolean(PREF_ENABLED, DEFAULT_ENABLED) &&
            SpeechRecognizer.isRecognitionAvailable(context)
}

class GoogleDictationWindow(
    private val manager: KeyboardManagerForAction,
    private val locales: List<Locale>,
) : ActionWindow() {
    private val context = manager.getContext()
    private val main = Handler(Looper.getMainLooper())
    private val transaction = manager.createInputTransaction()

    private val segments = ArrayList<String>()
    private var partial = ""
    private var recognizer: SpeechRecognizer? = null
    private var stopRequested = false
    private var finished = false
    private var silentRounds = 0

    private val level = mutableFloatStateOf(0f)
    private val status = mutableStateOf("Habla ahora")
    private val shown = mutableStateOf("")

    init {
        main.post { listen() }
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        val locale = locales.firstOrNull() ?: Locale.getDefault()
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Let people pause to think; not every recognizer honours these
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
    }

    private fun listen() {
        if (finished) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        try {
            r.startListening(intent())
        } catch (e: Exception) {
            Log.w(TAG, "Could not start Google dictation", e)
            status.value = "No se pudo usar el reconocimiento de voz del móvil"
        }
    }

    private fun text(): String = (segments + partial).filter { it.isNotBlank() }.joinToString(" ").trim()

    private fun show() {
        val t = text()
        shown.value = t
        transaction.updatePartial(t)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { status.value = "Escuchando…" }
        override fun onBeginningOfSpeech() { silentRounds = 0 }
        override fun onRmsChanged(rmsdB: Float) { level.floatValue = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                partial = it
                show()
            }
        }

        override fun onResults(results: Bundle?) {
            val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            partial = ""
            if (best.isNotBlank()) segments += best.trim()
            show()
            // Keep going through pauses until the person taps to finish
            if (stopRequested) finish() else main.post { listen() }
        }

        override fun onError(error: Int) {
            partial = ""
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // A long silence: done if something was said, else give it one more go
                    silentRounds++
                    if (stopRequested || segments.isNotEmpty() || silentRounds >= 2) finish() else main.post { listen() }
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT ->
                    if (stopRequested) finish() else main.postDelayed({ listen() }, 250)
                else -> {
                    Log.w(TAG, "Google dictation error $error")
                    status.value = when (error) {
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        SpeechRecognizer.ERROR_SERVER -> "Sin conexión con el reconocimiento de voz"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta el permiso del micrófono"
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Ese idioma no está disponible para dictar"
                        else -> "El reconocimiento de voz ha fallado"
                    }
                    if (segments.isNotEmpty()) finish()
                }
            }
        }
    }

    private fun finish() {
        if (finished) return
        finished = true
        recognizer?.destroy()
        recognizer = null
        val result = text()
        if (result.isEmpty()) {
            transaction.cancel()
            manager.closeActionWindow()
            return
        }
        transaction.commit(result)
        AiDictation.onDictated(manager, result)
        manager.announce(result)
        manager.closeActionWindow()
    }

    private fun requestStop() {
        if (finished) return
        stopRequested = true
        status.value = "Terminando…"
        // stopListening makes the recognizer deliver what it has heard so far
        recognizer?.stopListening() ?: finish()
        main.postDelayed({ finish() }, 2500)
    }

    @Composable
    override fun windowName(): String = "Dictado"

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        val scale by animateFloatAsState(1f + level.floatValue * 0.35f, label = "mic")
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) { requestStop() }
        ) {
            Column(
                Modifier.align(Alignment.Center).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier.size(72.dp).scale(scale).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(painterResource(R.drawable.mic_fill), contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text(shown.value.ifEmpty { status.value }, textAlign = TextAlign.Center,
                    maxLines = 3, color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                Text("Toca para terminar", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    override fun close(): CloseResult {
        if (!finished) {
            finished = true
            recognizer?.destroy()
            recognizer = null
            // Closed by hand: keep what was already said and polish it all the same
            val result = text()
            if (result.isNotEmpty()) {
                transaction.commit(result)
                AiDictation.onDictated(manager, result)
            } else transaction.cancel()
        }
        main.removeCallbacksAndMessages(null)
        return CloseResult.Default
    }

    private companion object {
        const val TAG = "FMAboardGoogleVoice"
    }
}
