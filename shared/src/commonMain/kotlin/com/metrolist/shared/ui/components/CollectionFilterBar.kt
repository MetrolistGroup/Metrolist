@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.metrolist.shared.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.clear_search
import com.metrolist.shared.generated.resources.playlist_direction_ascending
import com.metrolist.shared.generated.resources.playlist_direction_descending
import com.metrolist.shared.model.CollectionSort
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.ExpressiveSelectionMenu
import com.metrolist.shared.ui.menu.trackPressPosition
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.ui.utils.carouselScroll
import org.jetbrains.compose.resources.stringResource

data class CollectionFilterBarText(
    val searchLabel: String,
    val sortLabels: Map<CollectionSort, String>,
    val sortOptions: String,
    val clear: String? = null,
)

@Composable
fun CollectionFilterBar(
    text: CollectionFilterBarText,
    query: String,
    onQueryChange: (String) -> Unit,
    sort: CollectionSort,
    onSortChange: (CollectionSort) -> Unit,
    sortDirection: SortDirection,
    onSortDirectionChange: (SortDirection) -> Unit,
    onClear: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    showSearch: Boolean = true,
) {
    var sortExpanded by remember { mutableStateOf(false) }
    var sortMenuOffset by remember { mutableStateOf(IntOffset.Zero) }
    val textFieldState = rememberTextFieldState(initialText = query)
    val focusManager = LocalFocusManager.current
    val searchBarState = rememberSearchBarState(initialValue = SearchBarValue.Expanded)
    val latestQuery by rememberUpdatedState(query)
    val latestOnQueryChange by rememberUpdatedState(onQueryChange)

    LaunchedEffect(query) {
        if (textFieldState.text.toString() != query) {
            textFieldState.setTextAndPlaceCursorAtEnd(query)
        }
    }

    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .collect { text ->
                if (text != latestQuery) {
                    latestOnQueryChange(text)
                }
            }
    }

    val searchBarColors =
        SearchBarDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    val filterScrollState = rememberScrollState()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showSearch) {
            Surface(
                shape = SearchBarDefaults.inputFieldShape,
                content = {
                    SearchBarDefaults.InputField(
                        textFieldState = textFieldState,
                        searchBarState = searchBarState,
                        onSearch = { focusManager.clearFocus() },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(text.searchLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = {
                            Icon(
                                imageVector = MetrolistIcons.Default.Search,
                                contentDescription = null,
                            )
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChange("") }, shapes = IconButtonDefaults.shapes()) {
                                    Icon(
                                        imageVector = MetrolistIcons.Default.Clear,
                                        contentDescription = stringResource(Res.string.clear_search),
                                    )
                                }
                            }
                        },
                        colors = searchBarColors.inputFieldColors,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                color = searchBarColors.containerColor,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            )
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .carouselScroll(filterScrollState)
                    .horizontalScroll(filterScrollState),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.trackPressPosition { sortMenuOffset = it.round() }) {
                SplitButtonLayout(
                    leadingButton = {
                        SplitButtonDefaults.TonalLeadingButton(
                            onClick = { sortExpanded = true },
                            modifier =
                                Modifier
                                    .tvFocusHighlight(
                                        RoundedCornerShape(
                                            topStart = 24.dp,
                                            bottomStart = 24.dp,
                                            topEnd = 4.dp,
                                            bottomEnd = 4.dp,
                                        ),
                                    ).menuAnchorSemantics(sortExpanded),
                        ) {
                            Text(
                                text = text.sortLabels.getValue(sort),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    trailingButton = {
                        SplitButtonDefaults.TrailingButton(
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            elevation = ButtonDefaults.filledTonalButtonElevation(),
                            onClick = {
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
                            modifier =
                                Modifier
                                    .tvFocusHighlight(
                                        RoundedCornerShape(
                                            topStart = 4.dp,
                                            bottomStart = 4.dp,
                                            topEnd = 24.dp,
                                            bottomEnd = 24.dp,
                                        ),
                                    ).clip(
                                        RoundedCornerShape(
                                            topStart = 4.dp,
                                            bottomStart = 4.dp,
                                            topEnd = 24.dp,
                                            bottomEnd = 24.dp,
                                        ),
                                    ),
                            shapes = SplitButtonDefaults.trailingButtonShapesFor(SplitButtonDefaults.SmallContainerHeight),
                        ) {
                            val rotation by animateFloatAsState(
                                targetValue = if (sortDirection == SortDirection.ASCENDING) 180f else 0f,
                                label = "Collection sort arrow",
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
                    expanded = sortExpanded,
                    onDismissRequest = { sortExpanded = false },
                    anchorOffset = sortMenuOffset,
                    options = text.sortLabels.keys.toList(),
                    selectedOption = sort,
                    optionLabel = { text.sortLabels.getValue(it) },
                    onOptionSelected = { option -> onSortChange(option) },
                )
            }
            if (onClear != null) {
                TextButton(onClick = onClear, modifier = Modifier.tvFocusHighlight(CircleShape), shapes = ButtonDefaults.shapes()) {
                    Text(
                        text = text.clear ?: "",
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
