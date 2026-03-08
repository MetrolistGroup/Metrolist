package com.metrolist.shared.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

internal actual val renderEffectBlurSupported: Boolean = true

private val desktopProgressiveEffectH by lazy {
    runCatching { RuntimeEffect.makeForShader(PROGRESSIVE_BLUR_H_SKSL) }.getOrNull()
}

private val desktopProgressiveEffectV by lazy {
    runCatching { RuntimeEffect.makeForShader(PROGRESSIVE_BLUR_V_SKSL) }.getOrNull()
}

private val desktopProgressiveHorizontalEffectH by lazy {
    runCatching { RuntimeEffect.makeForShader(PROGRESSIVE_BLUR_HORIZONTAL_H_SKSL) }.getOrNull()
}

private val desktopProgressiveHorizontalEffectV by lazy {
    runCatching { RuntimeEffect.makeForShader(PROGRESSIVE_BLUR_HORIZONTAL_V_SKSL) }.getOrNull()
}

private val desktopProgressiveVerticalEffectH by lazy {
    runCatching { RuntimeEffect.makeForShader(PROGRESSIVE_BLUR_VERTICAL_H_SKSL) }.getOrNull()
}

private val desktopProgressiveVerticalEffectV by lazy {
    runCatching { RuntimeEffect.makeForShader(PROGRESSIVE_BLUR_VERTICAL_V_SKSL) }.getOrNull()
}

@Composable
actual fun Modifier.queueVerticalBlur(
    blurHeight: Dp,
    maxRadius: Dp,
    enabled: Boolean,
): Modifier {
    if (!enabled) return this
    val density = LocalDensity.current
    val effectH = desktopProgressiveEffectH ?: return this
    val effectV = desktopProgressiveEffectV ?: return this

    return this.graphicsLayer {
        val w = size.width
        val h = size.height
        if (w > 0f && h > 0f) {
            val maxRadiusPx = with(density) { maxRadius.toPx() }
            val blurHeightPx = with(density) { blurHeight.toPx() }
            val startY = (h - blurHeightPx).coerceAtLeast(0f)
            val endY = h - with(density) { 6.dp.toPx() }

            val builderH =
                RuntimeShaderBuilder(effectH).apply {
                    uniform("blurRadius", maxRadiusPx)
                    uniform("crop", 0f, 0f, w, h)
                    uniform("startY", startY)
                    uniform("endY", endY)
                }

            val filterH =
                ImageFilter.makeRuntimeShader(
                    builderH,
                    arrayOf("content"),
                    arrayOf(null),
                )

            val builderV =
                RuntimeShaderBuilder(effectV).apply {
                    uniform("blurRadius", maxRadiusPx)
                    uniform("crop", 0f, 0f, w, h)
                    uniform("startY", startY)
                    uniform("endY", endY)
                }

            val filterV =
                ImageFilter.makeRuntimeShader(
                    builderV,
                    arrayOf("content"),
                    arrayOf(filterH),
                )

            renderEffect = filterV.asComposeRenderEffect()
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
    val density = LocalDensity.current
    val effectH = desktopProgressiveHorizontalEffectH ?: return this
    val effectV = desktopProgressiveHorizontalEffectV ?: return this

    return this.graphicsLayer {
        val w = size.width
        val h = size.height
        if (w > 0f && h > 0f) {
            val maxRadiusPx = with(density) { maxRadius.toPx() }
            val leftStartX = w * leftStartFraction
            val leftEndX = w * leftEndFraction
            val rightStartX = w * rightStartFraction
            val rightEndX = w * rightEndFraction

            val builderH =
                RuntimeShaderBuilder(effectH).apply {
                    uniform("blurRadius", maxRadiusPx)
                    uniform("crop", 0f, 0f, w, h)
                    uniform("leftStartX", leftStartX)
                    uniform("leftEndX", leftEndX)
                    uniform("rightStartX", rightStartX)
                    uniform("rightEndX", rightEndX)
                }

            val filterH =
                ImageFilter.makeRuntimeShader(
                    builderH,
                    arrayOf("content"),
                    arrayOf(null),
                )

            val builderV =
                RuntimeShaderBuilder(effectV).apply {
                    uniform("blurRadius", maxRadiusPx)
                    uniform("crop", 0f, 0f, w, h)
                    uniform("leftStartX", leftStartX)
                    uniform("leftEndX", leftEndX)
                    uniform("rightStartX", rightStartX)
                    uniform("rightEndX", rightEndX)
                }

            val filterV =
                ImageFilter.makeRuntimeShader(
                    builderV,
                    arrayOf("content"),
                    arrayOf(filterH),
                )

            renderEffect = filterV.asComposeRenderEffect()
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
    val density = LocalDensity.current
    val effectH = desktopProgressiveVerticalEffectH ?: return this
    val effectV = desktopProgressiveVerticalEffectV ?: return this

    return this.graphicsLayer {
        val w = size.width
        val h = size.height
        if (w > 0f && h > 0f) {
            val maxRadiusPx = with(density) { maxRadius.toPx() }
            val topStartY = h * topStartFraction
            val topEndY = h * topEndFraction
            val bottomStartY = h * bottomStartFraction
            val bottomEndY = h * bottomEndFraction

            val builderH =
                RuntimeShaderBuilder(effectH).apply {
                    uniform("blurRadius", maxRadiusPx)
                    uniform("crop", 0f, 0f, w, h)
                    uniform("topStartY", topStartY)
                    uniform("topEndY", topEndY)
                    uniform("bottomStartY", bottomStartY)
                    uniform("bottomEndY", bottomEndY)
                }

            val filterH =
                ImageFilter.makeRuntimeShader(
                    builderH,
                    arrayOf("content"),
                    arrayOf(null),
                )

            val builderV =
                RuntimeShaderBuilder(effectV).apply {
                    uniform("blurRadius", maxRadiusPx)
                    uniform("crop", 0f, 0f, w, h)
                    uniform("topStartY", topStartY)
                    uniform("topEndY", topEndY)
                    uniform("bottomStartY", bottomStartY)
                    uniform("bottomEndY", bottomEndY)
                }

            val filterV =
                ImageFilter.makeRuntimeShader(
                    builderV,
                    arrayOf("content"),
                    arrayOf(filterH),
                )

            renderEffect = filterV.asComposeRenderEffect()
        }
    }
}
