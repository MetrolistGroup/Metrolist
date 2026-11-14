/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.utils

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.metrolist.shared.settings.Settings

fun CornerBasedShape.top(): CornerBasedShape = copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))

@Composable
fun segmentedGroupShape(
    isFirst: Boolean,
    isLast: Boolean,
    pressed: Boolean,
    outer: Dp = 20.dp,
    inner: Dp = 5.dp,
): Shape {
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val top by animateDpAsState(if (isFirst || pressed) outer else inner, spec, label = "segmentTop")
    val bottom by animateDpAsState(if (isLast || pressed) outer else inner, spec, label = "segmentBottom")
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val artistArtworkPolygons =
    listOf(
        MaterialShapes.Sunny,
        MaterialShapes.Cookie4Sided,
        MaterialShapes.Cookie6Sided,
        MaterialShapes.Cookie7Sided,
        MaterialShapes.Cookie9Sided,
        MaterialShapes.Cookie12Sided,
        MaterialShapes.Clover8Leaf,
        MaterialShapes.Puffy,
        MaterialShapes.Bun,
    )

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun artistArtworkShape(seed: String): Shape {
    if (!Settings.shared.uiEnhancements) return CircleShape
    val hash = seed.hashCode() and Int.MAX_VALUE
    val startAngle = ((hash / artistArtworkPolygons.size) % 8) * 45
    val polygon = artistArtworkPolygons[hash % artistArtworkPolygons.size]
    val sourcePath = polygon.toPath(startAngle = startAngle)
    return remember(polygon, startAngle) {
        FittedPolygonShape(Path().apply { addPath(sourcePath) })
    }
}

// Material's toShape() returns one reused Path per shape; Skiko frees it under another layer sharing the shape (#1205).
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RoundedPolygon.toPolygonShape(startAngle: Int = 0): Shape {
    val path = toPath(startAngle = startAngle)
    return remember(this, startAngle) { FittedPolygonShape(path) }
}

/** Rotation can expand polygon bounds past 0..1; fit the rotated bounds to avoid covering captions. */
internal class FittedPolygonShape(
    private val sourcePath: Path,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = Path().apply { addPath(sourcePath) }
        val bounds = path.getBounds()
        if (bounds.width > 0f && bounds.height > 0f && size.width > 0f && size.height > 0f) {
            val scale = minOf(size.width / bounds.width, size.height / bounds.height)
            path.translate(-bounds.center)
            path.transform(Matrix().apply { scale(scale, scale) })
            path.translate(Offset(size.width / 2f, size.height / 2f))
        }
        return Outline.Generic(path)
    }
}
