package com.piyushbodaa.autark.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.piyushbodaa.autark.R

// Tokens from piyushbodaa.com: paper and ink, a grey for the quiet half of a headline,
// and one orange accent kept for status.
@Immutable
data class Palette(
    val paper: Color,
    val paper2: Color,
    val ink: Color,
    val ink2: Color,
    val grey: Color,
    val muted: Color,
    val line: Color,
    val pill: Color,
    val solid: Color,
    val onSolid: Color,
    val accent: Color,
)

val Day = Palette(
    paper = Color(0xFFF5F5F3),
    paper2 = Color(0xFFEBEBE8),
    ink = Color(0xFF1C1C1B),
    ink2 = Color(0xFF3A3A39),
    grey = Color(0xFF8B8B88),
    muted = Color(0xFF666663),
    line = Color(0xFFD4D4CF),
    pill = Color(0xFFE3E3DF),
    solid = Color(0xFF3A3A39),
    onSolid = Color(0xFFF5F5F3),
    accent = Color(0xFFF0552E),
)

val Night = Palette(
    paper = Color(0xFF141414),
    paper2 = Color(0xFF1D1D1C),
    ink = Color(0xFFF2F2F0),
    ink2 = Color(0xFFE2E2DF),
    grey = Color(0xFF7C7C79),
    muted = Color(0xFFA6A6A2),
    line = Color(0xFF2F2F2E),
    pill = Color(0xFF2A2A29),
    solid = Color(0xFFF2F2F0),
    onSolid = Color(0xFF141414),
    accent = Color(0xFFF0552E),
)

val LocalPalette = staticCompositionLocalOf { Day }

/** One variable font file; each weight is an instance of its wght axis. */
val InterTight = FontFamily(
    interTight(FontWeight.Normal),
    interTight(FontWeight.Medium),
    interTight(FontWeight.SemiBold),
)

// The weight alone only picks this entry; without the axis setting, every entry draws the file's
// default instance (Regular).
@OptIn(ExperimentalTextApi::class)
private fun interTight(weight: FontWeight) =
    Font(R.font.inter_tight, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

object Type {
    val body = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 23.sp, letterSpacing = (-0.005).em)
    val headline = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 30.sp, letterSpacing = (-0.012).em)
    val label = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp)
    val wordmark = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp)
    val fine = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
    val title = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 23.sp, letterSpacing = (-0.01).em)
}

private fun materialTypography(): Typography {
    val base = Typography()
    fun TextStyle.inter() = copy(fontFamily = InterTight, fontWeight = FontWeight.Medium)
    return Typography(
        displayLarge = base.displayLarge.inter(),
        displayMedium = base.displayMedium.inter(),
        displaySmall = base.displaySmall.inter(),
        headlineLarge = base.headlineLarge.inter(),
        headlineMedium = base.headlineMedium.inter(),
        headlineSmall = base.headlineSmall.inter(),
        titleLarge = base.titleLarge.inter(),
        titleMedium = base.titleMedium.inter(),
        titleSmall = base.titleSmall.inter(),
        bodyLarge = Type.body,
        bodyMedium = base.bodyMedium.inter(),
        bodySmall = base.bodySmall.inter(),
        labelLarge = base.labelLarge.inter(),
        labelMedium = base.labelMedium.inter(),
        labelSmall = base.labelSmall.inter(),
    )
}

private fun Palette.material(dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = ink, onPrimary = paper, background = paper, onBackground = ink, surface = paper, onSurface = ink,
        surfaceVariant = pill, onSurfaceVariant = muted, outline = line, outlineVariant = line,
        surfaceContainerLowest = paper, surfaceContainerLow = paper2, surfaceContainer = paper2,
        surfaceContainerHigh = pill, surfaceContainerHighest = pill, secondary = accent, error = accent,
    )
} else {
    lightColorScheme(
        primary = ink2, onPrimary = paper, background = paper, onBackground = ink, surface = paper, onSurface = ink,
        surfaceVariant = pill, onSurfaceVariant = muted, outline = line, outlineVariant = line,
        surfaceContainerLowest = paper, surfaceContainerLow = paper2, surfaceContainer = paper2,
        surfaceContainerHigh = pill, surfaceContainerHighest = pill, secondary = accent, error = accent,
    )
}

/** Wraps content in a palette; [palette] lets a section go night regardless of the system. */
@Composable
fun AutarkTheme(palette: Palette = if (isSystemInDarkTheme()) Night else Day, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = palette.material(dark = palette == Night),
            typography = materialTypography(),
            content = content,
        )
    }
}
