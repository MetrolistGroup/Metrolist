package com.metrolist.android.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import com.metrolist.android.ui.glass.backdrop.Backdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.layerBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCombinedBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberLayerBackdrop

/** Artwork-only export below a lyrics/visualizer overlay, never the player foreground or its controls. */
val LocalGlassOverlayArtworkBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Records the actual local overlay background and scene in a sibling excluding every utility button. */
@Composable
fun GlassOverlayScene(
    background: Color,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    sceneModifier: Modifier = Modifier,
    scope: GlassScope = LocalGlassScope.current,
    scene: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val localScene = rememberLayerBackdrop()
    val capture = glassContentCaptureEnabled(rememberGlassSettings(scope))
    // Full-screen overlays without artwork reveal the clean player background; thumbnail
    // overlays explicitly supply their artwork sibling instead of inheriting that background.
    val underlay = LocalGlassOverlayArtworkBackdrop.current ?: LocalGlassBackdrop.current
    val backdrop = if (!capture) underlay else if (underlay != null)
        rememberCombinedBackdrop(underlay, localScene) else localScene
    Box(modifier, contentAlignment = contentAlignment) {
        Box(Modifier.matchParentSize().then(sceneModifier)
            .then(if (capture) Modifier.layerBackdrop(localScene) else Modifier)
            .background(background).testTag("glass.overlay.scene.background"), content = scene)
        CompositionLocalProvider(
            LocalGlassContainerBackdrop provides backdrop,
            LocalGlassMaterialSource provides backdrop,
            LocalGlassScope provides scope,
        ) { content() }
    }
}
