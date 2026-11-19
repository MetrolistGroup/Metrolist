/*
 * Vendored from Kyant0/Capsule
 * https://github.com/Kyant0/Capsule - Copyright 2025 Kyant0, Apache License 2.0
 * capsule/src/main/java/com/kyant/capsule/ContinuousRoundedRectangle.kt
 *
 * Vendored so the shape ships as source with this app (not published to
 * Maven Central). Package renamed accordingly, and the AbsoluteContinuous*
 * variants were dropped - this app only uses the LTR-aware
 * ContinuousRoundedRectangle.
 */
package com.metrolist.android.ui.glass.shapes

import androidx.annotation.FloatRange
import androidx.annotation.IntRange
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.LayoutDirection.Ltr
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import kotlin.math.min

@Immutable
open class ContinuousRoundedRectangle(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
    open val continuity: Continuity = Continuity.Default
) : CornerBasedShape(
    topStart = topStart,
    topEnd = topEnd,
    bottomEnd = bottomEnd,
    bottomStart = bottomStart
) {

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection
    ): Outline {
        // rectangle
        if (topStart + topEnd + bottomEnd + bottomStart == 0f) {
            return Outline.Rectangle(size.toRect())
        }

        val maxRadius = min(size.width, size.height) * 0.5f
        val topLeft = (if (layoutDirection == Ltr) topStart else topEnd).fastCoerceIn(0f, maxRadius)
        val topRight = (if (layoutDirection == Ltr) topEnd else topStart).fastCoerceIn(0f, maxRadius)
        val bottomRight = (if (layoutDirection == Ltr) bottomEnd else bottomStart).fastCoerceIn(0f, maxRadius)
        val bottomLeft = (if (layoutDirection == Ltr) bottomStart else bottomEnd).fastCoerceIn(0f, maxRadius)

        return continuity.createRoundedRectangleOutline(
            size = size,
            topLeft = topLeft,
            topRight = topRight,
            bottomRight = bottomRight,
            bottomLeft = bottomLeft
        )
    }

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize
    ): ContinuousRoundedRectangle {
        return ContinuousRoundedRectangle(
            topStart = topStart,
            topEnd = topEnd,
            bottomEnd = bottomEnd,
            bottomStart = bottomStart,
            continuity = continuity
        )
    }

    fun copy(
        topStart: CornerSize = this.topStart,
        topEnd: CornerSize = this.topEnd,
        bottomEnd: CornerSize = this.bottomEnd,
        bottomStart: CornerSize = this.bottomStart,
        continuity: Continuity = this.continuity
    ): ContinuousRoundedRectangle {
        return ContinuousRoundedRectangle(
            topStart = topStart,
            topEnd = topEnd,
            bottomEnd = bottomEnd,
            bottomStart = bottomStart,
            continuity = continuity
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContinuousRoundedRectangle) return false

        if (topStart != other.topStart) return false
        if (topEnd != other.topEnd) return false
        if (bottomEnd != other.bottomEnd) return false
        if (bottomStart != other.bottomStart) return false
        if (continuity != other.continuity) return false

        return true
    }

    override fun hashCode(): Int {
        var result = topStart.hashCode()
        result = 31 * result + topEnd.hashCode()
        result = 31 * result + bottomEnd.hashCode()
        result = 31 * result + bottomStart.hashCode()
        result = 31 * result + continuity.hashCode()
        return result
    }

    override fun toString(): String {
        return "ContinuousRoundedRectangle(topStart=$topStart, topEnd=$topEnd, bottomEnd=$bottomEnd, " +
                "bottomStart=$bottomStart, continuity=$continuity)"
    }
}

@Stable
val ContinuousRectangle: ContinuousRoundedRectangle = ContinuousRectangleImpl()

@Suppress("FunctionName")
@Stable
fun ContinuousRectangle(continuity: Continuity = Continuity.Default): ContinuousRoundedRectangle =
    ContinuousRectangleImpl(continuity)

@Immutable
private data class ContinuousRectangleImpl(
    override val continuity: Continuity = Continuity.Default
) : ContinuousRoundedRectangle(
    topStart = ZeroCornerSize,
    topEnd = ZeroCornerSize,
    bottomEnd = ZeroCornerSize,
    bottomStart = ZeroCornerSize,
    continuity = continuity
) {

    override fun toString(): String {
        return "ContinuousRectangle(continuity=$continuity)"
    }
}

