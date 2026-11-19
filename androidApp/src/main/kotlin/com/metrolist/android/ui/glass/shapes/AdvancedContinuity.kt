/*
 * Vendored from Kyant0/Capsule
 * https://github.com/Kyant0/Capsule - Copyright 2025 Kyant0, Apache License 2.0
 * capsule/src/main/java/com/kyant/capsule/AdvancedContinuity.kt
 *
 * Vendored so the shape ships as source with this app. Package renamed
 * accordingly (com.kyant.capsule -> com.metrolist.android.ui.glass.shapes).
 */
package com.metrolist.android.ui.glass.shapes

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import com.metrolist.android.ui.glass.shapes.core.Point
import com.metrolist.android.ui.glass.shapes.path.PathSegments
import com.metrolist.android.ui.glass.shapes.path.buildCirclePathSegments
import com.metrolist.android.ui.glass.shapes.path.toPath

@Immutable
abstract class AdvancedContinuity : Continuity {

    protected abstract fun createStandardRoundedRectanglePathSegments(
        width: Double,
        height: Double,
        topLeft: Double,
        topRight: Double,
        bottomRight: Double,
        bottomLeft: Double
    ): PathSegments

    protected open fun createStandardRoundedRectangleOutline(
        size: Size,
        topLeft: Float,
        topRight: Float,
        bottomRight: Float,
        bottomLeft: Float
    ): Outline {
        val path =
            createStandardRoundedRectanglePathSegments(
                width = size.width.toDouble(),
                height = size.height.toDouble(),
                topLeft = topLeft.toDouble(),
                topRight = topRight.toDouble(),
                bottomRight = bottomRight.toDouble(),
                bottomLeft = bottomLeft.toDouble()
            ).toPath()
        return Outline.Generic(path)
    }

    protected open fun createHorizontalCapsulePathSegments(width: Double, height: Double): PathSegments {
        val cornerRadius = width * 0.5
        return createStandardRoundedRectanglePathSegments(
            width = width,
            height = height,
            topLeft = cornerRadius,
            topRight = cornerRadius,
            bottomRight = cornerRadius,
            bottomLeft = cornerRadius
        )
    }

    protected open fun createHorizontalCapsuleOutline(size: Size): Outline {
        val path =
            createHorizontalCapsulePathSegments(
                width = size.width.toDouble(),
                height = size.height.toDouble()
            ).toPath()
        return Outline.Generic(path)
    }

    protected open fun createVerticalCapsulePathSegments(width: Double, height: Double): PathSegments {
        val cornerRadius = height * 0.5
        return createStandardRoundedRectanglePathSegments(
            width = width,
            height = height,
            topLeft = cornerRadius,
            topRight = cornerRadius,
            bottomRight = cornerRadius,
            bottomLeft = cornerRadius
        )
    }

    protected open fun createVerticalCapsuleOutline(size: Size): Outline {
        val path =
            createVerticalCapsulePathSegments(
                width = size.width.toDouble(),
                height = size.height.toDouble()
            ).toPath()
        return Outline.Generic(path)
    }

    protected open fun createCirclePathSegments(size: Double): PathSegments {
        val radius = size * 0.5
        return buildCirclePathSegments(
            center = Point(radius, radius),
            radius = radius
        )
    }

    protected open fun createCircleOutline(size: Float): Outline {
        val radius = size * 0.5f
        return Outline.Rounded(
            RoundRect(
                rect = Rect(0f, 0f, size, size),
                radiusX = radius,
                radiusY = radius
            )
        )
    }

    final override fun createRoundedRectanglePathSegments(
        width: Double,
        height: Double,
        topLeft: Double,
        topRight: Double,
        bottomRight: Double,
        bottomLeft: Double
    ): PathSegments {
        // capsule
        if ((topLeft + topRight == width || topLeft + bottomLeft == height) &&
            (topLeft == topRight && bottomRight == bottomLeft && topLeft == bottomRight)
        ) {
            return createCapsulePathSegments(width, height)
        }

        return createStandardRoundedRectanglePathSegments(
            width = width,
            height = height,
            topLeft = topLeft,
            topRight = topRight,
            bottomRight = bottomRight,
            bottomLeft = bottomLeft
        )
    }

    final override fun createRoundedRectangleOutline(
        size: Size,
        topLeft: Float,
        topRight: Float,
        bottomRight: Float,
        bottomLeft: Float
    ): Outline {
        // capsule
        if ((topLeft + topRight == size.width || topLeft + bottomLeft == size.height) &&
            (topLeft == topRight && bottomRight == bottomLeft && topLeft == bottomRight)
        ) {
            return createCapsuleOutline(size)
        }

        return createStandardRoundedRectangleOutline(
            size = size,
            topLeft = topLeft,
            topRight = topRight,
            bottomRight = bottomRight,
            bottomLeft = bottomLeft
        )
    }

    fun createCapsulePathSegments(width: Double, height: Double): PathSegments =
        when {
            width > height -> createHorizontalCapsulePathSegments(width, height)
            width < height -> createVerticalCapsulePathSegments(width, height)
            else -> createCirclePathSegments(width)
        }

    final override fun createCapsuleOutline(size: Size): Outline =
        when {
            size.width > size.height -> createHorizontalCapsuleOutline(size)
            size.width < size.height -> createVerticalCapsuleOutline(size)
            else -> createCircleOutline(size.width)
        }
}
