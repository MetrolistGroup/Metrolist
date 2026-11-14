package com.metrolist.shared.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.metrolist.shared.ui.navigation.LocalNavigationContainerState
import com.metrolist.shared.ui.navigation.NavigationPopupOrigin
import com.metrolist.shared.ui.navigation.blockInput
import com.metrolist.shared.ui.navigation.resetPopupSource
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog as PlatformDialog
import androidx.compose.ui.window.Popup as PlatformPopup

internal class PopupSession {
    val state = PopupTransitionState()
    val alpha = Animatable(0f)
    val scale = Animatable(0.95f)
    val sheetVisible = mutableStateOf(false)
    var origin: NavigationPopupOrigin? = null
}

internal val LocalPopupSession = staticCompositionLocalOf<PopupSession?> { null }

@Composable
internal fun popupSheetVisibility(
    visible: Boolean,
    sheetVisible: Boolean,
): Boolean {
    val session = LocalPopupSession.current ?: return visible
    val requested = visible && session.state.targetVisible
    SideEffect { session.sheetVisible.value = requested || sheetVisible }
    DisposableEffect(session.sheetVisible) {
        onDispose { session.sheetVisible.value = false }
    }
    return requested
}

@Composable
private fun rememberPopupSession(visible: Boolean): PopupSession {
    val session = remember { PopupSession() }
    val containers = LocalNavigationContainerState.current
    if (session.state.request(visible) && visible) session.origin = containers?.takePopupSource()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(visible) {
        session.state.animate {
            coroutineScope {
                launch { session.alpha.animateTo(if (visible) 1f else 0f, effects) }
                launch { session.scale.animateTo(if (visible) 1f else 0.95f, spatial) }
            }
        }
    }
    DisposableEffect(session) {
        onDispose { session.state.dispose() }
    }
    return session
}

@Composable
fun PopupVisibility(
    visible: Boolean,
    onHidden: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val session = rememberPopupSession(visible)
    val hidden = rememberUpdatedState(onHidden)
    var requested by remember { mutableStateOf(false) }
    LaunchedEffect(visible, session.state.retained, session.sheetVisible.value) {
        if (visible) requested = true
        if (requested && !visible && !session.state.retained && !session.sheetVisible.value) {
            requested = false
            hidden.value?.invoke()
        }
    }
    if (visible || session.state.retained || session.sheetVisible.value) {
        CompositionLocalProvider(LocalPopupSession provides session, content = content)
    }
}

@Composable
fun Modifier.popupMotion(interactive: Boolean = true): Modifier {
    val session = LocalPopupSession.current ?: rememberPopupSession(true)
    return graphicsLayer {
        alpha = session.alpha.value.coerceIn(0f, 1f)
        scaleX = session.scale.value
        scaleY = session.scale.value
    }.blockInput(!session.state.acceptsInput || !interactive)
}

@Composable
internal fun Modifier.popupWindowBounds(
    windowSize: IntSize,
    onDismissRequest: () -> Unit,
    dismissOnClickOutside: Boolean = true,
    positionProvider: PopupPositionProvider? = null,
): Modifier {
    val origin = LocalPopupSession.current?.origin ?: return this
    var contentBounds by remember { mutableStateOf(Rect.Zero) }
    val acceptsInput = LocalPopupSession.current?.state?.acceptsInput != false
    return blockInput(!acceptsInput)
        .resetPopupSource(LocalNavigationContainerState.current)
        .pointerInput(dismissOnClickOutside, onDismissRequest) {
            detectTapGestures { position ->
                if (dismissOnClickOutside && !contentBounds.contains(position)) onDismissRequest()
            }
        }.layout { measurable, constraints ->
            val width = constraints.constrainWidth(windowSize.width)
            val height = constraints.constrainHeight(windowSize.height)
            val child = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = width, maxHeight = height))
            val offset =
                positionProvider?.calculatePosition(
                    origin.windowBounds.roundToIntRect(),
                    IntSize(width, height),
                    layoutDirection,
                    IntSize(child.width, child.height),
                ) ?: IntOffset((width - child.width) / 2, (height - child.height) / 2)
            contentBounds = Rect(offset.toOffset(), IntSize(child.width, child.height).toSize())
            layout(width, height) { child.place(offset) }
        }
}

@Composable
fun <T : Any> PopupContent(
    value: T?,
    content: @Composable (T) -> Unit,
) {
    var retained by remember { mutableStateOf(value) }
    if (value != null) retained = value
    PopupVisibility(value != null) { retained?.let { content(it) } }
}

@Composable
fun Dialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    PlatformDialog(onDismissRequest, properties) {
        CompositionLocalProvider(LocalDensity provides density) {
            Box(Modifier.popupMotion()) { content() }
        }
    }
}

@Composable
internal fun Popup(
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: () -> Unit,
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable () -> Unit,
) {
    val windowSize = LocalWindowInfo.current.containerSize
    val sourced = LocalPopupSession.current?.origin != null
    val position =
        if (sourced) {
            remember {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ) = IntOffset.Zero
                }
            }
        } else {
            popupPositionProvider
        }
    PlatformPopup(position, onDismissRequest, properties = properties) {
        Box(Modifier.popupWindowBounds(windowSize, onDismissRequest, properties.dismissOnClickOutside, popupPositionProvider)) { content() }
    }
}
