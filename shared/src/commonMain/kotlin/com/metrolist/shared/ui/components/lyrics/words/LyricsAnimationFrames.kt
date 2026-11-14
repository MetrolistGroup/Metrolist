package com.metrolist.shared.ui.components.lyrics.words

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.metrolist.shared.ui.components.LocalVisualWorkActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

internal val LocalLyricsAnimationVisible = compositionLocalOf { true }

@Composable
internal fun rememberLyricsAnimationVisibility(): Flow<Boolean> {
    val visible = LocalLyricsAnimationVisible.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val windowVisible = LocalVisualWorkActive.current
    return remember(visible, lifecycle, windowVisible) {
        combine(lifecycle.currentStateFlow, windowVisible) { state, shown ->
            visible && shown && state.isAtLeast(Lifecycle.State.STARTED)
        }.distinctUntilChanged()
    }
}

internal suspend fun animateLyricsFrames(
    visibility: Flow<Boolean>,
    shouldAnimate: () -> Boolean = { true },
    onFrame: (Long) -> Unit,
) {
    visibility.collectLatest { visible ->
        if (visible) {
            do {
                withFrameNanos { onFrame(it / 1_000_000L) }
            } while (shouldAnimate())
        }
    }
}
