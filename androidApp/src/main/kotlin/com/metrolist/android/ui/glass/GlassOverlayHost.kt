package com.metrolist.android.ui.glass

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MenuDefaults
import com.metrolist.android.models.NavigationBarPosition
import com.metrolist.android.ui.theme.Dimensions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.metrolist.android.R
import com.metrolist.android.ui.glass.backdrop.Backdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.layerBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCombinedBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCanvasBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberLayerBackdrop

@Immutable
data class GlassMenuEntry(
    val id: Any,
    val anchor: Rect,
    val settings: GlassSettings,
    val onDismiss: () -> Unit,
    val content: @Composable ColumnScope.() -> Unit,
    val modifier: Modifier = Modifier,
    val visible: Boolean = true,
    val trigger: GlassMenuTriggerState? = null,
    val shape: Shape,
    val containerColor: Color? = null,
    val backdrop: Backdrop? = null,
    val locals: CompositionLocalContext? = null,
)

/** A dismissed entry stays mounted until its exit animation actually completes. */
class GlassMenuHostState {
    internal val visibility = GlassOverlayVisibility()
    var entry by mutableStateOf<GlassMenuEntry?>(null)
        private set
    var exitGeneration by mutableStateOf(0L)
        private set
    var backdrop by mutableStateOf<Backdrop?>(null)
    // Navigation-complete source for the sibling mini-player, excluding all player pixels.
    var miniPlayerBackdrop by mutableStateOf<Backdrop?>(null)
        internal set
    // Chrome samples the page; overlays sample the completed page plus its chrome.
    var overlayBackdrop by mutableStateOf<Backdrop?>(null)
    // The activity scene includes siblings outside navigation, notably the full/mini player.
    var sceneBackdrop by mutableStateOf<Backdrop?>(null)
    // Only a fully covering foreground may replace the activity scene (never a mini player).
    var foregroundBackdrop by mutableStateOf<Backdrop?>(null)
        internal set
    val modalBackdrop: Backdrop? get() = foregroundBackdrop ?: sceneBackdrop ?: overlayBackdrop ?: backdrop
    internal var artworkTint by mutableStateOf(GlassArtworkTint())
    var standaloneSettings by mutableStateOf(false)
    val dialogs = GlassDialogHostState()
    internal val nativeOverlays = mutableStateListOf<Any>()
    val hasOverlays: Boolean get() = entry != null || dialogs.hasDialogs || nativeOverlays.isNotEmpty()

    fun show(next: GlassMenuEntry) {
        val previous = entry
        if (previous != null && (previous.id != next.id || previous.trigger !== next.trigger)) {
            previous.trigger?.release(previous.id)
        }
        next.trigger?.claim(next.id)
        entry = next
    }

    fun hide(id: Any) {
        if (entry?.id == id && entry?.visible == true) {
            exitGeneration++
            entry = entry?.copy(visible = false)
        }
    }

    fun clear(id: Any, generation: Long = exitGeneration) {
        if (entry?.id == id && entry?.visible == false && generation == exitGeneration) remove(id)
    }

    /** Remove an entry immediately when its caller leaves this host. */
    fun remove(id: Any) {
        if (entry?.id == id) {
            entry?.trigger?.release(id)
            entry = null
        }
    }
}

@Immutable
data class GlassMenuPlacement(val offset: IntOffset, val pivot: TransformOrigin)

