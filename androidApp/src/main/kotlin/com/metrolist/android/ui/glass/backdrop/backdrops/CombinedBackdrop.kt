/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 */
package com.metrolist.android.ui.glass.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import com.metrolist.android.ui.glass.backdrop.Backdrop

@Composable
fun rememberCombinedBackdrop(
    backdrop1: Backdrop,
    backdrop2: Backdrop
): Backdrop {
    return remember(backdrop1, backdrop2) {
        Combined2Backdrops(backdrop1, backdrop2)
    }
}

@Composable
fun rememberCombinedBackdrop(
    backdrop1: Backdrop,
    backdrop2: Backdrop,
    backdrop3: Backdrop
): Backdrop {
    return remember(backdrop1, backdrop2, backdrop3) {
        Combined3Backdrops(backdrop1, backdrop2, backdrop3)
    }
}

@Composable
fun rememberCombinedBackdrop(vararg backdrops: Backdrop): Backdrop {
    return remember(*backdrops) {
        CombinedBackdrops(*backdrops)
    }
}

@Immutable
private class Combined2Backdrops(
    val backdrop1: Backdrop,
    val backdrop2: Backdrop
) : Backdrop {

    override val contentVersion: Int?
        get() {
            val first = backdrop1.contentVersion
            val second = backdrop2.contentVersion
            return if (first == null && second == null) null else 31 * (first ?: 0) + (second ?: 0)
        }

    override val isCoordinatesDependent: Boolean =
        backdrop1.isCoordinatesDependent || backdrop2.isCoordinatesDependent

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        with(backdrop1) { drawBackdrop(density, coordinates, layerBlock) }
        with(backdrop2) { drawBackdrop(density, coordinates, layerBlock) }
    }
}

@Immutable
private class Combined3Backdrops(
    val backdrop1: Backdrop,
    val backdrop2: Backdrop,
    val backdrop3: Backdrop
) : Backdrop {

    override val contentVersion: Int?
        get() {
            val first = backdrop1.contentVersion
            val second = backdrop2.contentVersion
            val third = backdrop3.contentVersion
            return if (first == null && second == null && third == null) null
                else (31 * (first ?: 0) + (second ?: 0)) * 31 + (third ?: 0)
        }

    override val isCoordinatesDependent: Boolean =
        backdrop1.isCoordinatesDependent ||
                backdrop2.isCoordinatesDependent ||
                backdrop3.isCoordinatesDependent

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        with(backdrop1) { drawBackdrop(density, coordinates, layerBlock) }
        with(backdrop2) { drawBackdrop(density, coordinates, layerBlock) }
        with(backdrop3) { drawBackdrop(density, coordinates, layerBlock) }
    }
}

@Immutable
private class CombinedBackdrops(
    vararg val backdrops: Backdrop
) : Backdrop {

    override val contentVersion: Int?
        get() {
            var version = 0
            var versioned = false
            for (backdrop in backdrops) {
                val sourceVersion = backdrop.contentVersion
                versioned = versioned || sourceVersion != null
                version = version * 31 + (sourceVersion ?: 0)
            }
            return if (versioned) version else null
        }

    override val isCoordinatesDependent: Boolean =
        backdrops.any { it.isCoordinatesDependent }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        backdrops.forEach { backdrop ->
            with(backdrop) { drawBackdrop(density, coordinates, layerBlock) }
        }
    }
}
