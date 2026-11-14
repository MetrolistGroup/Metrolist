@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.playlist_direction_ascending
import com.metrolist.shared.generated.resources.playlist_direction_descending
import com.metrolist.shared.generated.resources.sort_options
import com.metrolist.shared.generated.resources.top_period_all_time
import com.metrolist.shared.generated.resources.top_period_past_24_hours
import com.metrolist.shared.generated.resources.top_period_past_month
import com.metrolist.shared.generated.resources.top_period_past_week
import com.metrolist.shared.generated.resources.top_period_past_year
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.TopPlaylistPeriod
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.ExpressiveSelectionMenu
import com.metrolist.shared.ui.menu.trackPressPosition
import com.metrolist.shared.ui.tv.tvFocusHighlight
import org.jetbrains.compose.resources.stringResource

data class TopPlaylistOrderControlsText(
    val periodLabels: Map<TopPlaylistPeriod, String>,
    val sortOptions: String,
    val ascending: String,
    val descending: String,
)

@Composable
fun topPlaylistOrderControlsText() =
    TopPlaylistOrderControlsText(
        periodLabels =
            mapOf(
                TopPlaylistPeriod.ALL_TIME to stringResource(Res.string.top_period_all_time),
                TopPlaylistPeriod.PAST_24_HOURS to stringResource(Res.string.top_period_past_24_hours),
                TopPlaylistPeriod.PAST_WEEK to stringResource(Res.string.top_period_past_week),
                TopPlaylistPeriod.PAST_MONTH to stringResource(Res.string.top_period_past_month),
                TopPlaylistPeriod.PAST_YEAR to stringResource(Res.string.top_period_past_year),
            ),
        sortOptions = stringResource(Res.string.sort_options),
        ascending = stringResource(Res.string.playlist_direction_ascending),
        descending = stringResource(Res.string.playlist_direction_descending),
    )

@Composable
fun TopPlaylistOrderControls(
    text: TopPlaylistOrderControlsText,
    period: TopPlaylistPeriod,
    sortDirection: SortDirection,
    onPeriodChange: (TopPlaylistPeriod) -> Unit,
    onSortDirectionChange: (SortDirection) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(IntOffset.Zero) }
    val leadingFocusRequester = remember { FocusRequester() }
    val trailingFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = SplitButtonDefaults.MediumContainerHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.trackPressPosition { menuOffset = it.round() }) {
            SplitButtonLayout(
                leadingButton = {
                    SplitButtonDefaults.TonalLeadingButton(
                        onClick = {
                            if (!leadingFocusRequester.requestFocus()) focusManager.clearFocus()
                            menuExpanded = true
                        },
                        modifier =
                            Modifier
                                .focusRequester(leadingFocusRequester)
                                .tvFocusHighlight(
                                    RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 4.dp, bottomEnd = 4.dp),
                                ).menuAnchorSemantics(menuExpanded)
                                .semantics { contentDescription = text.sortOptions },
                    ) {
                        Text(text.periodLabels.getValue(period), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                },
                trailingButton = {
                    SplitButtonDefaults.TrailingButton(
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        elevation = ButtonDefaults.filledTonalButtonElevation(),
                        onClick = {
                            if (!trailingFocusRequester.requestFocus()) focusManager.clearFocus()
                            onSortDirectionChange(
                                if (sortDirection == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING,
                            )
                        },
                        modifier =
                            Modifier
                                .focusRequester(trailingFocusRequester)
                                .tvFocusHighlight(
                                    RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 24.dp, bottomEnd = 24.dp),
                                ).clip(
                                    RoundedCornerShape(
                                        topStart = 4.dp,
                                        bottomStart = 4.dp,
                                        topEnd = 24.dp,
                                        bottomEnd = 24.dp,
                                    ),
                                ),
                        shapes = SplitButtonDefaults.trailingButtonShapesFor(SplitButtonDefaults.MediumContainerHeight),
                    ) {
                        val rotation by animateFloatAsState(
                            targetValue = if (sortDirection == SortDirection.ASCENDING) 180f else 0f,
                            label = "Top playlist sort direction icon rotation",
                        )
                        Icon(
                            imageVector = MetrolistIcons.Outlined.KeyboardArrowDown,
                            contentDescription = if (sortDirection == SortDirection.ASCENDING) text.descending else text.ascending,
                            modifier =
                                Modifier
                                    .size(SplitButtonDefaults.TrailingIconSize)
                                    .graphicsLayer { rotationZ = rotation },
                        )
                    }
                },
            )
            ExpressiveSelectionMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                anchorOffset = menuOffset,
                options = text.periodLabels.keys.toList(),
                selectedOption = period,
                optionLabel = { text.periodLabels.getValue(it) },
                onOptionSelected = { option -> onPeriodChange(option) },
            )
        }
    }
}
