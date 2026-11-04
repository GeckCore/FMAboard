package org.futo.inputmethod.latin.fmaboard

import android.content.Intent
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import org.futo.inputmethod.latin.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import org.futo.inputmethod.latin.uix.settings.Ios
import org.futo.inputmethod.latin.uix.settings.IosFootnote
import org.futo.inputmethod.latin.uix.settings.IosGroup
import org.futo.inputmethod.latin.uix.settings.IosIconTile
import org.futo.inputmethod.latin.uix.settings.IosSectionHeader
import org.futo.inputmethod.latin.uix.settings.LocalIosGroup
import org.futo.inputmethod.latin.uix.settings.SettingItem
import org.futo.inputmethod.latin.uix.settings.iosDividerColor
import org.futo.inputmethod.latin.uix.LocalNavController
import org.futo.inputmethod.latin.uix.PreferenceUtils
import org.futo.inputmethod.latin.uix.settings.NavigationItem
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.settings.userSettingToggleSharedPrefs
import org.futo.inputmethod.updates.openURI

// Bumped when a key or the provider changes, so the status card and the home entry refresh.
internal val aiSettingsGeneration = mutableIntStateOf(0)

@Composable
internal fun SectionHeader(text: String) = IosSectionHeader(text)

/** True when the AI can answer with the keys and the choice saved now. */
@Composable
internal fun rememberAiReady(): Boolean {
    val context = LocalContext.current
    val generation = aiSettingsGeneration.intValue
    return remember(generation) { AiRewrite.hasApiKey(context) }
}

