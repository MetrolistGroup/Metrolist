package com.metrolist.android.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import com.metrolist.android.ui.theme.colorPalette
import com.metrolist.android.ui.theme.uiRoundnessShape
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCanvasBackdrop
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

private data class GlassGroupMenuMotion(
    val trigger: GlassMenuTriggerState,
    val triggerIndex: Int,
    val compact: Float,
    val shift: Float,
    val middle: Boolean,
    val rightShift: Float,
    val mirrored: Boolean,
) {
    fun offset(index: Int? = null): Int {
        if (index == triggerIndex) return 0
        val anchor = trigger.bounds
        val cell = anchor.width
        if (middle && index != null) return glassGroupPhysicalOffset(if (index < triggerIndex) shift else rightShift, mirrored)
        val slotShift = if (index != null && index > triggerIndex) -cell else 0f
        return glassGroupPhysicalOffset(shift + slotShift * compact, mirrored)
    }
}

internal fun glassGroupPhysicalOffset(logical: Float, mirrored: Boolean): Int =
    if (mirrored) -logical.roundToInt() else logical.roundToInt()

internal val LocalGlassActionGroupSurface = staticCompositionLocalOf<Color?> { null }

private val LocalGlassGroupMenuMotion = staticCompositionLocalOf<GlassGroupMenuMotion?> { null }

