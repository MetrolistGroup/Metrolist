package com.metrolist.shared.ui.utils

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.blur.BlurRadiusSpec
import androidx.compose.ui.graphics.blur.BlurStop
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal actual val renderEffectBlurSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
actual fun Modifier.queueVerticalBlur(
    blurHeight: Dp,
    maxRadius: Dp,
    enabled: Boolean,
): Modifier {
    if (!enabled) return this

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return queueVerticalBlurTiramisu(blurHeight, maxRadius)
    }

    return this.blur {
        val h = size.height
        if (h > 0.dp) {
            val startFraction = (1f - (blurHeight.value / h.value)).coerceIn(0.5f, 0.95f)
            val midFraction = (startFraction + 1f) / 2f
            blurRadiusSpec =
                BlurRadiusSpec.verticalGradient(
                    stops =
                        listOf(
                            BlurStop(fraction = startFraction, radius = 0.dp),
                            BlurStop(fraction = (startFraction + midFraction) / 2f, radius = maxRadius * 0.35f),
                            BlurStop(fraction = midFraction, radius = maxRadius * 0.65f),
                            BlurStop(fraction = 1.00f, radius = maxRadius),
                        ),
                )
            edgeTreatment = BlurredEdgeTreatment.Unbounded
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.queueVerticalBlurTiramisu(
    blurHeight: Dp,
    maxRadius: Dp,
): Modifier {
    val density = LocalDensity.current
    val shaderH = remember { runCatching { RuntimeShader(PROGRESSIVE_BLUR_H_SKSL) }.getOrNull() }
    val shaderV = remember { runCatching { RuntimeShader(PROGRESSIVE_BLUR_V_SKSL) }.getOrNull() }

    if (shaderH == null || shaderV == null) {
        return this
    }

    return this.graphicsLayer {
        val w = size.width
        val h = size.height
        if (w > 0f && h > 0f) {
            val maxRadiusPx = with(density) { maxRadius.toPx() }
            val blurHeightPx = with(density) { blurHeight.toPx() }
            val startY = (h - blurHeightPx).coerceAtLeast(0f)
            val endY = h - with(density) { 6.dp.toPx() }

            shaderH.setFloatUniform("blurRadius", maxRadiusPx)
            shaderH.setFloatUniform("crop", 0f, 0f, w, h)
            shaderH.setFloatUniform("startY", startY)
            shaderH.setFloatUniform("endY", endY)

            val effectH = RenderEffect.createRuntimeShaderEffect(shaderH, "content")

            shaderV.setFloatUniform("blurRadius", maxRadiusPx)
            shaderV.setFloatUniform("crop", 0f, 0f, w, h)
            shaderV.setFloatUniform("startY", startY)
            shaderV.setFloatUniform("endY", endY)

            val effectV = RenderEffect.createRuntimeShaderEffect(shaderV, "content")
            val chained = RenderEffect.createChainEffect(effectV, effectH)

            renderEffect = chained.asComposeRenderEffect()
        }
    }
}

@Composable
actual fun Modifier.artworkHorizontalProgressiveBlur(
    leftStartFraction: Float,
    leftEndFraction: Float,
    rightStartFraction: Float,
    rightEndFraction: Float,
    maxRadius: Dp,
    enabled: Boolean,
): Modifier {
    if (!enabled) return this

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return artworkHorizontalProgressiveBlurTiramisu(
            leftStartFraction,
            leftEndFraction,
            rightStartFraction,
            rightEndFraction,
            maxRadius,
        )
    }

    return this.blur {
        val w = size.width
        if (w > 0.dp) {
            val stops = mutableListOf<BlurStop>()
            if (leftEndFraction > leftStartFraction) {
                val leftMid = (leftStartFraction + leftEndFraction) / 2f
                stops.add(BlurStop(fraction = leftStartFraction, radius = maxRadius))
                stops.add(BlurStop(fraction = (leftStartFraction + leftMid) / 2f, radius = maxRadius * 0.65f))
                stops.add(BlurStop(fraction = leftMid, radius = maxRadius * 0.35f))
                stops.add(BlurStop(fraction = leftEndFraction, radius = 0.dp))
            } else {
                stops.add(BlurStop(fraction = 0f, radius = 0.dp))
            }
            val midFraction = (rightStartFraction + rightEndFraction) / 2f
            stops.add(BlurStop(fraction = rightStartFraction, radius = 0.dp))
            stops.add(BlurStop(fraction = (rightStartFraction + midFraction) / 2f, radius = maxRadius * 0.35f))
            stops.add(BlurStop(fraction = midFraction, radius = maxRadius * 0.65f))
            stops.add(BlurStop(fraction = rightEndFraction, radius = maxRadius))

            blurRadiusSpec = BlurRadiusSpec.horizontalGradient(stops = stops)
            edgeTreatment = BlurredEdgeTreatment.Unbounded
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.artworkHorizontalProgressiveBlurTiramisu(
    leftStartFraction: Float,
    leftEndFraction: Float,
    rightStartFraction: Float,
    rightEndFraction: Float,
    maxRadius: Dp,
): Modifier {
    val density = LocalDensity.current
    val shaderH = remember { runCatching { RuntimeShader(PROGRESSIVE_BLUR_HORIZONTAL_H_SKSL) }.getOrNull() }
    val shaderV = remember { runCatching { RuntimeShader(PROGRESSIVE_BLUR_HORIZONTAL_V_SKSL) }.getOrNull() }

    if (shaderH == null || shaderV == null) {
        return this
    }

    return this.graphicsLayer {
        val w = size.width
        val h = size.height
        if (w > 0f && h > 0f) {
            val maxRadiusPx = with(density) { maxRadius.toPx() }
            val leftStartX = w * leftStartFraction
            val leftEndX = w * leftEndFraction
            val rightStartX = w * rightStartFraction
            val rightEndX = w * rightEndFraction

            shaderH.setFloatUniform("blurRadius", maxRadiusPx)
            shaderH.setFloatUniform("crop", 0f, 0f, w, h)
            shaderH.setFloatUniform("leftStartX", leftStartX)
            shaderH.setFloatUniform("leftEndX", leftEndX)
            shaderH.setFloatUniform("rightStartX", rightStartX)
            shaderH.setFloatUniform("rightEndX", rightEndX)

            val effectH = RenderEffect.createRuntimeShaderEffect(shaderH, "content")

            shaderV.setFloatUniform("blurRadius", maxRadiusPx)
            shaderV.setFloatUniform("crop", 0f, 0f, w, h)
            shaderV.setFloatUniform("leftStartX", leftStartX)
            shaderV.setFloatUniform("leftEndX", leftEndX)
            shaderV.setFloatUniform("rightStartX", rightStartX)
            shaderV.setFloatUniform("rightEndX", rightEndX)

            val effectV = RenderEffect.createRuntimeShaderEffect(shaderV, "content")
            val chained = RenderEffect.createChainEffect(effectV, effectH)

            renderEffect = chained.asComposeRenderEffect()
        }
    }
}

@Composable
actual fun Modifier.artworkVerticalProgressiveBlur(
    topStartFraction: Float,
    topEndFraction: Float,
    bottomStartFraction: Float,
    bottomEndFraction: Float,
    maxRadius: Dp,
    enabled: Boolean,
): Modifier {
    if (!enabled) return this

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return artworkVerticalProgressiveBlurTiramisu(
            topStartFraction,
            topEndFraction,
            bottomStartFraction,
            bottomEndFraction,
            maxRadius,
        )
    }

    return this.blur {
        val h = size.height
        if (h > 0.dp) {
            val stops = mutableListOf<BlurStop>()
            if (topEndFraction > topStartFraction) {
                val topMid = (topStartFraction + topEndFraction) / 2f
                stops.add(BlurStop(fraction = topStartFraction, radius = maxRadius))
                stops.add(BlurStop(fraction = (topStartFraction + topMid) / 2f, radius = maxRadius * 0.65f))
                stops.add(BlurStop(fraction = topMid, radius = maxRadius * 0.35f))
                stops.add(BlurStop(fraction = topEndFraction, radius = 0.dp))
            } else {
                stops.add(BlurStop(fraction = 0f, radius = 0.dp))
            }
            val midFraction = (bottomStartFraction + bottomEndFraction) / 2f
            stops.add(BlurStop(fraction = bottomStartFraction, radius = 0.dp))
            stops.add(BlurStop(fraction = (bottomStartFraction + midFraction) / 2f, radius = maxRadius * 0.35f))
            stops.add(BlurStop(fraction = midFraction, radius = maxRadius * 0.65f))
            stops.add(BlurStop(fraction = bottomEndFraction, radius = maxRadius))

            blurRadiusSpec = BlurRadiusSpec.verticalGradient(stops = stops)
            edgeTreatment = BlurredEdgeTreatment.Unbounded
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.artworkVerticalProgressiveBlurTiramisu(
    topStartFraction: Float,
    topEndFraction: Float,
    bottomStartFraction: Float,
    bottomEndFraction: Float,
    maxRadius: Dp,
): Modifier {
    val density = LocalDensity.current
    val shaderH = remember { runCatching { RuntimeShader(PROGRESSIVE_BLUR_VERTICAL_H_SKSL) }.getOrNull() }
    val shaderV = remember { runCatching { RuntimeShader(PROGRESSIVE_BLUR_VERTICAL_V_SKSL) }.getOrNull() }

    if (shaderH == null || shaderV == null) {
        return this
    }

    return this.graphicsLayer {
        val w = size.width
        val h = size.height
        if (w > 0f && h > 0f) {
            val maxRadiusPx = with(density) { maxRadius.toPx() }
            val topStartY = h * topStartFraction
            val topEndY = h * topEndFraction
            val bottomStartY = h * bottomStartFraction
            val bottomEndY = h * bottomEndFraction

            shaderH.setFloatUniform("blurRadius", maxRadiusPx)
            shaderH.setFloatUniform("crop", 0f, 0f, w, h)
            shaderH.setFloatUniform("topStartY", topStartY)
            shaderH.setFloatUniform("topEndY", topEndY)
            shaderH.setFloatUniform("bottomStartY", bottomStartY)
            shaderH.setFloatUniform("bottomEndY", bottomEndY)

            val effectH = RenderEffect.createRuntimeShaderEffect(shaderH, "content")

            shaderV.setFloatUniform("blurRadius", maxRadiusPx)
            shaderV.setFloatUniform("crop", 0f, 0f, w, h)
            shaderV.setFloatUniform("topStartY", topStartY)
            shaderV.setFloatUniform("topEndY", topEndY)
            shaderV.setFloatUniform("bottomStartY", bottomStartY)
            shaderV.setFloatUniform("bottomEndY", bottomEndY)

            val effectV = RenderEffect.createRuntimeShaderEffect(shaderV, "content")
            val chained = RenderEffect.createChainEffect(effectV, effectH)

            renderEffect = chained.asComposeRenderEffect()
        }
    }
}
