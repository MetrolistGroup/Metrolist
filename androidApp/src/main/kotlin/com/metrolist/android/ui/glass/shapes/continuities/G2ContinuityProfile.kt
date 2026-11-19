/*
 * Vendored from Kyant0/Capsule
 * https://github.com/Kyant0/Capsule - Copyright 2025 Kyant0, Apache License 2.0
 * capsule/src/main/java/com/kyant/capsule/continuities/G2ContinuityProfile.kt
 *
 * Vendored so the shape ships as source with this app. Package renamed
 * accordingly (com.kyant.capsule -> com.metrolist.android.ui.glass.shapes).
 */
package com.metrolist.android.ui.glass.shapes.continuities

import androidx.annotation.FloatRange
import androidx.compose.runtime.Immutable
import androidx.compose.ui.util.fastCoerceAtLeast
import com.metrolist.android.ui.glass.shapes.core.CubicBezier
import com.metrolist.android.ui.glass.shapes.core.Point
import com.metrolist.android.ui.glass.shapes.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Immutable
data class G2ContinuityProfile(
    @param:FloatRange(from = 0.0) val extendedFraction: Double,
    @param:FloatRange(from = 0.0, to = 1.0) val arcFraction: Double,
    @param:FloatRange(from = 0.0) val bezierCurvatureScale: Double,
    @param:FloatRange(from = 0.0, fromInclusive = false) val arcCurvatureScale: Double
) {

    private var _bezier: CubicBezier? = null
    internal val bezier: CubicBezier
        get() = _bezier ?: createBaseBezier().also { _bezier = it }

    private fun createBaseBezier(): CubicBezier {
        val arcRadians = PI * 0.5 * arcFraction
        val bezierRadians = (PI * 0.5 - arcRadians) * 0.5
        val sin = sin(bezierRadians)
        val cos = cos(bezierRadians)

        return if (bezierCurvatureScale == 1.0 && arcCurvatureScale == 1.0) {
            val halfTan = sin / (1.0 + cos)
            CubicBezier(
                Point(-extendedFraction, 0.0),
                Point((1.0 - 1.5 / (1.0 + cos)) * halfTan, 0.0),
                Point(halfTan, 0.0),
                Point(sin, 1.0 - cos)
            )
        } else {
            val radiusScale = 1.0 / arcCurvatureScale
            val arcCenter = Point(0.0, 1.0) + Point(1.0 / sqrt(2.0), -1.0 / sqrt(2.0)) * (1.0 - radiusScale)
            val arcStartPoint = arcCenter + Point(sin, -cos) * radiusScale
            return generateG2ContinuousBezierWithZeroStartCurvature(
                start = Point(-extendedFraction, 0.0),
                end = arcStartPoint,
                startTangent = Point(1.0, 0.0),
                endTangent = Point(cos, sin),
                endCurvature = bezierCurvatureScale
            )
        }
    }

    companion object {

        val RoundedRectangle: G2ContinuityProfile =
            G2ContinuityProfile(
                extendedFraction = 0.5286651,
                arcFraction = 5.0 / 9.0,
                bezierCurvatureScale = 1.0732051,
                arcCurvatureScale = 1.0732051
            )

        val Capsule: G2ContinuityProfile =
            G2ContinuityProfile(
                extendedFraction = 0.5286651 * 0.75,
                arcFraction = 0.0,
                bezierCurvatureScale = 1.0,
                arcCurvatureScale = 1.0
            )

        val G1Equivalent: G2ContinuityProfile =
            G2ContinuityProfile(
                extendedFraction = 0.0,
                arcFraction = 1.0,
                bezierCurvatureScale = 1.0,
                arcCurvatureScale = 1.0
            )
    }
}

fun lerp(start: G2ContinuityProfile, stop: G2ContinuityProfile, fraction: Double): G2ContinuityProfile {
    return G2ContinuityProfile(
        extendedFraction = lerp(start.extendedFraction, stop.extendedFraction, fraction),
        arcFraction = lerp(start.arcFraction, stop.arcFraction, fraction),
        bezierCurvatureScale = lerp(start.bezierCurvatureScale, stop.bezierCurvatureScale, fraction),
        arcCurvatureScale = lerp(start.arcCurvatureScale, stop.arcCurvatureScale, fraction)
    )
}

private fun generateG2ContinuousBezierWithZeroStartCurvature(
    start: Point,
    end: Point,
    startTangent: Point,
    endTangent: Point,
    endCurvature: Double
): CubicBezier {
    val a2 = 1.5 * endCurvature
    val b = startTangent.x * endTangent.y - startTangent.y * endTangent.x
    val dx = end.x - start.x
    val dy = end.y - start.y
    val c1 = -dy * startTangent.x + dx * startTangent.y
    val c2 = dy * endTangent.x - dx * endTangent.y

    val lambda0 = -c2 / b - a2 * c1 * c1 / b / b / b
    val lambda3 = -c1 / b

    val p0 = start
    val p1 = start + Point(
        (lambda0 * startTangent.x).fastCoerceAtLeast(0.0),
        (lambda0 * startTangent.y).fastCoerceAtLeast(0.0)
    )
    val p2 = end - Point(
        (lambda3 * endTangent.x).fastCoerceAtLeast(0.0),
        (lambda3 * endTangent.y).fastCoerceAtLeast(0.0)
    )
    val p3 = end

    return CubicBezier(p0, p1, p2, p3)
}