/** A single optical capsule with independently clickable, animated action cells. */
@Composable
fun GlassActionGroup(
    settings: GlassSettings,
    area: GlassArea,
    modifier: Modifier = Modifier,
    trailingTrigger: GlassMenuTriggerState? = null,
    menuActionCount: Int? = null,
    menuTriggerIndex: Int = 1,
    separatorBefore: Set<Int>? = null,
    drawSurface: Boolean = true,
    leadingActionJoinProgress: Float? = null,
    filmColor: Color? = null,
    detachRemainingActionsOnMenu: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val cellSize = LocalGlassActionCellSize.current
    val shape = uiRoundnessShape()
    val transferred = trailingTrigger?.isTransferred == true
    val singleMenuAction = trailingTrigger != null && menuActionCount == 1
    val rejoin = remember { Animatable(if (transferred) 0f else 1f) }
    val motion = isGlassMotionAllowed()
    val backgroundColor = colorPalette().background0
    val drawBackground: DrawScope.() -> Unit = remember(backgroundColor) { { drawRect(backgroundColor) } }
    // Home actions live inside the page capture. A themed canvas is safe to sample
    // when no independent header backdrop is supplied, without capturing ourselves.
    val fallbackBackdrop = rememberCanvasBackdrop(drawBackground)
    val backdrop = LocalGlassContainerBackdrop.current ?: LocalGlassBackdrop.current ?: fallbackBackdrop
    val actionFilm = filmColor ?: if (area == GlassArea.ActionButtons) glassActionFilmColor() else null
    val progress = if (transferred) 0f else if (!motion || singleMenuAction) 1f else rejoin.value
    val middleTrigger = menuActionCount != null && menuTriggerIndex > 0 && menuTriggerIndex < menuActionCount - 1
    SideEffect { trailingTrigger?.menuAnchorFraction = if (middleTrigger) .5f else 1f }
    val leadingJoin = !transferred && progress == 1f && leadingActionJoinProgress != null
    val joinProgress = if (leadingJoin) leadingActionJoinProgress ?: progress else progress
    val joining = joinProgress < 1f
    var rootWidth by remember { mutableIntStateOf(0) }
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    val targetShift = trailingTrigger?.let { trigger ->
        val anchor = trigger.bounds
        val count = menuActionCount ?: 2
        val anchorStart = if (mirrored) rootWidth - anchor.right else anchor.left
        val groupLeft = anchorStart - menuTriggerIndex * anchor.width
        val packedRight = groupLeft + (count - 1) * anchor.width
        trigger.menuBounds?.let { menu ->
            val menuStart = if (mirrored) rootWidth - menu.right else menu.left
            val requested = if (middleTrigger) menuStart - anchorStart else menuStart - packedRight
            // A neighbor acknowledges the opening; it must not travel around the whole menu.
            requested.coerceIn(-minOf(anchor.width / 4f, groupLeft.coerceAtLeast(0f)), 0f)
        } ?: 0f
    } ?: 0f
    val targetRightShift = if (middleTrigger) trailingTrigger?.let { trigger ->
        trigger.menuBounds?.let { menu ->
            val anchor = trigger.bounds
            val anchorEnd = if (mirrored) rootWidth - anchor.left else anchor.right
            val menuEnd = if (mirrored) rootWidth - menu.left else menu.right
            val groupRight = anchorEnd + ((menuActionCount ?: 2) - menuTriggerIndex - 1) * anchor.width
            (menuEnd - anchorEnd).coerceIn(0f,
                minOf(anchor.width / 4f, (rootWidth - groupRight).coerceAtLeast(0f)))
        }
    } ?: 0f else 0f
    val neighborShift = remember { Animatable(0f) }
    val rightShift = remember { Animatable(0f) }
    LaunchedEffect(targetShift, targetRightShift, transferred, motion, singleMenuAction) {
        if (!motion || singleMenuAction) {
            neighborShift.snapTo(targetShift)
            rightShift.snapTo(targetRightShift)
            rejoin.snapTo(if (transferred) 0f else 1f)
        } else if (transferred) {
            rejoin.snapTo(0f)
            kotlinx.coroutines.coroutineScope {
                launch { neighborShift.animateTo(targetShift, spring(1f, Spring.StiffnessLow)) }
                launch { rightShift.animateTo(targetRightShift, spring(1f, Spring.StiffnessLow)) }
            }
        } else {
            // Return every button to its own seat before joining their resting surfaces.
            kotlinx.coroutines.coroutineScope {
                // Pixel offsets need not wait for the default subpixel spring tail.
                launch { neighborShift.animateTo(0f, spring(1f, Spring.StiffnessMediumLow, visibilityThreshold = 1f)) }
                launch { rightShift.animateTo(0f, spring(1f, Spring.StiffnessMediumLow, visibilityThreshold = 1f)) }
            }
            rejoin.animateTo(1f, glassGroupRejoinSpec())
        }
    }
    val menuMotion = if (trailingTrigger != null && menuActionCount != null)
        GlassGroupMenuMotion(trailingTrigger, menuTriggerIndex, 1f - rejoin.value,
            neighborShift.value, middleTrigger, rightShift.value, mirrored) else null
    val cellWidthPx = with(LocalDensity.current) { cellSize.width.toPx() }
    val joinShape = remember(shape, cellWidthPx, joinProgress, leadingJoin) {
        if (leadingJoin) glassActionJoinShape(shape, cellWidthPx, joinProgress, fixedTrailingCell = true)
        else glassGroupFusionShape(shape, cellWidthPx, joinProgress)
    }
    GlassActionGroupMotionHost(
        settings = settings,
        enabled = settings.usesGlassMaterial(area) && motion && !transferred,
        modifier = modifier.onGloballyPositioned { rootWidth = it.findRootCoordinates().size.width },
    ) {
        val returning = !transferred && rejoin.value < 1f
        val separated = menuActionCount != null && ((middleTrigger || detachRemainingActionsOnMenu) && transferred || returning &&
            (kotlin.math.abs(neighborShift.value) > .5f || kotlin.math.abs(rightShift.value) > .5f))
        if (separated && drawSurface) {
            repeat(menuActionCount ?: 0) { index ->
                if (index != menuTriggerIndex || !transferred) {
                    val cellSurface = Modifier.absoluteOffset {
                        IntOffset(glassGroupPhysicalOffset(index * cellWidthPx, mirrored) + (menuMotion?.offset(index) ?: 0), 0)
                    }.size(cellSize.width, cellSize.height).clip(shape)
                    Box(if (settings.usesGlassMaterial(area)) cellSurface.glassMaterial(
                        settings.forArea(area), shape, ownBackdrop = backdrop, filmColor = actionFilm)
                        else cellSurface.glassAreaContour(settings, area, shape))
                }
            }
        }
        // The menu owns the trailing cell while transferred. Keep its layout slot but
        // remove that part of the resting surface, including its rim and capture.
        val surface = Modifier.matchParentSize().absoluteOffset {
            IntOffset(menuMotion?.offset() ?: glassGroupPhysicalOffset(neighborShift.value, mirrored), 0)
        }.layout { measurable, constraints ->
            val cellWidth = cellSize.width.roundToPx()
            val restingWidth = if (menuActionCount != null) constraints.maxWidth - cellWidth else cellWidth
            val width = if (singleMenuAction) constraints.maxWidth
                else if (returning) constraints.maxWidth
                else glassActionGroupSurfaceWidth(constraints.maxWidth, restingWidth, progress, transferred)
            val pane = measurable.measure(Constraints.fixed(width, constraints.maxHeight))
            // Only the drawn pane changes width; the action row and source anchor stay fixed.
            layout(constraints.maxWidth, constraints.maxHeight) { pane.placeRelative(0, 0) }
        }
        // Keep the CornerBasedShape in the optical renderer (its lens needs it),
        // then mask only that pane. Glyphs and source anchors live in the untouched row.
        val pane = surface.clip(joinShape)
        val background = if (settings.usesGlassMaterial(area)) {
            pane.glassMaterial(settings.forArea(area), shape, ownBackdrop = backdrop, filmColor = actionFilm,
                highlightAlpha = if (joining) 0f else .3f)
                .then(if (joining && settings.usesGlassContour(area))
                    Modifier.glassEdgeRim(settings, joinShape, glassTint(settings)) else Modifier)
        } else pane.glassAreaContour(settings, area, joinShape)
        // Retain its measured optical surface so returning from the menu needs no capture warm-up.
        if (drawSurface && !separated) Box(Modifier.graphicsLayer {
            alpha = if (singleMenuAction && trailingTrigger?.isTransferred == true) 0f else 1f
        }.then(background)
            .glassActionGroupSeparators(settings, area, cellSize.width,
                progress = { progress }, transferred = { transferred }, separatorBefore = separatorBefore))
        CompositionLocalProvider(LocalGlassGroupMenuMotion provides menuMotion) {
            // Cell feedback stays inside the connected pane. This mask is INSIDE the
            // whole-group motion host, so the complete capsule can still overdraw its slot.
            // Detached menu cells retain their individual masks and moving seats.
            Row(modifier = if (settings.usesGlassMaterial(area) && !separated && !transferred) Modifier.clip(joinShape) else Modifier,
                verticalAlignment = Alignment.CenterVertically, content = {
                    val row = this
                    if (drawSurface && settings.usesGlassMaterial(area)) {
                        val areaSettings = settings.forArea(area)
                        val film = glassFilmColor(areaSettings, actionFilm, menu = false)
                        val surface = effectiveSurfaceColor(surfaceBackdropColor(), film)
                        CompositionLocalProvider(LocalGlassActionGroupSurface provides surface) {
                            SurfaceForegroundContent(surface, glassInkDarkness = glassSurfaceDarkness(areaSettings)) {
                                content(row)
                            }
                        }
                    } else GlassArtworkContent(settings, settings.usesGlassMaterial(area)) { content(row) }
                })
        }
    }
}

