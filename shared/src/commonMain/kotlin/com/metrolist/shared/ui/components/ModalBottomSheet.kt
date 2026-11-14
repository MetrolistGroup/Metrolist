package com.metrolist.shared.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.bottom_sheet_pane_title
import com.metrolist.shared.generated.resources.dismiss
import com.metrolist.shared.generated.resources.expand
import com.metrolist.shared.ui.navigation.LocalNavigationContainerState
import com.metrolist.shared.ui.navigation.blockInput
import com.metrolist.shared.ui.theme.ComponentShapes
import com.metrolist.shared.ui.utils.mouseWheelScroll
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    sheetState: ModalSheetState = rememberModalSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    shape: Shape = ComponentShapes.BottomSheetShape,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.modalWindowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    gesturesEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val requestedVisible = popupSheetVisibility(isVisible, sheetState.isVisible)
    val scope = rememberCoroutineScope()
    val onDismiss = rememberUpdatedState(onDismissRequest)
    val dismissSheet: suspend () -> Unit = {
        if (sheetState.hide()) onDismiss.value()
    }
    val dismiss: () -> Unit = {
        scope.launch { dismissSheet() }
    }
    val nestedScrollConnection =
        remember(sheetState) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset =
                    if (source == NestedScrollSource.UserInput && available.y < 0f) {
                        Offset(0f, sheetState.scrollBy(available.y))
                    } else {
                        Offset.Zero
                    }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset = if (source == NestedScrollSource.UserInput) Offset(0f, sheetState.scrollBy(available.y)) else Offset.Zero

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (available.y < 0f && sheetState.offset > sheetState.anchorsState.anchors.minPosition()) {
                        sheetState.settle(available.y)
                        return available
                    }
                    return Velocity.Zero
                }

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity {
                    sheetState.settle(available.y)
                    if (!sheetState.isVisible) onDismiss.value()
                    return available
                }
            }
        }
    val density = LocalDensity.current
    val dismissLabel = stringResource(Res.string.dismiss)
    val sheetLabel = stringResource(Res.string.bottom_sheet_pane_title)
    val expandLabel = stringResource(Res.string.expand)
    val visible = requestedVisible && sheetState.anchorsState.targetValue != SheetValue.Hidden
    val scrimAlpha by animateFloatAsState(if (visible) 1f else 0f, MaterialTheme.motionScheme.fastEffectsSpec())
    val containers = LocalNavigationContainerState.current
    remember { containers?.takePopupSource() }
    ModalSheetDialog(onDismissRequest = dismissSheet, properties = properties, contentColor = contentColor) { backProgress ->
        CompositionLocalProvider(LocalDensity provides density) {
            Box(Modifier.fillMaxSize().blockInput(!requestedVisible || !sheetState.acceptsInput)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(scrimColor.copy(alpha = scrimColor.alpha * scrimAlpha))
                        .semantics { contentDescription = dismissLabel }
                        .then(
                            if (properties.shouldDismissOnClickOutside) {
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = dismiss,
                                )
                            } else {
                                Modifier
                            },
                        ),
                )
                // Insets are independent of the sheet offset, so expanding cannot remeasure its anchors.
                Layout(
                    modifier =
                        Modifier.fillMaxSize().clipToBounds().windowInsetsPadding(
                            contentWindowInsets().only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                        ),
                    content = {
                        Surface(
                            modifier =
                                modifier
                                    .graphicsLayer {
                                        applySheetBackTransform(backProgress, sheetState.offset)
                                    }.widthIn(max = sheetMaxWidth)
                                    .fillMaxWidth()
                                    .then(
                                        if (gesturesEnabled) {
                                            Modifier
                                                .nestedScroll(nestedScrollConnection)
                                                .mouseWheelScroll(horizontal = false) { delta ->
                                                    sheetState.scrollWheelBy(delta, scope) { onDismiss.value() }
                                                }.draggable(
                                                    state = sheetState,
                                                    orientation = Orientation.Vertical,
                                                    startDragImmediately = sheetState.isAnimationRunning,
                                                    onDragStopped = { velocity ->
                                                        sheetState.settle(velocity)
                                                        if (!sheetState.isVisible) onDismiss.value()
                                                    },
                                                )
                                        } else {
                                            Modifier
                                        },
                                    ).semantics {
                                        isTraversalGroup = true
                                        paneTitle = sheetLabel
                                        dismiss {
                                            dismiss()
                                            true
                                        }
                                        expand {
                                            scope.launch { sheetState.expand() }
                                            true
                                        }
                                        collapse {
                                            scope.launch { sheetState.show() }
                                            true
                                        }
                                    },
                            shape = shape,
                            color = containerColor,
                            contentColor = contentColor,
                            tonalElevation = tonalElevation,
                        ) {
                            Column(
                                Modifier
                                    .windowInsetsPadding(contentWindowInsets().only(WindowInsetsSides.Bottom))
                                    .graphicsLayer {
                                        applySheetBackContentTransform(backProgress)
                                    },
                            ) {
                                if (dragHandle != null) {
                                    val expanded = sheetState.anchorsState.settledValue == SheetValue.Expanded
                                    Box(
                                        Modifier.fillMaxWidth().clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClickLabel = if (expanded) dismissLabel else expandLabel,
                                        ) {
                                            if (expanded) dismiss() else scope.launch { sheetState.expand() }
                                        },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        dragHandle()
                                    }
                                }
                                content()
                            }
                        }
                    },
                ) { measurables, constraints ->
                    val sheet = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
                    sheetState.updateBounds(constraints.maxHeight.toFloat(), sheet.height.toFloat())
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        sheet.placeRelativeWithLayer((constraints.maxWidth - sheet.width) / 2, sheetState.offset.roundToInt())
                    }
                }
            }
        }
    }
    LaunchedEffect(sheetState, requestedVisible) {
        if (requestedVisible) sheetState.show() else sheetState.hide()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal expect fun ModalSheetDialog(
    onDismissRequest: suspend () -> Unit,
    properties: ModalBottomSheetProperties,
    contentColor: Color,
    content: @Composable (backProgress: Float) -> Unit,
)
