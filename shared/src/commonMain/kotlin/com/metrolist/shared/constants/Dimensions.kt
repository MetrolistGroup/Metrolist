/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.constants

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.shared.settings.GridItemSize
import com.metrolist.shared.settings.Settings

val MiniPlayerHeight = 64.dp
val ModernMiniPlayerHeight = 66.dp

fun modernMiniPlayerHeight(density: Density): Dp =
    with(density) {
        maxOf(ModernMiniPlayerHeight, 16.sp.toDp() + 14.sp.toDp() + 2.dp + 16.dp)
    }

val ModernMiniPlayerHorizontalPadding = 12.dp
val ModernMiniPlayerBottomSpacing = 6.dp
val QueuePeekHeight = 64.dp

val ListItemHeight = 64.dp
val ListThumbnailSize = 48.dp
val SmallGridThumbnailHeight = 104.dp
val GridThumbnailHeight = 128.dp
val SpeedDialTargetItemSize = 160.dp

val GridTileSpacing = 6.dp

val CarouselItemSize = 115.dp
val CarouselItemSpacing = 5.dp
val CarouselItemCornerRadius = 12.dp

val GridRowSpacing = 12.dp

val GridScreenPadding = 16.dp

/** Prevents clipped Arabic glyphs and Latin descenders from extending beyond grid cells. */
val GridCaptionSafeSpace = 8.dp

@Composable
fun gridCaptionHeight(largeText: Boolean): Dp =
    with(LocalDensity.current) {
        val lines = if (largeText) 56.sp.toDp() else 44.sp.toDp()
        4.dp + lines + GridCaptionSafeSpace
    }

val ThumbnailCornerRadius = 4.dp

val WideNavigationRailExpandedWidth = 220.dp
val WideNavigationRailCollapsedWidth = 96.dp

val GridItemSize.thumbnailHeight: Dp
    get() = if (this == GridItemSize.BIG) GridThumbnailHeight else SmallGridThumbnailHeight

fun gridTileSize(
    availableWidth: Dp,
    size: GridItemSize,
): Dp {
    val columns = gridTileColumns(availableWidth, size)
    return ((availableWidth - GridTileSpacing * (columns - 1)) / columns).coerceAtLeast(0.dp)
}

fun gridTileColumns(
    availableWidth: Dp,
    size: GridItemSize,
): Int = speedDialLayout(availableWidth, size).columns

@Composable
fun currentGridThumbnailHeight(): Dp {
    val size = Settings.shared.gridItemSize
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    if (windowWidthPx <= 0) return size.thumbnailHeight
    val windowWidth = with(LocalDensity.current) { windowWidthPx.toDp() }
    return gridTileSize(windowWidth - GridScreenPadding * 2, size)
}

data class TileGridCells(
    val size: GridItemSize,
) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(
        availableSize: Int,
        spacing: Int,
    ): List<Int> {
        val columns = gridTileColumns(availableSize.toDp(), size).coerceAtLeast(1)
        val cellArea = (availableSize - spacing * (columns - 1)).coerceAtLeast(0)
        val cell = cellArea / columns
        val remainder = cellArea - cell * columns
        return List(columns) { index -> if (index < remainder) cell + 1 else cell }
    }
}

@Composable
fun currentGridCells(): GridCells = TileGridCells(Settings.shared.gridItemSize)

data class SpeedDialLayout(
    val columns: Int,
    val rows: Int,
) {
    val itemsPerPage: Int get() = columns * rows
}

fun speedDialLayout(
    availableWidth: Dp,
    size: GridItemSize,
): SpeedDialLayout {
    val target = SpeedDialTargetItemSize * (size.thumbnailHeight / GridThumbnailHeight)
    val minColumns = if (size == GridItemSize.BIG) 3 else 4
    val columns = (availableWidth / target).toInt().coerceAtLeast(minColumns)
    val rows =
        when {
            columns >= 6 -> 1
            columns >= 4 -> 2
            else -> 3
        }
    return SpeedDialLayout(columns, rows)
}
