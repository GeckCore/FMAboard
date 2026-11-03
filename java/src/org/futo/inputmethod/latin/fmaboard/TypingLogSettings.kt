package org.futo.inputmethod.latin.fmaboard

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.PreferenceUtils
import org.futo.inputmethod.latin.uix.findActivity
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.settings.userSettingToggleSharedPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val EXPORT_TYPING_LOG_REQUEST = 41860317

// Bumped whenever the log is deleted so the stats card reloads.
private val statsGeneration = mutableIntStateOf(0)

private data class TypingLogStats(
    val days: Int, val messages: Long, val messagesToday: Long, val events: Long, val bytes: Long
)

private fun loadStats(context: Context): TypingLogStats {
    TypingLogStore.flushBlocking()
    val files = TypingLogStore.logFiles(context)
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    var messages = 0L
    var messagesToday = 0L
    var events = 0L
    files.forEach { file ->
        val lines = file.bufferedReader().useLines { it.count().toLong() }
        if (file.name.endsWith(TypingLogStore.Stream.MESSAGES.suffix)) {
            messages += lines
            if (file.name.startsWith(today)) messagesToday = lines
        } else {
            events += lines
        }
    }
    val days = files.map { it.name.substring(0, 10) }.distinct().size
    return TypingLogStats(days, messages, messagesToday, events, files.sumOf { it.length() })
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

@Composable
private fun TypingLogStatsCard() {
    val context = LocalContext.current
    val generation = statsGeneration.intValue
    var stats by remember { mutableStateOf<TypingLogStats?>(null) }

    LaunchedEffect(generation) {
        stats = withContext(Dispatchers.IO) { loadStats(context) }
    }

    Tip(stats?.let {
        "Mensajes guardados: ${it.messages} (hoy: ${it.messagesToday})\n" +
        "Eventos de tecleo: ${it.events}\n" +
        "Días con datos: ${it.days}\n" +
        "Tamaño: ${formatBytes(it.bytes)}"
    } ?: "Calculando…")
}

fun triggerExportTypingLog(context: Context) {
    val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "application/zip"
        putExtra(Intent.EXTRA_TITLE, "fmaboard-typing-$date.zip")
    }
    findActivity(context)!!.startActivityForResult(intent, EXPORT_TYPING_LOG_REQUEST)
}

// Password field so the keyboard's own typing log skips it.
@Composable
private fun AiApiKeyField() {
    val context = LocalContext.current
    val prefs = remember { PreferenceUtils.getDefaultSharedPreferences(context) }
    var key by remember { mutableStateOf(prefs.getString(AiRewrite.PREF_API_KEY, "") ?: "") }

    OutlinedTextField(
        value = key,
        onValueChange = {
            key = it.trim()
            prefs.edit { putString(AiRewrite.PREF_API_KEY, key) }
        },
        label = { Text("Clave de OpenRouter (sk-or-…)") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp)
    )
}

private val autocorrectGeneration = mutableIntStateOf(0)

@Composable
private fun PersonalAutocorrectCard() {
    val context = LocalContext.current
    val generation = autocorrectGeneration.intValue
    var loadedRules by remember { mutableStateOf<PersonalAutocorrect.Rules?>(null) }
    LaunchedEffect(generation) {
        loadedRules = withContext(Dispatchers.IO) { PersonalAutocorrect.rulesBlocking(context) }
    }
    val rules = loadedRules ?: run { Tip("Autocorrector personal: cargando…"); return }
    val active = rules.fixes.filter { it.third >= PersonalAutocorrect.FIX_MIN_COUNT }
    Tip(buildString {
        append("Autocorrector personal (aprende solo, en el móvil):\n")
        append("• No corregir (deshiciste la autocorrección): ")
        append(if (rules.keep.isEmpty()) "nada aún" else rules.keep.take(15).joinToString(", "))
        append("\n• Tus errores que ya corrige: ")
        append(if (active.isEmpty()) "nada aún" else active.take(15).joinToString(", ") { "${it.first}→${it.second}" })
        val learning = rules.fixes.size - active.size
        if (learning > 0) append("\n• Aprendiendo (corregidos 1 vez): $learning")
    })
}

