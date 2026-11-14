package com.metrolist.shared.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.metrolist.shared.ui.haptics.MetrolistHapticsProvider

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF1B5E20),
        onPrimary = Color.White,
        secondary = Color(0xFF388E3C),
        onSecondary = Color.White,
        background = Color(0xFFF1F8E9),
        onBackground = Color(0xFF212121),
        surface = Color.White,
        onSurface = Color(0xFF212121),
        error = Color(0xFFB00020),
        onError = Color.White,
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF81C784),
        onPrimary = Color.Black,
        secondary = Color(0xFFA5D6A7),
        onSecondary = Color.Black,
        background = Color(0xFF101010),
        onBackground = Color(0xFFEFEFEF),
        surface = Color(0xFF121212),
        onSurface = Color(0xFFEFEFEF),
        error = Color(0xFFCF6679),
        onError = Color.Black,
    )

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MetrolistTheme(
    useDarkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = if (useDarkTheme) DarkColors else LightColors

    val motionScheme = remember { MotionScheme.expressive() }
    MaterialExpressiveTheme(
        colorScheme = animateColorScheme(colors, motionScheme),
        motionScheme = motionScheme,
        typography = Typography(),
        content = { MetrolistHapticsProvider(content) },
    )
}
