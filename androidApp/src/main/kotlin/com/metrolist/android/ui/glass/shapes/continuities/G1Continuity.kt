/*
 * Vendored from Kyant0/Capsule
 * https://github.com/Kyant0/Capsule - Copyright 2025 Kyant0, Apache License 2.0
 * capsule/src/main/java/com/kyant/capsule/continuities/G1Continuity.kt
 *
 * Vendored so the shape ships as source with this app. Package renamed
 * accordingly (com.kyant.capsule -> com.metrolist.android.ui.glass.shapes).
 */
package com.metrolist.android.ui.glass.shapes.continuities

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import com.metrolist.android.ui.glass.shapes.Continuity
import com.metrolist.android.ui.glass.shapes.core.Point
import com.metrolist.android.ui.glass.shapes.path.PathSegments
import com.metrolist.android.ui.glass.shapes.path.buildPathSegments
import kotlin.math.PI

@Immutable
data object G1Continuity : Continuity {

    override fun createRoundedRectanglePathSegments(
        width: Double,
        height: Double,
        topLeft: Double,
        topRight: Double,
        bottomRight: Double,
        bottomLeft: Double
    ): PathSegments {
        return buildPathSegments {
            moveTo(0.0, topLeft)
            if (topLeft > 0.0) {
                arcTo(
                    center = Point(topLeft, topLeft),
                    radius = topLeft,
                    startAngle = PI,
                    sweepAngle = PI * 0.5
                )
            }
            lineTo(width - topRight, 0.0)
            if (topRight > 0.0) {
                arcTo(
                    center = Point(width - topRight, topRight),
                    radius = topRight,
                    startAngle = -PI * 0.5,
                    sweepAngle = PI * 0.5
                )
            }
            lineTo(width, height - bottomRight)
            if (bottomRight > 0.0) {
                arcTo(
                    center = Point(width - bottomRight, height - bottomRight),
                    radius = bottomRight,
                    startAngle = 0.0,
                    sweepAngle = PI * 0.5
                )
            }
            lineTo(bottomLeft, height)
            if (bottomLeft > 0.0) {
                arcTo(
                    center = Point(bottomLeft, height - bottomLeft),
                    radius = bottomLeft,
                    startAngle = PI * 0.5,
                    sweepAngle = PI * 0.5
                )
            }
            close()
        }
    }

    override fun createRoundedRectangleOutline(
        size: Size,
        topLeft: Float,
        topRight: Float,
        bottomRight: Float,
        bottomLeft: Float
    ): Outline {
        return Outline.Rounded(
            RoundRect(
                rect = Rect(0f, 0f, size.width, size.height),
                topLeft = CornerRadius(topLeft),
                topRight = CornerRadius(topRight),
                bottomRight = CornerRadius(bottomRight),
                bottomLeft = CornerRadius(bottomLeft)
            )
        )
    }

    override fun lerp(stop: Continuity, fraction: Double): Continuity {
        return when (stop) {
            is G1Continuity -> this
            is G2Continuity ->
                G2Continuity(
                    profile = lerp(G2ContinuityProfile.G1Equivalent, stop.profile, fraction),
                    capsuleProfile = lerp(G2ContinuityProfile.G1Equivalent, stop.capsuleProfile, fraction)
                )

            else -> stop.lerp(this, 1f - fraction)
        }
    }
}
