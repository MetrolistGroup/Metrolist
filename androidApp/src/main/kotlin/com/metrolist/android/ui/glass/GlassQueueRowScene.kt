package com.metrolist.android.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import com.metrolist.android.ui.glass.backdrop.Backdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.LayerBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.layerBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import com.metrolist.android.ui.glass.backdrop.internal.InverseLayerScope

/** Child-free materials and foreground captures shared by rows in the same queue. */
internal val LocalQueueRowScene = staticCompositionLocalOf<QueueRowScene?> { null }
private val LocalQueueRowOwner = staticCompositionLocalOf<QueueRowSceneOwner?> { null }

/** Keeps a song and its sibling handle under one exclusion and real lazy-list draw order. */
@Composable
fun QueueGlassRowObjects(order: Int, dragging: Boolean = false, content: @Composable () -> Unit) {
    val owner = remember { QueueRowSceneOwner(order, dragging) }
    SideEffect { owner.order = order; owner.dragging = dragging }
    CompositionLocalProvider(LocalQueueRowOwner provides owner, content = content)
}

@Stable
internal class QueueRowSceneOwner(order: Int, dragging: Boolean) {
    var order by mutableStateOf(order)
    var dragging by mutableStateOf(dragging)
}

/** Records a passive sibling such as the native drag handle; never apply around a row material. */
@Composable
fun Modifier.glassQueueObjectBackdrop(contentKey: Any? = Unit): Modifier =
    then(rememberQueueRowSceneCapture(contentKey)?.modifier ?: Modifier)

/** Records the separate swipe background beneath its row, including its revealed action glyph. */
@Composable
internal fun Modifier.glassQueueUnderlayBackdrop(contentKey: Any? = Unit): Modifier {
    if (LocalQueueRowOwner.current == null) return this
    val capture = rememberQueueRowSceneCapture(contentKey) ?: return this
    SideEffect { capture.isUnderlay = true }
    return then(capture.modifier)
}

/** Retains composed row surfaces and objects, never a recording of the queue list. */
@Stable
internal class QueueRowScene {
    internal val rows = mutableStateListOf<QueueRowSceneCapture>()
    private var recordedTopology: List<Any?> = emptyList()

    internal fun topology(): List<Any?> = rows.flatMap {
        listOf(it.key, it.owner?.order, it.owner?.dragging, it.material, it.surfaceShape != null, it.isUnderlay)
    }

    internal fun prepareMaterials() {
        val topology = topology()
        if (recordedTopology == topology) return
        recordedTopology = topology
        // Cut every old cross-row RenderNode edge before an order reversal can create a cycle.
        rows.forEach { row ->
            row.material?.let { material ->
                // An active export is already replacing its display list.
                if (material.hasRecording && !material.isRecording) material.graphicsLayer.record(
                    density = Density(1f), layoutDirection = LayoutDirection.Ltr,
                    size = material.graphicsLayer.size,
                ) {}
                material.materialChanged()
            }
        }
    }
}

/** Registers a row and publishes changed content only after its next recording is ready. */
@Composable
internal fun rememberQueueRowSceneCapture(contentKey: Any? = Unit): QueueRowSceneCapture? {
    val scene = LocalQueueRowScene.current ?: return null
    val owner = LocalQueueRowOwner.current
    val foreground = rememberLayerBackdrop()
    val capture = remember(scene, foreground, owner) { QueueRowSceneCapture(scene, foreground, owner) }
    DisposableEffect(scene, capture) {
        scene.rows.add(capture)
        onDispose {
            scene.rows.remove(capture)
            capture.clear()
        }
    }
    SideEffect { capture.contentChanged(contentKey) }
    return capture
}

/** Append [modifier] after the row material so its recording contains only foreground objects. */
@Stable
internal class QueueRowSceneCapture internal constructor(
    private val scene: QueueRowScene,
    internal val foreground: LayerBackdrop,
    internal val owner: QueueRowSceneOwner?,
) {
    internal val key = Any()
    internal var material by mutableStateOf<LayerBackdrop?>(null)
    internal var surfaceShape by mutableStateOf<Shape?>(null)
    internal var isUnderlay by mutableStateOf(false)
    internal var bounds by mutableStateOf<Rect?>(null)
        private set
    internal var visibleBounds by mutableStateOf<Rect?>(null)
        private set
    private var contentKey: Any? = null
    private var hasContentKey = false
    private var cachedUnderlay: Backdrop? = null
    private var cachedSource: Backdrop? = null

    val modifier: Modifier = Modifier
        .onGloballyPositioned { coordinates ->
            if (coordinates.isAttached) {
                val position = coordinates.positionInWindow()
                bounds = Rect(
                    position.x,
                    position.y,
                    position.x + coordinates.size.width,
                    position.y + coordinates.size.height,
                )
                visibleBounds = coordinates.boundsInWindow()
            }
        }
        .layerBackdrop(foreground)

    /** Samples only objects behind this logical row, excluding its artwork, text and handle. */
    fun source(underlay: Backdrop): Backdrop {
        if (cachedUnderlay !== underlay) {
            cachedUnderlay = underlay
            cachedSource = QueueRowBackdrop(scene, this, underlay)
        }
        return requireNotNull(cachedSource)
    }

    internal fun contentChanged(value: Any?) {
        if (!hasContentKey || contentKey != value) {
            hasContentKey = true
            contentKey = value
            foreground.materialChanged()
        }
    }

    internal fun clear() {
        material = null
        surfaceShape = null
        bounds = null
        visibleBounds = null
        cachedUnderlay = null
        cachedSource = null
    }
}

