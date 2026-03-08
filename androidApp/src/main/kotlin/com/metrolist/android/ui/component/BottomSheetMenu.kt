/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.LocalPlayerImmersiveMode
import com.metrolist.shared.ui.components.ModalBottomSheet
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.theme.ComponentShapes
import com.metrolist.android.ui.component.DensityAwareDialog as Dialog
import com.metrolist.shared.ui.components.ModalSheetState as SheetState
import com.metrolist.shared.ui.components.rememberModalSheetState as rememberBottomSheetState

val LocalMenuState = compositionLocalOf { MenuState() }

@Composable
private fun ApplyPlayerImmersiveMode() {
    val hideSystemBars = LocalPlayerImmersiveMode.current
    val view = LocalView.current

    DisposableEffect(view, hideSystemBars) {
        // Use the dialog window explicitly, including on Android versions before API 30.
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@DisposableEffect onDispose {}
        val updateSystemBars =
            Runnable {
                WindowCompat.getInsetsController(window, view).apply {
                    if (hideSystemBars) {
                        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(WindowInsetsCompat.Type.systemBars())
                    } else {
                        show(WindowInsetsCompat.Type.systemBars())
                    }
                }
            }
        updateSystemBars.run()
        view.post(updateSystemBars)
        onDispose { view.removeCallbacks(updateSystemBars) }
    }
}

@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberBottomSheetState(SheetValue.Hidden),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    shape: Shape = ComponentShapes.BottomSheetShape,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = {
        Box(
            modifier =
                Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
    },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties,
    gesturesEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalIsTv.current) {
        Dialog(onDismissRequest = onDismissRequest) {
            ApplyPlayerImmersiveMode()
            val initialFocusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                withFrameNanos { }
                initialFocusRequester.requestFocus()
            }
            Surface(
                modifier =
                    modifier
                        .ignoreHeldActivationRelease()
                        .fillMaxWidth()
                        .widthIn(max = sheetMaxWidth)
                        .heightIn(max = 640.dp),
                shape = shape,
                color = containerColor,
                contentColor = contentColor,
                tonalElevation = tonalElevation,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(initialFocusRequester)
                            .focusGroup()
                            .focusRestorer()
                            .padding(24.dp),
                    content = content,
                )
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier.ignoreHeldActivationRelease(),
            sheetState = sheetState,
            sheetMaxWidth = sheetMaxWidth,
            shape = shape,
            containerColor = containerColor,
            contentColor = contentColor,
            tonalElevation = tonalElevation,
            scrimColor = scrimColor,
            dragHandle = dragHandle,
            contentWindowInsets = contentWindowInsets,
            properties = properties,
            gesturesEnabled = gesturesEnabled,
            content = {
                ApplyPlayerImmersiveMode()
                content()
            },
        )
    }
}

@Stable
class MenuState(
    isVisible: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    var isVisible by mutableStateOf(isVisible)
    var content by mutableStateOf(content)

    fun show(content: @Composable ColumnScope.() -> Unit) {
        isVisible = true
        this.content = content
    }

    fun dismiss() {
        isVisible = false
    }
}

@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimatedBottomSheet(
    isVisible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberBottomSheetState(SheetValue.Hidden),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    shape: Shape = ComponentShapes.BottomSheetShape,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = {
        Box(
            modifier =
                Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
    },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties,
    content: @Composable ColumnScope.() -> Unit,
) {
    var lastContent by remember { mutableStateOf(content) }

    LaunchedEffect(content) {
        if (isVisible) {
            lastContent = content
        }
    }

    if (!sheetState.isVisible && !isVisible) {
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        isVisible = isVisible,
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        scrimColor = scrimColor,
        dragHandle = dragHandle,
        contentWindowInsets = contentWindowInsets,
        properties = properties,
        content = {
            ApplyPlayerImmersiveMode()
            lastContent()
        },
    )
}

@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetMenu(
    modifier: Modifier = Modifier,
    state: MenuState,
    background: Color = MaterialTheme.colorScheme.surfaceContainerLow,
) {
    val sheetState = rememberBottomSheetState(SheetValue.Hidden)
    val isTv = LocalIsTv.current

    if (isTv) {
        PopupVisibility(visible = state.isVisible) {
            Dialog(onDismissRequest = state::dismiss) {
                ApplyPlayerImmersiveMode()
                val initialFocusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    withFrameNanos { }
                    initialFocusRequester.requestFocus()
                }
                Surface(
                    modifier =
                        modifier
                            .ignoreHeldActivationRelease()
                            .fillMaxWidth()
                            .widthIn(max = 720.dp)
                            .heightIn(max = 640.dp),
                    shape = ComponentShapes.MenuExteriorShape,
                    color = background,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(initialFocusRequester)
                                .focusGroup()
                                .focusRestorer()
                                .padding(horizontal = 20.dp, vertical = 24.dp),
                    ) {
                        state.content(this)
                    }
                }
            }
        }
        return
    }

    AnimatedBottomSheet(
        isVisible = state.isVisible,
        onDismissRequest = {
            state.isVisible = false
        },
        sheetState = sheetState,
        containerColor = background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.ignoreHeldActivationRelease(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
        ) {
            state.content(this)
        }
    }
}

/**
 * Long-pressing OK opens menus while the key is still held. Its repeats and release belong to that long press,
 * so they must not activate the menu's first item; a fresh press inside the menu works normally.
 */
@Composable
private fun Modifier.ignoreHeldActivationRelease(): Modifier {
    val pressStarted = remember { BooleanArray(1) }
    return onPreviewKeyEvent { event ->
        val key = event.key
        if (key != Key.DirectionCenter && key != Key.Enter && key != Key.NumPadEnter) return@onPreviewKeyEvent false
        when (event.type) {
            KeyEventType.KeyDown -> {
                if (event.nativeKeyEvent.repeatCount == 0) pressStarted[0] = true
                !pressStarted[0]
            }

            KeyEventType.KeyUp -> {
                !pressStarted[0]
            }

            else -> {
                false
            }
        }
    }
}
