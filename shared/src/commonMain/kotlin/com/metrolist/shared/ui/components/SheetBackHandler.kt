package com.metrolist.shared.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.launch

private val SheetBackEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
internal val LocalSheetBackProgress = staticCompositionLocalOf<State<Float>?> { null }

@Composable
internal fun Modifier.sheetBackContent(): Modifier {
    val progress = LocalSheetBackProgress.current ?: return this
    return graphicsLayer { applySheetBackContentTransform(progress.value) }
}

@Composable
internal fun rememberSheetBackProgress(
    enabled: Boolean,
    onBack: suspend () -> Unit,
): State<Float> {
    val scope = rememberCoroutineScope()
    val onComplete by rememberUpdatedState(onBack)
    val back = rememberNavigationEventState(NavigationEventInfo.None)
    val progress = remember { Animatable(0f) }
    var completing by remember { mutableStateOf(false) }
    NavigationBackHandler(
        state = back,
        isBackEnabled = enabled,
        onBackCompleted = {
            if (!completing) {
                completing = true
                scope.launch {
                    try {
                        onComplete()
                    } finally {
                        completing = false
                    }
                }
            }
        },
    )
    val gesture = back.transitionState as? NavigationEventTransitionState.InProgress
    val returnSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(gesture, completing) {
        if (!completing) {
            if (gesture != null) {
                progress.snapTo(SheetBackEasing.transform(gesture.latestEvent.progress))
            } else {
                progress.animateTo(0f, returnSpec)
            }
        }
    }
    return progress.asState()
}

internal fun GraphicsLayerScope.applySheetBackTransform(
    progress: Float,
    offset: Float,
) {
    if (size.width > 0f && size.height > 0f && offset.isFinite()) {
        scaleX = 1f - (48.dp.toPx() / size.width).coerceAtMost(1f) * progress
        scaleY = 1f - (24.dp.toPx() / size.height).coerceAtMost(1f) * progress
        transformOrigin = TransformOrigin(0.5f, 1f + offset / size.height)
    }
}

internal fun GraphicsLayerScope.applySheetBackContentTransform(progress: Float) {
    if (size.width > 0f && size.height > 0f) {
        val scaleX = 1f - (48.dp.toPx() / size.width).coerceAtMost(1f) * progress
        val scaleY = 1f - (24.dp.toPx() / size.height).coerceAtMost(1f) * progress
        this.scaleY = if (scaleY > 0f) scaleX / scaleY else 1f
        transformOrigin = TransformOrigin(0.5f, 0f)
    }
}
