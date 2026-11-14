/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.player.LocalPlayerArtworkHandoff
import com.metrolist.shared.ui.player.PlayerArtworkHandoff
import com.metrolist.shared.ui.theme.uiEnhanced
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow

private object HiddenSheetContentShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Rectangle(Rect.Zero)
}

private class BottomSheetClipShape(
    private val progress: Float,
    private val cornerRadius: Float,
    private val collapsedHeaderHeightPx: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val p = progress.coerceIn(0f, 1f)
        val visibleHeight =
            if (p <= 0f) {
                collapsedHeaderHeightPx
            } else {
                collapsedHeaderHeightPx + (size.height - collapsedHeaderHeightPx) * p
            }
        val rect = Rect(0f, 0f, size.width, visibleHeight.coerceIn(0f, size.height))
        return if (cornerRadius > 0f) {
            Outline.Rounded(
                RoundRect(
                    rect = rect,
                    topLeft = CornerRadius(cornerRadius),
                    topRight = CornerRadius(cornerRadius),
                ),
            )
        } else {
            Outline.Rectangle(rect)
        }
    }
}

/**
 * Bottom Sheet
 * Modified from [ViMusic](https://github.com/vfsfitvnm/ViMusic)
 */
@Composable
fun BottomSheet(
    state: BottomSheetState,
    modifier: Modifier = Modifier,
    collapsedHeaderHeight: Dp = state.collapsedBound,
    background: @Composable (BoxScope.() -> Unit) = { },
    prepareDismissal: (() -> (() -> Unit))? = null,
    collapsedContentAlpha: (Float) -> Float = { progress -> 1f - (progress * 4).coerceAtMost(1f) },
    contentAlpha: (Float) -> Float = { progress -> ((progress - 0.15f) * 4).coerceIn(0f, 1f) },
    isCollapsedContentClickable: Boolean = true,
    collapsedContent: @Composable BoxScope.() -> Unit,
    isExpandable: Boolean = true,
    isDraggableWhenCollapsed: Boolean = true,
    preserveContentWhenCollapsed: Boolean = false,
    morphCollapsedContainer: Boolean = false,
    backHandlerEnabled: Boolean = true,
    predictiveBack: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val container = remember(morphCollapsedContainer) { if (morphCollapsedContainer) PlayerSheetContainerState() else null }
    val artworkHandoff = remember(morphCollapsedContainer) { if (morphCollapsedContainer) PlayerArtworkHandoff() else null }
    val motionScheme = MaterialTheme.motionScheme
    val motion = remember(motionScheme) { PlayerSheetMotion(motionScheme) }
    val expandedColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 1f)
    val expandedAlpha = if (morphCollapsedContainer) motion::expandedAlpha else contentAlpha
    val miniPlayerAlpha = if (morphCollapsedContainer) motion::collapsedAlpha else collapsedContentAlpha

    val fadeStrategy = CompositingStrategy.Auto
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val mandatoryGestureBottom by rememberUpdatedState(WindowInsets.mandatorySystemGestures.getBottom(density))
    val currentHeaderHeight by rememberUpdatedState(collapsedHeaderHeight)
    val currentPrepareDismissal by rememberUpdatedState(prepareDismissal)

    val isOpening by remember(state) { derivedStateOf { state.progress > 0f } }
    val handlesBack by remember(state) { derivedStateOf { state.progress > 0.2f && !state.isDismissed } }
    val backProgress =
        if (predictiveBack) {
            rememberSheetBackProgress(enabled = backHandlerEnabled && handlesBack) {
                state.collapse(motionScheme.defaultSpatialSpec()).join()
            }
        } else {
            null
        }
    LaunchedEffect(state.isExpanded) {
        if (state.isExpanded) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }

    val dragGestureModifier =
        if (isExpandable) {
            Modifier.pointerInput(state) {
                val velocityTracker = VelocityTracker()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val headerHeightPx = with(density) { currentHeaderHeight.toPx() }
                    if (state.isCollapsed && down.position.y > headerHeightPx) return@awaitEachGesture
                    val translationY = with(density) { (state.expandedBound - state.value).toPx().coerceAtLeast(0f) }
                    val dragAccepted = down.position.y + translationY < size.height - mandatoryGestureBottom
                    if (!dragAccepted) return@awaitEachGesture

                    val onDismiss = currentPrepareDismissal?.invoke()
                    velocityTracker.resetTracking()
                    var verticalGesture: Boolean? = null
                    val drag =
                        awaitTouchSlopOrCancellation(down.id) { change, overSlop ->
                            if (verticalGesture == null) {
                                val direction = if (overSlop == Offset.Zero) change.position - down.position else overSlop
                                verticalGesture = abs(direction.y) > abs(direction.x)
                            }
                            if (verticalGesture == true) {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                velocityTracker.addPointerInputChange(change)
                                change.consume()
                                state.dispatchRawDelta(overSlop.y)
                            }
                        } ?: return@awaitEachGesture
                    state.isDragging = true
                    var completed = false
                    try {
                        completed =
                            verticalDrag(drag.id) { change ->
                                velocityTracker.addPointerInputChange(change)
                                state.dispatchRawDelta(change.positionChange().y)
                                change.consume()
                            }
                    } finally {
                        state.isDragging = false
                        if (!completed) state.resumeAnimation()
                    }
                    if (completed) {
                        val velocity = -velocityTracker.calculateVelocity().y
                        velocityTracker.resetTracking()
                        state.performFling(velocity, onDismiss)
                    } else {
                        velocityTracker.resetTracking()
                    }
                }
            }
        } else {
            Modifier
        }

    Box(
        modifier =
            modifier
                .graphicsLayer {
                    compositingStrategy = fadeStrategy
                    alpha =
                        if (morphCollapsedContainer) {
                            state.progress.coerceIn(0f, 1f)
                        } else {
                            (1.4f * (state.progress.coerceAtLeast(0.1f) - 0.1f).pow(0.5f)).coerceIn(0f, 1f)
                        }
                }.fillMaxSize(),
        content = background,
    )
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .graphicsLayer {
                    val y =
                        (state.expandedBound - state.value)
                            .toPx()
                            .coerceAtLeast(0f)
                    translationY = y
                    val bounds = container?.bounds
                    val style = container?.style
                    shape =
                        if (bounds != null && style != null) {
                            PlayerSheetClipShape(bounds, style, state.progress)
                        } else {
                            BottomSheetClipShape(
                                progress = state.progress,
                                cornerRadius =
                                    if (state.isSettledExpanded) {
                                        0f
                                    } else {
                                        16.dp.toPx() *
                                            uiEnhanced(1f, 1f - state.progress.coerceIn(0f, 1f))
                                    },
                                collapsedHeaderHeightPx = collapsedHeaderHeight.toPx(),
                            )
                        }
                    shadowElevation = if (style != null) style.elevation.toPx() * (1f - state.progress.coerceIn(0f, 1f)) else 0f
                    clip = true
                }.onGloballyPositioned { container?.coordinates = it }
                .drawWithContent {
                    val style = container?.style
                    val bounds = container?.bounds
                    val progress = state.progress.coerceIn(0f, 1f)
                    val outline =
                        if (bounds != null && style != null) {
                            PlayerSheetClipShape(bounds, style, progress).createOutline(size, layoutDirection, this)
                        } else {
                            BottomSheetClipShape(
                                progress,
                                if (state.isSettledExpanded) 0f else 16.dp.toPx() * uiEnhanced(1f, 1f - progress),
                                collapsedHeaderHeight.toPx(),
                            ).createOutline(size, layoutDirection, this)
                        }
                    if (morphCollapsedContainer) {
                        val color = if (style != null) lerp(style.color.copy(alpha = 1f), expandedColor, progress) else expandedColor
                        drawOutline(outline, color)
                    }
                    if (outline is Outline.Generic) {
                        // Bound retained player content to the Expressive path during sheet motion.
                        clipPath(outline.path) { this@drawWithContent.drawContent() }
                    } else {
                        drawContent()
                    }
                    if (style?.border != null && bounds != null && progress < 1f) {
                        drawOutline(outline, style.border.brush, alpha = 1f - progress, style = Stroke(style.border.width.toPx() * 2f))
                    }
                }.then(if (state.isCollapsed && !isDraggableWhenCollapsed) Modifier else dragGestureModifier),
    ) {
        if (!predictiveBack) {
            NavigationBackHandler(enabled = backHandlerEnabled && handlesBack, onBack = state::collapseSoft)
        }

        if (!state.isDismissed && (preserveContentWhenCollapsed || (isOpening && !state.isCollapsed))) {
            Box(
                modifier =
                    Modifier
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, placeable.height) {
                                if (!state.isCollapsed && !state.isDismissed && expandedAlpha(state.progress) > 0f) {
                                    placeable.placeRelative(0, 0)
                                }
                            }
                        }.fillMaxSize()
                        .focusProperties {
                            onEnter = { if (!state.isExpanded) cancelFocusChange() }
                            onExit = { if (state.isExpanded) cancelFocusChange() }
                        }.focusGroup()
                        .graphicsLayer {
                            compositingStrategy =
                                if (state.progress > 0f && state.progress < 1f) CompositingStrategy.Offscreen else fadeStrategy
                            alpha = expandedAlpha(state.progress).coerceIn(0f, 1f)
                            if (backProgress != null) {
                                applySheetBackTransform(backProgress.value, (state.expandedBound - state.value).toPx().coerceAtLeast(0f))
                            }
                        },
            ) {
                CompositionLocalProvider(
                    LocalSheetBackProgress provides backProgress,
                    LocalPlayerArtworkHandoff provides artworkHandoff,
                ) {
                    content()
                }
            }
        }

        if (!state.isDismissed) {
            val collapsedModifier =
                Modifier
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            if (!state.isExpanded && miniPlayerAlpha(state.progress) > 0f) {
                                placeable.placeRelative(0, 0)
                            } else if (morphCollapsedContainer) {
                                placeable.placeRelativeWithLayer(0, 0) {
                                    shape = HiddenSheetContentShape
                                    clip = true
                                }
                            }
                        }
                    }.focusProperties {
                        onEnter = { if (state.isExpanded || miniPlayerAlpha(state.progress) <= 0f) cancelFocusChange() }
                    }.focusGroup()
                    .semantics {
                        if (state.isExpanded || miniPlayerAlpha(state.progress) <= 0f) hideFromAccessibility()
                    }.graphicsLayer {
                        compositingStrategy = if (morphCollapsedContainer) fadeStrategy else CompositingStrategy.ModulateAlpha
                        alpha = miniPlayerAlpha(state.progress).coerceIn(0f, 1f)
                    }.then(
                        if (isCollapsedContentClickable) {
                            Modifier.focusProperties { canFocus = false }.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { if (isExpandable) state.expandSoft() },
                            )
                        } else {
                            Modifier
                        },
                    ).fillMaxWidth()
                    .height(collapsedHeaderHeight)

            Box(
                modifier = collapsedModifier,
            ) {
                CompositionLocalProvider(
                    LocalPlayerSheetContainer provides container,
                    LocalPlayerArtworkHandoff provides artworkHandoff,
                ) {
                    collapsedContent()
                }
            }
        }
    }
}

