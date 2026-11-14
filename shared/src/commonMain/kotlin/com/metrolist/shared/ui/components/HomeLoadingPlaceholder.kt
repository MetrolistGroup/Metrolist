/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.constants.GridThumbnailHeight
import com.metrolist.shared.constants.GridTileSpacing
import com.metrolist.shared.constants.ThumbnailCornerRadius
import com.metrolist.shared.constants.currentGridThumbnailHeight
import com.metrolist.shared.constants.speedDialLayout
import com.metrolist.shared.constants.thumbnailHeight
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.theme.uiEnhanced
import org.jetbrains.compose.resources.stringResource

private val ChipWidths = listOf(72.dp, 96.dp, 64.dp, 88.dp, 76.dp, 104.dp, 68.dp)

@Composable
fun HomeLoadingPlaceholder(
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false,
) {
    val label = stringResource(Res.string.loading)
    BoxWithConstraints(
        modifier =
            modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        val isWide = maxWidth > 840.dp
        val horizontalPadding = if (isWide) 24.dp else 16.dp
        val tile = currentGridThumbnailHeight()
        val tileShape = RoundedCornerShape(uiEnhanced(ThumbnailCornerRadius, 12.dp))
        val availableWidth = maxWidth.coerceAtMost(1400.dp) - horizontalPadding * 2
        val speedDial = speedDialLayout(availableWidth, Settings.shared.gridItemSize)
        val landscapeTile = (if (isWide) 170.dp else 136.dp) * (Settings.shared.gridItemSize.thumbnailHeight / GridThumbnailHeight)
        val speedDialColumns = if (isLandscape) (availableWidth / (landscapeTile + GridTileSpacing)).toInt() + 1 else speedDial.columns
        val speedDialRows = if (isLandscape) 3 else speedDial.rows
        val carouselCount = ((maxWidth - horizontalPadding) / (tile + 12.dp)).toInt().coerceAtLeast(2) + 1
        Column(
            modifier =
                Modifier
                    .then(if (isWide) Modifier.widthIn(max = 1400.dp) else Modifier)
                    .fillMaxWidth()
                    .loadingShimmer(),
            verticalArrangement = Arrangement.spacedBy(if (isWide) 16.dp else 8.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clipToBounds()
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .padding(start = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChipWidths.forEach { width -> SkeletonBlock(Modifier.width(width).height(32.dp), CircleShape) }
            }

            Column(Modifier.padding(horizontal = horizontalPadding).padding(bottom = if (isWide) 24.dp else 16.dp).clipToBounds()) {
                ShelfTitlePlaceholder(isWide)
                repeat(speedDialRows) { row ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .then(if (isLandscape) Modifier.wrapContentWidth(Alignment.Start, unbounded = true) else Modifier)
                                .padding(top = if (row == 0) 0.dp else GridTileSpacing),
                        horizontalArrangement = Arrangement.spacedBy(GridTileSpacing),
                    ) {
                        repeat(speedDialColumns) {
                            SkeletonBlock(
                                if (isLandscape) Modifier.size(landscapeTile) else Modifier.weight(1f).aspectRatio(1f),
                                tileShape,
                            )
                        }
                    }
                }
                if (!isLandscape) Spacer(Modifier.height(30.dp))
            }

            repeat(3) {
                Column(Modifier.padding(start = horizontalPadding, bottom = if (isWide) 24.dp else 16.dp)) {
                    ShelfTitlePlaceholder(isWide)
                    Row(
                        modifier = Modifier.fillMaxWidth().clipToBounds().wrapContentWidth(Alignment.Start, unbounded = true),
                        horizontalArrangement = Arrangement.spacedBy(if (isWide) 12.dp else GridTileSpacing),
                    ) {
                        repeat(carouselCount) { CarouselItemPlaceholder(tile, tileShape) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfTitlePlaceholder(isWide: Boolean) {
    Column(Modifier.height(if (isWide) 60.dp else 50.dp), verticalArrangement = Arrangement.Center) {
        SkeletonBlock(Modifier.width(168.dp).height(22.dp), RoundedCornerShape(8.dp))
    }
}

@Composable
private fun CarouselItemPlaceholder(
    size: Dp,
    shape: Shape,
) {
    Column(Modifier.width(size), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(1f), shape)
        Spacer(Modifier.height(2.dp))
        SkeletonBlock(Modifier.fillMaxWidth(0.85f).height(14.dp), CircleShape)
        SkeletonBlock(Modifier.fillMaxWidth(0.55f).height(12.dp), CircleShape)
    }
}

@Composable
private fun SkeletonBlock(
    modifier: Modifier,
    shape: Shape,
) {
    Spacer(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest))
}
