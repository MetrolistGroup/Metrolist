package com.metrolist.shared.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.MarqueeDefaults
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainWidth
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

// Round up the 30 Hz period so no one-second window gets more than 30 steps.
private val marqueeFrameInterval = 34.milliseconds

internal fun marqueeStepOffset(
    elapsedMillis: Long,
    distance: Int,
    durationMillis: Int,
): Int =
    (elapsedMillis.coerceAtLeast(0L).toDouble() / durationMillis.coerceAtLeast(1) * distance)
        .coerceIn(0.0, distance.toDouble())
        .toInt()

internal fun marqueeDrawOffsets(
    offset: Int,
    contentWidth: Int,
    containerWidth: Int,
    distance: Int,
    rtl: Boolean,
    direction: Int = 1,
): Pair<Int, Int> {
    val movement = if (rtl) direction else -direction
    val start = (if (rtl) containerWidth - contentWidth else 0) + movement * offset
    return start to start - movement * distance
}

internal actual suspend fun Animatable<Float, AnimationVector1D>.animateMarquee(
    distance: Int,
    durationMillis: Int,
) {
    stepMarquee(distance, durationMillis) { snapTo(it.toFloat()) }
}

private suspend fun stepMarquee(
    distance: Int,
    durationMillis: Int,
    onOffset: suspend (Int) -> Unit,
) {
    val start = playbackAnimationOrigin.elapsedNow().inWholeMilliseconds
    do {
        // A plain delay: waiting for a frame would itself repaint the window before each step.
        delay(
            playbackAnimationDelayMillis(
                playbackAnimationOrigin.elapsedNow().inWholeMilliseconds,
                marqueeFrameInterval.inWholeMilliseconds,
            ),
        )
        val elapsed = playbackAnimationOrigin.elapsedNow().inWholeMilliseconds - start
        onOffset(marqueeStepOffset(elapsed, distance, durationMillis))
    } while (elapsed < durationMillis)
}

actual fun Modifier.playbackMarquee(
    iterations: Int,
    initialDelayMillis: Int,
    velocity: Dp,
): Modifier =
    composed {
        val visible by LocalVisualWorkActive.current.collectAsState()
        val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
        this.then(MarqueeElement(iterations, initialDelayMillis, velocity, visible && lifecycle.isAtLeast(Lifecycle.State.STARTED)))
    }

private data class MarqueeElement(
    val iterations: Int,
    val initialDelayMillis: Int,
    val velocity: Dp,
    val active: Boolean,
) : ModifierNodeElement<MarqueeNode>() {
    override fun create() = MarqueeNode(this)

    override fun update(node: MarqueeNode) {
        node.parameters = this
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "playbackMarquee"
    }
}

private class MarqueeNode(
    parameters: MarqueeElement,
) : Modifier.Node(),
    LayoutModifierNode,
    DrawModifierNode {
    var parameters by mutableStateOf(parameters)
    private var contentWidth by mutableIntStateOf(0)
    private var containerWidth by mutableIntStateOf(0)
    private var pixelsPerSecond by mutableStateOf(0f)
    private var scrolling = false
    private var offset by mutableIntStateOf(0)
    private var layer: GraphicsLayer? = null

    override fun onAttach() {
        layer = requireGraphicsContext().createGraphicsLayer()
        coroutineScope.launch {
            snapshotFlow { listOf(parameters, contentWidth, containerWidth, pixelsPerSecond) }.collectLatest {
                val config = parameters
                val distance = contentWidth + (containerWidth / 3f).roundToInt()
                if (!config.active || config.iterations <= 0 || contentWidth <= containerWidth ||
                    pixelsPerSecond == 0f
                ) {
                    return@collectLatest
                }
                val duration = ceil(distance / abs(pixelsPerSecond) * 1000.0).toInt().coerceAtLeast(1)
                try {
                    repeat(config.iterations) { iteration ->
                        delay(if (iteration == 0) config.initialDelayMillis.toLong() else MarqueeDefaults.RepeatDelayMillis.toLong())
                        scrolling = true
                        invalidateMeasurement()
                        stepMarquee(distance, duration) { offset = it }
                        offset = 0
                        scrolling = false
                        invalidateMeasurement()
                    }
                } finally {
                    offset = 0
                    scrolling = false
                    if (isAttached) invalidateMeasurement()
                }
            }
        }
    }

    override fun onDetach() {
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ) = run {
        contentWidth = measurable.maxIntrinsicWidth(constraints.maxHeight)
        containerWidth = constraints.constrainWidth(contentWidth)
        pixelsPerSecond = parameters.velocity.toPx()
        val child = measurable.measure(if (scrolling) constraints.copy(maxWidth = Constraints.Infinity) else constraints)
        layout(containerWidth, child.height) { child.placeWithLayer(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(
        measurable: IntrinsicMeasurable,
        height: Int,
    ) = 0

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
        measurable: IntrinsicMeasurable,
        height: Int,
    ) = measurable.maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurable: IntrinsicMeasurable,
        width: Int,
    ) = measurable.minIntrinsicHeight(Constraints.Infinity)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurable: IntrinsicMeasurable,
        width: Int,
    ) = measurable.maxIntrinsicHeight(Constraints.Infinity)

    override fun ContentDrawScope.draw() {
        if (!scrolling) {
            drawContent()
            return
        }
        val distance = contentWidth + (containerWidth / 3f).roundToInt()
        val (first, second) =
            marqueeDrawOffsets(
                offset,
                contentWidth,
                containerWidth,
                distance,
                rtl = layoutDirection == LayoutDirection.Rtl,
                direction = if (pixelsPerSecond < 0f) -1 else 1,
            )
        // Record one copy so drawing the repeat adds no accessibility or input targets.
        val content = layer ?: return
        content.record(size = IntSize(contentWidth, size.height.roundToInt())) { this@draw.drawContent() }
        clipRect(right = containerWidth.toFloat()) {
            translate(left = first.toFloat()) { drawLayer(content) }
            translate(left = second.toFloat()) { drawLayer(content) }
        }
    }
}