private fun confirmClearAutocorrect(context: Context) {
    AlertDialog.Builder(findActivity(context) ?: context)
        .setTitle("Olvidar autocorrector personal")
        .setMessage("Se borrarán las palabras que no quieres que te corrija y los errores que ha aprendido. Tu registro de escritura no se toca.")
        .setPositiveButton("Olvidar") { _, _ ->
            PersonalAutocorrect.clear()
            autocorrectGeneration.intValue++
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun confirmDeleteTypingLog(context: Context) {
    AlertDialog.Builder(findActivity(context) ?: context)
        .setTitle(R.string.fmaboard_delete)
        .setMessage("Se borrarán permanentemente todos los datos de escritura de este móvil, incluida la copia de Documentos/FMAboard. Exporta antes si quieres conservarlos.")
        .setPositiveButton("Borrar") { _, _ ->
            Thread {
                TypingLogStore.deleteAll(context)
                statsGeneration.intValue++
            }.start()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

val TypingLogMenu = UserSettingsMenu(
    title = R.string.fmaboard_title,
    navPath = "fmaboard", registerNavPath = true,
    settings = listOf(
        userSettingToggleSharedPrefs(
            title = R.string.fmaboard_enabled,
            subtitle = R.string.fmaboard_enabled_subtitle,
            key = TypingLog.PREF_ENABLED,
            default = { TypingLog.DEFAULT_ENABLED }
        ),

        userSettingDecorationOnly { TypingLogStatsCard() },

        userSettingDecorationOnly {
            Tip("Guardado automático: cada pocos minutos se copia todo a Documentos/FMAboard/ (una carpeta por mes). Esa copia sobrevive aunque desinstales el teclado.")
        },

        userSettingDecorationOnly {
            Tip("Nunca se registran: contraseñas, códigos (OTP), campos numéricos o de teléfono, ni apps en modo incógnito. Los cambios se aplican al abrir el siguiente campo de texto.")
        },

        userSettingNavigationItem(
            title = R.string.fmaboard_export,
            subtitle = R.string.fmaboard_export_subtitle,
            style = NavigationItemStyle.Misc,
            navigate = { nav -> triggerExportTypingLog(nav.context) }
        ),

        userSettingNavigationItem(
            title = R.string.fmaboard_delete,
            subtitle = R.string.fmaboard_delete_subtitle,
            style = NavigationItemStyle.MiscNoArrow,
            navigate = { nav -> confirmDeleteTypingLog(nav.context) }
        ),

        userSettingDecorationOnly { PersonalAutocorrectCard() },

        userSettingNavigationItem(
            title = R.string.fmaboard_autocorrect_clear,
            style = NavigationItemStyle.MiscNoArrow,
            navigate = { nav -> confirmClearAutocorrect(nav.context) }
        ),

        userSettingDecorationOnly {
            Tip("IA (\"Reescribir con IA\" y \"Responder con mi estilo\"): ábrelas desde el menú de acciones (⋯ en la barra de sugerencias). Usan modelos gratuitos de OpenRouter. Solo se envía el texto que eliges reescribir o la conversación que quieres contestar (leída de la pantalla solo al abrir \"Responder con mi estilo\", si activas \"FMAboard: contexto para responder\" en Accesibilidad, o el mensaje copiado), más un resumen de tu estilo calculado en el móvil (longitud, tildes, muletillas, emojis). Tus mensajes guardados nunca se envían.")
        },

        userSettingDecorationOnly { AiApiKeyField() },

        userSettingToggleSharedPrefs(
            title = R.string.fmaboard_ai_dictation,
            subtitle = R.string.fmaboard_ai_dictation_subtitle,
            key = AiDictation.PREF_ENABLED,
            default = { AiDictation.DEFAULT_ENABLED }
        ),
    )
)