private class QueueRowBackdrop(
    private val scene: QueueRowScene,
    private val consumer: QueueRowSceneCapture,
    private val underlay: Backdrop,
) : Backdrop {
    override val isCoordinatesDependent: Boolean = true
    private val inverse = InverseLayerScope()

    private fun isBehind(row: QueueRowSceneCapture): Boolean {
        if (row === consumer) return false
        if (row.owner != null && row.owner === consumer.owner) return row.isUnderlay && !consumer.isUnderlay
        return compareRows(row, consumer) < 0
    }

    private fun compareRows(first: QueueRowSceneCapture, second: QueueRowSceneCapture): Int =
        compareValuesBy(first, second,
            { it.owner?.dragging == true },
            { it.owner?.order ?: scene.rows.indexOf(it) },
            // Native handles are composed first but drawn above their row with zIndex(2).
            { when { it.isUnderlay -> 0; it.surfaceShape != null -> 1; else -> 2 } },
            { scene.rows.indexOf(it) },
        )

    private fun behindRows(): List<QueueRowSceneCapture> = scene.rows.filter(::isBehind)
        .sortedWith(::compareRows)

    override val contentVersion: Int?
        get() {
            scene.prepareMaterials()
            val underlayVersion = underlay.contentVersion
            var version = underlayVersion ?: 0
            version = 31 * version + scene.topology().hashCode()
            version = 31 * version + (consumer.bounds?.hashCode() ?: 0)
            version = 31 * version + (consumer.owner?.order ?: 0)
            version = 31 * version + (consumer.owner?.dragging?.hashCode() ?: 0)
            for (row in behindRows()) {
                version = 31 * version + row.key.hashCode()
                version = 31 * version + (row.bounds?.hashCode() ?: 0)
                version = 31 * version + (row.visibleBounds?.hashCode() ?: 0)
                version = 31 * version + (row.surfaceShape?.hashCode() ?: 0)
                version = 31 * version + row.foreground.contentVersion
                version = 31 * version + (row.material?.contentVersion ?: 0)
            }
            // Observe row revisions even when an unversioned parent requires live sampling.
            return if (underlayVersion == null) null else version
        }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        scene.prepareMaterials()
        // Publish only after the enclosing material has recorded these changed optical inputs.
        consumer.material?.materialChanged()
        with(underlay) { drawBackdrop(density, coordinates, layerBlock) }
        val target = coordinates?.takeIf { it.isAttached } ?: return
        for (row in behindRows()) {
            val visible = row.visibleBounds?.takeUnless { it.isEmpty } ?: continue
            val foreground = row.foreground
            if (!foreground.hasRecording || foreground.layerCoordinates?.isAttached != true) continue
            val topLeft = target.windowToLocal(visible.topLeft)
            val topRight = target.windowToLocal(visible.topRight)
            val bottomRight = target.windowToLocal(visible.bottomRight)
            val bottomLeft = target.windowToLocal(visible.bottomLeft)
            val clip = Path().apply {
                moveTo(topLeft.x, topLeft.y)
                lineTo(topRight.x, topRight.y)
                lineTo(bottomRight.x, bottomRight.y)
                lineTo(bottomLeft.x, bottomLeft.y)
                close()
            }
            // A standalone row recording does not retain its LazyColumn ancestor's clip.
            withTransform({
                if (layerBlock != null) with(inverse.apply { reset() }) { inverseTransform(density, layerBlock) }
            }) {
                clipPath(clip) {
                    row.material?.let { material ->
                        if (material.hasRecording) with(material) { drawBackdrop(density, target, null) }
                    }
                    val rowCoordinates = foreground.layerCoordinates
                    val shape = row.surfaceShape
                    if (rowCoordinates?.isAttached == true && shape != null) {
                        val origin = target.localPositionOf(rowCoordinates)
                        val outline = shape.createOutline(rowCoordinates.size.toSize(), layoutDirection, density)
                        val path = when (outline) {
                            is Outline.Generic -> outline.path
                            is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
                            is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
                        }
                        translate(origin.x, origin.y) {
                            clipPath(path) {
                                with(foreground) { drawBackdrop(density, rowCoordinates, null) }
                            }
                        }
                    } else with(foreground) { drawBackdrop(density, target, null) }
                }
            }
        }
    }
}
