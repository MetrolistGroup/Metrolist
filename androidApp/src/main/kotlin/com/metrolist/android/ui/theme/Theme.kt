/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.core.view.WindowCompat
import com.metrolist.android.extensions.findActivity
import com.metrolist.android.utils.isAndroidTv
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.ThemePaletteStyle
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.FocusOutlineProvider
import com.metrolist.shared.ui.haptics.MetrolistHapticsProvider
import com.metrolist.shared.ui.theme.DefaultThemeColor
import com.metrolist.shared.ui.theme.MetrolistShapes
import com.metrolist.shared.ui.theme.ThemeColorExtractor
import com.metrolist.shared.ui.theme.animateColorScheme
import com.metrolist.shared.ui.theme.orIfIllegible
import com.metrolist.shared.ui.theme.pureBlack
import com.metrolist.shared.ui.theme.themeColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MetrolistTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlack: Boolean = false,
    themeColor: Color = DefaultThemeColor,
    dynamicColor: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lightStatusBars = !darkTheme && SystemBarAppearance.imageOwners == 0
    SideEffect {
        context.findActivity()?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightStatusBars
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    val isTv = remember(context) { context.isAndroidTv() }
    val paletteStyle = Settings.shared.themePaletteStyle

    val useSystemDynamicColor = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val contrast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) rememberSystemContrast(context) else 0f

    val baseColorScheme =
        if (useSystemDynamicColor) {
            val configuration = LocalConfiguration.current
            val wallpaperSeed = colorResource(android.R.color.system_accent1_500)
            remember(context, configuration, darkTheme, contrast, paletteStyle, wallpaperSeed) {
                val generated = { themeColorScheme(wallpaperSeed, darkTheme, paletteStyle, contrast.toDouble()) }
                if (paletteStyle == ThemePaletteStyle.TONAL_SPOT) {
                    systemDynamicColorScheme(context, darkTheme).orIfIllegible(darkTheme) {
                        Timber.w("System dynamic colors are illegible on %s; generating from the wallpaper seed", Build.MANUFACTURER)
                        generated()
                    }
                } else {
                    generated()
                }
            }
        } else {
            val generateScheme = {
                themeColorScheme(themeColor, darkTheme, paletteStyle, contrast.toDouble())
            }
            key(darkTheme, contrast) {
                val initial = remember { (themeColor to paletteStyle) to generateScheme() }
                val scheme by produceState(
                    initialValue = initial.second,
                    key1 = themeColor,
                    key2 = paletteStyle,
                ) {
                    value =
                        if (initial.first == (themeColor to paletteStyle)) {
                            initial.second
                        } else {
                            withContext(Dispatchers.Default) { generateScheme() }
                        }
                }
                scheme
            }
        }

    val colorScheme =
        remember(baseColorScheme, pureBlack, darkTheme) {
            if (darkTheme && pureBlack) {
                baseColorScheme.pureBlack()
            } else {
                baseColorScheme
            }
        }

    val motionScheme = remember { MotionScheme.expressive() }
    val animatedColorScheme = animateColorScheme(colorScheme, motionScheme)
    val background = colorScheme.surface.toArgb()
    DisposableEffect(context, background) {
        context.findActivity()?.window?.setBackgroundDrawable(ColorDrawable(background))
        onDispose { }
    }

    MaterialExpressiveTheme(
        colorScheme = animatedColorScheme,
        typography = getAppTypography(),
        shapes = MetrolistShapes,
        motionScheme = motionScheme,
    ) {
        CompositionLocalProvider(LocalIsTv provides isTv) {
            MetrolistHapticsProvider {
                FocusOutlineProvider(enabled = isTv, content = content)
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@Composable
private fun rememberSystemContrast(context: Context): Float {
    val modeManager = remember(context) { context.getSystemService(UiModeManager::class.java) }
    var contrast by remember(modeManager) { mutableFloatStateOf(modeManager.contrast) }
    DisposableEffect(modeManager) {
        val listener = UiModeManager.ContrastChangeListener { contrast = it }
        modeManager.addContrastChangeListener(context.mainExecutor, listener)
        contrast = modeManager.contrast
        onDispose { modeManager.removeContrastChangeListener(listener) }
    }
    return contrast
}

fun Bitmap.extractThemeColor(): Color = ThemeColorExtractor().extractSeedColor(this)

internal object SystemBarAppearance {
    var imageOwners by mutableIntStateOf(0)
}
