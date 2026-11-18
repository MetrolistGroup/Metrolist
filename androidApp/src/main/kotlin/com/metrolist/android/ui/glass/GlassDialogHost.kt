package com.metrolist.android.ui.glass

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.ui.window.DialogProperties
import com.metrolist.android.ui.component.animateDialogMotion
import com.metrolist.android.ui.component.NativeDialogDimming
import com.metrolist.android.ui.component.LocalDialogPresentation
import com.metrolist.android.ui.component.dialogFadeAlpha
import com.metrolist.android.ui.component.dialogScale
import com.metrolist.android.ui.glass.backdrop.Backdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.layerBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCombinedBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberLayerBackdrop

@Stable
internal class GlassDialogEntry(
    val id: Any,
    val locals: () -> CompositionLocalContext,
    val onDismiss: () -> Unit,
    val properties: () -> DialogProperties,
    val edgeToEdge: () -> Boolean,
    val content: @Composable () -> Unit,
    val backdrop: () -> Backdrop?,
    val dimmingAlpha: () -> Float,
    val scrimProgress: () -> Float,
    val scrimColor: () -> Color = { Color.Black },
    val contentAlpha: () -> Float = { 1f },
) {
    internal val exposure = Animatable(1f)
}

/** Dialogs are registered by their owners and drawn outside the captured page. */
@Stable
class GlassDialogHostState {
    internal val entries = mutableStateListOf<GlassDialogEntry>()
    internal var windowRoot by mutableStateOf<View?>(null)
    val hasDialogs: Boolean get() = entries.isNotEmpty()

    internal fun add(entry: GlassDialogEntry) { entries.add(entry) }
    internal fun remove(id: Any) { entries.removeAll { it.id == id } }
}

internal fun canHostGlassDialog(settings: GlassSettings, renderer: GlassRenderer, hasBackdrop: Boolean): Boolean =
    settings.usesGlassMaterial(GlassArea.Dialogs) && renderer != GlassRenderer.Tint && hasBackdrop

internal fun canHostGlassMenuSheet(settings: GlassSettings, renderer: GlassRenderer, hasBackdrop: Boolean): Boolean =
    settings.usesGlassMaterial(GlassArea.Menus) && renderer != GlassRenderer.Tint && hasBackdrop

