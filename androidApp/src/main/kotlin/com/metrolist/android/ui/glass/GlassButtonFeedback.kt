package com.metrolist.android.ui.glass

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.util.lerp
import com.metrolist.android.ui.player.LocalMiniPlayerFeedbackOverflow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

private class GlassButtonPressState {
    var press: PressInteraction.Press? = null
    var pointerHeld = false
}

/** Native clickable owns actions; retained controls keep their visual press until physical up. */
@Composable
fun Modifier.glassButtonFeedback(
    interactionSource: InteractionSource,
    shape: Shape,
    enabled: Boolean = true,
    visualOutset: Dp = 0.dp,
    clipToBounds: Boolean = false,
    drawMotionOnly: Boolean = false,
    retainPressUntilUp: Boolean = false,
    retainAfterLongPress: Boolean = false,
    animateMotion: Boolean = true,
    drawHighlight: Boolean = true,
    settings: GlassSettings = glassInteractionSettings(),
    clipContent: Boolean = true,
): Modifier {
    if (!enabled) return this
    val policy = settings.interactionPolicy()
    val motionEnabled = animateMotion && policy.jellyIntensity > 0f
    val context = LocalContext.current
    val outset = with(LocalDensity.current) { visualOutset.toPx() }
    val positionOffset = Offset(outset, outset)
    val scope = rememberCoroutineScope()
    val highlight = remember(scope, interactionSource, outset) { InteractiveHighlight(scope) }
    val overflow = LocalMiniPlayerFeedbackOverflow.current
    var feedbackCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val density = LocalDensity.current
    DisposableEffect(overflow, highlight, motionEnabled, drawMotionOnly, outset, policy.jellyIntensity, context) {
        overflow?.register(highlight) { target ->
            val coordinates = feedbackCoordinates
            if (coordinates == null || !coordinates.isAttached || !motionEnabled ||
                !isGlassMotionAllowed(context) ||
                (highlight.pressProgress == 0f && highlight.offset == Offset.Zero)) null
            else {
                // Reading the spring here invalidates only the ancestor's clipping layer.
                val surface = Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
                val motion = glassButtonMotion(Size(surface.width + 2 * outset, surface.height + 2 * outset),
                    with(density) { 4.dp.toPx() }, highlight, policy.jellyIntensity)
                if (outset == 0f && !drawMotionOnly) {
                    // Coordinates already include this control's animated graphics layer.
                    target.localBoundingBoxOf(coordinates, clipBounds = false)
                } else {
                    val center = Offset(surface.width / 2f, surface.height / 2f)
                    fun point(x: Float, y: Float) = target.localPositionOf(coordinates, Offset(
                        center.x + (x - center.x) * motion.scaleX + motion.x,
                        center.y + (y - center.y) * motion.scaleY + motion.y,
                    ))
                    val points = listOf(point(-outset, -outset), point(surface.width + outset, -outset),
                        point(-outset, surface.height + outset), point(surface.width + outset, surface.height + outset))
                    Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
                }
            }
        }
        onDispose { overflow?.unregister(highlight) }
    }
    val state = remember(highlight) { GlassButtonPressState() }
    val stateLayer = remember(highlight) { GlassInteractionStateLayer() }
    val elevation = LocalGlassGroupElevation.current
    val active by remember(highlight, motionEnabled, context) { derivedStateOf {
        motionEnabled && isGlassMotionAllowed(context) &&
            (highlight.pressProgress != 0f || highlight.offset != Offset.Zero)
    } }
    DisposableEffect(elevation, highlight, active) {
        if (active) elevation?.acquire(highlight)
        onDispose { elevation?.release(highlight) }
    }
    LaunchedEffect(highlight, interactionSource) {
        interactionSource.interactions.collect { event ->
            stateLayer.update(event)
            when (event) {
                is PressInteraction.Press -> {
                    state.press = event
                    if (!state.pointerHeld) highlight.startPress(event.pressPosition + positionOffset)
                }
                is PressInteraction.Release -> if (state.press == event.press) {
                    state.press = null
                    if (!state.pointerHeld) highlight.releasePress()
                }
                is PressInteraction.Cancel -> if (state.press == event.press) {
                    state.press = null
                    if (!state.pointerHeld) highlight.releasePress()
                }
            }
        }
    }
    // Group cells share one stationary pane. Clip outside the animated layer so
    // its spring expansion cannot paint over the adjacent cell or outside that pane.
    return (if (clipToBounds) clip(shape) else this)
        .zIndex(if (active) 3f else 0f)
        .graphicsLayer { if (motionEnabled && outset == 0f && !drawMotionOnly && isGlassMotionAllowed(context)) glassButtonLayer(highlight, policy.jellyIntensity) }
        // This marker must be inside the layer so its coordinates include its spring transform.
        .then(if (overflow != null) Modifier.onGloballyPositioned { feedbackCoordinates = it } else Modifier)
        .drawWithContent {
            // A visual overdraw pane may be larger than the unchanged native hit slot.
            val motion = glassButtonMotion(Size(size.width + 2 * outset, size.height + 2 * outset), 4.dp.toPx(), highlight, policy.jellyIntensity)
            withTransform({
                // Overdraw controls and native chips transform drawing only: hit bounds stay fixed.
                if (motionEnabled && (outset > 0f || drawMotionOnly) && isGlassMotionAllowed(context)) {
                    translate(motion.x, motion.y)
                    scale(motion.scaleX, motion.scaleY, center)
                }
            }) {
            inset(-outset, -outset, -outset, -outset) {
                val outline = shape.createOutline(size, layoutDirection, this)
                val path = when (outline) {
                    is Outline.Generic -> outline.path
                    is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
                    is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
                }
                if (!clipContent) translate(outset, outset) { this@drawWithContent.drawContent() }
                clipPath(path) {
                    if (clipContent) translate(outset, outset) { this@drawWithContent.drawContent() }
                    if (drawHighlight) {
                        stateLayer.draw(this, policy.rippleIntensity)
                        highlight.drawHighlight(this, policy.rippleIntensity, policy.rippleSize)
                    }
                }
            }
            }
        }
        .pointerInput(highlight, retainPressUntilUp, retainAfterLongPress, motionEnabled) {
            if (retainAfterLongPress && motionEnabled && isGlassMotionAllowed(context)) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    state.pointerHeld = true
                    highlight.startPress(down.position + positionOffset)
                    highlight.movePress(held.position + positionOffset)
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val primary = event.changes.firstOrNull { it.id == down.id }
                            if (primary == null || !primary.pressed) break
                            highlight.movePress(primary.position + positionOffset)
                            // Claim only movement after a stationary long hold; immediate swipes stay native.
                            if (primary.position != primary.previousPosition) primary.consume()
                        }
                    } finally {
                        state.pointerHeld = false
                        state.press = null
                        highlight.releasePress()
                    }
                }
            } else if (retainPressUntilUp) {
                // Retention consumes motion to protect fixed controls from parent scrolling.
                // That cancels clickable's action, but the finger still owns the visual hold.
                // Never consume here: clickable remains the only action recognizer.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    state.pointerHeld = true
                    highlight.startPress(down.position + positionOffset)
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val primary = event.changes.firstOrNull { it.id == down.id }
                            if (primary == null || !primary.pressed) break
                            highlight.movePress(primary.position + positionOffset)
                        } while (true)
                    } finally {
                        state.pointerHeld = false
                        state.press = null
                        highlight.releasePress()
                    }
                }
            } else {
                // Scrollable chips/list items follow the native scroll cancellation.
                inspectDragGestures(observeConsumedMovement = true) { change, _ ->
                    if (state.press != null) highlight.movePress(change.position + positionOffset)
                }
            }
        }
}