/** Matches Material3 DropdownMenu's start/end and below/above/center placement candidates. */
fun placeGlassMenu(
    anchor: Rect,
    menu: IntSize,
    window: IntSize,
    margin: Int,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    anchorAligned: Boolean = false,
    anchorFraction: Float = 1f,
    anchorOffsetX: Int = 0,
): GlassMenuPlacement {
    // Candidate order and overflow policy follow AndroidX Material3 1.5 MenuPosition.kt (Apache-2.0).
    val left = anchor.left.roundToInt()
    val right = anchor.right.roundToInt() - menu.width
    val edgeX = if (anchor.center.x < window.width / 2) 0 else window.width - menu.width
    val candidatesX = if (layoutDirection == LayoutDirection.Ltr) listOf(left, right, edgeX)
        else listOf(right, left, edgeX)
    val logicalFraction = if (anchorFraction.isFinite()) anchorFraction.coerceIn(0f, 1f) else 1f
    val fraction = if (layoutDirection == LayoutDirection.Rtl) 1f - logicalFraction else logicalFraction
    val alignedX = (anchor.left + (anchor.width - menu.width) * fraction).roundToInt() + anchorOffsetX
    val x = if (anchorAligned) alignedX.coerceIn(0, (window.width - menu.width).coerceAtLeast(0))
        else candidatesX.firstOrNull { it >= 0 && it + menu.width <= window.width }
        ?: if (menu.width >= window.width) (window.width - menu.width) / 2
        else edgeX.coerceIn(0, window.width - menu.width)
    val edgeY = if (anchor.center.y < window.height / 2) 0 else window.height - menu.height
    val candidatesY = listOf(
        anchor.bottom.roundToInt(),
        anchor.top.roundToInt() - menu.height,
        anchor.top.roundToInt() - menu.height / 2,
        edgeY,
    )
    val y = if (anchorAligned) anchor.bottom.roundToInt().coerceIn(0, (window.height - menu.height).coerceAtLeast(0))
        else candidatesY.firstOrNull { it >= margin && it + menu.height <= window.height - margin }
        ?: if (menu.height >= window.height - 2 * margin) (window.height - menu.height) / 2
        else edgeY.coerceIn(margin, window.height - margin - menu.height)
    return GlassMenuPlacement(
        IntOffset(x, y),
        TransformOrigin(
            ((anchor.center.x - x) / menu.width.coerceAtLeast(1)).coerceIn(0f, 1f),
            ((anchor.center.y - y) / menu.height.coerceAtLeast(1)).coerceIn(0f, 1f),
        ),
    )
}

@Immutable
internal data class GlassMenuFrame(val bounds: Rect, val refractionPulse: Float)

internal const val GLASS_MENU_OPEN_DURATION_MS = 440
internal const val GLASS_MENU_CLOSE_DURATION_MS = 380
internal val GlassMenuOpenEasing = CubicBezierEasing(.28f, 0f, .18f, 1.04f)
internal val GlassMenuCloseEasing = CubicBezierEasing(.3f, 0f, .2f, 1.02f)

/** Size and a bounded spatial arc evolve independently, then retrace on close with elastic settling. */
internal fun glassMenuFrame(anchor: Rect, target: Rect, progress: Float, viewport: IntSize? = null): GlassMenuFrame {
    val p = progress.coerceIn(0f, 1f)
    // A safe viewport may exclude part of a chrome button. Its first/last frame must
    // still coincide with that button; clamping it first creates a visible jump.
    if (p == 0f) return GlassMenuFrame(anchor, 0f)
    val spatial = progress.coerceIn(-.03f, 1.03f)
    val pulse = if (p == 0f || p == 1f) 0f else sin(PI.toFloat() * p)
    // Open sideways before stretching down. Reversing the same path returns a soft bubble,
    // rather than a tall narrow column with the source glyph halfway down the screen.
    val widthProgress = if (spatial in 0f..1f) 1f - (1f - spatial).pow(1.35f) else spatial
    val heightProgress = if (spatial in 0f..1f) spatial.pow(1.6f) else spatial
    val sway = minOf(anchor.width, anchor.height).coerceAtLeast(0f) * .28f * pulse
    val width = lerp(anchor.width.coerceAtLeast(1f), target.width, widthProgress)
        .coerceIn(1f, (viewport?.width?.toFloat() ?: Float.POSITIVE_INFINITY).coerceAtLeast(1f))
    val height = lerp(anchor.height.coerceAtLeast(1f), target.height, heightProgress)
        .coerceIn(1f, (viewport?.height?.toFloat() ?: Float.POSITIVE_INFINITY).coerceAtLeast(1f))
    val centerX = lerp(anchor.center.x, target.center.x, widthProgress) + sign(target.center.x - anchor.center.x) * sway
    val centerY = lerp(anchor.center.y, target.center.y, heightProgress) + sign(target.center.y - anchor.center.y) * sway
    val left = if (viewport == null) centerX - width / 2
        else (centerX - width / 2).coerceIn(0f, (viewport.width - width).coerceAtLeast(0f))
    val top = if (viewport == null) centerY - height / 2
        else (centerY - height / 2).coerceIn(0f, (viewport.height - height).coerceAtLeast(0f))
    return GlassMenuFrame(
        bounds = Rect(left, top, left + width, top + height),
        refractionPulse = pulse,
    )
}

internal fun glassMenuContentAlpha(progress: Float): Float = ((progress - .5f) / .4f).coerceIn(0f, 1f)

internal fun glassMenuSourceAlpha(progress: Float): Float = ((.2f - progress) / .12f).coerceIn(0f, 1f)

/** Route changes may move the surviving trigger while the old pane is already closing. */
internal fun glassMenuReturnAnchor(entry: GlassMenuEntry): Rect =
    if (entry.visible) entry.anchor else entry.trigger?.bounds?.takeUnless { it == Rect.Zero } ?: entry.anchor

