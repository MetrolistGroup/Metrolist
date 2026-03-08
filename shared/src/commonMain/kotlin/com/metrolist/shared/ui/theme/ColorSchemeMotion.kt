package com.metrolist.shared.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.lerp

@Composable
fun animateColorScheme(
    colorScheme: ColorScheme,
    motionScheme: MotionScheme,
): ColorScheme {
    val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val enabled = scale > 0f && !rememberReducedMotion()
    val progress = remember { Animatable(1f) }
    var start by remember { mutableStateOf(colorScheme) }
    var end by remember { mutableStateOf(colorScheme) }
    val displayed =
        interpolateColorScheme(
            start = start,
            end = if (enabled) end else colorScheme,
            fraction = if (enabled) progress.value else 1f,
            enabled = enabled,
        )
    val current by rememberUpdatedState(displayed)
    LaunchedEffect(colorScheme, motionScheme, enabled) {
        start = current
        end = colorScheme
        val animate = enabled && start !== end
        progress.snapTo(if (animate) 0f else 1f)
        if (animate) progress.animateTo(1f, motionScheme.defaultEffectsSpec())
    }
    return displayed
}

internal fun interpolateColorScheme(
    start: ColorScheme,
    end: ColorScheme,
    fraction: Float,
    enabled: Boolean = true,
): ColorScheme =
    if (!enabled || start === end) {
        end
    } else {
        start.copy(
            primary = lerp(start.primary, end.primary, fraction),
            onPrimary = lerp(start.onPrimary, end.onPrimary, fraction),
            primaryContainer = lerp(start.primaryContainer, end.primaryContainer, fraction),
            onPrimaryContainer = lerp(start.onPrimaryContainer, end.onPrimaryContainer, fraction),
            inversePrimary = lerp(start.inversePrimary, end.inversePrimary, fraction),
            secondary = lerp(start.secondary, end.secondary, fraction),
            onSecondary = lerp(start.onSecondary, end.onSecondary, fraction),
            secondaryContainer = lerp(start.secondaryContainer, end.secondaryContainer, fraction),
            onSecondaryContainer = lerp(start.onSecondaryContainer, end.onSecondaryContainer, fraction),
            tertiary = lerp(start.tertiary, end.tertiary, fraction),
            onTertiary = lerp(start.onTertiary, end.onTertiary, fraction),
            tertiaryContainer = lerp(start.tertiaryContainer, end.tertiaryContainer, fraction),
            onTertiaryContainer = lerp(start.onTertiaryContainer, end.onTertiaryContainer, fraction),
            background = lerp(start.background, end.background, fraction),
            onBackground = lerp(start.onBackground, end.onBackground, fraction),
            surface = lerp(start.surface, end.surface, fraction),
            onSurface = lerp(start.onSurface, end.onSurface, fraction),
            surfaceVariant = lerp(start.surfaceVariant, end.surfaceVariant, fraction),
            onSurfaceVariant = lerp(start.onSurfaceVariant, end.onSurfaceVariant, fraction),
            surfaceTint = lerp(start.surfaceTint, end.surfaceTint, fraction),
            inverseSurface = lerp(start.inverseSurface, end.inverseSurface, fraction),
            inverseOnSurface = lerp(start.inverseOnSurface, end.inverseOnSurface, fraction),
            error = lerp(start.error, end.error, fraction),
            onError = lerp(start.onError, end.onError, fraction),
            errorContainer = lerp(start.errorContainer, end.errorContainer, fraction),
            onErrorContainer = lerp(start.onErrorContainer, end.onErrorContainer, fraction),
            outline = lerp(start.outline, end.outline, fraction),
            outlineVariant = lerp(start.outlineVariant, end.outlineVariant, fraction),
            scrim = lerp(start.scrim, end.scrim, fraction),
            surfaceBright = lerp(start.surfaceBright, end.surfaceBright, fraction),
            surfaceDim = lerp(start.surfaceDim, end.surfaceDim, fraction),
            surfaceContainer = lerp(start.surfaceContainer, end.surfaceContainer, fraction),
            surfaceContainerHigh = lerp(start.surfaceContainerHigh, end.surfaceContainerHigh, fraction),
            surfaceContainerHighest = lerp(start.surfaceContainerHighest, end.surfaceContainerHighest, fraction),
            surfaceContainerLow = lerp(start.surfaceContainerLow, end.surfaceContainerLow, fraction),
            surfaceContainerLowest = lerp(start.surfaceContainerLowest, end.surfaceContainerLowest, fraction),
            primaryFixed = lerp(start.primaryFixed, end.primaryFixed, fraction),
            primaryFixedDim = lerp(start.primaryFixedDim, end.primaryFixedDim, fraction),
            onPrimaryFixed = lerp(start.onPrimaryFixed, end.onPrimaryFixed, fraction),
            onPrimaryFixedVariant = lerp(start.onPrimaryFixedVariant, end.onPrimaryFixedVariant, fraction),
            secondaryFixed = lerp(start.secondaryFixed, end.secondaryFixed, fraction),
            secondaryFixedDim = lerp(start.secondaryFixedDim, end.secondaryFixedDim, fraction),
            onSecondaryFixed = lerp(start.onSecondaryFixed, end.onSecondaryFixed, fraction),
            onSecondaryFixedVariant = lerp(start.onSecondaryFixedVariant, end.onSecondaryFixedVariant, fraction),
            tertiaryFixed = lerp(start.tertiaryFixed, end.tertiaryFixed, fraction),
            tertiaryFixedDim = lerp(start.tertiaryFixedDim, end.tertiaryFixedDim, fraction),
            onTertiaryFixed = lerp(start.onTertiaryFixed, end.onTertiaryFixed, fraction),
            onTertiaryFixedVariant = lerp(start.onTertiaryFixedVariant, end.onTertiaryFixedVariant, fraction),
        )
    }

@Composable
internal expect fun rememberReducedMotion(): Boolean
