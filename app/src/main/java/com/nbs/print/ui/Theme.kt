package com.nbs.print.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.nbs.print.R

/** The "Sheet" look: warm neutral ground, white tiles, ink type, one vermilion dot that marks true size. */
data class Palette(
    val ground: Color,
    val tile: Color,
    val ink: Color,
    /** Secondary text; at least 4.5:1 on both [ground] and [tile]. */
    val muted: Color,
    val hairline: Color,
    val accent: Color,
    /** Text/icons on top of an [ink] fill. */
    val onInk: Color,
    val ok: Color,
    val problem: Color,
)

private val LightPalette = Palette(
    ground = Color(0xFFF2F1EE),
    tile = Color(0xFFFFFFFF),
    ink = Color(0xFF111111),
    muted = Color(0xFF6F6E69),
    hairline = Color(0xFFE3E1DB),
    accent = Color(0xFFFF4F1F),
    onInk = Color(0xFFFFFFFF),
    ok = Color(0xFF2E9E5B),
    problem = Color(0xFFB3330F),
)

private val DarkPalette = Palette(
    ground = Color(0xFF0E0E0F),
    tile = Color(0xFF1B1B1D),
    ink = Color(0xFFF2F1EE),
    muted = Color(0xFFA3A29D),
    hairline = Color(0xFF2C2C2F),
    accent = Color(0xFFFF6A3D),
    onInk = Color(0xFF111111),
    ok = Color(0xFF4CC38A),
    problem = Color(0xFFFF8A63),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

val palette: Palette
    @Composable get() = LocalPalette.current

@OptIn(ExperimentalTextApi::class)
private fun hanken(weight: Int) = Font(
    resId = R.font.hanken_grotesk,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val HankenGrotesk = FontFamily(hanken(200), hanken(400), hanken(500), hanken(600), hanken(700))

/** Text styles from the concept. Uppercase is applied where the text is drawn. */
object Type {
    val micro = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, letterSpacing = 0.09.em)
    val tileValue = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium, fontSize = 26.sp, letterSpacing = (-0.02).em, lineHeight = 28.sp)
    val tileValueSmall = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = (-0.01).em, lineHeight = 19.sp)
    val caption = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
    val body = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val bodyMedium = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = (-0.01).em)
    val title = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = (-0.025).em)
    val button = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = (-0.005).em)
    val hero = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.ExtraLight, fontSize = 132.sp, letterSpacing = (-0.06).em, lineHeight = 134.sp)
    val heroUnit = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.Normal, fontSize = 38.sp)
}

private fun scheme(p: Palette, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.ink,
        onPrimary = p.onInk,
        background = p.ground,
        onBackground = p.ink,
        surface = p.tile,
        onSurface = p.ink,
        surfaceVariant = p.ground,
        onSurfaceVariant = p.muted,
        outline = p.hairline,
        outlineVariant = p.hairline,
        error = p.problem,
        secondaryContainer = p.ink,
        onSecondaryContainer = p.onInk,
    )
}

private fun typography(): Typography {
    val d = Typography()
    fun TextStyle.h() = copy(fontFamily = HankenGrotesk)
    return Typography(
        displayLarge = d.displayLarge.h(), displayMedium = d.displayMedium.h(), displaySmall = d.displaySmall.h(),
        headlineLarge = d.headlineLarge.h(), headlineMedium = d.headlineMedium.h(), headlineSmall = d.headlineSmall.h(),
        titleLarge = d.titleLarge.h(), titleMedium = d.titleMedium.h(), titleSmall = d.titleSmall.h(),
        bodyLarge = d.bodyLarge.h(), bodyMedium = d.bodyMedium.h(), bodySmall = d.bodySmall.h(),
        labelLarge = d.labelLarge.h(), labelMedium = d.labelMedium.h(), labelSmall = d.labelSmall.h(),
    )
}

@Composable
fun NbsPrintTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme(p, dark), typography = typography(), content = content)
    }
}