val LocalGlassMenuHost = staticCompositionLocalOf<GlassMenuHostState?> { null }

internal class GlassDropdownBackdropOwner(
    val host: GlassMenuHostState,
    val id: Any,
    val underlying: Backdrop?,
) {
    fun resolve(completed: Backdrop?): Backdrop? =
        if (host.entry?.let { it.id == id && it.visible } == true) completed else underlying
}

internal val LocalGlassDropdownBackdropOwner = staticCompositionLocalOf<GlassDropdownBackdropOwner?> { null }

/** Same-window menus share trigger-directed motion across glass and native surfaces. */
@Composable
fun GlassOverlayHost(host: GlassMenuHostState) {
    val entry = host.entry ?: return
    CompositionLocalProvider(entry.locals ?: currentCompositionLocalContext) {
        CompositionLocalProvider(
            LocalGlassOpticsEnabled provides true,
            LocalGlassOpticsTransitionProgress provides 1f,
            LocalGlassLiveFrame provides rememberGlassModalLiveFrameIfEnabled(covered = host.nativeOverlays.isNotEmpty()),
            LocalGlassUnderlayCovered provides host.visuallyCovered(entry.id),
            LocalGlassOpticalUnderlayCovered provides host.nativeOverlays.isNotEmpty(),
            LocalGlassOverlayPaintScope provides remember(host, entry.id) { GlassOverlayPaintScope(host, entry.id) },
        ) { GlassOverlayEntry(host, entry) }
    }
}

