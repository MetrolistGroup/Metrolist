package com.metrolist.android.ui.glass

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.requireLayoutCoordinates
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Coordinates of the real native surface, exported by its zero-size content probe. */
internal class GlassNativeSurfaceBounds {
    var coordinates: LayoutCoordinates? = null
    var indication: Modifier = Modifier
}

/** Uses native clickable interactions, so list scrolling still cancels the held highlight. */
internal class GlassHighlightIndication(
    private val pointerTracker: GlassHighlightPointerTracker,
    private val drawBeforeContent: Boolean = false,
    private val policy: GlassInteractionPolicy = GlassInteractionPolicy(),
    private val nativeSurfaceShape: Shape? = null,
    private val nativeSurfaceBounds: GlassNativeSurfaceBounds? = null,
) : IndicationNodeFactory {
    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        GlassHighlightNode(interactionSource, pointerTracker, drawBeforeContent, policy, nativeSurfaceShape, nativeSurfaceBounds)
}

internal class GlassHighlightNode(
    private val interactionSource: InteractionSource,
    private val pointerTracker: GlassHighlightPointerTracker,
    private val drawBeforeContent: Boolean = false,
    private val policy: GlassInteractionPolicy = GlassInteractionPolicy(),
    private val nativeSurfaceShape: Shape? = null,
    private val nativeSurfaceBounds: GlassNativeSurfaceBounds? = null,
) : Modifier.Node(), DrawModifierNode {
    internal var highlight: InteractiveHighlight? = null
        private set
    private var activePress: PressInteraction.Press? = null
    private var stateLayer = GlassInteractionStateLayer()

    override fun onAttach() {
        val animation = InteractiveHighlight(coroutineScope)
        highlight = animation
        // A clickable may create its indication lazily on the first down event.
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            interactionSource.interactions.collect { interaction ->
                stateLayer.update(interaction)
                when (interaction) {
                    is PressInteraction.Press -> {
                        activePress = interaction
                        animation.startPress(interaction.pressPosition)
                        pointerTracker.add(this@GlassHighlightNode)
                    }
                    is PressInteraction.Release -> if (activePress == interaction.press) {
                        activePress = null
                        pointerTracker.remove(this@GlassHighlightNode)
                        animation.releasePress()
                    }
                    is PressInteraction.Cancel -> if (activePress == interaction.press) {
                        activePress = null
                        pointerTracker.remove(this@GlassHighlightNode)
                        animation.releasePress()
                    }
                }
            }
        }
    }

    override fun onDetach() {
        stateLayer = GlassInteractionStateLayer()
        highlight = null
        activePress = null
        pointerTracker.remove(this)
    }

    internal fun movePointer(position: Offset, coordinates: LayoutCoordinates) {
        if (isAttached && activePress != null && coordinates.isAttached) {
            highlight?.movePress(requireLayoutCoordinates().localPositionOf(coordinates, position) - nativeSurfaceInset())
        }
    }

    internal fun pressPositionIn(coordinates: LayoutCoordinates): Offset? {
        val press = activePress ?: return null
        if (!isAttached || !coordinates.isAttached) return null
        return coordinates.localPositionOf(requireLayoutCoordinates(), press.pressPosition + nativeSurfaceInset())
    }

    private fun nativeSurfaceCoordinates(): LayoutCoordinates? =
        nativeSurfaceBounds?.coordinates?.takeIf { it.isAttached }

    private fun nativeSurfaceInset(): Offset = nativeSurfaceCoordinates()?.let {
        requireLayoutCoordinates().localPositionOf(it, Offset.Zero)
    } ?: Offset.Zero

    private fun ContentDrawScope.drawFeedback() {
        val shape = nativeSurfaceShape
        if (shape == null) {
            stateLayer.draw(this, policy.rippleIntensity)
            highlight?.drawHighlight(this, policy.rippleIntensity, policy.rippleSize)
            return
        }
        // The consumer exports the actual clickable surface, after Material's touch spacer.
        // Native PressInteraction positions already use this visible coordinate system.
        val surface = nativeSurfaceCoordinates()
        val offset = nativeSurfaceInset()
        val width = surface?.size?.width?.toFloat() ?: size.width
        val height = surface?.size?.height?.toFloat() ?: size.height
        inset(offset.x, offset.y, size.width - offset.x - width, size.height - offset.y - height) {
            if (surface != null) {
                // Material paints its shaped background under its graphics-layer clip.
                // Paint feedback through that same outline too: a rectangular highlight
                // under only the layer clip has stronger coverage at rounded AA edges.
                val outline = shape.createOutline(size, layoutDirection, this)
                val shapedScope = NativeSurfaceDrawScope(this, outline)
                stateLayer.draw(shapedScope, policy.rippleIntensity)
                highlight?.drawHighlight(shapedScope, policy.rippleIntensity, policy.rippleSize)
                return@inset
            }
            val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawFeedback)) }
            clipPath(path) {
                stateLayer.draw(this, policy.rippleIntensity)
                highlight?.drawHighlight(this, policy.rippleIntensity, policy.rippleSize)
            }
        }
    }

    override fun ContentDrawScope.draw() {
        if (drawBeforeContent) {
            drawFeedback()
        }
        drawContent()
        // Rows paint an opaque background in their content, unlike the button glass layer.
        if (!drawBeforeContent) {
            drawFeedback()
        }
    }
}

/** Reuses Material background drawing and retains each effect's original blend operation. */
private class NativeSurfaceDrawScope(private val scope: DrawScope, private val outline: Outline) : DrawScope by scope {
    override fun drawRect(color: Color, topLeft: Offset, size: Size, alpha: Float, style: DrawStyle,
        colorFilter: ColorFilter?, blendMode: BlendMode) {
        if (topLeft == Offset.Zero && size == scope.size) {
            scope.drawOutline(outline, color, alpha, style, colorFilter, blendMode)
        } else {
            scope.drawRect(color, topLeft, size, alpha, style, colorFilter, blendMode)
        }
    }

    override fun drawRect(brush: Brush, topLeft: Offset, size: Size, alpha: Float, style: DrawStyle,
        colorFilter: ColorFilter?, blendMode: BlendMode) {
        if (topLeft == Offset.Zero && size == scope.size) {
            scope.drawOutline(outline, brush, alpha, style, colorFilter, blendMode)
        } else {
            scope.drawRect(brush, topLeft, size, alpha, style, colorFilter, blendMode)
        }
    }
}
