/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 */
package com.metrolist.android.ui.glass.backdrop.effects

import androidx.annotation.FloatRange
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceAtMost
import com.metrolist.android.ui.glass.backdrop.BackdropEffectScope
import com.metrolist.android.ui.glass.backdrop.internal.RoundedRectRefractionShaderString
import com.metrolist.android.ui.glass.backdrop.internal.RoundedRectRefractionWithDispersionShaderString
import com.metrolist.android.ui.glass.backdrop.internal.RuntimeShaderEffect
import com.metrolist.android.ui.glass.backdrop.isRuntimeShaderSupported

fun BackdropEffectScope.lens(
    @FloatRange(from = 0.0) refractionHeight: Float,
    @FloatRange(from = 0.0) refractionAmount: Float,
    depthEffect: Boolean = false,
    dispersion: Float = 0f
) {
    if (!isRuntimeShaderSupported()) return
    if (refractionHeight <= 0f || refractionAmount <= 0f) return

    if (padding > 0f) {
        padding = (padding - refractionHeight).fastCoerceAtLeast(0f)
    }

    val cornerRadii = cornerRadii
    val effect =
        if (cornerRadii != null) {
            val shader =
                if (dispersion <= 0f) {
                    obtainRuntimeShader(
                        "Refraction",
                        RoundedRectRefractionShaderString
                    )
                } else {
                    obtainRuntimeShader(
                        "RefractionWithDispersion",
                        RoundedRectRefractionWithDispersionShaderString
                    )
                }
            shader.apply {
                setFloatUniform("size", size.width, size.height)
                setFloatUniform("offset", -padding, -padding)
                setFloatUniform("cornerRadii", cornerRadii)
                setFloatUniform("refractionHeight", refractionHeight)
                setFloatUniform("refractionAmount", -refractionAmount)
                setFloatUniform("depthEffect", if (depthEffect) 1f else 0f)
                if (dispersion > 0f) {
                    setFloatUniform("chromaticAberration", dispersion.coerceIn(0f, 1f))
                }
            }
            RuntimeShaderEffect(shader, "content")
        } else {
            throwUnsupportedSDFException()
        }
    effect(effect)
}

// Resolve size-dependent corners in the full shape's coordinates before downsampling once.
private val BackdropEffectScope.cornerRadii: FloatArray?
    get() = lensCornerRadii(shape, size, contentScale, this, layoutDirection)

/** Keeps the shader's corner geometry aligned with the full-resolution clipping outline. */
internal fun lensCornerRadii(
    shape: Shape,
    recordedSize: Size,
    contentScale: Float,
    density: Density,
    layoutDirection: LayoutDirection,
): FloatArray? {
    if (shape !is CornerBasedShape) return null
    val fullSize = Size(recordedSize.width / contentScale, recordedSize.height / contentScale)
    val maxRadius = recordedSize.minDimension / 2f
    val physicalOrder = shape is AbsoluteRoundedCornerShape || layoutDirection == LayoutDirection.Ltr
    val corners = if (physicalOrder) listOf(shape.topStart, shape.topEnd, shape.bottomEnd, shape.bottomStart)
        else listOf(shape.topEnd, shape.topStart, shape.bottomStart, shape.bottomEnd)
    return FloatArray(4) { index ->
        (corners[index].toPx(fullSize, density) * contentScale).fastCoerceAtMost(maxRadius)
    }
}
private fun throwUnsupportedSDFException(): Nothing {
    throw UnsupportedOperationException(
        "Only RoundedRectangularShape or CornerBasedShape is supported in lens effects."
    )
}
