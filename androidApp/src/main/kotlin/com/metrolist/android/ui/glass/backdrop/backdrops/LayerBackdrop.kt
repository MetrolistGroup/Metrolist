/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 */
package com.metrolist.android.ui.glass.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import com.metrolist.android.ui.glass.backdrop.Backdrop
import com.metrolist.android.ui.glass.backdrop.internal.InverseLayerScope

private val DefaultOnDraw: ContentDrawScope.() -> Unit = { drawContent() }

// A coordinate argument can describe the sampling origin without owning the destination.
internal val opticalRecordingCoordinates = ThreadLocal<LayoutCoordinates?>()

@Composable
fun rememberLayerBackdrop(
    graphicsLayer: GraphicsLayer = rememberGraphicsLayer(),
    onDraw: ContentDrawScope.() -> Unit = DefaultOnDraw
): LayerBackdrop {
    return remember(graphicsLayer, onDraw) {
        LayerBackdrop(graphicsLayer, onDraw)
    }
}

@Stable
class LayerBackdrop internal constructor(
    val graphicsLayer: GraphicsLayer,
    internal val onDraw: ContentDrawScope.() -> Unit
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    internal var layerCoordinates: LayoutCoordinates? by mutableStateOf(null)

    /**
     * Capture generation bumped by [LayerBackdropModifier]'s node each time this
     * backdrop's content is re-recorded. Glass surfaces read it during their draw
     * pass to skip re-recording their own layers when the source is static (e.g.
     * a surface-local animation such as the rim highlight drift), which used to
     * re-capture the whole screen on every frame.
     *
     * Its recording counter is deliberately a plain field, NOT snapshot state. It is
     * written and read during the draw phase by every sampling glass surface;
     * as snapshot state that read registered a draw dependency, so the write at
     * the end of each frame invalidated every surface, which dirtied the root
     * layer, which re-ran this node's draw and bumped the counter again. That
     * loop redrew the whole app at the GPU's maximum rate forever, on every
     * screen, with nothing on screen moving — measured 394 draws in 5s on a
     * static settings list, with no recomposition, measure or layout in the
     * trace. Raw source recordings replay their RenderNode by reference. Exported
     * material input changes additionally publish one snapshot revision after the
     * new recording is ready, so retained nested consumers refresh their processed
     * captures without making every ordinary source record observable.
     */
    private var recordingVersion: Int = 0
    private var materialRevision by mutableIntStateOf(0)
    private var pendingMaterialChange: Boolean = false

    // Ordinary records never write snapshot state. Changed material inputs publish
    // their revision only after recording: retained child layers can otherwise refresh
    // before their enclosing layer draws and sample the previous export indefinitely.
    override val contentVersion: Int get() = recordingVersion + materialRevision

    internal fun materialChanged() {
        pendingMaterialChange = true
    }

    // NZik: never let a descendant put this RenderNode into its own display list.
    internal var isRecording: Boolean = false
    internal var capturesSubtree: Boolean = false

    /** Called by the recording node after a successful record. */
    internal fun contentRecorded() {
        recordingVersion++
        if (pendingMaterialChange) {
            pendingMaterialChange = false
            materialRevision++
        }
    }

    /**
     * Regions declared static THIS generation by [backdropStaticRegion] children,
     * in this backdrop's own coordinate space. Plain list, not snapshot state, for
     * the same draw-phase-write reason as [contentVersion]. Cleared and rebuilt by
     * [LayerBackdropNode.draw] every time the source re-records, so a stale
     * declaration from a since-removed or since-changed composable never lingers
     * past the generation it was made in.
     */
    internal val staticRegions = ArrayList<Rect>()

    /**
     * True if [rect] (in this backdrop's coordinate space) falls entirely inside a
     * single region declared static this generation. Deliberately checks a single
     * covering rect rather than the union of several: a target only partly covered
     * by static declarations is treated as unstable rather than pulled into
     * region-union math, so a miss only costs a skipped optimization, never a
     * stale pixel on a sampling glass surface.
     */
    internal fun isFullyStatic(rect: Rect): Boolean =
        staticRegions.any { region ->
            region.left <= rect.left && region.top <= rect.top &&
                region.right >= rect.right && region.bottom >= rect.bottom
        }

    /**
     * True once the source has been recorded at least once. Freezing before that would
     * leave sampling surfaces replaying an empty layer.
     */
    internal val hasRecording: Boolean get() = recordingVersion > 0

    private var inverseLayerScope: InverseLayerScope? = null

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        if (isRecording) return
        val coordinates = coordinates?.takeIf { it.isAttached } ?: return
        val layerCoordinates = layerCoordinates?.takeIf { it.isAttached } ?: return
        // Even a retained child draw that runs outside the parent record must never
        // capture the parent display list. HWUI replays these layers by reference.
        if (capturesSubtree && generateSequence(opticalRecordingCoordinates.get()) { it.parentCoordinates }
                .any { it === layerCoordinates }) return
        withTransform({
            if (layerBlock != null) {
                with(obtainInverseLayerScope()) { inverseTransform(density, layerBlock) }
            }
            val offset =
                try {
                    layerCoordinates.localPositionOf(coordinates)
                } catch (_: Exception) {
                    // TODO: outer transformations lead to wrong position calculation
                    coordinates.positionInWindow() - layerCoordinates.positionInWindow()
                }
            translate(-offset.x, -offset.y)
        }) {
            drawLayer(graphicsLayer)
        }
    }

    private fun obtainInverseLayerScope(): InverseLayerScope {
        return inverseLayerScope?.apply { reset() }
            ?: InverseLayerScope().also { inverseLayerScope = it }
    }
}
