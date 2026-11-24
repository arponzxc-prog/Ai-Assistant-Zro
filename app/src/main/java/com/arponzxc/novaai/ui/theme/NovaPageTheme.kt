package com.arponzxc.novaai.ui.theme

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arponzxc.novaai.JarvisApp
import com.arponzxc.novaai.R
import com.arponzxc.novaai.util.ThemeManager

/**
 * Colour tokens for the Compose pages (Storage, Camera Pill, Dev Studio, API Manager, Vision,
 * Research, File Manager). They are read from the very same theme attributes the XML pages use
 * (themeBg, themeCardBg, themeTextPrimary ...), so every page looks identical in Light, Dark,
 * Red, Sunset and Custom themes.
 *
 * Plain (non-@Composable) properties on purpose: they can be read from draw lambdas and
 * helper functions too. [NovaUi.refresh] is called once per Activity by [NovaPageTheme].
 */
object NovaUi {
    var isLight: Boolean = false; private set

    var bg: Color = Color(0xFF070914); private set
    var card: Color = Color(0x33141B3D); private set
    var cardSolid: Color = Color(0xFF111832); private set
    var border: Color = Color(0x4D5B8CFF); private set
    var divider: Color = Color(0x335B8CFF); private set
    var input: Color = Color(0x33141B3D); private set

    var textPrimary: Color = Color(0xFFF8FAFC); private set
    var textSecondary: Color = Color(0xFF94A3B8); private set
    var textMuted: Color = Color(0xFF64748B); private set

    var accent: Color = Color(0xFF5B8CFF); private set
    var accent2: Color = Color(0xFFA78BFA); private set
    var accentSoft: Color = Color(0x295B8CFF); private set
    var onAccent: Color = Color.White; private set

    var success: Color = Color(0xFF10B981); private set
    var warn: Color = Color(0xFFF59E0B); private set
    var error: Color = Color(0xFFEF4444); private set
    var successBg: Color = Color(0x2410B981); private set
    var warnBg: Color = Color(0x24F59E0B); private set
    var errorBg: Color = Color(0x24EF4444); private set

    private fun resolve(context: Context, @AttrRes attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true) &&
            tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT
        ) tv.data else fallback
    }

    fun refresh(context: Context) {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null }
        val themeKey = prefs?.appTheme ?: "sunset"
        val bgInt = ThemeManager.getThemeBgColor(themeKey, prefs?.customThemeColor)
        val light = ThemeManager.isLight(bgInt)
        isLight = light

        bg = Color(bgInt)
        card = Color(resolve(context, R.attr.themeCardBg, if (light) 0xB8FFFFFF.toInt() else 0x33141B3D))
        cardSolid = card.compositeOver(bg)
        border = Color(resolve(context, R.attr.themeCardBorder, if (light) 0xFFCBD5E1.toInt() else 0x4D5B8CFF))
        divider = border.copy(alpha = (border.alpha * 0.6f).coerceAtLeast(0.18f))
        input = Color(resolve(context, R.attr.themeInputBg, card.toArgb()))

        textPrimary = Color(resolve(context, R.attr.themeTextPrimary, if (light) 0xFF0F172A.toInt() else 0xFFF8FAFC.toInt()))
        textSecondary = Color(resolve(context, R.attr.themeTextSecondary, if (light) 0xFF475569.toInt() else 0xFF94A3B8.toInt()))
        textMuted = Color(resolve(context, R.attr.themeTextMuted, 0xFF64748B.toInt()))

        accent = Color(resolve(context, R.attr.themeAccent1, 0xFF5B8CFF.toInt()))
        accent2 = Color(resolve(context, R.attr.themeAccent2, 0xFFA78BFA.toInt()))
        accentSoft = accent.copy(alpha = if (light) 0.14f else 0.18f)
        onAccent = Color.White

        success = if (light) Color(0xFF059669) else Color(0xFF10B981)
        warn = if (light) Color(0xFFD97706) else Color(0xFFF59E0B)
        error = if (light) Color(0xFFDC2626) else Color(0xFFEF4444)
        successBg = success.copy(alpha = 0.14f)
        warnBg = warn.copy(alpha = 0.14f)
        errorBg = error.copy(alpha = 0.14f)
    }

    /** Colour scheme handed to Material3 so stock widgets (Switch, Tab, TextField...) follow the theme. */
    fun materialScheme() = if (isLight) {
        lightColorScheme(
            primary = accent, onPrimary = onAccent,
            secondary = accent2, onSecondary = onAccent,
            background = bg, onBackground = textPrimary,
            surface = cardSolid, onSurface = textPrimary,
            surfaceVariant = cardSolid, onSurfaceVariant = textSecondary,
            outline = border, error = error
        )
    } else {
        darkColorScheme(
            primary = accent, onPrimary = onAccent,
            secondary = accent2, onSecondary = onAccent,
            background = bg, onBackground = textPrimary,
            surface = cardSolid, onSurface = textPrimary,
            surfaceVariant = cardSolid, onSurfaceVariant = textSecondary,
            outline = border, error = error
        )
    }
}

/**
 * Wrap every Compose page with this. Call [ThemeManager.applyTheme] in the Activity *before*
 * setContent (exactly like the XML pages do) so the window / status bar colours match too.
 */
@Composable
fun NovaPageTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    remember(context) { NovaUi.refresh(context); true }
    MaterialTheme(colorScheme = NovaUi.materialScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = NovaUi.bg) {
            content()
        }
    }
}

/**
 * Same top bar as the XML pages: back button on the left, bold letter-spaced centred title,
 * optional action(s) on the right. Collapses gracefully on narrow phones (title is single-line
 * with ellipsis, actions never push the title off screen).
 */
@Composable
fun NovaPageHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    backTestTag: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(44.dp)
                .then(if (backTestTag != null) Modifier.testTag(backTestTag) else Modifier)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = NovaUi.textPrimary
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title.uppercase(),
                color = NovaUi.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 1.2.sp,
                lineHeight = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = NovaUi.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
        if (actions != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        } else {
            // keep the title optically centred when there is no action on the right
            Spacer(Modifier.size(44.dp))
        }
    }
}

/** Page body: themed background, centred max width, keyboard-aware. Use under [NovaPageHeader]. */
@Composable
fun NovaPageBody(
    modifier: Modifier = Modifier,
    maxWidth: androidx.compose.ui.unit.Dp = 720.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NovaUi.bg)
            .windowInsetsPadding(WindowInsets.ime),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth()) { content() }
    }
}

/** Convenience for Activities: apply the app theme to the window, then return. */
fun Activity.applyNovaWindowTheme() = ThemeManager.applyTheme(this)
