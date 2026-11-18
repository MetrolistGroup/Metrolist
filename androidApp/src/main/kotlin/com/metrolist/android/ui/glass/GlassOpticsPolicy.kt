package com.metrolist.android.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/** Suspends sampling without changing material preferences or geometry. */
val LocalGlassOpticsEnabled = compositionLocalOf { true }

/** The expanded endpoint requests full optics; deployment fades to the inexpensive fill. */
internal fun playerGlassOpticsEnabled(isExpanded: Boolean): Boolean = isExpanded

/** True only when opaque paint covers the viewport; visible decorations may then pause. */
val LocalGlassUnderlayCovered = compositionLocalOf { false }

/** Any modal/deployment may lower sampling cost while still-visible animations continue. */
val LocalGlassOpticalUnderlayCovered = compositionLocalOf { false }

/** Shared deployment clock; modal coverage still has its own independent fade. */
val LocalGlassOpticsTransitionProgress = compositionLocalOf { 1f }

/** Effective sampling budget; source recordings remain available to foreground overlays. */
@Composable
fun glassOpticsEnabled(settings: GlassSettings = rememberGlassSettings()): Boolean {
    // Keep composition-local reads outside short circuits so remembered animation groups stay stable.
    val enabled = LocalGlassOpticsEnabled.current
    val covered = LocalGlassUnderlayCovered.current
    val opticalCovered = LocalGlassOpticalUnderlayCovered.current
    return enabled && !covered && (!settings.optimizationEnabled || !opticalCovered)
}

internal const val GLASS_OPTICS_FADE_MILLIS = 280

/** The target is immediate; sampling stops only after the short optical fade finishes. */
@Composable
fun glassOpticsProgress(): Float = glassOpticsProgress(glassOpticsEnabled())

@Composable
fun glassOpticsProgress(enabled: Boolean): Float = glassOpticsProgressState(enabled).value

/** Read this clock during drawing so modifier-returning material lambdas need not recompose. */
@Composable
internal fun glassOpticsProgressState(enabled: Boolean): State<Float> {
    val gate = glassOpticsGateProgressState(enabled)
    val deployment = rememberUpdatedState(LocalGlassOpticsTransitionProgress.current)
    return remember(gate, deployment) { derivedStateOf { gate.value * deployment.value } }
}

/** The global gate without a deployment multiplier, for source-owner capture lifetime. */
@Composable
internal fun glassOpticsGateProgress(enabled: Boolean): Float {
    return glassOpticsGateProgressState(enabled).value
}

@Composable
private fun glassOpticsGateProgressState(enabled: Boolean): State<Float> =
    animateFloatAsState(if (enabled) 1f else 0f,
        animationSpec = tween(GLASS_OPTICS_FADE_MILLIS), label = "glassOptics")

/** Fades only the live sampled scene, preserving film and foreground content. */
fun DrawScope.drawGlassOptics(progress: Float, draw: DrawScope.() -> Unit) {
    if (progress >= 1f) { draw(); return }
    if (progress <= 0f) return
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint().apply { alpha = progress })
    try { draw() } finally { canvas.restore() }
}