private val FullCornerSize = CornerSize(50)

@Stable
val ContinuousCapsule: ContinuousRoundedRectangle = ContinuousCapsule()

@Suppress("FunctionName")
@Stable
fun ContinuousCapsule(continuity: Continuity = Continuity.Default): ContinuousRoundedRectangle =
    ContinuousCapsuleImpl(continuity)

@Immutable
private data class ContinuousCapsuleImpl(
    override val continuity: Continuity = Continuity.Default
) : ContinuousRoundedRectangle(
    topStart = FullCornerSize,
    topEnd = FullCornerSize,
    bottomEnd = FullCornerSize,
    bottomStart = FullCornerSize,
    continuity = continuity
) {

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection
    ): Outline = continuity.createCapsuleOutline(size)

    override fun toString(): String {
        return "ContinuousCapsule(continuity=$continuity)"
    }
}

@Stable
fun ContinuousRoundedRectangle(
    corner: CornerSize,
    continuity: Continuity = Continuity.Default
): ContinuousRoundedRectangle =
    ContinuousRoundedRectangle(
        topStart = corner,
        topEnd = corner,
        bottomEnd = corner,
        bottomStart = corner,
        continuity = continuity
    )

@Stable
fun ContinuousRoundedRectangle(
    size: Dp,
    continuity: Continuity = Continuity.Default
): ContinuousRoundedRectangle =
    ContinuousRoundedRectangle(
        corner = CornerSize(size),
        continuity = continuity
    )

@Stable
fun ContinuousRoundedRectangle(
    @FloatRange(from = 0.0) size: Float,
    continuity: Continuity = Continuity.Default
): ContinuousRoundedRectangle =
    ContinuousRoundedRectangle(
        corner = CornerSize(size),
        continuity = continuity
    )

@Stable
fun ContinuousRoundedRectangle(
    @IntRange(from = 0, to = 100) percent: Int,
    continuity: Continuity = Continuity.Default
): ContinuousRoundedRectangle =
    ContinuousRoundedRectangle(
        corner = CornerSize(percent),
        continuity = continuity
    )

@Stable
fun ContinuousRoundedRectangle(
    topStart: Dp = 0f.dp,
    topEnd: Dp = 0f.dp,
    bottomEnd: Dp = 0f.dp,
    bottomStart: Dp = 0f.dp,
    continuity: Continuity = Continuity.Default
): ContinuousRoundedRectangle =
    ContinuousRoundedRectangle(
        topStart = CornerSize(topStart),
        topEnd = CornerSize(topEnd),
        bottomEnd = CornerSize(bottomEnd),
        bottomStart = CornerSize(bottomStart),
        continuity = continuity
    )

@Stable
fun ContinuousRoundedRectangle(
    @FloatRange(from = 0.0) topStart: Float = 0f,
    @FloatRange(from = 0.0) topEnd: Float = 0f,
    @FloatRange(from = 0.0) bottomEnd: Float = 0f,
    @FloatRange(from = 0.0) bottomStart: Float = 0f,
    continuity: Continuity = Continuity.Default
): ContinuousRoundedRectangle =
    ContinuousRoundedRectangle(
        topStart = CornerSize(topStart),
        topEnd = CornerSize(topEnd),
        bottomEnd = CornerSize(bottomEnd),
        bottomStart = CornerSize(bottomStart),
        continuity = continuity
    )

@Stable
fun ContinuousRoundedRectangle(
    @IntRange(from = 0, to = 100) topStartPercent: Int = 0,
    @IntRange(from = 0, to = 100) topEndPercent: Int = 0,
    @IntRange(from = 0, to = 100) bottomEndPercent: Int = 0,
    @IntRange(from = 0, to = 100) bottomStartPercent: Int = 0,
    continuity: Continuity = Continuity.Default
): ContinuousRoundedRectangle =
    ContinuousRoundedRectangle(
        topStart = CornerSize(topStartPercent),
        topEnd = CornerSize(topEndPercent),
        bottomEnd = CornerSize(bottomEndPercent),
        bottomStart = CornerSize(bottomStartPercent),
        continuity = continuity
    )
