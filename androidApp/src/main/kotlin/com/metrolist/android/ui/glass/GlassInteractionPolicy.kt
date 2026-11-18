package com.metrolist.android.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalGlassInteractionSettings = staticCompositionLocalOf<GlassSettings?> { null }

@Composable
internal fun glassInteractionSettings(): GlassSettings =
    LocalGlassInteractionSettings.current ?: rememberGlassSettings()


/** Independent visual coefficients; gesture ownership and optical material are unchanged. */
internal data class GlassInteractionPolicy(
    val rippleIntensity: Float = 1f,
    val rippleSize: Float = 1f,
    val jellyIntensity: Float = 1f,
)

internal fun GlassSettings.interactionPolicy(): GlassInteractionPolicy = GlassInteractionPolicy(
    rippleIntensity = if (rippleEnabled) clampGlassValue(glassRippleIntensityKey, rippleIntensity) else 0f,
    rippleSize = clampGlassValue(glassRippleSizeKey, rippleSize),
    jellyIntensity = if (jellyEnabled) clampGlassValue(glassJellyIntensityKey, jellyIntensity) else 0f,
)
