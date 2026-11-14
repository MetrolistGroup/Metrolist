/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.ui.components.expressiveEntrance
import com.metrolist.shared.ui.components.loadingShimmer
import org.jetbrains.compose.resources.stringResource

private val PlaceholderLineWidths = listOf(0.72f, 0.54f, 0.86f, 0.62f, 0.78f, 0.46f, 0.68f, 0.58f)

@Composable
internal fun LyricsLoadingPlaceholder(
    textColor: Color,
    textAlign: TextAlign,
    lineHeight: Dp,
    anchorTop: Dp,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(Res.string.loading)
    val alignment =
        when (textAlign) {
            TextAlign.Start -> Alignment.Start
            TextAlign.End -> Alignment.End
            else -> Alignment.CenterHorizontally
        }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    liveRegion = LiveRegionMode.Polite
                }.padding(top = anchorTop, start = 12.dp, end = 12.dp)
                .loadingShimmer(),
        verticalArrangement = Arrangement.spacedBy(lineHeight * 0.55f),
    ) {
        PlaceholderLineWidths.forEachIndexed { index, width ->
            Spacer(
                Modifier
                    .expressiveEntrance(index)
                    .fillMaxWidth()
                    .wrapContentWidth(alignment)
                    .fillMaxWidth(width)
                    .height(lineHeight * 0.62f)
                    .clip(CircleShape)
                    .background(textColor.copy(alpha = if (index == 0) 0.34f else 0.16f)),
            )
        }
    }
}
