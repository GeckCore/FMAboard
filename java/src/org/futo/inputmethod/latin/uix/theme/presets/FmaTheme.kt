package org.futo.inputmethod.latin.uix.theme.presets

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.extendedDarkColorScheme
import org.futo.inputmethod.latin.uix.extendedLightColorScheme
import org.futo.inputmethod.latin.uix.theme.ThemeOption
import org.futo.inputmethod.latin.uix.theme.selector.ThemePreview

// FMAboard's own look: quiet greys for the keys and one accent, the violet-blue of the app icon,
// for the enter key, the swipe trail and selections. Nothing else competes for attention.
object FmaColors {
    val AccentDark = Color(0xFF7B61FF)
    val AccentLight = Color(0xFF5B3FF0)
}

val FmaDarkColors = extendedDarkColorScheme(
    primary = FmaColors.AccentDark,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF2B2456),
    onPrimaryContainer = Color(0xFFE6E0FF),
    secondary = Color(0xFFA99BFF),
    onSecondary = Color(0xFF1E1747),
    secondaryContainer = Color(0xFF5A43E0),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFF5B8CFF),
    onTertiary = Color(0xFF0A1A40),
    tertiaryContainer = Color(0xFF1F2F5C),
    onTertiaryContainer = Color(0xFFDCE6FF),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF4A0E0E),
    errorContainer = Color(0xFF7A1E1E),
    onErrorContainer = Color(0xFFFFDADA),
    outline = Color(0xFF6B6B78),
    outlineVariant = Color(0xFF26262D),
    surface = Color(0xFF0F0F13),
    onSurface = Color(0xFFEDEDF2),
    onSurfaceVariant = Color(0xFFA0A0AB),
    surfaceContainerHighest = Color(0xFF25252C),
    shadow = Color(0xFF000000).copy(alpha = 0.7f),
    keyboardSurface = Color(0xFF0F0F13),
    keyboardSurfaceDim = Color(0xFF0B0B0E),
    keyboardContainer = Color(0xFF26262D),
    keyboardContainerVariant = Color(0xFF1A1A20),
    onKeyboardContainer = Color(0xFFF2F2F5),
    keyboardPress = Color(0xFF3A3A44),
    keyboardFade0 = Color(0xFF0F0F13),
    keyboardFade1 = Color(0xFF0F0F13),
    primaryTransparent = FmaColors.AccentDark.copy(alpha = 0.3f),
    onSurfaceTransparent = Color(0xFFEDEDF2).copy(alpha = 0.1f),
    keyboardContainerPressed = Color(0xFF3A3A44),
)

val FmaLightColors = extendedLightColorScheme(
    primary = FmaColors.AccentLight,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6E0FF),
    onPrimaryContainer = Color(0xFF1E1250),
    secondary = Color(0xFF4A3BB8),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF3F27C9),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFF2563EB),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCE6FF),
    onTertiaryContainer = Color(0xFF0A1A40),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF7A7A85),
    outlineVariant = Color(0xFFD4D4DC),
    surface = Color(0xFFF4F4F7),
    onSurface = Color(0xFF111114),
    onSurfaceVariant = Color(0xFF5C5C66),
    surfaceContainerHighest = Color(0xFFE2E2E8),
    shadow = Color(0xFF000000).copy(alpha = 0.25f),
    keyboardSurface = Color(0xFFE4E5EA),
    keyboardSurfaceDim = Color(0xFFDCDDE2),
    keyboardContainer = Color(0xFFFFFFFF),
    keyboardContainerVariant = Color(0xFFCDCFD6),
    onKeyboardContainer = Color(0xFF111114),
    keyboardPress = Color(0xFFC2C4CC),
    keyboardFade0 = Color(0xFFE4E5EA),
    keyboardFade1 = Color(0xFFE4E5EA),
    primaryTransparent = FmaColors.AccentLight.copy(alpha = 0.3f),
    onSurfaceTransparent = Color(0xFF111114).copy(alpha = 0.1f),
    keyboardContainerPressed = Color(0xFFC2C4CC),
)

fun Context.isNightMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** The default: dark or light following the phone. */
val FmaAutoTheme = ThemeOption(
    dynamic = true,
    key = "FmaAuto",
    name = R.string.theme_fma_auto,
    available = { true },
    obtainColors = { if (it.isNightMode()) FmaDarkColors else FmaLightColors }
)

val FmaDarkTheme = ThemeOption(
    dynamic = false,
    key = "FmaDark",
    name = R.string.theme_fma_dark,
    available = { true },
    obtainColors = { FmaDarkColors }
)

val FmaLightTheme = ThemeOption(
    dynamic = false,
    key = "FmaLight",
    name = R.string.theme_fma_light,
    available = { true },
    obtainColors = { FmaLightColors }
)

@Composable
@Preview
private fun PreviewFmaDark() {
    ThemePreview(FmaDarkTheme)
}

@Composable
@Preview
private fun PreviewFmaLight() {
    ThemePreview(FmaLightTheme)
}