internal fun glassActionGroupSurfaceWidth(fullWidth: Int, cellWidth: Int, progress: Float, transferred: Boolean): Int {
    if (transferred) return cellWidth.coerceAtMost(fullWidth)
    val fraction = if (progress.isFinite()) progress else 1f
    return (cellWidth + (fullWidth - cellWidth) * fraction.coerceIn(0f, 1f)).roundToInt()
        .coerceIn(cellWidth.coerceAtMost(fullWidth), fullWidth)
}

/** An action inside a shared surface; it never draws a second material or rim. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun GlassActionGroupItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    menuTrigger: GlassMenuTriggerState? = null,
    groupIndex: Int? = null,
    enabled: Boolean = true,
    nativeIndication: Boolean = false,
    retainGesture: Boolean = true,
    fillCell: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = toolbarItemShape(),
    content: @Composable BoxScope.() -> Unit,
) {
    val cellSize = LocalGlassActionCellSize.current
    val indication = if (nativeIndication) LocalIndication.current else null
    val menuMotion = LocalGlassGroupMenuMotion.current
    val latestContent = rememberUpdatedState(content)
    val sourceLocals = currentCompositionLocalContext
    if (menuTrigger != null) {
        val source: @Composable () -> Unit = remember(sourceLocals) {
            { CompositionLocalProvider(sourceLocals) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = latestContent.value) } }
        }
        SideEffect {
            menuTrigger.content = source
            menuTrigger.shape = shape
        }
    }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.then(if (groupIndex != null && menuMotion != null)
            Modifier.absoluteOffset { IntOffset(menuMotion.offset(groupIndex), 0) } else Modifier)
            .then(if (fillCell) Modifier.size(cellSize.width, cellSize.height) else Modifier)
            .glassMenuTrigger(menuTrigger)
            .then(if (nativeIndication) Modifier.clip(shape) else Modifier)
            .glassGroupPressMotion(interaction, enabled && !nativeIndication,
                retainPressUntilUp = retainGesture)
            .retainGlassButtonGesture(enabled && retainGesture && !nativeIndication)
            .then(if (onLongClick == null) Modifier.clickable(interactionSource = interaction,
                indication = indication, enabled = enabled, role = Role.Button, onClick = onClick)
            else Modifier.combinedClickable(interactionSource = interaction, indication = indication,
                enabled = enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick)),
        contentAlignment = Alignment.Center,
    ) {
        // Keep the click target and menu source anchor fixed while the cell's drawing responds.
        Box((if (fillCell) Modifier.fillMaxSize() else Modifier)
            .glassButtonFeedback(interaction, shape, enabled && !nativeIndication, clipToBounds = true,
                retainPressUntilUp = retainGesture, animateMotion = LocalGlassGroupPressMotion.current == null),
            contentAlignment = Alignment.Center, content = content)
    }
}

/** Retains native content, size and indication while sharing the capsule's neighbor motion. */
@Composable
fun GlassActionGroupNativeItem(
    index: Int,
    content: @Composable () -> Unit,
) {
    val motion = LocalGlassGroupMenuMotion.current
    Box(Modifier.absoluteOffset { IntOffset(motion?.offset(index) ?: 0, 0) }) { content() }
}