internal data class GlassButtonMotion(val x: Float, val y: Float, val scaleX: Float, val scaleY: Float)

/** Shared LiquidButton physics, also used by grouped and native player controls. */
internal fun glassButtonMotion(surfaceSize: Size, expansion: Float, highlight: InteractiveHighlight, intensity: Float = 1f): GlassButtonMotion {
    if (surfaceSize.minDimension <= 0f) return GlassButtonMotion(0f, 0f, 1f, 1f)
    val strength = clampGlassValue(glassJellyIntensityKey, intensity)
    val width = surfaceSize.width
    val height = surfaceSize.height
    val scale = lerp(1f, 1f + expansion / height, highlight.pressProgress)
    val offset = highlight.offset
    val maxOffset = surfaceSize.minDimension
    val maxDragScale = expansion / height
    val angle = atan2(offset.y, offset.x)
    val original = GlassButtonMotion(
        maxOffset * tanh(.05f * offset.x / maxOffset),
        maxOffset * tanh(.05f * offset.y / maxOffset),
        scale + maxDragScale * abs(cos(angle) * offset.x / surfaceSize.maxDimension) * (width / height).coerceAtMost(1f),
        scale + maxDragScale * abs(sin(angle) * offset.y / surfaceSize.maxDimension) * (height / width).coerceAtMost(1f),
    )
    if (strength == 1f) return original
    return GlassButtonMotion(original.x * strength, original.y * strength,
        1f + (original.scaleX - 1f) * strength, 1f + (original.scaleY - 1f) * strength)
}

internal fun GraphicsLayerScope.glassButtonLayer(highlight: InteractiveHighlight, intensity: Float = 1f) {
    val motion = glassButtonMotion(size, 4.dp.toPx(), highlight, intensity)
    translationX = motion.x
    translationY = motion.y
    scaleX = motion.scaleX
    scaleY = motion.scaleY
}
