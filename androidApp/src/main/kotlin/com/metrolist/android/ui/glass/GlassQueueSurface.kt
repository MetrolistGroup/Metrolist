package com.metrolist.android.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.android.ui.component.Button
import com.metrolist.android.ui.component.Icon
import com.metrolist.android.ui.theme.colorPalette
import com.metrolist.android.ui.glass.backdrop.Backdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.LayerBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.layerBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCanvasBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCombinedBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import com.metrolist.android.ui.glass.backdrop.internal.InverseLayerScope
import com.metrolist.android.ui.utils.LocalMiniPlayerQueueRequest
import com.metrolist.android.ui.theme.topUiRoundnessShape
import com.metrolist.android.ui.theme.uiRoundnessShape
import com.metrolist.android.utils.toast.trackToastObstruction

/** Only row decorations consult this scope; menus continue using their normal Glass policy. */
internal val LocalNativeQueueRows = staticCompositionLocalOf { false }
internal val LocalQueueListBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
internal val LocalQueueGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }
/** Player content captured before the queue is drawn; it cannot contain queue materials. */
val LocalQueuePlayerBackdrop = staticCompositionLocalOf<Backdrop?> { null }
internal val LocalQueueRowBackdrop = staticCompositionLocalOf<Backdrop?> { null }
private val LocalQueueRowUnderlay = staticCompositionLocalOf<Backdrop?> { null }
private val LocalQueuePanelGeometry = staticCompositionLocalOf<QueuePanelGeometry?> { null }
private val LocalQueuePanelHosted = staticCompositionLocalOf { false }
internal val LocalEmbeddedQueueMini = staticCompositionLocalOf { false }
private val IgnoreEmbeddedQueueRequest: () -> Unit = {}

