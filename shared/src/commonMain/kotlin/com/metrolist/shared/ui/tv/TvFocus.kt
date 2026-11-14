package com.metrolist.shared.ui.tv

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.focusOutline
import kotlinx.coroutines.launch

@Composable
fun Modifier.tvFocusHighlight(
    shape: Shape = RoundedCornerShape(12.dp),
    scale: Float = 1f,
    bringIntoView: Boolean = true,
    borderWidth: Dp = 3.dp,
    color: Color = MaterialTheme.colorScheme.primary,
): Modifier {
    if (!LocalIsTv.current) return focusOutline(shape, color, borderWidth)

    var isFocused by remember { mutableStateOf(false) }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    return this
        .then(if (bringIntoView) Modifier.bringIntoViewRequester(bringIntoViewRequester) else Modifier)
        .onFocusChanged { state ->
            isFocused = state.isFocused
            if (state.isFocused && bringIntoView) {
                scope.launch { bringIntoViewRequester.bringIntoView() }
            }
        }.zIndex(if (isFocused) 1f else 0f)
        .scale(if (isFocused) scale else 1f)
        .focusOutline(shape, color, borderWidth)
        .clip(shape)
}

@Composable
fun RequestTvFocus(
    focusRequester: FocusRequester,
    enabled: Boolean = true,
) {
    val isTv = LocalIsTv.current
    var focusRequested by remember(isTv, focusRequester) { mutableStateOf(false) }
    LaunchedEffect(isTv, enabled, focusRequester) {
        if (isTv && enabled && !focusRequested) {
            withFrameNanos { }
            focusRequested = runCatching { focusRequester.requestFocus() }.getOrDefault(false)
        }
    }
}

@Composable
fun Modifier.tvVerticalScrollFocus(
    scrollState: ScrollState,
    shape: Shape = RoundedCornerShape(12.dp),
    scrollStep: Dp = 180.dp,
): Modifier {
    if (!LocalIsTv.current) return this
    val scope = rememberCoroutineScope()
    var isFocused by remember { mutableStateOf(false) }
    val scrollStepPx = with(LocalDensity.current) { scrollStep.toPx() }
    return tvFocusHighlight(shape = shape)
        .onFocusChanged { isFocused = it.isFocused }
        .onPreviewKeyEvent { event ->
            if (!isFocused || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val delta =
                when (event.key) {
                    Key.DirectionDown -> scrollStepPx
                    Key.DirectionUp -> -scrollStepPx
                    else -> return@onPreviewKeyEvent false
                }
            val target = (scrollState.value + delta).toInt().coerceIn(0, scrollState.maxValue)
            if (target == scrollState.value) return@onPreviewKeyEvent false
            scope.launch { scrollState.animateScrollTo(target) }
            true
        }.focusable()
}

@Composable
fun Modifier.tvHorizontalScrollFocus(
    scrollState: ScrollState,
    shape: Shape = RoundedCornerShape(12.dp),
    scrollStep: Dp = 180.dp,
): Modifier {
    if (!LocalIsTv.current) return this
    val scope = rememberCoroutineScope()
    var isFocused by remember { mutableStateOf(false) }
    val scrollStepPx = with(LocalDensity.current) { scrollStep.toPx() }
    return tvFocusHighlight(shape = shape)
        .onFocusChanged { isFocused = it.isFocused }
        .onPreviewKeyEvent { event ->
            if (!isFocused || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val delta =
                when (event.key) {
                    Key.DirectionRight -> scrollStepPx
                    Key.DirectionLeft -> -scrollStepPx
                    else -> return@onPreviewKeyEvent false
                }
            val target = (scrollState.value + delta).toInt().coerceIn(0, scrollState.maxValue)
            if (target == scrollState.value) return@onPreviewKeyEvent false
            scope.launch { scrollState.animateScrollTo(target) }
            true
        }.focusable()
}
