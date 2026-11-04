package org.futo.inputmethod.latin.uix.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import org.futo.inputmethod.latin.uix.theme.presets.FmaDarkColors
import org.futo.inputmethod.latin.uix.theme.presets.FmaLightColors
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp

/**
 * FMAboard: iPhone-like settings look. Grouped lists on a plain background, hairline separators,
 * small coloured icon tiles, large titles. Only the settings screens use it, not the keyboard.
 */
object Ios {
    val Blue = Color(0xFF0A84FF)
    val Green = Color(0xFF34C759)
    val Orange = Color(0xFFFF9500)
    val Red = Color(0xFFFF3B30)
    val Pink = Color(0xFFFF2D55)
    val Purple = Color(0xFFAF52DE)
    val Indigo = Color(0xFF5856D6)
    val Teal = Color(0xFF30B0C7)
    val Yellow = Color(0xFFFFCC00)
    val Gray = Color(0xFF8E8E93)

    val RowTitle = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 17.sp, lineHeight = 22.sp)
    val RowSubtitle = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 18.sp)
    val Footnote = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp)
    val SectionHeader = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp,
        letterSpacing = 0.3.sp)
    val LargeTitle = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 32.sp, lineHeight = 38.sp,
        fontWeight = FontWeight.Bold)

    /** Text start inside a row: 16dp margin, plus the icon tile and its gap when there is one. */
    val RowPadding: Dp = 16.dp
    val IconTile: Dp = 30.dp
    val IconGap: Dp = 14.dp
}

/** True while composing inside an [IosGroup]: rows then draw the separator above themselves. */
val LocalIosGroup = compositionLocalOf { false }

@Composable
fun iosDividerColor(): Color = MaterialTheme.colorScheme.outlineVariant

/** Neutral iPhone greys for page, groups and secondary text; the theme keeps its accent colour. */
@Composable
fun IosSettingsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val base = if (dark) FmaDarkColors.base else FmaLightColors.base
    val scheme = remember(base, dark) {
        if (dark) base.copy(
            background = Color(0xFF000000),
            surface = Color(0xFF000000),
            surfaceContainerLowest = Color(0xFF1C1C1E),
            surfaceContainerLow = Color(0xFF1C1C1E),
            surfaceContainer = Color(0xFF1C1C1E),
            surfaceContainerHigh = Color(0xFF2C2C2E),
            surfaceContainerHighest = Color(0xFF3A3A3C),
            onBackground = Color(0xFFFFFFFF),
            onSurface = Color(0xFFFFFFFF),
            onSurfaceVariant = Color(0xFF8E8E93),
            outlineVariant = Color(0xFF38383A),
        ) else base.copy(
            background = Color(0xFFF2F2F7),
            surface = Color(0xFFF2F2F7),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFFFFFFF),
            surfaceContainer = Color(0xFFFFFFFF),
            surfaceContainerHigh = Color(0xFFFFFFFF),
            surfaceContainerHighest = Color(0xFFE5E5EA),
            onBackground = Color(0xFF000000),
            onSurface = Color(0xFF000000),
            onSurfaceVariant = Color(0xFF6C6C70),
            outlineVariant = Color(0xFFC6C6C8),
        )
    }
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes,
        content = content)
}

/** The rounded white (or dark grey) block that holds a list of rows. */
@Composable
fun IosGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val hairline = with(LocalDensity.current) { 1.dp.roundToPx() }
    Layout(
        content = { CompositionLocalProvider(LocalIosGroup provides true) { content() } },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val total = placeables.sumOf { it.height }
        // Each row draws its separator on its top edge; shifting up by one hairline hides the first one.
        val height = (total - hairline).coerceAtLeast(0)
        layout(constraints.maxWidth, height) {
            var y = -hairline
            placeables.forEach { it.place(0, y); y += it.height }
        }
    }
}

/** Grey caption above a group. */
@Composable
fun IosSectionHeader(text: String) {
    if (text.isEmpty()) { androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp)); return }
    androidx.compose.material3.Text(
        text, style = Ios.SectionHeader, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 2.dp)
    )
}

/** Grey note under a group. */
@Composable
fun IosFootnote(text: String) {
    androidx.compose.material3.Text(
        text, style = Ios.Footnote, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 2.dp, bottom = 10.dp)
    )
}

/** White glyph on a small coloured rounded square, like the icons in iPhone Settings. */
@Composable
fun IosIconTile(painter: Painter, color: Color) {
    Box(
        Modifier.size(Ios.IconTile).clip(RoundedCornerShape(7.dp)).background(color),
        contentAlignment = Alignment.Center
    ) {
        Icon(painter, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

/** Green iPhone-like switch: same-size thumb on and off, no outline. */
@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    androidx.compose.material3.Switch(
        checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
        thumbContent = {},
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Ios.Green,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            uncheckedBorderColor = Color.Transparent,
        )
    )
}
