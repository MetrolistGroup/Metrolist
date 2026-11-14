package com.metrolist.android.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.metrolist.android.LocalPlayerExpandedOrExpanding
import com.metrolist.shared.ui.LocalIsTv
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

internal val LocalTvAppHasFocus = staticCompositionLocalOf<() -> Boolean> { { false } }

internal val LocalTvNavigationFocusFallback = staticCompositionLocalOf<() -> Boolean> { { false } }

@Composable
internal fun TvNavigationFocus(
    requestInitialFocus: Boolean,
    hasFocusableContent: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!LocalIsTv.current) {
        content()
        return
    }
    val focusRequester = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }
    val appHasFocus = LocalTvAppHasFocus.current()
    val fallback = LocalTvNavigationFocusFallback.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = lifecycleState == Lifecycle.State.RESUMED && !LocalPlayerExpandedOrExpanding.current
    LaunchedEffect(active, requestInitialFocus, appHasFocus, windowFocused) {
        if (active && windowFocused && (!appHasFocus || (requestInitialFocus && !initialFocusRequested))) {
            val focused =
                hasFocusableContent && withTimeoutOrNull(1.seconds) {
                    do {
                        withFrameNanos { }
                    } while (!runCatching { focusRequester.requestFocus() }.getOrDefault(false))
                    true
                } == true
            initialFocusRequested = true
            if (!focused) {
                withFrameNanos { }
                fallback()
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusGroup(),
    ) {
        content()
    }
}