@Stable
class BottomSheetState(
    draggableState: DraggableState,
    private val coroutineScope: CoroutineScope,
    private val animatable: Animatable<Dp, AnimationVector1D>,
    private val onAnchorChanged: (Int) -> Unit,
    initialCollapsedBound: Dp,
    initialAnchor: Int = DISMISSED_ANCHOR,
    private val spatialSpec: AnimationSpec<Dp>,
    private val isDismissible: Boolean = true,
) : DraggableState by draggableState {
    var collapsedBound by mutableStateOf(initialCollapsedBound)
        private set
    var targetAnchor by mutableIntStateOf(initialAnchor)
        private set

    private fun changeAnchor(anchor: Int) {
        targetAnchor = anchor
        onAnchorChanged(anchor)
    }

    private var onDismissComplete: (() -> Unit)? = null
    internal var isDragging by mutableStateOf(false)

    val isSettledExpanded by derivedStateOf { value == expandedBound && !isDragging && !animatable.isRunning }

    internal val acceptsInput by derivedStateOf { bottomSheetAcceptsInput(targetAnchor, isSettledExpanded, isDragging) }

    private fun completeDismissal() {
        val callback = onDismissComplete
        onDismissComplete = null
        callback?.invoke()
    }

    internal suspend fun updateBounds(
        dismissed: Dp,
        expanded: Dp,
        collapsed: Dp,
        targetAnchor: Int,
    ) {
        val lowerBound = if (isDismissible) dismissed else collapsed
        this.targetAnchor = targetAnchor
        val target =
            when (targetAnchor) {
                EXPANDED_ANCHOR -> expanded
                COLLAPSED_ANCHOR -> collapsed
                DISMISSED_ANCHOR -> dismissed
                else -> error("Unknown BottomSheet anchor")
            }
        val wasAnimating = animatable.isRunning
        if (dismissedBound == lowerBound && expandedBound == expanded && collapsedBound == collapsed) {
            if (!isDragging && !wasAnimating && value != target) animatable.snapTo(target)
            return
        }
        val velocity = animatable.velocity
        val resizedValue =
            when {
                !isDragging && !wasAnimating -> {
                    target
                }

                isDismissed -> {
                    dismissed
                }

                isCollapsed -> {
                    collapsed
                }

                isExpanded -> {
                    expanded
                }

                value < collapsedBound -> {
                    dismissed + (collapsed - dismissed) *
                        ((value - dismissedBound) / (collapsedBound - dismissedBound)).coerceIn(0f, 1f)
                }

                else -> {
                    collapsed + (expanded - collapsed) * progress.coerceIn(0f, 1f)
                }
            }
        collapsedBound = collapsed
        dismissedBound = lowerBound.coerceAtMost(expanded)
        expandedBound = expanded
        animatable.updateBounds(dismissedBound, expanded)
        animatable.snapTo(resizedValue)
        if (wasAnimating) {
            coroutineScope.launch {
                animatable.animateTo(target, spatialSpec, initialVelocity = velocity)
                if (targetAnchor == DISMISSED_ANCHOR) completeDismissal()
            }
        }
    }

    // Animatable bounds are not snapshot state; mirror them so layers and progress follow resizes.
    var dismissedBound by mutableStateOf(animatable.lowerBound ?: 0.dp)
        private set

    var expandedBound by mutableStateOf(animatable.upperBound ?: initialCollapsedBound)
        private set

    val value by animatable.asState()

    val isDismissed by derivedStateOf {
        isDismissible && abs(value.value - dismissedBound.value) < 0.5f
    }

    val isCollapsed by derivedStateOf {
        abs(value.value - collapsedBound.value) < 0.5f
    }

    val isExpanded by derivedStateOf {
        abs(value.value - expandedBound.value) < 0.5f
    }

    val progress by derivedStateOf {
        if (expandedBound > collapsedBound) (value - collapsedBound) / (expandedBound - collapsedBound) else 0f
    }

    fun collapse(animationSpec: AnimationSpec<Dp>): Job {
        onDismissComplete = null
        changeAnchor(COLLAPSED_ANCHOR)
        return coroutineScope.launch {
            animatable.animateTo(collapsedBound, animationSpec)
        }
    }

    fun expand(animationSpec: AnimationSpec<Dp>) {
        onDismissComplete = null
        changeAnchor(EXPANDED_ANCHOR)
        coroutineScope.launch {
            animatable.upperBound?.let { animatable.animateTo(it, animationSpec) }
        }
    }

    private fun collapse() {
        collapse(spatialSpec)
    }

    private fun expand() {
        expand(spatialSpec)
    }

    fun collapseSoft() {
        collapse(spatialSpec)
    }

    fun expandSoft() {
        expand(spatialSpec)
    }

    fun dismiss(onComplete: (() -> Unit)? = null) {
        if (!isDismissible) {
            collapseSoft()
            return
        }
        onDismissComplete = onComplete
        changeAnchor(DISMISSED_ANCHOR)
        coroutineScope.launch {
            animatable.lowerBound?.let {
                animatable.animateTo(it, spatialSpec)
                completeDismissal()
            }
        }
    }

    internal fun resumeAnimation() {
        coroutineScope.launch {
            val target =
                when (targetAnchor) {
                    EXPANDED_ANCHOR -> expandedBound
                    COLLAPSED_ANCHOR -> collapsedBound
                    DISMISSED_ANCHOR -> dismissedBound
                    else -> error("Unknown BottomSheet anchor")
                }
            animatable.animateTo(target, spatialSpec)
            if (targetAnchor == DISMISSED_ANCHOR) completeDismissal()
        }
    }

    fun snapTo(value: Dp) {
        coroutineScope.launch {
            animatable.snapTo(value)
        }
    }

    fun performFling(
        velocity: Float,
        onDismiss: (() -> Unit)?,
    ) {
        if (velocity > 250) {
            expand()
        } else if (velocity < -250) {
            if (value < collapsedBound && onDismiss != null) {
                dismiss(onDismiss)
            } else {
                collapse()
            }
        } else {
            val dismissThreshold = dismissedBound + (collapsedBound - dismissedBound) / 2
            val expandThreshold = collapsedBound + (expandedBound - collapsedBound) / 2

            when {
                value < dismissThreshold -> {
                    if (onDismiss != null) {
                        dismiss(onDismiss)
                    } else {
                        collapse()
                    }
                }

                value < expandThreshold -> {
                    collapse()
                }

                else -> {
                    expand()
                }
            }
        }
    }

    val preUpPostDownNestedScrollConnection: NestedScrollConnection =
        object : NestedScrollConnection {
            var isTopReached = false

            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (isExpanded && available.y < 0) {
                    isTopReached = false
                }

                return if (!isExpanded && available.y < 0 && source == NestedScrollSource.UserInput) {
                    isDragging = true
                    dispatchRawDelta(available.y)
                    available
                } else if (isTopReached && available.y < 0 && source == NestedScrollSource.UserInput) {
                    isDragging = true
                    dispatchRawDelta(available.y)
                    available
                } else {
                    Offset.Zero
                }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    isTopReached = true
                }

                return if (isTopReached && available.y != 0f && source == NestedScrollSource.UserInput) {
                    isDragging = true
                    dispatchRawDelta(available.y)
                    available
                } else {
                    Offset.Zero
                }
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                isDragging = false
                return if (isTopReached || (!isExpanded && !isCollapsed)) {
                    val velocity = -available.y
                    performFling(velocity, null)

                    available
                } else {
                    Velocity.Zero
                }
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity {
                isDragging = false
                isTopReached = false
                // onPreFling already chose the anchor; settling again can reverse that animation.
                return Velocity.Zero
            }
        }
}