/** Uses the Android window whenever the shared host cannot sample a compatible backdrop. */
@Composable
internal fun GlassDialogWindow(
    useSharedHost: Boolean,
    onDismissRequest: () -> Unit,
    properties: DialogProperties,
    edgeToEdge: Boolean = false,
    area: GlassArea = GlassArea.Dialogs,
    scrimVisible: Boolean = true,
    scrimColor: Color = Color.Unspecified,
    scrimAnimation: Animatable<Float, AnimationVector1D>? = null,
    content: @Composable () -> Unit,
) {
    val host = LocalGlassMenuHost.current
    val presentation = LocalDialogPresentation.current
    val settings = rememberGlassSettings()
    if (!useSharedHost || host == null || properties.securePolicy != SecureFlagPolicy.Inherit) {
        val id = remember { Any() }
        NativeGlassOverlayCoverage(host, id)
        Dialog(onDismissRequest, properties = properties) {
            val transparentSource = app.n_zik.android.components.glass.backdrop.backdrops.rememberCanvasBackdrop {}
            CompositionLocalProvider(
                LocalGlassBackdrop provides null,
                LocalGlassOverlayBackdrop provides null,
                LocalGlassContainerBackdrop provides transparentSource,
                LocalGlassMaterialSource provides transparentSource,
                LocalGlassOpticsEnabled provides true,
                LocalGlassOpticalUnderlayCovered provides (host != null && host.overlaysAbove(id).isNotEmpty()),
                LocalGlassOpticsTransitionProgress provides 1f,
                LocalGlassLiveFrame provides rememberGlassModalLiveFrameIfEnabled(
                    covered = host != null && host.nativeOverlays.lastOrNull() !== id),
                LocalGlassUnderlayCovered provides (host?.visuallyCovered(id) == true),
                LocalGlassOverlayPaintScope provides host?.let { GlassOverlayPaintScope(it, id) {
                    presentation?.motion?.value?.let(::dialogFadeAlpha) ?: 1f
                } },
            ) {
                if (presentation == null || (useSharedHost && host != null && properties.securePolicy != SecureFlagPolicy.Inherit)) {
                    NativeDialogDimming(presentation, area, scrimColor, scrimVisible)
                }
                val nativeScrim = if (!scrimVisible) 0f else if (scrimColor.isSpecified) scrimColor.alpha
                    else if (area == GlassArea.Menus) settings.menuDimming else settings.dialogDimming
                val backdrop = effectiveSurfaceColor(surfaceBackdropColor(), Color.Transparent,
                    Color.Black.copy(alpha = nativeScrim * (presentation?.motion?.value?.let(::dialogFadeAlpha) ?: 1f)))
                CompositionLocalProvider(LocalSurfaceBackdropColor provides backdrop) {
                    GlassRippleProvider(settings) { GlassArtworkContent(settings, settings.usesGlassMaterial(area)) { content() } }
                }
            }
        }
        return
    }
    val id = remember { Any() }
    val currentDimming = rememberUpdatedState(if (scrimColor.isSpecified) 1f
        else if (area == GlassArea.Menus) settings.menuDimming else settings.dialogDimming)
    val currentScrimColor = rememberUpdatedState(if (scrimColor.isSpecified) scrimColor else Color.Black)
    val scrimMotion = scrimAnimation ?: remember { Animatable(0f) }
    val motionAllowed = isGlassMotionAllowed()
    LaunchedEffect(presentation, scrimVisible, motionAllowed) {
        if (presentation == null) {
            val target = if (scrimVisible) 1f else 0f
            if (motionAllowed) animateDialogMotion(scrimMotion, target) else scrimMotion.snapTo(target)
        }
    }
    val currentScrimProgress = rememberUpdatedState<() -> Float> {
        presentation?.motion?.value ?: scrimMotion.value
    }
    val locals = currentCompositionLocalContext
    val currentLocals = rememberUpdatedState(locals)
    val currentContent = rememberUpdatedState<@Composable () -> Unit> {
        GlassArtworkContent(settings, settings.usesGlassMaterial(area)) { content() }
    }
    val currentDismiss = rememberUpdatedState(onDismissRequest)
    val currentProperties = rememberUpdatedState(properties)
    val currentEdgeToEdge = rememberUpdatedState(edgeToEdge)
    val currentBackdrop = rememberUpdatedState(currentGlassOverlayBackdrop() ?: LocalIndependentGlassBackdrop.current)
    val currentDropdownOwner = rememberUpdatedState(LocalGlassDropdownBackdropOwner.current)
    val currentPresentation = rememberUpdatedState(presentation)
    DisposableEffect(host, id) {
        host.dialogs.add(GlassDialogEntry(id, { currentLocals.value }, { currentDismiss.value() },
            { currentProperties.value }, { currentEdgeToEdge.value }, { currentContent.value() }, {
                val owner = currentDropdownOwner.value
                if (owner != null) owner.resolve(currentBackdrop.value) else currentBackdrop.value
            }, { currentDimming.value }, { currentScrimProgress.value() }, { currentScrimColor.value },
            { currentPresentation.value?.motion?.value?.let(::dialogFadeAlpha) ?: 1f }))
        onDispose { host.dialogs.remove(id); host.visibility.removeOverlay(id) }
    }
}

