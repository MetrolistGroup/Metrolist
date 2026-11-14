@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.metrolist.shared.model.PlaylistSongSort
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.allowsReordering
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.ExpressiveSelectionMenu
import com.metrolist.shared.ui.menu.trackPressPosition
import com.metrolist.shared.ui.tv.tvFocusHighlight

data class PlaylistOrderControlsText(
    val sortLabels: Map<PlaylistSongSort, String>,
    val sortOptions: String,
    val ascending: String,
    val descending: String,
    val reorder: String,
)

@Composable
fun PlaylistOrderControls(
    text: PlaylistOrderControlsText,
    sort: PlaylistSongSort,
    sortDirection: SortDirection,
    reorderMode: Boolean,
    canReorder: Boolean,
    onSortChange: (PlaylistSongSort) -> Unit,
    onSortDirectionChange: (SortDirection) -> Unit,
    onReorderModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var sortMenuOffset by remember { mutableStateOf(IntOffset.Zero) }
    val leadingFocusRequester = remember { FocusRequester() }
    val trailingFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = SplitButtonDefaults.MediumContainerHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.weight(1f).trackPressPosition { sortMenuOffset = it.round() }) {
            SplitButtonLayout(
                leadingButton = {
                    SplitButtonDefaults.TonalLeadingButton(
                        onClick = {
                            // Touch-mode buttons reject focus; release the search field in that case.
                            if (!leadingFocusRequester.requestFocus()) focusManager.clearFocus()
                            sortMenuExpanded = true
                        },
                        enabled = !reorderMode,
                        modifier =
                            Modifier
                                .focusRequester(leadingFocusRequester)
                                .tvFocusHighlight(
                                    RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 4.dp, bottomEnd = 4.dp),
                                ).menuAnchorSemantics(sortMenuExpanded)
                                .semantics { contentDescription = text.sortOptions },
                    ) {
                        Text(text.sortLabels.getValue(sort), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                },
                trailingButton = {
                    SplitButtonDefaults.TrailingButton(
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        elevation = ButtonDefaults.filledTonalButtonElevation(),
                        onClick = {
                            if (!trailingFocusRequester.requestFocus()) focusManager.clearFocus()
                            onSortDirectionChange(
                                if (sortDirection ==
                                    SortDirection.ASCENDING
                                ) {
                                    SortDirection.DESCENDING
                                } else {
                                    SortDirection.ASCENDING
                                },
                            )
                        },
                        enabled = !reorderMode && sort != PlaylistSongSort.ORIGINAL,
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
                            label = "Playlist sort direction icon rotation",
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
                expanded = sortMenuExpanded,
                onDismissRequest = { sortMenuExpanded = false },
                anchorOffset = sortMenuOffset,
                options = text.sortLabels.keys.toList(),
                selectedOption = sort,
                optionLabel = { text.sortLabels.getValue(it) },
                onOptionSelected = { option -> onSortChange(option) },
            )
        }
        if (canReorder) {
            FilledTonalButton(
                onClick = { onReorderModeChange(!reorderMode) },
                enabled = reorderMode || sort.allowsReordering(),
                modifier = Modifier.tvFocusHighlight(CircleShape).semantics { selected = reorderMode },
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor =
                            if (reorderMode) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                        contentColor =
                            if (reorderMode) {
                                MaterialTheme.colorScheme.onSecondary
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                    ),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(text.reorder, maxLines = 1, softWrap = false)
            }
        }
    }
}