internal fun bottomSheetAcceptsInput(
    targetAnchor: Int,
    isSettledExpanded: Boolean,
    isDragging: Boolean,
): Boolean = targetAnchor == EXPANDED_ANCHOR && (isSettledExpanded || isDragging)

const val EXPANDED_ANCHOR = 2
const val COLLAPSED_ANCHOR = 1
const val DISMISSED_ANCHOR = 0

@Composable
fun rememberBottomSheetState(
    dismissedBound: Dp,
    expandedBound: Dp,
    collapsedBound: Dp = dismissedBound,
    initialAnchor: Int = DISMISSED_ANCHOR,
    isDismissible: Boolean = true,
): BottomSheetState {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    var previousAnchor by rememberSaveable {
        mutableIntStateOf(initialAnchor)
    }
    val animatable =
        remember(isDismissible) {
            val initialValue =
                when (previousAnchor) {
                    EXPANDED_ANCHOR -> expandedBound
                    COLLAPSED_ANCHOR -> collapsedBound
                    DISMISSED_ANCHOR -> dismissedBound
                    else -> error("Unknown BottomSheet anchor")
                }
            Animatable(initialValue, Dp.VectorConverter).apply {
                updateBounds((if (isDismissible) dismissedBound else collapsedBound).coerceAtMost(expandedBound), expandedBound)
            }
        }
    val currentDensity by rememberUpdatedState(density)
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Dp>()
    val state =
        remember(coroutineScope, spatialSpec, isDismissible) {
            BottomSheetState(
                draggableState =
                    DraggableState { delta ->
                        coroutineScope.launch {
                            animatable.snapTo(animatable.value - with(currentDensity) { delta.toDp() })
                        }
                    },
                onAnchorChanged = { previousAnchor = it },
                coroutineScope = coroutineScope,
                animatable = animatable,
                initialCollapsedBound = collapsedBound,
                initialAnchor = previousAnchor,
                spatialSpec = spatialSpec,
                isDismissible = isDismissible,
            )
        }
    LaunchedEffect(state, dismissedBound, expandedBound, collapsedBound) {
        state.updateBounds(dismissedBound, expandedBound, collapsedBound, previousAnchor)
    }
    return state
}
