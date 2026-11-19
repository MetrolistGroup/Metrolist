/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 */
package com.metrolist.android.ui.glass.backdrop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.layer.setOutline
import androidx.compose.ui.graphics.layer.CompositingStrategy as LayerCompositingStrategy
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.unit.toSize
import com.metrolist.android.ui.glass.backdrop.backdrops.LayerBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.opticalRecordingCoordinates
import com.metrolist.android.ui.glass.backdrop.highlight.Highlight
import com.metrolist.android.ui.glass.backdrop.highlight.HighlightElement
import com.metrolist.android.ui.glass.backdrop.internal.ShapeProvider
import com.metrolist.android.ui.glass.backdrop.internal.MaterialLayers
import com.metrolist.android.ui.glass.backdrop.internal.recordLayer
import com.metrolist.android.ui.glass.backdrop.shadow.InnerShadow
import com.metrolist.android.ui.glass.backdrop.shadow.InnerShadowElement
import com.metrolist.android.ui.glass.backdrop.shadow.Shadow
import com.metrolist.android.ui.glass.backdrop.shadow.ShadowElement

private val DefaultHighlight = { Highlight.Default }
private val DefaultShadow = { Shadow.Default }
private val DefaultOnDrawBackdrop: DrawScope.(DrawScope.() -> Unit) -> Unit = { it() }
/**
 * How long the fresh capture takes to replace the held one.
 *
 * Long enough to read as the reflection settling rather than as a cut, short
 * enough that it is over before anyone looks for it. Two layers are alive for
 * this window and one of them is released at the end of it.
 */
private const val HandoverMillis = 180f

/**
 * How stale a held capture is allowed to get before it is taken again.
 *
 * Three or four recordings across a fold rather than one per frame, and a
 * reflection that is never more than this far behind — which is close enough
 * that the moment it catches up is not an event.
 */
private const val HoldRefreshMillis = 110f

private val NeverFrozen: () -> Boolean = { false }

/** Defensive cap on a loop-bucket pool's size — see [DrawBackdropNode.drawBucketedLayer]. */
private const val LoopPoolMaxSize = 64

fun Modifier.drawPlainBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = DefaultOnDrawBackdrop,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null,
    // Vendored addition: same meaning as [drawBackdrop]'s parameter of the same
    // name — record the backdrop at this fraction of the surface resolution so
    // the RenderEffect chain processes far fewer pixels. Callers must
    // pre-multiply pixel-sized effect parameters (blur radius) by the same
    // factor; the blur hides the upscaling. 1f keeps full resolution.
    backdropScale: Float = 1f,
    // While this returns true the surface reuses its last recorded backdrop
    // instead of re-capturing the source. Meant for the frames of a transition
    // that animates the surface's size or position: those change every frame, so
    // the size/offset checks below force a fresh full-screen capture (plus the
    // whole effect chain) on every one of them. Measured on the search
    // transition: 9 frames at a 250-450ms median with the nav bar's glass on,
    // versus 77 frames at 42ms with it off. The bar is moving and heavily
    // blurred for those ~300ms, so reusing the previous capture is not visible.
    frozen: () -> Boolean = NeverFrozen,
    heldWhileMoving: () -> Boolean = NeverFrozen,
    opticsAlpha: Float = 1f,
    opticsAlphaProvider: (() -> Float)? = null,
    // Passive exported tint surfaces have no effects and retain native clip compositing.
    compositingStrategy: CompositingStrategy = CompositingStrategy.Offscreen,
    liveFrame: (() -> Long)? = null,
): Modifier {
    val shapeProvider = ShapeProvider(shape)
    return this
        .then(
            if (layerBlock != null) {
                Modifier.graphicsLayer(layerBlock)
            } else {
                Modifier
            }
        )
        .graphicsLayer {
            clip = true
            this.shape = shapeProvider.shape
            this.compositingStrategy = compositingStrategy
        }
        .then(
            DrawBackdropElement(
                backdrop = backdrop,
                shapeProvider = shapeProvider,
                effects = effects,
                layerBlock = layerBlock,
                exportedBackdrop = exportedBackdrop,
                compositingStrategy = compositingStrategy,
                onDrawBehind = onDrawBehind,
                onDrawBackdrop = onDrawBackdrop,
                onDrawSurface = onDrawSurface,
                onDrawFront = onDrawFront,
                opticsAlpha = opticsAlpha,
                opticsAlphaProvider = opticsAlphaProvider,
                backdropScale = backdropScale.coerceIn(0.05f, 1f),
                frozen = frozen,
                heldWhileMoving = heldWhileMoving,
                liveFrame = liveFrame,
                loopBucket = null
            )
        )
}

