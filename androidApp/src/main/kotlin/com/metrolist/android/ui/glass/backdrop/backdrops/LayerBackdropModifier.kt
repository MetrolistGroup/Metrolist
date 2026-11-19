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

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import com.metrolist.android.ui.glass.backdrop.internal.recordLayer

/**
 * @param drawOnScreen False when a sibling presents the recorded content above another layer.
 * @param frozen while this returns true the source is NOT re-recorded: the content is
 *   drawn straight to the screen on Compose's normal incremental path, and sampling glass
 *   surfaces keep replaying the last capture. Used during scroll, where re-recording the
 *   whole tree every frame is by far the most expensive thing the app does (measured on
 *   Home: 47ms of record with it, 1.9ms without). The screen itself stays live — only the
 *   blur behind the chrome lags, which is invisible against moving content.
 */
fun Modifier.layerBackdrop(
    backdrop: LayerBackdrop,
    drawOnScreen: Boolean = true,
    frozen: () -> Boolean = { false },
): Modifier = this then LayerBackdropElement(backdrop, frozen, drawOnScreen)

private class LayerBackdropElement(
    val backdrop: LayerBackdrop,
    val frozen: () -> Boolean,
    val drawOnScreen: Boolean,
) : ModifierNodeElement<LayerBackdropNode>() {

    override fun create(): LayerBackdropNode {
        return LayerBackdropNode(backdrop, frozen, drawOnScreen)
    }

    override fun update(node: LayerBackdropNode) {
        if (node.backdrop != backdrop) {
            node.backdrop.layerCoordinates = null
            node.backdrop = backdrop
        }
        node.frozen = frozen
        node.drawOnScreen = drawOnScreen
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "layerBackdrop"
        properties["backdrop"] = backdrop
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayerBackdropElement) return false

        if (backdrop != other.backdrop) return false
        if (frozen != other.frozen || drawOnScreen != other.drawOnScreen) return false

        return true
    }

    override fun hashCode(): Int {
        return 31 * (31 * backdrop.hashCode() + frozen.hashCode()) + drawOnScreen.hashCode()
    }
}

private class LayerBackdropNode(
    var backdrop: LayerBackdrop,
    var frozen: () -> Boolean,
    var drawOnScreen: Boolean,
) : DrawModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {

    override fun ContentDrawScope.draw() {
        // Cleared every generation, before children (backdropStaticRegion among
        // them) redraw and repopulate it below — see LayerBackdrop.staticRegions.
        backdrop.staticRegions.clear()

        // Record the subtree ONCE, then composite that recording to the screen —
        // rather than drawing the tree, then walking it a second time to record
        // it for glass to sample.
        //
        // Measured on a Galaxy M34 (120Hz, 8.33ms budget) scrolling Home on v1.4:
        // frame time was 89ms median with measure+layout at 0.03ms and the GPU at
        // 4ms, i.e. essentially all of it in display-list recording — 38.6ms
        // median, 42.9ms p90. Recording cost scales with the number of draw ops in
        // the tree, not with pixels, so the second traversal was close to a
        // straight doubling of the frame's dominant cost. (It also explains why
        // tuning the glass resolution scale changed nothing: that only affects GPU
        // work, which had headroom to spare.)
        //
        // Safe to composite from the layer because the recorded content is what
        // belongs on screen anyway: the app's onDraw fills an opaque background
        // before drawContent(), so the layer is opaque and drawing it is
        // equivalent to having drawn the tree directly.
        // While frozen, take Compose's ordinary draw path instead. That path reuses
        // every child RenderNode that did not change; layer.record { drawContent() }
        // cannot, so it re-issues the whole subtree every frame. Skipping the version
        // bump is what keeps the glass surfaces on their existing captures.
        if (frozen() && backdrop.hasRecording) {
            if (drawOnScreen) backdrop.onDraw(this@draw)
            return
        }
        backdrop.isRecording = true
        try {
            recordLayer(this@LayerBackdropNode, backdrop.graphicsLayer) { backdrop.onDraw(this@draw) }
        } finally {
            backdrop.isRecording = false
        }
        if (drawOnScreen) drawLayer(backdrop.graphicsLayer)
        // Notify sampling glass surfaces that the source pixels changed, so they
        // re-record their own layers. When the source is static they skip it.
        backdrop.contentRecorded()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            backdrop.capturesSubtree = true
            backdrop.layerCoordinates = coordinates
        }
    }

    override fun onDetach() {
        backdrop.layerCoordinates = null
    }
}