/** Shares only the list recording between sibling surfaces, never a recording of the panel. */
@Composable
fun GlassQueuePanel(
    modifier: Modifier = Modifier,
    settings: GlassSettings = rememberGlassSettings(),
    panelBackgroundColor: Color = Color.Transparent,
    panelShape: Shape = RoundedCornerShape(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val settings = settings.inScope(GlassScope.Queue)
    val list = rememberLayerBackdrop()
    val capture = glassContentCaptureEnabled(settings)
    val rowScene = remember { QueueRowScene() }
    val geometry = remember { QueuePanelGeometry() }
    // The explicit player export ends before this panel. Never use the completed overlay scene:
    // it already contains the queue and its sampling surfaces.
    val player = LocalQueuePlayerBackdrop.current ?: LocalGlassBackdrop.current
    val direction = LocalLayoutDirection.current
    val underlay = remember(player, panelBackgroundColor, panelShape, direction, geometry) {
        QueueNativeFillBackdrop(player, panelBackgroundColor, panelShape, direction) { geometry.coordinates }
    }
    val visible = rememberCombinedBackdrop(underlay, list)
    CompositionLocalProvider(
        LocalGlassScope provides GlassScope.Queue,
        LocalQueueListBackdrop provides if (capture) list else null,
        LocalQueueGlassBackdrop provides if (capture) visible else null,
        LocalQueueRowUnderlay provides underlay,
        LocalQueuePanelGeometry provides geometry,
        LocalQueueRowScene provides if (capture) rowScene else null,
        LocalQueuePanelHosted provides true,
    ) {
        Box(Modifier.onGloballyPositioned { geometry.coordinates = it }.then(modifier), content = content)
    }
}

/** Reconstructs the actual player, native panel and list fill, excluding repeated Glass film. */
@Composable
internal fun rememberQueueRowGlassBackdrop(nativeFill: Color, settings: GlassSettings = rememberGlassSettings()): Backdrop {
    val color = queueContentBackground(nativeFill, settings)
    val underlay = LocalQueueRowUnderlay.current ?: LocalGlassContainerBackdrop.current
    val list = LocalQueueListBackdrop.current
    val panel = LocalQueuePanelGeometry.current
    if (panel == null) {
        val fill = rememberCanvasBackdrop { drawRect(color) }
        return if (underlay != null) rememberCombinedBackdrop(underlay, fill) else fill
    }
    val direction = LocalLayoutDirection.current
    return remember(underlay, color, direction, list, panel) {
        QueueNativeFillBackdrop(underlay, color, RoundedCornerShape(0.dp), direction) {
            list?.layerCoordinates?.takeIf { it.isAttached } ?: panel.coordinates
        }
    }
}

private class QueuePanelGeometry {
    var coordinates: LayoutCoordinates? by mutableStateOf(null)
}

/** Native fills occupy their real layout bounds rather than painting every sampled pixel. */
private class QueueNativeFillBackdrop(
    private val parent: Backdrop?,
    private val color: Color,
    private val shape: Shape,
    private val direction: LayoutDirection,
    private val bounds: () -> LayoutCoordinates?,
) : Backdrop {
    private val inverse = InverseLayerScope()
    override val isCoordinatesDependent = true
    // Native fill inputs may change at draw time. Preserve the parent's unversioned contract.
    override val contentVersion: Int? get() = parent?.contentVersion?.let { version ->
        31 * (31 * version + color.hashCode()) + (bounds()?.takeIf { it.isAttached }?.let {
            31 * it.size.hashCode() + it.localToWindow(androidx.compose.ui.geometry.Offset.Zero).hashCode()
        } ?: 0)
    }

    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?) {
        parent?.let { with(it) { drawBackdrop(density, coordinates, layerBlock) } }
        if (color.alpha == 0f) return
        val target = coordinates?.takeIf { it.isAttached } ?: return
        val source = bounds()?.takeIf { it.isAttached } ?: return
        val rect = try { target.localBoundingBoxOf(source, clipBounds = false) } catch (_: IllegalStateException) { return }
        val size = androidx.compose.ui.geometry.Size(rect.width, rect.height)
        val path = Path().apply { addOutline(shape.createOutline(size, direction, density)) }
        withTransform({
            if (layerBlock != null) with(inverse.apply { reset() }) { inverseTransform(density, layerBlock) }
            translate(rect.left, rect.top)
        }) {
            clipPath(path) { drawRect(color, size = size) }
        }
    }
}

/** The recording excludes the sibling mini player, toolbar and search overlay. */
@Composable
@Suppress("UNUSED_PARAMETER") // Keep source compatibility for queue owners passing their list state.
fun Modifier.glassQueueListBackdrop(state: LazyListState): Modifier {
    val source = LocalQueueListBackdrop.current ?: return this
    return layerBackdrop(source)
}

/**
 * Exports the panel material without capturing its foreground controls.
 * @param systemBottomInset Ignored; retained for source compatibility.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun GlassQueueToolbar(
    modifier: Modifier = Modifier,
    settings: GlassSettings = rememberGlassSettings(),
    systemBottomInset: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val settings = settings.inScope(GlassScope.Queue)
    val shape = topUiRoundnessShape()
    val nativeColor = colorPalette().background1
    val material = settings.usesGlassMaterial(GlassArea.Menus)
    val source = LocalQueueGlassBackdrop.current
    Box(modifier) {
        GlassMaterialContainerScope(
            modifier = Modifier.matchParentSize(),
            sectionFilm = if (material) null else nativeColor,
            material = if (material) ({ exported ->
                Modifier.glassMaterial(settings.forArea(GlassArea.Menus), shape,
                    ownBackdrop = source, separateWindow = source == null, menu = true,
                    filmColor = nativeColor, exportedBackdrop = exported)
            }) else null,
        ) { surface ->
            val panel = if (material) surface else surface.background(nativeColor, shape)
                .glassAreaContour(settings, GlassArea.Menus, shape)
            Box(panel)
            // The export includes the visible panel film and queue scene, never these controls.
            CompositionLocalProvider(LocalGlassMaterialSource provides LocalGlassContainerBackdrop.current) {
                Box(Modifier.fillMaxSize()) { content(this) }
            }
        }
    }
}

/** Material owns the navigation strip; native hosts retain it when material is disabled. */
@Composable
fun usesQueueToolbarGlass(settings: GlassSettings = rememberGlassSettings()): Boolean =
    queueToolbarUsesGlassMaterial(settings)