@Composable
private fun GlassOverlayEntry(host: GlassMenuHostState, entry: GlassMenuEntry) {
    val menuPaneTitle = stringResource(R.string.common_menu_label)
    val source = entry.backdrop ?: host.modalBackdrop
    val visibleMenu = rememberLayerBackdrop()
    val menuMaterial = rememberLayerBackdrop()
    val focus = remember(entry.id) { FocusRequester() }
    LaunchedEffect(entry.id) { focus.requestFocus() }
    val motion = isGlassMotionAllowed()
    val progress = remember(entry.id) { Animatable(if (motion) 0f else 1f) }
    val glassEnabled = entry.settings.usesGlassMaterial(GlassArea.Dropdowns)
    val nativeColor = entry.containerColor ?: MenuDefaults.containerColor
    // Native child controls sample only the theme fill, never their own visible menu capture.
    val nativeMaterial = rememberCanvasBackdrop { drawRect(nativeColor) }
    val dimmingAlpha = if (glassEnabled) entry.settings.menuDimming * progress.value.coerceIn(0f, 1f) else 0f
    val dimmedSource = rememberGlassDimmedBackdrop(source, dimmingAlpha)
    val completedBackdrop = if (dimmedSource != null) rememberCombinedBackdrop(dimmedSource, visibleMenu) else visibleMenu
    var menuSize by remember(entry.id) { mutableStateOf(IntSize.Zero) }
    var rootOffset by remember { mutableStateOf<IntOffset?>(null) }
    val ready = menuSize != IntSize.Zero && rootOffset != null
    val generation = host.exitGeneration
    LaunchedEffect(entry.id, entry.visible, generation, motion, ready) {
        if (!ready && entry.visible) return@LaunchedEffect
        if (!motion) progress.snapTo(if (entry.visible) 1f else 0f)
        else progress.animateTo(
            if (entry.visible) 1f else 0f,
            tween(
                if (entry.visible) GLASS_MENU_OPEN_DURATION_MS else GLASS_MENU_CLOSE_DURATION_MS,
                easing = if (entry.visible) GlassMenuOpenEasing else GlassMenuCloseEasing,
            ),
        )
        if (!entry.visible) host.clear(entry.id, generation)
    }
    fun dismiss() {
        host.hide(entry.id)
        entry.onDismiss()
    }
    BackHandler(enabled = entry.visible) { dismiss() }
    Box(Modifier.fillMaxSize().focusRequester(focus)
        .focusProperties { onExit = { cancelFocusChange() } }.focusGroup()) {
        Box(Modifier.matchParentSize()
            .background(Color.Black.copy(alpha = dimmingAlpha))
            .glassScrimInput().clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = { if (entry.visible) dismiss() },
        ).clearAndSetSemantics { })
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
                .onGloballyPositioned {
                    val position = it.positionInRoot()
                    rootOffset = IntOffset(position.x.roundToInt(), position.y.roundToInt())
                },
            // Frame bounds and absoluteOffset both use physical coordinates.
            contentAlignment = AbsoluteAlignment.TopLeft,
        ) {
            val density = LocalDensity.current
            val window = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
            val margin = with(density) { 48.dp.roundToPx() }
            val origin = rootOffset ?: IntOffset.Zero
            val localAnchor = entry.anchor.translate(-origin.x.toFloat(), -origin.y.toFloat())
            val returnAnchor = glassMenuReturnAnchor(entry).translate(-origin.x.toFloat(), -origin.y.toFloat())
            val sharedTrigger = entry.trigger != null
            val navigationPosition = NavigationBarPosition.current()
            val railWidth = with(density) { Dimensions.navigationRailWidth.toPx() }
            val isLeftRailTrigger = sharedTrigger && navigationPosition == NavigationBarPosition.Left &&
                localAnchor.right <= railWidth
            val isRightRailTrigger = sharedTrigger && navigationPosition == NavigationBarPosition.Right &&
                localAnchor.left >= window.width - railWidth
            val railOffset = localAnchor.width.roundToInt() + with(density) { 4.dp.roundToPx() }
            val direction = LocalLayoutDirection.current
            val placement = placeGlassMenu(
                localAnchor, menuSize, window, margin, direction, sharedTrigger,
                anchorFraction = when {
                    isLeftRailTrigger -> if (direction == LayoutDirection.Rtl) 1f else 0f
                    isRightRailTrigger -> if (direction == LayoutDirection.Rtl) 0f else 1f
                    else -> entry.trigger?.menuAnchorFraction ?: 1f
                },
                anchorOffsetX = when {
                    isLeftRailTrigger -> railOffset
                    isRightRailTrigger -> -railOffset
                    else -> 0
                },
            )
            val target = Rect(
                placement.offset.x.toFloat(), placement.offset.y.toFloat(),
                (placement.offset.x + menuSize.width).toFloat(), (placement.offset.y + menuSize.height).toFloat(),
            )
            val fraction = if (motion) progress.value else if (entry.visible) 1f else 0f
            val frame = glassMenuFrame(returnAnchor, target, fraction, window)
            SideEffect {
                // Do not hide the real button before both measurement and safe-area origin exist.
                // Otherwise the first overlay frame is misplaced by the status-bar inset.
                if (ready) entry.trigger?.updateMenuBounds(entry.id, frame.bounds.translate(origin.x.toFloat(), origin.y.toFloat()))
            }
            val shape = glassMenuShape(
                source = entry.trigger?.shape ?: entry.shape,
                target = entry.shape,
                sourceSize = returnAnchor.size,
                targetSize = target.size,
                progress = fraction,
                density = density,
            )
            val settings = entry.settings.copy(
                depth = (entry.settings.depth * (1f + .35f * frame.refractionPulse)).coerceAtMost(1f),
                amount = (entry.settings.amount * (1f + .35f * frame.refractionPulse)).coerceAtMost(1f),
            )
            CompositionLocalProvider(
                LocalGlassBackdrop provides dimmedSource,
                LocalGlassMaterialSource provides if (glassEnabled) dimmedSource else nativeMaterial,
                LocalGlassOverlayBackdrop provides completedBackdrop,
                LocalGlassContainerBackdrop provides if (glassEnabled) menuMaterial else nativeMaterial,
                LocalGlassDropdownBackdropOwner provides remember(host, entry.id, source) {
                    GlassDropdownBackdropOwner(host, entry.id, source)
                },
            ) {
                key(entry.id) {
                    GlassMenuPane(
                        progress = fraction,
                        anchor = returnAnchor,
                        modifier = Modifier
                            .absoluteOffset { IntOffset(frame.bounds.left.roundToInt(), frame.bounds.top.roundToInt()) }
                            .graphicsLayer { alpha = if (!ready) 0f else if (sharedTrigger) 1f else (fraction * 5f).coerceIn(0f, 1f) }
                            .layerBackdrop(visibleMenu)
                            .glassOverlayPaint((if (glassEnabled) entry.settings.film else nativeColor.alpha) *
                                (if (!ready) 0f else if (sharedTrigger) 1f else (fraction * 5f).coerceIn(0f, 1f)))
                            .semantics { paneTitle = menuPaneTitle }
                            .pointerInput(entry.id) {
                                // Keep taps in the pane's padding away from the outside-dismiss sibling.
                                awaitPointerEventScope {
                                    while (true) awaitPointerEvent(PointerEventPass.Final)
                                }
                            }
                            .then(if (entry.visible) Modifier else Modifier.clearAndSetSemantics { }
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                    }
                                })
                            .then(if (glassEnabled) Modifier.glassMaterial(
                                settings, shape, menu = true, highlightAlpha = .3f, excludeBottomRim = false,
                                filmColor = entry.containerColor,
                                menuFilmProgress = if (sharedTrigger) fraction else 1f,
                                blurRadiusDp = settings.blur * if (sharedTrigger) (.5f + .5f * fraction) else 1f,
                                backdropScale = if (sharedTrigger) lerp(.4f, glassResolutionScale(settings.blur), fraction) else null,
                                exportedBackdrop = menuMaterial,
                            ) else Modifier.clip(shape)
                                .background(nativeColor)
                                .glassAreaContour(settings, GlassArea.Dropdowns, shape)),
                        contentModifier = if (sharedTrigger) entry.modifier.heightIn(
                            max = with(density) { (window.height - localAnchor.bottom).coerceAtLeast(1f).toDp() },
                        ) else entry.modifier,
                        pivot = placement.pivot,
                        onFinalSize = { menuSize = it },
                        content = {
                            // The pane exports before its foreground. Descendant badges see
                            // that visible plate without redirecting the pane to its own export.
                            CompositionLocalProvider(LocalGlassMaterialSource provides
                                if (glassEnabled) menuMaterial else nativeMaterial) {
                                val column = this
                                val fill = if (glassEnabled) glassFilmColor(settings, entry.containerColor,
                                    progress = if (sharedTrigger) fraction else 1f) else nativeColor
                                val surface = effectiveSurfaceColor(surfaceBackdropColor(), fill,
                                    Color.Black.copy(alpha = dimmingAlpha))
                                GlassArtworkContent(settings, glassEnabled, surface) { entry.content(column) }
                            }
                        },
                    )
                    if (ready) entry.trigger?.content?.let { sourceContent ->
                        GlassMenuSource(fraction, returnAnchor, frame.bounds, sourceContent)
                    }
                }
            }
        }
    }
}