// Password field so the keyboard's own typing log skips it. Saves as you type. Drawn as a row of the group.
@Composable
private fun AiApiKeyField(pref: String, placeholder: String) {
    val context = LocalContext.current
    val prefs = remember { PreferenceUtils.getDefaultSharedPreferences(context) }
    var key by remember { mutableStateOf(prefs.getString(pref, "") ?: "") }
    val divider = iosDividerColor()
    val inGroup = LocalIosGroup.current

    Box(
        Modifier.fillMaxWidth()
            .let { if (inGroup) it.drawBehind { drawLine(divider, Offset(16.dp.toPx(), 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) } else it }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        if (key.isEmpty()) Text(placeholder, style = Ios.RowTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(
            value = key,
            onValueChange = {
                key = it.trim()
                prefs.edit { putString(pref, key) }
                aiSettingsGeneration.intValue++
            },
            singleLine = true,
            textStyle = Ios.RowTitle.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun keyField(pref: String, placeholder: String) =
    UserSetting(name = 0, appearsInSearch = false, grouped = true) { AiApiKeyField(pref, placeholder) }

// Which AI service answers first, and whether the other one steps in when it fails: an iPhone checkmark list
@Composable
private fun AiProviderChooser() {
    val context = LocalContext.current
    val prefs = remember { PreferenceUtils.getDefaultSharedPreferences(context) }
    var mode by remember { mutableStateOf(AiRewrite.mode(context)) }
    IosGroup {
        AiRewrite.Mode.entries.forEach { m ->
            SettingItem(title = m.label, compact = true, onClick = {
                mode = m
                prefs.edit { putString(AiRewrite.PREF_PROVIDER, m.id) }
                aiSettingsGeneration.intValue++
            }) {
                if (mode == m) Icon(Icons.Default.Check, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
        }
    }
    IosFootnote("Solo importa si pones las dos claves. Si la primera falla o está saturada, responde la otra.")
}

@Composable
private fun AiStatusCard() {
    val ready = rememberAiReady()
    IosGroup {
        SettingItem(
            title = if (ready) "La IA está lista" else "Falta tu clave gratuita",
            subtitle = if (ready) "Úsala desde las acciones del teclado: «Reescribir con IA» y «Responder con mi estilo»."
                else "Es opcional. Toca «Conseguir clave gratis», inicia sesión, pulsa «Create API key» y pega la clave aquí abajo.",
            icon = { IosIconTile(painterResource(R.drawable.fmaboard_sparkle), if (ready) Ios.Green else Ios.Orange) }
        ) {}
    }
}

val AiMenu = UserSettingsMenu(
    title = R.string.fmaboard_ai_title,
    navPath = "ai", registerNavPath = true,
    settings = listOf(
        userSettingDecorationOnly { AiStatusCard() },

        userSettingDecorationOnly { SectionHeader("Gemini (recomendada)") },
        userSettingNavigationItem(
            title = R.string.fmaboard_ai_get_gemini,
            subtitle = R.string.fmaboard_ai_get_gemini_subtitle,
            style = NavigationItemStyle.ExternalLink,
            icon = R.drawable.unlock,
            iconColor = Ios.Blue,
            navigate = { nav -> nav.context.openURI("https://aistudio.google.com/apikey", newTask = true) }
        ),
        keyField(AiRewrite.PREF_GEMINI_KEY, "Pega aquí tu clave de Gemini"),

        userSettingDecorationOnly { SectionHeader("OpenRouter (opcional, de reserva)") },
        userSettingNavigationItem(
            title = R.string.fmaboard_ai_get_openrouter,
            subtitle = R.string.fmaboard_ai_get_openrouter_subtitle,
            style = NavigationItemStyle.ExternalLink,
            icon = R.drawable.unlock,
            iconColor = Ios.Blue,
            navigate = { nav -> nav.context.openURI("https://openrouter.ai/keys", newTask = true) }
        ),
        keyField(AiRewrite.PREF_API_KEY, "Pega aquí tu clave de OpenRouter"),

        userSettingDecorationOnly { SectionHeader("Qué IA usar") },
        userSettingDecorationOnly { AiProviderChooser() },

        userSettingDecorationOnly { SectionHeader("Opciones") },
        userSettingToggleSharedPrefs(
            title = R.string.fmaboard_google_voice,
            subtitle = R.string.fmaboard_google_voice_subtitle,
            key = GoogleDictation.PREF_ENABLED,
            default = { GoogleDictation.DEFAULT_ENABLED }
        ),
        userSettingToggleSharedPrefs(
            title = R.string.fmaboard_ai_dictation,
            subtitle = R.string.fmaboard_ai_dictation_subtitle,
            key = AiDictation.PREF_ENABLED,
            default = { AiDictation.DEFAULT_ENABLED }
        ),
        userSettingNavigationItem(
            title = R.string.fmaboard_ai_accessibility,
            subtitle = R.string.fmaboard_ai_accessibility_subtitle,
            style = NavigationItemStyle.Misc,
            navigate = { nav ->
                nav.context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        ),

        userSettingDecorationOnly {
            Tip("Privacidad: la IA solo se usa cuando tú la pides o dictas por voz. Se envía el texto que quieres reescribir, la conversación que quieres contestar o el audio que dictas (nunca en campos de contraseña ni en modo incógnito), y un resumen de tu estilo calculado en el móvil (longitud, tildes, emojis…). Lo que FMAboard ha aprendido de ti nunca se envía. La clave se guarda solo en este móvil.")
        },
    )
)

// One row of the guide: coloured icon, name and a short explanation
private fun guideCard(@DrawableRes icon: Int, title: String, text: String, color: Color, navigateTo: String? = null) =
    UserSetting(name = 0, appearsInSearch = false, grouped = true) {
        val nav = LocalNavController.current
        val faint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        SettingItem(
            title = title, subtitle = text,
            icon = { IosIconTile(painterResource(icon), color) },
            onClick = navigateTo?.let { { nav?.navigate(it) } }
        ) {
            if (navigateTo != null) Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = faint,
                modifier = Modifier.size(24.dp))
        }
    }

val GuideMenu = UserSettingsMenu(
    title = R.string.fmaboard_guide_title,
    navPath = "fmaboardGuide", registerNavPath = true,
    settings = listOf(
        userSettingDecorationOnly {
            Tip("Casi todo está en las acciones del teclado: toca la flecha de la izquierda de la barra de sugerencias (encima de las letras). Salen tus acciones favoritas, y el primer botón abre «Todas las acciones». Abajo puedes elegir cuáles tienes más a mano.")
        },
        guideCard(R.drawable.text_prediction, "Frases sugeridas",
            "Cuando empiezas una frase que sueles escribir, te ofrece terminarla en la barra de sugerencias. Mantén pulsada una frase para que la olvide.", Ios.Indigo),
        guideCard(R.drawable.keyboard, "Autocorrector personal",
            "Aprende cómo escribes tú: si deshaces una corrección no la repite, y si te equivocas siempre igual, te lo corrige.",
            Ios.Blue, navigateTo = TypingLogMenu.navPath),
        guideCard(R.drawable.clipboard, "Portapapeles",
            "Guarda lo que copias. Puedes fijar clips, escribir notas, buscar, borrar varios a la vez, pegar capturas de pantalla y abrir enlaces sin rastreo.", Ios.Gray),
        guideCard(R.drawable.image, "Texto de imagen",
            "Copia el texto de una foto o captura, sin internet. Reconoce código y conserva la sangría.", Ios.Teal),
        guideCard(R.drawable.fmaboard_sticker, "Crear sticker",
            "Convierte una foto, vídeo o GIF en sticker de WhatsApp: recórtalo, quítale el fondo y añade texto. Desde Compartir, «Sticker al momento» lo hace en un toque.", Ios.Green),
        guideCard(R.drawable.mic, "Mensaje de voz",
            "Convierte lo que escribes en un audio con voz natural y lo envía al chat. La voz se descarga la primera vez (${VoiceMessage.downloadBytes / 1_000_000} MB) y funciona sin internet.", Ios.Red),
        userSettingNavigationItem(
            title = R.string.fmaboard_voice_delete,
            subtitle = R.string.fmaboard_voice_delete_subtitle,
            style = NavigationItemStyle.MiscNoArrow,
            navigate = { nav -> confirmDeleteVoice(nav.context) }
        ),
        guideCard(R.drawable.globe, "Descargar enlace",
            "Descarga un vídeo o su audio desde un enlace y lo envía al chat.", Ios.Blue),
        guideCard(R.drawable.fmaboard_sparkle, "Inteligencia artificial",
            "Reescribe textos, responde con tu estilo y pule el dictado. Es opcional y gratuita. Toca aquí para activarla.",
            Ios.Purple, navigateTo = AiMenu.navPath),
        userSettingDecorationOnly { IosSectionHeader("") },
        userSettingNavigationItem(
            title = R.string.fmaboard_guide_actions,
            style = NavigationItemStyle.Misc,
            navigateTo = "actions",
            icon = R.drawable.smile,
            iconColor = Ios.Yellow
        ),
    )
)

/** Home entry for the AI screen, telling at a glance whether it still needs a key. */
val AiHomeEntry = UserSetting(name = R.string.fmaboard_ai_title, searchTags = R.string.fmaboard_ai_search_tags) {
    val nav = LocalNavController.current
    NavigationItem(
        title = androidx.compose.ui.res.stringResource(R.string.fmaboard_ai_title),
        subtitle = if (rememberAiReady()) "Lista para usar" else "Opcional · falta tu clave gratuita",
        style = NavigationItemStyle.Misc,
        icon = painterResource(R.drawable.fmaboard_sparkle),
        iconColor = Ios.Purple,
        navigate = { nav?.navigate(AiMenu.navPath) }
    )
}