/** Removes covered content from accessibility and keyboard traversal while keeping it drawn. */
@Composable
fun GlassModalContent(
    host: GlassMenuHostState,
    manageFocus: Boolean = true,
    focusSessionKey: Any? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val blocked = host.hasOverlays
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var previouslyBlocked by remember { mutableStateOf(false) }
    var blockedSession by remember { mutableStateOf(focusSessionKey) }
    var savedPageFocus by remember { mutableStateOf(false) }
    LaunchedEffect(blocked, manageFocus, focusSessionKey) {
        if (blocked && !previouslyBlocked) {
            blockedSession = focusSessionKey
            // Save before the modal takes focus, never during its disposal handoff.
            savedPageFocus = manageFocus && focus.saveFocusedChild()
        }
        if (manageFocus && previouslyBlocked && !blocked) {
            val restored = savedPageFocus && blockedSession == focusSessionKey && focus.restoreFocusedChild()
            // Removing the modal can automatically focus the page's first input.
            if (!restored) focusManager.clearFocus(force = true)
        }
        previouslyBlocked = blocked
    }
    val viewportId = remember { Any() }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    val view = LocalView.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing
    val safeTop = safe.getTop(density)
    val safeLeft = safe.getLeft(density, direction)
    val safeRight = safe.getRight(density, direction)
    DisposableEffect(host, viewportId) { onDispose { host.visibility.viewports.remove(viewportId) } }
    val covered = LocalGlassUnderlayCovered.current || host.visuallyCovered(viewport = viewport)
    val cheapOptics = LocalGlassOpticalUnderlayCovered.current || blocked
    CompositionLocalProvider(LocalGlassUnderlayCovered provides covered,
        LocalGlassOpticalUnderlayCovered provides cheapOptics) {
        Box(Modifier.fillMaxSize().onGloballyPositioned {
            val bounds = it.glassBoundsOnScreen(view)
            val rootOrigin = IntArray(2)
            view.rootView.getLocationOnScreen(rootOrigin)
            viewport = Rect(maxOf(bounds.left, rootOrigin[0] + safeLeft.toFloat()),
                maxOf(bounds.top, rootOrigin[1] + safeTop.toFloat()),
                minOf(bounds.right, rootOrigin[0] + view.rootView.width - safeRight.toFloat()), bounds.bottom)
            host.visibility.viewports[viewportId] = viewport
        // Read after layout: the first lowered frame must draw newly exposed content,
        // even before the physical-coverage local is recomposed for animation consumers.
        }.drawWithContent { if (!host.visuallyCovered(viewport = viewport)) drawContent() }.blockGlassCoveredInput(blocked)
            .then(if (manageFocus) Modifier
                .then(if (blocked) Modifier.clearAndSetSemantics { } else Modifier)
                .focusRequester(focus)
                .focusProperties {
                    onEnter = {
                        if (blocked || (previouslyBlocked &&
                                (!savedPageFocus || blockedSession != focusSessionKey)) ||
                            (savedPageFocus && blockedSession == focusSessionKey && focus.restoreFocusedChild())) {
                            cancelFocusChange()
                        }
                    }
                }
                .focusGroup() else Modifier), content = content)
    }
}

