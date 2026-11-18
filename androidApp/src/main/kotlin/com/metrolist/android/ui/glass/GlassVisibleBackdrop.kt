package com.metrolist.android.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.metrolist.android.ui.glass.backdrop.Backdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.layerBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.LayerBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberLayerBackdrop

/** Completed visible surface for overlays opened by descendants, separate from control-safe material. */
val LocalGlassOverlayBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Bounded player surfaces retain the whole scene unless a fully covering player owns it. */
@Composable
fun glassSceneOverlayBackdrop(surfaceFallback: Backdrop): Backdrop =
    LocalGlassMenuHost.current?.let { it.foregroundBackdrop ?: it.sceneBackdrop } ?: surfaceFallback

private val LocalGlassSceneNeedsLiveBackdrop = staticCompositionLocalOf { false }

/**
 * A fully expanded player owns a separate live display list. Its controls still sample their
 * background-only source; modal glass samples the completed player, excluding navigation and
 * the sibling overlay hosts. Clearing ownership on collapse restores the whole-scene source.
 */
@Composable
fun Modifier.glassPlayerSceneSource(backdrop: LayerBackdrop, active: Boolean,
    fullyCovering: Boolean = active): Modifier {
    val host = LocalGlassMenuHost.current
    val live = LocalGlassSceneNeedsLiveBackdrop.current
    SideEffect {
        if (active && fullyCovering) host?.foregroundBackdrop = backdrop
        else if (host?.foregroundBackdrop === backdrop) host.foregroundBackdrop = null
    }
    DisposableEffect(host, backdrop) {
        onDispose { if (host?.foregroundBackdrop === backdrop) host.foregroundBackdrop = null }
    }
    val frozen = remember(live) { { !live } }
    return if (active) layerBackdrop(backdrop, frozen = frozen) else this
}

/** Resolve at the owner before its content is moved to the global overlay host. */
@Composable
internal fun currentGlassOverlayBackdrop(): Backdrop? =
    LocalGlassOverlayBackdrop.current ?: LocalGlassMenuHost.current?.modalBackdrop ?: LocalGlassBackdrop.current

/**
 * Capture the complete live app scene while an overlay needs its pixels.
 * Overlay hosts must be siblings outside this content to avoid recursive sampling.
 * [refreshKey] invalidates the recording modifier when an owner's scene changes.
 */
@Composable
fun GlassSceneContent(
    host: GlassMenuHostState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    refreshKey: Any? = null,
    needsLiveBackdrop: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val active = host.hasOverlays || needsLiveBackdrop
    val scene = rememberLayerBackdrop()
    val frozen = remember(active, refreshKey) { { !active } }
    SideEffect { host.sceneBackdrop = if (enabled) scene else null }
    DisposableEffect(host, scene) {
        onDispose { if (host.sceneBackdrop === scene) host.sceneBackdrop = null }
    }
    CompositionLocalProvider(LocalGlassSceneNeedsLiveBackdrop provides active) {
        Box(Modifier.fillMaxSize()
            // Walking the activity would record the covered Home tree on every menu frame.
            // The foreground keeps drawing live through Compose's ordinary incremental path.
            .then(if (enabled && host.foregroundBackdrop == null) Modifier.layerBackdrop(scene, frozen = frozen) else Modifier)
            .then(modifier), content = content)
    }
}

