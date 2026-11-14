@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import com.metrolist.shared.generated.resources.library_sort_updated
import com.metrolist.shared.generated.resources.manual
import com.metrolist.shared.generated.resources.playlist_direction_ascending
import com.metrolist.shared.generated.resources.playlist_direction_descending
import com.metrolist.shared.generated.resources.playlist_picker_library_order
import com.metrolist.shared.generated.resources.songs
import com.metrolist.shared.generated.resources.sort_by_create_date
import com.metrolist.shared.generated.resources.sort_by_name
import com.metrolist.shared.generated.resources.sort_options
import com.metrolist.shared.model.PlaylistPickerSort
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.ExpressiveSelectionMenu
import com.metrolist.shared.ui.menu.trackPressPosition
import com.metrolist.shared.ui.tv.tvFocusHighlight
import org.jetbrains.compose.resources.stringResource

@Composable
fun PlaylistPickerOrderControls(modifier: Modifier = Modifier) {
    val sort = Settings.shared.playlistPickerSort
    val sortDirection = Settings.shared.playlistPickerSortDirection
    val sortOptions = stringResource(Res.string.sort_options)
    val labels =
        mapOf(
            PlaylistPickerSort.LIBRARY to stringResource(Res.string.playlist_picker_library_order),
            PlaylistPickerSort.MANUAL to stringResource(Res.string.manual),
            PlaylistPickerSort.NAME to stringResource(Res.string.sort_by_name),
            PlaylistPickerSort.CREATE_DATE to stringResource(Res.string.sort_by_create_date),
            PlaylistPickerSort.LAST_UPDATED to stringResource(Res.string.library_sort_updated),
            PlaylistPickerSort.SONG_COUNT to stringResource(Res.string.songs),
        )
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
                                .semantics {
                                    contentDescription =
                                        sortOptions
                                },
                    ) {
                        Text(labels.getValue(sort), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                },
                trailingButton = {
                    SplitButtonDefaults.TrailingButton(
                        enabled = sort != PlaylistPickerSort.LIBRARY && sort != PlaylistPickerSort.MANUAL,
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        elevation = ButtonDefaults.filledTonalButtonElevation(),
                        onClick = {
                            if (!trailingFocusRequester.requestFocus()) focusManager.clearFocus()
                            Settings.shared.playlistPickerSortDirection =
                                if (sortDirection == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING
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
                            label = "Playlist picker sort direction icon rotation",
                        )
                        Icon(
                            imageVector = MetrolistIcons.Outlined.KeyboardArrowDown,
                            contentDescription =
                                stringResource(
                                    if (sortDirection ==
                                        SortDirection.ASCENDING
                                    ) {
                                        Res.string.playlist_direction_descending
                                    } else {
                                        Res.string.playlist_direction_ascending
                                    },
                                ),
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
                options = labels.keys.toList(),
                selectedOption = sort,
                optionLabel = { labels.getValue(it) },
                onOptionSelected = { option -> Settings.shared.playlistPickerSort = option },
            )
        }
    }
}