/** The top dialog owns input; retained lower dialogs remain visible for nested dismissal. */
@Composable
fun GlassDialogOverlayHost(host: GlassMenuHostState) {
    val settings = rememberGlassSettings()
    val view = LocalView.current
    SideEffect { host.dialogs.windowRoot = view.rootView }
    DisposableEffect(host) { onDispose { host.dialogs.windowRoot = null } }
    host.dialogs.entries.forEach { entry ->
        key(entry.id) {
            val source = entry.backdrop() ?: host.modalBackdrop
            val dimmingAlpha = entry.dimmingAlpha() * entry.scrimProgress().coerceIn(0f, 1f)
            val scrimColor = entry.scrimColor()
            val dimmedSource = rememberGlassDimmedBackdrop(source, dimmingAlpha, scrimColor)
            val visibleDialog = rememberLayerBackdrop()
            val completedBackdrop = if (source != null) rememberCombinedBackdrop(source, visibleDialog) else visibleDialog
            val topmostSharedOverlay = entry === host.dialogs.entries.lastOrNull() && host.entry?.visible != true
            val topmost = topmostSharedOverlay && host.nativeOverlays.isEmpty()
            val properties = entry.properties()
            val focus = remember { FocusRequester() }
            LaunchedEffect(topmost) { if (topmost) focus.requestFocus() }
            LaunchedEffect(topmostSharedOverlay, settings.optimizationEnabled) {
                animateDialogMotion(entry.exposure, if (topmostSharedOverlay || !settings.optimizationEnabled) 1f else 0f)
            }
            BackHandler(enabled = topmost) {
                if (properties.dismissOnBackPress) entry.onDismiss()
            }
            GlassDialogEntryLocals(entry) {
                val backdrop = effectiveSurfaceColor(surfaceBackdropColor(), Color.Transparent,
                    scrimColor.copy(alpha = scrimColor.alpha * dimmingAlpha))
                CompositionLocalProvider(
                    LocalSurfaceBackdropColor provides backdrop,
                    LocalGlassOpticsEnabled provides true,
                    LocalGlassOpticalUnderlayCovered provides host.overlaysAbove(entry.id).isNotEmpty(),
                    LocalGlassOpticsTransitionProgress provides 1f,
                    LocalGlassLiveFrame provides rememberGlassModalLiveFrameIfEnabled(
                        covered = entry !== host.dialogs.entries.lastOrNull() || host.entry != null || host.nativeOverlays.isNotEmpty()),
                    LocalGlassUnderlayCovered provides host.visuallyCovered(entry.id),
                    LocalGlassOverlayPaintScope provides GlassOverlayPaintScope(host, entry.id) {
                        entry.contentAlpha() * dialogFadeAlpha(entry.exposure.value)
                    },
                    LocalGlassBackdrop provides dimmedSource,
                    LocalIndependentGlassBackdrop provides null,
                    LocalGlassMaterialSource provides dimmedSource,
                    LocalGlassOverlayBackdrop provides completedBackdrop,
                    LocalGlassDropdownBackdropOwner provides null,
                ) {
                    Box(Modifier.fillMaxSize().layerBackdrop(visibleDialog)
                        .testTag("glassDialogOverlay")
                        .blockGlassCoveredInput(!topmost)
                        .onPreviewKeyEvent { !topmost }
                        .then(if (topmost) Modifier.semantics { dialog() }
                            else Modifier.clearAndSetSemantics { })
                        .focusRequester(focus)
                        .focusProperties {
                            onEnter = {
                                if (!topmost || focus.restoreFocusedChild()) cancelFocusChange()
                            }
                            onExit = {
                                focus.saveFocusedChild()
                                if (topmost) cancelFocusChange()
                            }
                        }
                        .focusGroup()) {
                        Box(Modifier.matchParentSize()
                            .testTag("glassDialogScrim")
                            .glassScrimInput()
                            .background(scrimColor.copy(alpha = scrimColor.alpha * dimmingAlpha))
                            .clickable(indication = null,
                                interactionSource = remember { MutableInteractionSource() }) {
                                if (topmost && properties.dismissOnClickOutside) entry.onDismiss()
                            }.clearAndSetSemantics { })
                        Box(Modifier.fillMaxSize()
                            .then(if (entry.edgeToEdge()) Modifier else Modifier.safeDrawingPadding())
                            .imePadding()
                            .graphicsLayer {
                                alpha = dialogFadeAlpha(entry.exposure.value)
                                scaleX = dialogScale(entry.exposure.value)
                                scaleY = scaleX
                            },
                            contentAlignment = Alignment.Center) {
                            entry.content()
                        }
                    }
                }
            }
        }
    }
}

/** Native windows reduce background optics until their animated content is disposed. */
@Composable
internal fun NativeGlassOverlayCoverage(host: GlassMenuHostState?, id: Any) {
    DisposableEffect(host, id) {
        host?.nativeOverlays?.add(id)
        onDispose { host?.nativeOverlays?.remove(id); host?.visibility?.removeOverlay(id) }
    }
}

/** A child updating captured locals must not recompose and invalidate its parent dialog. */
@Composable
private fun GlassDialogEntryLocals(entry: GlassDialogEntry, content: @Composable () -> Unit) {
    CompositionLocalProvider(entry.locals(), content)
}