/** At rest the cells touch; the liquid neck opens only after their motion has finished. */
internal fun glassGroupRejoinSpec() = tween<Float>(160, easing = FastOutSlowInEasing)

internal fun glassGroupFusionShape(base: Shape, cell: Float, progress: Float, vertical: Boolean = false): Shape {
    if (progress >= 1f) return base
    val phase = progress.coerceIn(0f, 1f)
    return glassImageShapeIntersection(base, object : Shape {
        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
            val size = if (vertical) Size(size.height, size.width) else size
            val depth = size.height * .5f * (1f - phase)
            val span = cell * .5f
            val path = Path().apply {
                moveTo(0f, 0f)
                var seam = cell
                while (seam < size.width - 1f) {
                    lineTo(seam - span, 0f)
                    cubicTo(seam - span * .3f, 0f, seam - span * .3f, depth, seam, depth)
                    cubicTo(seam + span * .3f, depth, seam + span * .3f, 0f, seam + span, 0f)
                    seam += cell
                }
                lineTo(size.width, 0f); lineTo(size.width, size.height)
                seam = ((size.width / cell).toInt() - 1) * cell
                while (seam > 0f) {
                    lineTo(seam + span, size.height)
                    cubicTo(seam + span * .3f, size.height, seam + span * .3f, size.height - depth, seam, size.height - depth)
                    cubicTo(seam - span * .3f, size.height - depth, seam - span * .3f, size.height, seam - span, size.height)
                    seam -= cell
                }
                lineTo(0f, size.height); close()
            }
            if (vertical) path.transform(glassAxisTranspose())
            return Outline.Generic(path)
        }
    })
}