/** The glyph follows its pane from the exact source center without being clipped by its growing rim. */
@Composable
internal fun GlassMenuSource(progress: Float, anchor: Rect, frame: Rect = anchor, content: @Composable () -> Unit) {
    val opacity = glassMenuSourceAlpha(progress)
    if (opacity == 0f) return
    val density = LocalDensity.current
    Box(
        Modifier.absoluteOffset {
            IntOffset((frame.center.x - anchor.width / 2).roundToInt(), (frame.center.y - anchor.height / 2).roundToInt())
        }
            .size(with(density) { anchor.width.toDp() }, with(density) { anchor.height.toDp() })
            .graphicsLayer { alpha = opacity }
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Material intrinsic sizing and scrolling remain fixed while only the surrounding pane morphs. */
@Composable
internal fun GlassMenuPane(
    progress: Float,
    anchor: Rect,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    pivot: TransformOrigin = TransformOrigin.Center,
    onFinalSize: (IntSize) -> Unit = {},
    triggerContent: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            // Match AndroidX Material3 Menu.kt's Column modifier order (Apache-2.0).
            Column(
                contentModifier.onSizeChanged(onFinalSize)
                    .padding(vertical = 8.dp)
                    .width(IntrinsicSize.Max)
                    .verticalScroll(rememberScrollState())
                    .graphicsLayer { alpha = glassMenuContentAlpha(progress) },
                content = content,
            )
            if (triggerContent != null) {
                Box(
                    Modifier.graphicsLayer { alpha = glassMenuSourceAlpha(progress) }
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) { triggerContent() }
            }
        },
    ) { measurables, constraints ->
        val contentPlaceable = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val triggerPlaceable = measurables.getOrNull(1)?.measure(Constraints.fixed(
            anchor.width.roundToInt().coerceAtLeast(1), anchor.height.roundToInt().coerceAtLeast(1),
        ))
        val target = Rect(0f, 0f, contentPlaceable.width.toFloat(), contentPlaceable.height.toFloat())
        val frame = glassMenuFrame(anchor, target, progress)
        val width = constraints.constrainWidth(frame.bounds.width.roundToInt())
        val height = constraints.constrainHeight(frame.bounds.height.roundToInt())
        layout(width, height) {
            contentPlaceable.place(
                ((width - contentPlaceable.width) * pivot.pivotFractionX).roundToInt(),
                ((height - contentPlaceable.height) * pivot.pivotFractionY).roundToInt(),
            )
            triggerPlaceable?.place((width - triggerPlaceable.width) / 2, (height - triggerPlaceable.height) / 2)
        }
    }
}
