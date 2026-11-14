@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.tv.tvFocusHighlight

private val FabScrollDistance = 48.dp

enum class ScrollingFabState {
    Expanded,
    Collapsed,
    Hidden,
}

internal data class FabScrollPosition(
    val index: Int,
    val offset: Int,
    val atStart: Boolean,
    val nearEnd: Boolean,
)

internal class FabScrollTracker(
    private val threshold: Float,
) {
    private var previous: FabScrollPosition? = null
    private var travelled = 0f
    private var expanded = true
    private var scrollingDown = false

    fun update(position: FabScrollPosition): ScrollingFabState {
        val before = previous ?: position
        previous = position
        val delta =
            if (position.index == before.index) {
                (position.offset - before.offset).toFloat()
            } else {
                (position.index - before.index) * threshold
            }
        if (position.atStart) {
            travelled = 0f
            expanded = true
            scrollingDown = false
        } else if (delta != 0f) {
            if ((delta > 0f) != scrollingDown) travelled = 0f
            scrollingDown = delta > 0f
            travelled += delta
            if (travelled >= threshold) expanded = false
            if (travelled <= -threshold) expanded = true
        }
        return when {
            position.nearEnd && scrollingDown && !expanded -> ScrollingFabState.Hidden
            expanded -> ScrollingFabState.Expanded
            else -> ScrollingFabState.Collapsed
        }
    }
}

@Composable
fun rememberScrollingFabState(listState: LazyListState): ScrollingFabState {
    val threshold = with(LocalDensity.current) { FabScrollDistance.toPx() }
    return rememberScrollingFabState(listState, threshold) {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        FabScrollPosition(
            listState.firstVisibleItemIndex,
            listState.firstVisibleItemScrollOffset,
            atStart = !listState.canScrollBackward,
            nearEnd =
                last != null && last.index == info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + threshold,
        )
    }
}

@Composable
fun rememberScrollingFabState(gridState: LazyGridState): ScrollingFabState {
    val threshold = with(LocalDensity.current) { FabScrollDistance.toPx() }
    return rememberScrollingFabState(gridState, threshold) {
        val info = gridState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        FabScrollPosition(
            gridState.firstVisibleItemIndex,
            gridState.firstVisibleItemScrollOffset,
            atStart = !gridState.canScrollBackward,
            nearEnd =
                last != null && last.index == info.totalItemsCount - 1 &&
                    last.offset.y + last.size.height <= info.viewportEndOffset + threshold,
        )
    }
}

@Composable
private fun rememberScrollingFabState(
    scrollState: Any,
    threshold: Float,
    position: () -> FabScrollPosition,
): ScrollingFabState {
    var state by remember(scrollState, threshold) { mutableStateOf(ScrollingFabState.Expanded) }
    LaunchedEffect(scrollState, threshold) {
        val tracker = FabScrollTracker(threshold)
        snapshotFlow(position).collect { state = tracker.update(it) }
    }
    return state
}

@Composable
fun ScrollingAddButton(
    label: String,
    onClick: () -> Unit,
    state: ScrollingFabState,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    content: @Composable () -> Unit = {},
) {
    val isTv = LocalIsTv.current
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
    val labelSpatial = MaterialTheme.motionScheme.fastSpatialSpec<IntSize>()
    val expanded = isTv || state == ScrollingFabState.Expanded
    AnimatedVisibility(
        visible = visible && (isTv || state != ScrollingFabState.Hidden),
        modifier = modifier,
        enter = fadeIn(effects) + slideInVertically(spatial) { it / 2 },
        exit = fadeOut(effects) + slideOutVertically(spatial) { it / 2 },
    ) {
        Box {
            FloatingActionButton(
                onClick = onClick,
                modifier =
                    Modifier
                        .navigationPopupSource(
                            FloatingActionButtonDefaults.shape,
                        ).tvFocusHighlight(FloatingActionButtonDefaults.shape),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MetrolistIcons.Default.Add, contentDescription = label.takeUnless { expanded })
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandHorizontally(labelSpatial, expandFrom = Alignment.Start) + fadeIn(effects),
                        exit = shrinkHorizontally(labelSpatial, shrinkTowards = Alignment.Start) + fadeOut(effects),
                    ) {
                        Text(label, modifier = Modifier.padding(start = 12.dp, end = 4.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            content()
        }
    }
}