fun Modifier.drawBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    highlight: (() -> Highlight?)? = DefaultHighlight,
    shadow: (() -> Shadow?)? = DefaultShadow,
    innerShadow: (() -> InnerShadow?)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = DefaultOnDrawBackdrop,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null,
    // Vendored addition: records the backdrop into a layer at this fraction of the
    // surface resolution and draws it scaled back up, so the RenderEffect chain
    // processes far fewer pixels. Pixel-sized effect parameters (blur radius, lens
    // refraction) must be pre-multiplied by the same factor by the caller; the blur
    // hides the upscaling. 1f keeps the original full resolution behavior.
    backdropScale: Float = 1f,
    // While this returns true the surface reuses its last recorded backdrop
    // instead of re-capturing the source. Meant for the frames of a transition
    // that animates the surface's size or position: those change every frame, so
    // the size/offset checks below force a fresh full-screen capture (plus the
    // whole effect chain) on every one of them. Measured on the search
    // transition: 9 frames at a 250-450ms median with the nav bar's glass on,
    // versus 77 frames at 42ms with it off. The bar is moving and heavily
    // blurred for those ~300ms, so reusing the previous capture is not visible.
    frozen: () -> Boolean = NeverFrozen,
    /** See the use site: hold the capture even while this surface moves. */
    heldWhileMoving: () -> Boolean = NeverFrozen,
    // When set, this surface caches one recorded+effect-processed layer PER
    // bucket returned here instead of a single reused layer, and replays a
    // bucket's cached layer on repeat instead of re-recording. Meant for a
    // backdrop whose source loops (e.g. a looping canvas video): the visual
    // output at a given position in the loop is identical every repeat, so the
    // whole capture + blur/lens chain only needs to run once per bucket, ever,
    // instead of on every frame of every loop. Null (default) keeps the single-
    // layer behavior unchanged.
    loopBucket: (() -> Int)? = null,
    // Composite after RenderEffect processing; does not invalidate source captures.
    opticsAlpha: Float = 1f,
    opticsAlphaProvider: (() -> Float)? = null,
    liveFrame: (() -> Long)? = null,
): Modifier {
    val shapeProvider = ShapeProvider(shape, if (exportedBackdrop != null) MaterialLayers() else null)
    return this
        .then(
            if (layerBlock != null) {
                Modifier.graphicsLayer(layerBlock)
            } else {
                Modifier
            }
        )
        .graphicsLayer {
            clip = true
            this.shape = shapeProvider.shape
            compositingStrategy = CompositingStrategy.Offscreen
        }
        .then(
            if (innerShadow != null) {
                InnerShadowElement(
                    shapeProvider = shapeProvider,
                    shadow = innerShadow
                )
            } else {
                Modifier
            }
        )
        .then(
            if (shadow != null) {
                ShadowElement(
                    shapeProvider = shapeProvider,
                    shadow = shadow
                )
            } else {
                Modifier
            }
        )
        .then(
            if (highlight != null) {
                HighlightElement(
                    shapeProvider = shapeProvider,
                    highlight = highlight
                )
            } else {
                Modifier
            }
        )
        .then(
            DrawBackdropElement(
                backdrop = backdrop,
                shapeProvider = shapeProvider,
                effects = effects,
                layerBlock = layerBlock,
                exportedBackdrop = exportedBackdrop,
                compositingStrategy = CompositingStrategy.Offscreen,
                onDrawBehind = onDrawBehind,
                onDrawBackdrop = onDrawBackdrop,
                onDrawSurface = onDrawSurface,
                onDrawFront = onDrawFront,
                opticsAlpha = opticsAlpha,
                opticsAlphaProvider = opticsAlphaProvider,
                backdropScale = backdropScale.coerceIn(0.05f, 1f),
                frozen = frozen,
                heldWhileMoving = heldWhileMoving,
                liveFrame = liveFrame,
                loopBucket = loopBucket
            )
        )
}

