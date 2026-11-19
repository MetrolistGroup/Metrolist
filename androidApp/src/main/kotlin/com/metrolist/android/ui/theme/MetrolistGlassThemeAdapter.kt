package com.metrolist.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class ColorPalette(
    val background0: Color,
    val background1: Color,
    val background2: Color,
    val background3: Color,
    val background4: Color,
    val accent: Color,
    val onAccent: Color,
    val red: Color = Color(0xffbf4040),
    val blue: Color = Color(0xff4472cf),
    val text: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val isDark: Boolean,
    val iconButtonPlayer: Color = accent,
)

data class Appearance(
    val colorPalette: ColorPalette,
    val thumbnailShape: Shape = RoundedCornerShape(8.dp),
    val uiRoundnessShape: Shape = RoundedCornerShape(16.dp),
    val artistThumbnailShape: Shape = RoundedCornerShape(percent = 50)
)

data class Typography(
    val xxxs: TextStyle = TextStyle(),
    val xxs: TextStyle = TextStyle(),
    val xs: TextStyle = TextStyle(),
    val s: TextStyle = TextStyle(),
    val m: TextStyle = TextStyle(),
    val l: TextStyle = TextStyle(),
    val xl: TextStyle = TextStyle(),
    val xxl: TextStyle = TextStyle(),
    val xxxl: TextStyle = TextStyle(),
    val xlxl: TextStyle = TextStyle(),
)

fun Typography.withColor(color: Color): Typography = copy(
    xxxs = xxxs.copy(color = color),
    xxs = xxs.copy(color = color),
    xs = xs.copy(color = color),
    s = s.copy(color = color),
    m = m.copy(color = color),
    l = l.copy(color = color),
    xl = xl.copy(color = color),
    xxl = xxl.copy(color = color),
    xxxl = xxxl.copy(color = color),
    xlxl = xlxl.copy(color = color),
)

val LocalAppearance = compositionLocalOf {
    Appearance(
        colorPalette = ColorPalette(
            background0 = Color(0xFF121212),
            background1 = Color(0xFF1E1E1E),
            background2 = Color(0xFF2C2C2C),
            background3 = Color(0xFF383838),
            background4 = Color(0xFF484848),
            accent = Color(0xFFE5A93C),
            onAccent = Color.Black,
            text = Color.White,
            textSecondary = Color(0xFFB0B0B0),
            textDisabled = Color(0xFF707070),
            isDark = true,
        )
    )
}

val LocalPaletteFadeTarget = staticCompositionLocalOf<Appearance?> { null }
val LocalPaletteDarkness = compositionLocalOf<Float?> { null }

@Composable
fun colorPalette(): ColorPalette {
    val scheme = MaterialTheme.colorScheme
    val isDark = scheme.background.luminance() < 0.5f
    return ColorPalette(
        background0 = scheme.background,
        background1 = scheme.surface,
        background2 = scheme.surfaceVariant,
        background3 = scheme.surfaceContainer,
        background4 = scheme.surfaceContainerHigh,
        accent = scheme.primary,
        onAccent = scheme.onPrimary,
        text = scheme.onBackground,
        textSecondary = scheme.onSurfaceVariant,
        textDisabled = scheme.onSurface.copy(alpha = 0.38f),
        isDark = isDark,
        iconButtonPlayer = scheme.onPrimaryContainer
    )
}

@Composable
fun surfaceColor(): Color = MaterialTheme.colorScheme.surface

@Composable
fun uiRoundnessShape(): Shape = RoundedCornerShape(16.dp)

@Composable
fun exactUiRoundnessShape(): Shape = RoundedCornerShape(16.dp)

@Composable
fun topUiRoundnessShape(): Shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

@Composable
fun paletteDarkness(): Float = LocalPaletteDarkness.current ?: if (isSystemInDarkTheme()) 1f else 0f

object Dimensions {
    val itemsVerticalPadding: Dp = 8.dp
    val navigationRailWidth: Dp = 50.dp
    val navigationRailWidthLandscape: Dp = 128.dp
    val navigationRailIconOffset: Dp = 6.dp
    val headerHeight: Dp = 140.dp
    val halfheaderHeight: Dp = 60.dp
    val standardNavBarHeight: Dp = 64.dp
    val floatingNavBarHeight: Dp = 84.dp
    val floatingNavBarIconOnlyHeight: Dp = 64.dp
}