/** Adds the navigation strip only when the hosts delegate it to the material panel. */
@Composable
fun queueToolbarBottomInset(): Dp = if (usesQueueToolbarGlass())
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp

internal fun queueToolbarUsesGlassMaterial(settings: GlassSettings): Boolean =
    settings.inScope(GlassScope.Queue).usesGlassMaterial(GlassArea.Menus)

/** Uses the Home toolbar verbatim; the native total stays outside and the arrow has its own bubble. */
@Composable
fun RowScope.GlassQueueToolbarActions(
    buttons: List<Button>,
    arrow: Icon?,
    settings: GlassSettings = rememberGlassSettings(),
) {
    val settings = settings.inScope(GlassScope.Queue)
    CompositionLocalProvider(LocalGlassBackdrop provides LocalGlassContainerBackdrop.current) {
        GlassToolbarButtons(
            buttons = buttons,
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).testTag("queue.toolbar.actions"),
            settings = settings,
            itemShape = uiRoundnessShape(),
            disableAnimation = settings.usesGlassMaterial(GlassArea.ActionButtons),
        )
        if (arrow != null && arrow.isEnabled) {
            GlassToolbarRow(actionCount = 1, settings = settings, itemShape = uiRoundnessShape(),
                modifier = Modifier.testTag("queue.toolbar.arrow")) { arrow.ToolBarButton() }
        }
    }
}

/** The embedded mini samples the actual list, independently of the full player's opening progress. */
@Composable
fun QueueEmbeddedMini(content: @Composable () -> Unit) {
    val list = LocalQueueGlassBackdrop.current
    CompositionLocalProvider(
        LocalEmbeddedQueueMini provides true,
        LocalMiniPlayerQueueRequest provides IgnoreEmbeddedQueueRequest,
        LocalNativeQueueRows provides false,
        LocalGlassBackdrop provides (list ?: LocalGlassBackdrop.current),
        LocalGlassContainerBackdrop provides (list ?: LocalGlassContainerBackdrop.current),
        LocalGlassMaterialSource provides (list ?: LocalGlassMaterialSource.current),
    ) {
        Box(Modifier.trackToastObstruction()) { content() }
    }
}

/** Preserves the theme's exact opacity; Glass decorates only the panel contour. */
@Composable
fun Modifier.glassQueuePanelBackground(
    originalColor: Color,
    shape: Shape,
    settings: GlassSettings = rememberGlassSettings(),
): Modifier {
    val settings = settings.inScope(GlassScope.Queue)
    return background(originalColor).glassAreaContour(settings, GlassArea.Menus, shape)
}

/** The list keeps its native fill and leaves the contour to its panel when hosted. */
@Composable
fun Modifier.glassQueueListBackground(originalColor: Color, settings: GlassSettings = rememberGlassSettings()): Modifier {
    return if (LocalQueuePanelHosted.current) background(originalColor)
        else glassQueuePanelBackground(originalColor, RoundedCornerShape(0.dp), settings)
}

/** Glass contours never replace a queue row's native fill. */
@Suppress("UNUSED_PARAMETER") // Legacy queue owners still pass their scoped settings.
internal fun queueContentBackground(originalColor: Color, settings: GlassSettings): Color = originalColor

/** Always keep the same content group when toggling Glass so queue state is retained. */
@Composable
fun NativeQueueRows(
    settings: GlassSettings = rememberGlassSettings(),
    content: @Composable () -> Unit,
) {
    val settings = settings.inScope(GlassScope.Queue)
    CompositionLocalProvider(
        LocalNativeQueueRows provides settings.enabled,
        content = content,
    )
}
