package com.metrolist.shared.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DragScope
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Stable
class ModalSheetState internal constructor(
    initialValue: SheetValue,
    private val enabledValues: Set<SheetValue>,
    density: Float,
    private val spatialSpec: AnimationSpec<Float>,
    private val confirmValueChange: (SheetValue) -> Boolean = { true },
) : DraggableState {
    internal val anchorsState =
        AnchoredDraggableState(
            initialValue = initialValue,
            positionalThreshold = { 56f * density },
            velocityThreshold = { 125f * density },
            snapAnimationSpec = spatialSpec,
            decayAnimationSpec = exponentialDecay(),
            confirmValueChange = confirmValueChange,
        )
    var isDragging by mutableStateOf(false)
        private set
    private var pendingAnchors: DraggableAnchors<SheetValue>? = null
    private var animation: Job? = null
    private var wheelSettling: Job? = null

    val isVisible: Boolean
        get() =
            isDragging || anchorsState.currentValue != SheetValue.Hidden || anchorsState.targetValue != SheetValue.Hidden ||
                isAnimationRunning

    private val settleSlop = 16f * density

    // The spring's last pixels take long to settle; once the sheet is visually in place, taps must land.
    internal val acceptsInput: Boolean by derivedStateOf {
        val target = anchorsState.targetValue
        val settling = target != SheetValue.Hidden && abs(anchorsState.offset - anchorsState.anchors.positionOf(target)) <= settleSlop
        (isDragging || target != SheetValue.Hidden) && (!isAnimationRunning || settling)
    }

    internal val isAnimationRunning: Boolean get() = anchorsState.isAnimationRunning
    internal val offset: Float get() = anchorsState.offset

    internal fun updateBounds(
        fullHeight: Float,
        sheetHeight: Float,
    ) {
        if (fullHeight <= 0f || sheetHeight <= 0f) return
        val anchors =
            DraggableAnchors {
                SheetValue.Hidden at fullHeight
                SheetValue.Expanded at (fullHeight - sheetHeight).coerceAtLeast(0f)
                if (SheetValue.PartiallyExpanded in enabledValues && sheetHeight > fullHeight / 2f) {
                    SheetValue.PartiallyExpanded at fullHeight / 2f
                }
            }
        // Keep anchors stable under Foundation's drag lock, including animations that target a removed anchor.
        if (isDragging || animation != null) pendingAnchors = anchors else applyAnchors(anchors)
    }

    private fun applyAnchors(anchors: DraggableAnchors<SheetValue>) {
        val target = anchorsState.targetValue.let { if (anchors.hasPositionFor(it)) it else SheetValue.Expanded }
        anchorsState.updateAnchors(anchors, target)
    }

    private fun finishDrag() {
        isDragging = false
        applyPendingAnchors(preserveOffset = true)
    }

    private fun applyPendingAnchors(preserveOffset: Boolean = false) {
        if (isDragging || animation != null) return
        pendingAnchors?.let { anchors ->
            pendingAnchors = null
            val offset = anchorsState.offset
            applyAnchors(anchors)
            // Settling follows a drag; keep the finger's position until that animation starts.
            if (preserveOffset) anchorsState.dispatchRawDelta(offset - anchorsState.offset)
        }
    }

    override suspend fun drag(
        dragPriority: MutatePriority,
        block: suspend DragScope.() -> Unit,
    ) {
        wheelSettling?.cancel()
        var started = false
        try {
            anchorsState.anchoredDrag(dragPriority) {
                started = true
                isDragging = true
                val scope = this
                block(
                    object : DragScope {
                        override fun dragBy(pixels: Float) {
                            scope.dragTo(
                                (anchorsState.offset + pixels).coerceIn(
                                    anchorsState.anchors.minPosition(),
                                    anchorsState.anchors.maxPosition(),
                                ),
                            )
                        }
                    },
                )
            }
        } finally {
            if (started) finishDrag()
        }
    }

    override fun dispatchRawDelta(delta: Float) {
        scrollBy(delta)
    }

    internal fun scrollBy(delta: Float): Float {
        wheelSettling?.cancel()
        animation?.cancel()
        return anchorsState.dispatchRawDelta(delta).also { if (it != 0f) isDragging = true }
    }

    internal fun scrollWheelBy(
        delta: Float,
        scope: CoroutineScope,
        onDismiss: () -> Unit,
    ): Float {
        val consumed = scrollBy(delta)
        // Wheel events have no fling callback; settle after the last event in the burst.
        wheelSettling =
            scope.launch {
                delay(150)
                settle(0f)
                if (!isVisible) onDismiss()
            }
        return consumed
    }

    private suspend fun animateTo(value: SheetValue) {
        wheelSettling?.cancel()
        snapshotFlow { anchorsState.anchors.size > 0 }.first { it }
        if (!confirmValueChange(value)) return
        finishDrag()
        val job = currentCoroutineContext()[Job]
        animation = job
        try {
            anchorsState.animateTo(value, spatialSpec)
        } finally {
            if (animation === job) {
                animation = null
                applyPendingAnchors()
            }
        }
    }

    suspend fun show() {
        snapshotFlow { anchorsState.anchors.size > 0 }.first { it }
        animateTo(
            if (anchorsState.anchors.hasPositionFor(SheetValue.PartiallyExpanded)) SheetValue.PartiallyExpanded else SheetValue.Expanded,
        )
    }

    suspend fun hide(): Boolean {
        if (!confirmValueChange(SheetValue.Hidden)) return false
        animateTo(SheetValue.Hidden)
        return anchorsState.settledValue == SheetValue.Hidden
    }

    suspend fun expand() = animateTo(SheetValue.Expanded)

    internal suspend fun settle(velocity: Float) {
        finishDrag()
        val job = currentCoroutineContext()[Job]
        animation = job
        try {
            anchorsState.settle(velocity)
        } finally {
            if (animation === job) {
                animation = null
                applyPendingAnchors()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberModalSheetState(
    initialValue: SheetValue = SheetValue.Hidden,
    enabledValues: Set<SheetValue> = setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded),
    confirmValueChange: (SheetValue) -> Boolean = { true },
): ModalSheetState {
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val density = LocalDensity.current.density
    val confirm = rememberUpdatedState(confirmValueChange)
    return rememberSaveable(
        density,
        enabledValues,
        spatialSpec,
        saver =
            Saver(
                save = { it.anchorsState.settledValue },
                restore = { ModalSheetState(it, enabledValues, density, spatialSpec) { value -> confirm.value(value) } },
            ),
    ) { ModalSheetState(initialValue, enabledValues, density, spatialSpec) { confirm.value(it) } }
}