private class DrawBackdropElement(
    val backdrop: Backdrop,
    val shapeProvider: ShapeProvider,
    val effects: BackdropEffectScope.() -> Unit,
    val layerBlock: (GraphicsLayerScope.() -> Unit)?,
    val exportedBackdrop: LayerBackdrop?,
    val compositingStrategy: CompositingStrategy,
    val onDrawBehind: (DrawScope.() -> Unit)?,
    val onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit,
    val onDrawSurface: (DrawScope.() -> Unit)?,
    val onDrawFront: (DrawScope.() -> Unit)?,
    val opticsAlpha: Float,
    val opticsAlphaProvider: (() -> Float)?,
    val backdropScale: Float,
    val frozen: () -> Boolean,
    val heldWhileMoving: () -> Boolean,
    val liveFrame: (() -> Long)?,
    val loopBucket: (() -> Int)?
) : ModifierNodeElement<DrawBackdropNode>() {

    override fun create(): DrawBackdropNode {
        return DrawBackdropNode(
            backdrop = backdrop,
            shapeProvider = shapeProvider,
            effects = effects,
            layerBlock = layerBlock,
            exportedBackdrop = exportedBackdrop,
            compositingStrategy = compositingStrategy,
            onDrawBehind = onDrawBehind,
            onDrawBackdrop = onDrawBackdrop,
            onDrawSurface = onDrawSurface,
            onDrawFront = onDrawFront,
            opticsAlpha = opticsAlpha,
            opticsAlphaProvider = opticsAlphaProvider,
            backdropScale = backdropScale,
            frozen = frozen,
            heldWhileMoving = heldWhileMoving,
            liveFrame = liveFrame,
            loopBucket = loopBucket
        )
    }

    override fun update(node: DrawBackdropNode) {
        // Only the parameters that feed the CAPTURE invalidate it. onDrawBehind/
        // Surface/Front (and with them the highlight rim, shadow and tint) are
        // painted over an already-captured layer, so a change there needs a
        // repaint, not a re-record. Conflating the two meant any recomposition
        // reaching a glass call site — every one of which allocates fresh
        // lambdas — re-sampled the screen and re-ran saturation/blur/lens.
        val captureChanged =
            node.backdrop !== backdrop ||
                // The wrapper is rebuilt with the modifier; only its shape input
                // changes the capture. Keep dynamic shape reads in the draw phase.
                node.shapeProvider.shapeBlock !== shapeProvider.shapeBlock ||
                node.effects !== effects ||
                node.layerBlock !== layerBlock ||
                node.onDrawBackdrop !== onDrawBackdrop ||
                node.backdropScale != backdropScale ||
                node.liveFrame !== liveFrame

        val exportedMaterialChanged = captureChanged ||
            node.exportedBackdrop !== exportedBackdrop ||
            node.compositingStrategy != compositingStrategy ||
            node.onDrawBehind !== onDrawBehind ||
            node.onDrawSurface !== onDrawSurface ||
            node.onDrawFront !== onDrawFront ||
            node.opticsAlpha != opticsAlpha.coerceIn(0f, 1f) ||
            node.opticsAlphaProvider !== opticsAlphaProvider

        node.backdrop = backdrop
        node.shapeProvider = shapeProvider
        node.effects = effects
        node.compositingStrategy = compositingStrategy
        node.layerBlock = layerBlock
        if (node.exportedBackdrop != exportedBackdrop) {
            node.exportedBackdrop?.layerCoordinates = null
            node.exportedBackdrop = exportedBackdrop
        }
        node.onDrawBehind = onDrawBehind
        node.onDrawBackdrop = onDrawBackdrop
        node.onDrawSurface = onDrawSurface
        node.onDrawFront = onDrawFront
        node.opticsAlpha = opticsAlpha.coerceIn(0f, 1f)
        node.opticsAlphaProvider = opticsAlphaProvider
        node.backdropScale = backdropScale
        node.liveFrame = liveFrame
        node.frozen = frozen
        node.heldWhileMoving = heldWhileMoving
        // Queue one consumer notification after the changed export is ready. Ordinary
        // contentRecorded calls do not publish revisions, avoiding draw feedback loops.
        if (exportedMaterialChanged) exportedBackdrop?.materialChanged()
        if (node.loopBucket !== loopBucket) {
            // A different bucket function (or losing/gaining one) invalidates
            // whatever's pooled — the old buckets no longer mean anything under
            // the new function's numbering, or looping stopped/started. Force
            // surfaceDirty too: switching TO null must not let the single-layer
            // path's stale recordedBackdropVersion/size fields (last touched
            // before bucketing started, or never) accidentally read as "still
            // valid" and skip drawing into the never-yet-recorded graphicsLayer.
            node.loopBucket = loopBucket
            node.surfaceDirty = true
        }
        if (captureChanged) {
            node.surfaceDirty = true
            node.clearLoopPool()
        }
        // Always: the overlay repaints even when the capture is reused.
        if (captureChanged) node.invalidateDrawCache() else node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "drawBackdrop"
        properties["backdrop"] = backdrop
        properties["shapeProvider"] = shapeProvider
        properties["effects"] = effects
        properties["layerBlock"] = layerBlock
        properties["exportedBackdrop"] = exportedBackdrop
        properties["onDrawBehind"] = onDrawBehind
        properties["onDrawBackdrop"] = onDrawBackdrop
        properties["onDrawSurface"] = onDrawSurface
        properties["onDrawFront"] = onDrawFront
        properties["opticsAlpha"] = opticsAlpha
        properties["opticsAlphaProvider"] = opticsAlphaProvider
        properties["liveFrame"] = liveFrame
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DrawBackdropElement) return false

        if (liveFrame != other.liveFrame) return false
        if (backdrop != other.backdrop) return false
        if (shapeProvider != other.shapeProvider) return false
        if (effects != other.effects) return false
        if (layerBlock != other.layerBlock) return false
        if (exportedBackdrop != other.exportedBackdrop) return false
        if (compositingStrategy != other.compositingStrategy) return false
        if (onDrawBehind != other.onDrawBehind) return false
        if (onDrawBackdrop != other.onDrawBackdrop) return false
        if (onDrawSurface != other.onDrawSurface) return false
        if (onDrawFront != other.onDrawFront) return false
        if (backdropScale != other.backdropScale) return false
        if (opticsAlpha != other.opticsAlpha) return false
        if (opticsAlphaProvider != other.opticsAlphaProvider) return false

        return true
    }

    override fun hashCode(): Int {
        var result = backdrop.hashCode()
        result = 31 * result + (liveFrame?.hashCode() ?: 0)
        result = 31 * result + shapeProvider.hashCode()
        result = 31 * result + effects.hashCode()
        result = 31 * result + (layerBlock?.hashCode() ?: 0)
        result = 31 * result + (exportedBackdrop?.hashCode() ?: 0)
        result = 31 * result + compositingStrategy.hashCode()
        result = 31 * result + (onDrawBehind?.hashCode() ?: 0)
        result = 31 * result + onDrawBackdrop.hashCode()
        result = 31 * result + (onDrawSurface?.hashCode() ?: 0)
        result = 31 * result + (onDrawFront?.hashCode() ?: 0)
        result = 31 * result + backdropScale.hashCode()
        result = 31 * result + opticsAlpha.hashCode()
        result = 31 * result + (opticsAlphaProvider?.hashCode() ?: 0)
        return result
    }
}

