package com.metrolist.shared.ui.utils

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.dp
import com.metrolist.shared.utils.isDesktop

private fun Modifier.verticalMouseDragScroll(
    state: ScrollableState,
    enabled: Boolean,
    flingBehavior: FlingBehavior,
    reverseDirection: Boolean = false,
): Modifier =
    if (isDesktop && enabled) {
        mouseDragScroll(state, horizontal = false, flingBehavior = flingBehavior, reverseDirection = reverseDirection)
    } else {
        this
    }

fun Modifier.verticalScrollWithDrag(
    state: ScrollState,
    enabled: Boolean = true,
    flingBehavior: FlingBehavior? = null,
    reverseScrolling: Boolean = false,
): Modifier =
    composed {
        val resolvedFlingBehavior = flingBehavior ?: ScrollableDefaults.flingBehavior()
        verticalMouseDragScroll(state, enabled, resolvedFlingBehavior, reverseScrolling)
            .verticalScroll(state, enabled, resolvedFlingBehavior, reverseScrolling)
    }

@Composable
fun DragScrollableColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    val flingBehavior = ScrollableDefaults.flingBehavior()
    LazyColumn(
        modifier = modifier.verticalMouseDragScroll(state, userScrollEnabled, flingBehavior),
        state = state,
        flingBehavior = flingBehavior,
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        userScrollEnabled = userScrollEnabled,
        content = content,
    )
}

@Composable
fun DragScrollableGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    userScrollEnabled: Boolean = true,
    content: LazyGridScope.() -> Unit,
) {
    val flingBehavior = ScrollableDefaults.flingBehavior()
    LazyVerticalGrid(
        columns = columns,
        modifier = modifier.verticalMouseDragScroll(state, userScrollEnabled, flingBehavior),
        state = state,
        flingBehavior = flingBehavior,
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        horizontalArrangement = horizontalArrangement,
        userScrollEnabled = userScrollEnabled,
        content = content,
    )
}
