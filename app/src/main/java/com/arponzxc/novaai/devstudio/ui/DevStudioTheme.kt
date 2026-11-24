package com.arponzxc.novaai.devstudio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.arponzxc.novaai.ui.theme.NovaPageTheme
import com.arponzxc.novaai.ui.theme.NovaUi

/**
 * Dev Studio colours now come from the app theme ([NovaUi]) so this page looks exactly like
 * every other page (Light / Dark / Red / Sunset / Custom). Property getters (not vals) so the
 * values always reflect the theme that is active when the screen is drawn.
 */
object StudioPalette {
    val BgCanvas: Color get() = NovaUi.bg
    val BgSurface: Color get() = NovaUi.cardSolid
    val BgCard: Color get() = NovaUi.cardSolid
    val BgElevated: Color get() = NovaUi.cardSolid
    val BorderSubtle: Color get() = NovaUi.border

    val CyanAccent: Color get() = NovaUi.accent
    val IndigoAccent: Color get() = NovaUi.accent2
    val EmeraldSuccess: Color get() = NovaUi.success
    val AmberWarn: Color get() = NovaUi.warn
    val RoseError: Color get() = NovaUi.error

    val TextPrimary: Color get() = NovaUi.textPrimary
    val TextSecondary: Color get() = NovaUi.textSecondary
    val TextMuted: Color get() = NovaUi.textMuted

    // Code editor surfaces stay readable in both light and dark themes.
    val CodeBg: Color get() = if (NovaUi.isLight) Color(0xFFF1F5F9) else Color(0xFF090E1A)
    val CodeKeyword: Color get() = if (NovaUi.isLight) Color(0xFF7C3AED) else Color(0xFFC084FC)
    val CodeString: Color get() = if (NovaUi.isLight) Color(0xFF059669) else Color(0xFF34D399)
    val CodeType: Color get() = if (NovaUi.isLight) Color(0xFF0369A1) else Color(0xFF38BDF8)
    val CodeComment: Color get() = Color(0xFF64748B)
}

private val StudioTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.SansSerif
    )
)

@Composable
fun DevStudioTheme(content: @Composable () -> Unit) {
    NovaPageTheme {
        MaterialTheme(
            colorScheme = NovaUi.materialScheme(),
            typography = StudioTypography,
            content = content
        )
    }
}