private class DrawBackdropNode(
    var backdrop: Backdrop,
    var shapeProvider: ShapeProvider,
    var effects: BackdropEffectScope.() -> Unit,
    var layerBlock: (GraphicsLayerScope.() -> Unit)?,
    var exportedBackdrop: LayerBackdrop?,
    var compositingStrategy: CompositingStrategy,
    var onDrawBehind: (DrawScope.() -> Unit)?,
    var onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit,
    var onDrawSurface: (DrawScope.() -> Unit)?,
    var onDrawFront: (DrawScope.() -> Unit)?,
    var opticsAlpha: Float,
    var opticsAlphaProvider: (() -> Float)?,
    var backdropScale: Float,
    var frozen: () -> Boolean,
    var heldWhileMoving: () -> Boolean,
    var liveFrame: (() -> Long)?,
    var loopBucket: (() -> Int)?
) : DrawModifierNode, GlobalPositionAwareModifierNode, ObserverModifierNode, DelegatingNode() {

    private val materialObserver = delegate(MaterialObserverNode {
        exportedBackdrop?.materialChanged()
        invalidateDraw()
    })
    private var exportedOutline: Outline? = null
    private var exportedHighlight: Any? = null
    private var exportedShadow: Any? = null
    private var exportedInnerShadow: Any? = null

    private val effectScope =
        object : BackdropEffectScopeImpl() {

            override val shape: Shape get() = shapeProvider.innerShape
        }

    private var graphicsLayer: GraphicsLayer? = null

    // Only allocated when loopBucket is non-null. One recorded+effect-processed
    // layer per bucket, reused forever once present — see drawBucketedLayer.
    // Capped defensively; an evicted bucket simply re-records next visit.
    private var loopPool: HashMap<Int, GraphicsLayer>? = null

    internal fun clearLoopPool() {
        val pool = loopPool ?: return
        val graphicsContext = requireGraphicsContext()
        pool.values.forEach { graphicsContext.releaseGraphicsLayer(it) }
        pool.clear()
    }

    private var layoutCoordinates: LayoutCoordinates? by mutableStateOf(null, neverEqualPolicy())

    private var padding by mutableFloatStateOf(0f)

    // True whenever the surface's own state changed in a way that invalidates the
    // previously recorded backdrop layer (element update, effect retune, first
    // attach). When false, draw() reuses the last record unless the source content
    // version, size or offset moved — so surface-local redraws (e.g. a glass rim
    // highlight animation) no longer re-record the whole backdrop source per frame.
    internal var surfaceDirty = true

    private var recordedBackdropVersion: Int? = null
    private var recordedBackdropOffset: Offset? = null
    private var recordedBackdropSize: IntSize = IntSize.Zero

    private fun currentBackdropVersion(): Int? = backdrop.contentVersion

    /** True if [rect] falls entirely inside a region [backdrop] declared static
     *  this generation — see [LayerBackdrop.isFullyStatic]. */
    private fun isFullyStatic(rect: Rect): Boolean =
        (backdrop as? LayerBackdrop)?.isFullyStatic(rect) == true

    /** Position of this surface within the backdrop source's coordinate space. */
    private fun currentBackdropOffset(): Offset? {
        val selfCoordinates = layoutCoordinates?.takeIf { it.isAttached } ?: return null
        // Combined sources have no single producer origin. Their capture still depends
        // on the consumer's window position, even when every source revision is stable.
        val source = backdrop as? LayerBackdrop
            ?: return if (backdrop.isCoordinatesDependent) selfCoordinates.localToWindow(Offset.Zero) else null
        val sourceCoordinates = source.layerCoordinates ?: return null
        return try {
            sourceCoordinates.localPositionOf(selfCoordinates)
        } catch (_: Exception) {
            null
        }
    }

    private val recordBackdropBlock: (DrawScope.() -> Unit) = {
        val canvas = drawContext.canvas
        val padding = padding
        val scale = backdropScale

        if (padding != 0f) {
            canvas.translate(padding, padding)
        }
        // Vendored addition: draw the backdrop content downscaled so the recorded
        // layer (and therefore the RenderEffect chain) covers fewer pixels.
        if (scale != 1f) {
            canvas.scale(scale, scale)
        }
        // Canvas sources use DrawScope.size for their geometry. The recorded buffer
        // is reduced, but drawing coordinates remain logical after the canvas scale.
        // Keep the effect expansion in the same logical grid, including at scale 1.
        val recordedSize = drawContext.size
        try {
            drawContext.size = Size(
                (effectScope.size.width + padding * 2f) / scale,
                (effectScope.size.height + padding * 2f) / scale,
            )
            val coordinates = layoutCoordinates
            val previousRecording = opticalRecordingCoordinates.get()
            opticalRecordingCoordinates.set(coordinates)
            try {
                onDrawBackdrop {
                    with(backdrop) {
                        drawBackdrop(
                            density = effectScope,
                            coordinates = coordinates,
                            layerBlock = layerBlock
                        )
                    }
                }
            } finally {
                opticalRecordingCoordinates.set(previousRecording)
            }
        } finally {
            drawContext.size = recordedSize
        }
        if (scale != 1f) {
            canvas.scale(1f / scale, 1f / scale)
        }
        if (padding != 0f) {
            canvas.translate(-padding, -padding)
        }
    }

    /**
     * Loop-bucket path: one recorded+effect-processed layer per bucket, computed
     * once and reused forever after (unlike the single-layer path below, this
     * never re-records an existing bucket just because the source's contentVersion
     * bumped — a looping backdrop's pixels at a given bucket are the same every
     * repeat by definition). Geometry changes (this surface itself moving or
     * resizing) invalidate every pooled bucket, since they were all captured at
     * the old size/offset.
     */
    /** True on the previous frame; see the hand-over in [drawBackdropLayer]. */
    private var wasHolding = false

    /** When this surface last recorded, for the refresh while held. */
    private var lastRecordedAt = 0L

    /** The capture being faded out of, and when that fade began. */
    private var handoverLayer: GraphicsLayer? = null
    private var handoverStartedAt = 0L

    private fun DrawScope.drawBucketedLayer(bucket: Int): Boolean {
        val padding = padding
        val scale = backdropScale
        val recordSize = IntSize(
            ((size.width * scale).toInt() + padding.toInt() * 2).coerceAtLeast(1),
            ((size.height * scale).toInt() + padding.toInt() * 2).coerceAtLeast(1)
        )
        val offset = currentBackdropOffset()
        val geometryChanged = surfaceDirty ||
            offset != recordedBackdropOffset ||
            recordSize != recordedBackdropSize
        if (geometryChanged) {
            clearLoopPool()
        }
        recordedBackdropOffset = offset
        recordedBackdropSize = recordSize

        val pool = loopPool ?: HashMap<Int, GraphicsLayer>().also { loopPool = it }
        var recorded = false
        val layer = pool.getOrPut(bucket) {
            recorded = true
            val newLayer = requireGraphicsContext().createGraphicsLayer()
            recordLayer(this@DrawBackdropNode, newLayer, size = recordSize, block = recordBackdropBlock)
            newLayer.renderEffect = effectScope.renderEffect
            newLayer
        }
        if (pool.size > LoopPoolMaxSize) {
            val staleKey = pool.keys.first { it != bucket }
            pool.remove(staleKey)?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        }

        layer.topLeft =
            if (padding != 0f) IntOffset(-padding.toInt(), -padding.toInt())
            else IntOffset.Zero
        if (scale != 1f) {
            scale(1f / scale, pivot = Offset.Zero) {
                drawLayer(layer)
            }
        } else {
            drawLayer(layer)
        }
        return recorded
    }

    private val drawBackdropLayer: DrawScope.() -> Boolean = drawBackdropLayer@{
        // The modal frame read invalidates this draw node while open, even when
        // ordinary source generations are intentionally not snapshot-observable.
        liveFrame?.invoke()
        val live = liveFrame != null
        val bucket = if (live) null else loopBucket?.invoke()
        if (bucket != null) {
            return@drawBackdropLayer drawBucketedLayer(bucket)
        }
        val previousLayer = graphicsLayer
        if (previousLayer != null) {
            val padding = padding
            val scale = backdropScale
            val recordSize = IntSize(
                ((size.width * scale).toInt() + padding.toInt() * 2).coerceAtLeast(1),
                ((size.height * scale).toInt() + padding.toInt() * 2).coerceAtLeast(1)
            )
            val version = currentBackdropVersion()
            val offset = currentBackdropOffset()

            // Re-record only when the source pixels changed (version bump from the
            // source's own node) or this surface's size/position moved. Non-layer
            // backdrops have no version, so `version == null` keeps them recording
            // every draw, matching the original behavior.
            // Once frozen, hold the last capture. Only skip when there IS one —
            // a surface frozen before it ever recorded would otherwise draw an
            // empty layer.
            val hasRecording = recordedBackdropSize != IntSize.Zero
            val geometryChanged = surfaceDirty ||
                offset != recordedBackdropOffset ||
                recordSize != recordedBackdropSize
            val versionChanged = version == null || version != recordedBackdropVersion
            // A version bump with nothing of ours moved only needs a re-record if
            // the changed pixels could be ours. If our whole capture rect falls
            // inside a region declared static THIS generation (backdropStaticRegion),
            // the bump is provably from elsewhere — reuse the capture. Undeclared or
            // partially-covered rects fail safe to the original always-record path.
            val versionChangeIsElsewhere = versionChanged && !geometryChanged &&
                offset != null && isFullyStatic(Rect(offset, recordSize.toSize()))
            // Frozen holds the last capture — but only while this surface is
            // where it was when it took it. A pane that has moved and kept its
            // old recording is reflecting somewhere else on the screen: the mini
            // player sliding as the bar folds showed the page from the position
            // it used to be in, not the one under it.
            //
            // Unless the caller says it is worth it anyway, which is what
            // [heldWhileMoving] is for: a surface in the middle of changing
            // shape re-records on every frame of the animation, which is the
            // most expensive thing this app does at the busiest moment it does
            // it. A reflection that lags for the length of a morph is not
            // something anyone can see; the alternative, taking the glass away
            // and putting it back, is.
            // Held, but not indefinitely.
            //
            // A capture frozen for the whole of an animation is a reflection
            // that has to be corrected at the end of it, and that correction is
            // a visible jump however it is dressed up: the fade meant to cover
            // it belongs to a node the animation is about to throw away, since
            // the bar's two states are two different pieces of composition. The
            // answer is not to let it drift that far. While held, the capture is
            // still refreshed a few times a second, which costs three or four
            // recordings across a fold instead of one per frame, and leaves
            // nothing at the end big enough to see.
            val now = System.nanoTime()
            val heldLongEnough = (now - lastRecordedAt) / 1_000_000f >= HoldRefreshMillis
            val inMotion = !live && frozen()
            val holding = inMotion &&
                hasRecording &&
                !heldLongEnough &&
                (!geometryChanged || heldWhileMoving())

            // Coming out of a hold always takes a fresh capture, whether or not
            // anything else says to.
            //
            // Without this the held reflection could last for good: the page
            // only bumps its version when it redraws, and a page that has
            // stopped scrolling does not redraw. So the surface sat on the
            // capture it took before the scroll — the bar showing a piece of
            // artwork that had long since moved on, in a screen where nothing
            // was moving to correct it.
            val refreshAfterHold = wasHolding && !holding
            val needsRecord = when {
                live -> true
                holding -> false
                refreshAfterHold -> true
                else -> (geometryChanged || versionChanged) && !versionChangeIsElsewhere
            }

            // The first capture after a hold is a jump: the reflection has been
            // still through a whole animation and then, in one frame, it is
            // right again. That cut is more noticeable than the staleness it
            // corrects, so the old capture is kept and the new one is brought up
            // over it. Only after a hold — an ordinary re-record during a scroll
            // is a small change from the frame before it, and crossing them
            // would only cost an extra layer for nothing.
            val endingHold = refreshAfterHold && !live && !inMotion
            val layer = if (endingHold) {
                val context = requireGraphicsContext()
                val next = handoverLayer ?: context.createGraphicsLayer()
                next.alpha = 1f
                next.renderEffect = effectScope.renderEffect
                handoverLayer = previousLayer
                graphicsLayer = next
                handoverStartedAt = now
                next
            } else previousLayer
            wasHolding = holding

            if (needsRecord) {
                recordLayer(
                    this@DrawBackdropNode,
                    layer,
                    size = recordSize,
                    block = recordBackdropBlock
                )
                recordedBackdropVersion = version
                recordedBackdropOffset = offset
                recordedBackdropSize = recordSize
                lastRecordedAt = now
            }

            layer.topLeft =
                if (padding != 0f) IntOffset(-padding.toInt(), -padding.toInt())
                else IntOffset.Zero

            val handover = handoverLayer
            val fade = if (!live && handover != null && handoverStartedAt != 0L) {
                val elapsed = (System.nanoTime() - handoverStartedAt) / 1_000_000f
                (elapsed / HandoverMillis).fastCoerceIn(0f, 1f)
            } else {
                1f
            }

            fun DrawScope.paint(target: GraphicsLayer) {
                if (scale != 1f) {
                    // Vendored addition: stretch the low resolution layer back over
                    // the full surface; the blur in the chain masks the upscaling.
                    scale(1f / scale, pivot = Offset.Zero) {
                        drawLayer(target)
                    }
                } else {
                    drawLayer(target)
                }
            }

            if (fade < 1f && handover != null) {
                handover.topLeft = layer.topLeft
                handover.alpha = 1f - fade
                paint(handover)
                layer.alpha = fade
                paint(layer)
                layer.alpha = 1f
                // Nothing else is changing, so the next frame has to be asked
                // for or the fade would stop on its first step.
                this@DrawBackdropNode.invalidateDraw()
            } else {
                if (handover != null) {
                    requireGraphicsContext().releaseGraphicsLayer(handover)
                    handoverLayer = null
                    handoverStartedAt = 0L
                }
                paint(layer)
            }

            needsRecord
        } else {
            false
        }
    }

    /** Alpha belongs outside the processed layer, leaving capture/effect geometry stable. */
    private fun DrawScope.drawOpticalLayer() {
        val opticalAlpha = (opticsAlphaProvider?.invoke() ?: opticsAlpha).coerceIn(0f, 1f)
        if (opticalAlpha <= 0f) return
        if (opticalAlpha >= 1f) {
            drawBackdropLayer()
            return
        }
        val canvas = drawContext.canvas
        canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = opticalAlpha })
        try { drawBackdropLayer() } finally { canvas.restore() }
    }

    override fun ContentDrawScope.draw() {
        if (effectScope.update(this, backdropScale)) {
            updateEffects()
        }

        exportedBackdrop?.let { exported ->
            // Film/progress can invalidate drawing without changing the optical capture.
            // Publish before children sample it; never export descendant content.
            if (exported !== backdrop && !exported.isRecording) {
                exported.isRecording = true
                try {
                    materialObserver.observeMaterial {
                        val outline = shapeProvider.shape.createOutline(size, layoutDirection, this)
                        val material = shapeProvider.materialLayers
                        if (outline != exportedOutline ||
                            material?.highlight?.input != exportedHighlight ||
                            material?.shadow?.input != exportedShadow ||
                            material?.innerShadow?.input != exportedInnerShadow
                        ) {
                            exported.materialChanged()
                            exportedOutline = outline
                            exportedHighlight = material?.highlight?.input
                            exportedShadow = material?.shadow?.input
                            exportedInnerShadow = material?.innerShadow?.input
                        }
                        exported.graphicsLayer.setOutline(outline)
                        exported.graphicsLayer.clip = true
                        exported.graphicsLayer.compositingStrategy = when (compositingStrategy) {
                            CompositingStrategy.Offscreen -> LayerCompositingStrategy.Offscreen
                            CompositingStrategy.ModulateAlpha -> LayerCompositingStrategy.ModulateAlpha
                            else -> LayerCompositingStrategy.Auto
                        }
                        recordLayer(this@DrawBackdropNode, exported.graphicsLayer) {
                            material?.shadow?.draw(this)
                            onDrawBehind?.invoke(this)
                            drawOpticalLayer()
                            onDrawSurface?.invoke(this)
                            onDrawFront?.invoke(this)
                            material?.highlight?.draw(this)
                            material?.innerShadow?.draw(this)
                        }
                    }
                    exported.contentRecorded()
                } finally {
                    exported.isRecording = false
                }
            }
        }
        // Export preparation can refresh a changed peer's optical capture. Paint
        // that same prepared state on screen before descendants sample it.
        onDrawBehind?.invoke(this)
        drawOpticalLayer()
        onDrawSurface?.invoke(this)
        drawContent()
        onDrawFront?.invoke(this)
        surfaceDirty = false
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            if (backdrop.isCoordinatesDependent) {
                layoutCoordinates = coordinates
            } else {
                if (layoutCoordinates != null) {
                    layoutCoordinates = null
                }
            }
            exportedBackdrop?.layerCoordinates = coordinates
        }
    }

    override fun onObservedReadsChanged() {
        exportedBackdrop?.materialChanged()
        surfaceDirty = true
        invalidateDrawCache()
    }

    fun invalidateDrawCache() {
        observeDependencies()
        // Observed effect state can change without an element update (and thus
        // without Compose's automatic node invalidation). Repainting is needed
        // even when the effect's padding stays unchanged.
        invalidateDraw()
    }

    private fun observeDependencies() {
        observeReads {
            // A retained child layer may not redraw when its enclosing material does.
            backdrop.contentVersion
            updateEffects()
        }
    }

    private fun updateEffects() {
        if (!isRenderEffectSupported()) return

        effectScope.apply(effects)
        graphicsLayer?.renderEffect = effectScope.renderEffect
        val newPadding = effectScope.padding
        if (newPadding != padding) {
            padding = newPadding
            surfaceDirty = true
        }
    }

    override fun onAttach() {
        val graphicsContext = requireGraphicsContext()
        graphicsLayer = graphicsContext.createGraphicsLayer()
        exportedBackdrop?.materialChanged()

        surfaceDirty = true
        observeDependencies()
    }

    override fun onDetach() {
        val graphicsContext = requireGraphicsContext()
        graphicsLayer?.let { layer ->
            graphicsContext.releaseGraphicsLayer(layer)
            graphicsLayer = null
        }
        handoverLayer?.let { layer ->
            graphicsContext.releaseGraphicsLayer(layer)
            handoverLayer = null
        }
        clearLoopPool()

        effectScope.reset()
        layoutCoordinates = null
        exportedBackdrop?.layerCoordinates = null
        recordedBackdropVersion = null
        recordedBackdropOffset = null
        recordedBackdropSize = IntSize.Zero
        exportedOutline = null
        exportedHighlight = null
        exportedShadow = null
        exportedInnerShadow = null
    }
}

/** Observes film reads separately so their updates do not invalidate the optical capture. */
private class MaterialObserverNode(
    private val changed: () -> Unit,
) : ObserverModifierNode, Modifier.Node() {
    fun observeMaterial(block: () -> Unit) = observeReads(block)

    override fun onObservedReadsChanged() = changed()
}
