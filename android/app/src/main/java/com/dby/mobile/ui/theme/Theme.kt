package com.dby.mobile.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.dby.core.Env
import com.dby.mobile.R
import com.dby.mobile.data.Prefs

/** Colour tokens from the design canvas (spec §12). */
object Dby {
    val Bg = Color(0xFF0B0B0E)
    val Fg = Color(0xFFF5F5F7)
    val Secondary = Color(0xB3FFFFFF)
    val Tertiary = Color(0x9EFFFFFF)
    val Faint = Color(0x73FFFFFF)
    val Hairline = Color(0x1FFFFFFF)
    val Fill = Color(0x12FFFFFF)
    val FillStrong = Color(0x1FFFFFFF)
    val Selected = Color(0x2EFFFFFF)
    val Outline = Color(0x29FFFFFF)
    val GlassFill = Color(0x801C1C21)
    val GlassBorder = Color(0x24FFFFFF)
    val Solid = Color(0xFF1C1C21)
    val Stripe = Color(0x09FFFFFF)
    val StripeSolid = Color(0xFF131316)
    val Scrim = Color(0x80000000)
    val Danger = Color(0xFFFF9A92)
    val DangerFill = Color(0x26FF453A)
    val Success = Color(0xFF86E8A0)
    val Accents = listOf(Color(0xFF5AC8FA), Color(0xFF30D158), Color(0xFFFF9F0A), Color(0xFFBF5AF2))
}

/** A PROD / STAGING / DEV / LOCAL badge's text and background colours. */
fun Env.colors(): Pair<Color, Color> = when (this) {
    Env.PROD -> Color(0xFFFF9A92) to Color(0x33FF453A)
    Env.STAGING -> Color(0xFFFFC56B) to Color(0x33FF9F0A)
    Env.DEV -> Color(0xFF9BDFFF) to Color(0x2E5AC8FA)
    Env.LOCAL -> Color(0xFF86E8A0) to Color(0x2E30D158)
}

val LocalAccent = compositionLocalOf { Dby.Accents[0] }

private fun geist(weight: Int, mono: Boolean) = Font(
    if (mono) R.font.geist_mono else R.font.geist,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Geist = FontFamily(geist(400, false), geist(500, false), geist(600, false), geist(700, false))
val GeistMono = FontFamily(geist(400, true), geist(500, true), geist(600, true))

/** The canvas's type scale. */
object Type {
    val LargeTitle = TextStyle(fontFamily = Geist, fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
    val Title = TextStyle(fontFamily = Geist, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
    val Section = TextStyle(fontFamily = Geist, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
    val Body = TextStyle(fontFamily = Geist, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val Secondary = TextStyle(fontFamily = Geist, fontSize = 15.sp, lineHeight = 20.sp)
    val Caption = TextStyle(fontFamily = Geist, fontSize = 13.sp, lineHeight = 17.sp)
    val Tab = TextStyle(fontFamily = Geist, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val Button = TextStyle(fontFamily = Geist, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
    val Mono = TextStyle(fontFamily = GeistMono, fontSize = 15.sp, lineHeight = 20.sp)
    val MonoSmall = TextStyle(fontFamily = GeistMono, fontSize = 13.sp, lineHeight = 18.sp)
    val MonoCode = TextStyle(fontFamily = GeistMono, fontSize = 15.sp, lineHeight = 23.sp)
    val MonoTitle = TextStyle(fontFamily = GeistMono, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp)
}

/** Accent and text size come from Settings; text size scales every sp in the app. */
@Composable
fun DbyTheme(prefs: Prefs, content: @Composable () -> Unit) {
    val accent = Color(prefs.accent)
    val scale = when (prefs.textSize) {
        Prefs.TEXT_DEFAULT -> 0.875f
        Prefs.TEXT_LARGER -> 1.125f
        else -> 1f
    }
    val density = LocalDensity.current
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent, onPrimary = Dby.Bg, background = Dby.Bg, onBackground = Dby.Fg,
            surface = Dby.Solid, onSurface = Dby.Fg, surfaceContainer = Dby.Solid,
        ),
    ) {
        CompositionLocalProvider(
            LocalAccent provides accent,
            LocalDensity provides Density(density.density, density.fontScale * scale),
            LocalContentColor provides Dby.Fg,
            LocalTextStyle provides Type.Body,
            content = content,
        )
    }
}
