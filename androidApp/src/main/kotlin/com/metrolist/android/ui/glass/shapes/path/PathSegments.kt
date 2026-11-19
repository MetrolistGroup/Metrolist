/*
 * Vendored from Kyant0/Capsule
 * https://github.com/Kyant0/Capsule - Copyright 2025 Kyant0, Apache License 2.0
 * capsule/src/main/java/com/kyant/capsule/path/PathSegments.kt
 *
 * Vendored so the shape ships as source with this app. Package renamed
 * accordingly (com.kyant.capsule -> com.metrolist.android.ui.glass.shapes).
 */
package com.metrolist.android.ui.glass.shapes.path

import androidx.compose.ui.graphics.Path

typealias PathSegments = List<PathSegment>

fun PathSegments.toPath(): Path {
    return Path().apply {
        if (isEmpty()) return@apply
        val startPoint = first().from
        moveTo(startPoint.x.toFloat(), startPoint.y.toFloat())
        forEach { it.drawTo(this) }
    }
}
