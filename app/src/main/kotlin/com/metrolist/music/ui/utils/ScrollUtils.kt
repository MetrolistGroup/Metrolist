/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.utils

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun LazyListState.isScrollingUp(): Boolean {
    var previousIndex by remember(this) { androidx.compose.runtime.mutableIntStateOf(firstVisibleItemIndex) }
    var previousScrollOffset by remember(this) { androidx.compose.runtime.mutableIntStateOf(firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            if (previousIndex != firstVisibleItemIndex) {
                previousIndex > firstVisibleItemIndex
            } else {
                previousScrollOffset >= firstVisibleItemScrollOffset
            }.also {
                previousIndex = firstVisibleItemIndex
                previousScrollOffset = firstVisibleItemScrollOffset
            }
        }
    }.value
}

@Composable
fun LazyGridState.isScrollingUp(): Boolean {
    var previousIndex by remember(this) { androidx.compose.runtime.mutableIntStateOf(firstVisibleItemIndex) }
    var previousScrollOffset by remember(this) { androidx.compose.runtime.mutableIntStateOf(firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            if (previousIndex != firstVisibleItemIndex) {
                previousIndex > firstVisibleItemIndex
            } else {
                previousScrollOffset >= firstVisibleItemScrollOffset
            }.also {
                previousIndex = firstVisibleItemIndex
                previousScrollOffset = firstVisibleItemScrollOffset
            }
        }
    }.value
}

@Composable
fun ScrollState.isScrollingUp(): Boolean {
    var previousScrollOffset by remember(this) { mutableIntStateOf(value) }
    return remember(this) {
        derivedStateOf {
            (previousScrollOffset >= value).also {
                previousScrollOffset = value
            }
        }
    }.value
}
