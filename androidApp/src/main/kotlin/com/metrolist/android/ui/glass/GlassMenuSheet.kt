package com.metrolist.android.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.first

import com.metrolist.android.ui.theme.colorPalette
import com.metrolist.android.ui.menu.CenteredMenuSheetDragHandle
import com.metrolist.android.ui.menu.straightBottomMenuShape
import com.metrolist.android.ui.theme.topUiRoundnessShape

/** Retains the sheet's anchors and drag handling inside the shared glass window. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlassMenuSheet(
    showSheet: Boolean,
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    expandOnShow: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) = SharedGlassMenuSheet(
    showSheet, onDismissRequest, sheetState, partiallyExpanded = true,
    expandOnShow = expandOnShow, content = content,
)

/** Also hosts short form sheets, reserving their IME insets inside the modal viewport. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedGlassMenuSheet(
    showSheet: Boolean,
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    partiallyExpanded: Boolean = false,
    scrimColor: Color = Color.Unspecified,
    maxSheetHeight: Dp = Dp.Unspecified,
    modifier: Modifier = Modifier,
    shape: Shape = topUiRoundnessShape(),
    dragHandle: @Composable (() -> Unit)? = null,
    contentWindowInsets: @Composable () -> WindowInsets = { WindowInsets(0) },
    expandOnShow: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetShape = remember(shape) { straightBottomMenuShape(shape) }
    var composing by remember { mutableStateOf(showSheet) }
    var dismissRequested by remember { mutableStateOf(false) }
    var awaitingShow by remember { mutableStateOf(showSheet) }
    val scrimMotion = remember { Animatable(0f) }
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    val currentShowSheet by rememberUpdatedState(showSheet)
    LaunchedEffect(showSheet) {
        if (showSheet) {
            dismissRequested = false
            awaitingShow = true
            composing = true
        }
    }
    if (!composing && !showSheet) return

    GlassDialogWindow(
        useSharedHost = true,
        onDismissRequest = { dismissRequested = true },
        properties = DialogProperties(),
        edgeToEdge = true,
        area = GlassArea.Menus,
        scrimVisible = sheetState.targetValue != SheetValue.Hidden ||
            (awaitingShow && showSheet && !dismissRequested),
        scrimColor = scrimColor,
        scrimAnimation = scrimMotion,
    ) {
        LaunchedEffect(showSheet, dismissRequested, sheetState) {
            if (showSheet && !dismissRequested) {
                // Shared overlays can be attached after the caller's frame. Wait for the
                // scaffold to measure an actual expanded anchor before starting its motion.
                snapshotFlow { sheetState.hasExpandedState }.first { it }
                awaitingShow = false
                if (expandOnShow) sheetState.expand() else sheetState.show()
            } else {
                sheetState.hide()
                // confirmValueChange may veto Hidden. Keep both the sheet and its scrim mounted.
                if (!sheetState.isVisible) {
                    snapshotFlow { scrimMotion.value }.first { it == 0f }
                    composing = false
                    if (dismissRequested) currentDismiss()
                } else dismissRequested = false
            }
        }
        LaunchedEffect(sheetState) {
            var wasVisible = sheetState.isVisible
            snapshotFlow { sheetState.currentValue }.collect { value ->
                if (value != SheetValue.Hidden) wasVisible = true
                else if (wasVisible && currentShowSheet && !dismissRequested) dismissRequested = true
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        ).windowInsetsPadding(contentWindowInsets())) {
            BottomSheetScaffold(
                modifier = modifier.fillMaxSize(),
                scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState),
                // A fully expanded short form must retain its content height as IME insets change.
                sheetPeekHeight = if (partiallyExpanded) maxHeight / 2 else 0.dp,
                sheetShape = sheetShape,
                sheetContainerColor = Color.Transparent,
                sheetContentColor = colorPalette().text,
                sheetShadowElevation = 0.dp,
                sheetTonalElevation = 0.dp,
                sheetDragHandle = if (maxSheetHeight.isSpecified || dragHandle == null) null
                    else ({ CenteredMenuSheetDragHandle(dragHandle) }),
                containerColor = Color.Transparent,
                sheetContent = {
                    val paintScope = LocalGlassOverlayPaintScope.current
                    CompositionLocalProvider(LocalGlassOverlayPaintScope provides paintScope?.let {
                        GlassOverlayPaintScope(it.host, it.id) {
                            if (sheetState.currentValue == SheetValue.Expanded && sheetState.targetValue == SheetValue.Expanded) it.alpha() else 0f
                        }
                    }) {
                    if (maxSheetHeight.isSpecified) {
                        Column(Modifier.heightIn(max = maxSheetHeight)) {
                            dragHandle?.let { CenteredMenuSheetDragHandle(it) }
                            content()
                        }
                    } else content()
                    }
                },
            ) { }
        }
    }
}
