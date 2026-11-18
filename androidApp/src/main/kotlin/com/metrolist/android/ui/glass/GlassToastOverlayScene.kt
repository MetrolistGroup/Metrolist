package com.metrolist.android.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.metrolist.android.ui.glass.backdrop.Backdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.layerBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCombinedBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberLayerBackdrop

/**
 * A toast samples the completed same-window overlays above the scene. The recording
 * contains only overlay siblings; neither their scene source nor the toast can
 * enter it. Keep capture active through retained overlay exit animations.
 */
@Composable
fun GlassToastOverlayScene(
    backdrop: Backdrop?,
    captureOverlays: Boolean,
    overlays: @Composable BoxScope.() -> Unit,
    toast: @Composable (Backdrop?) -> Unit,
) {
    val overlayBackdrop = rememberLayerBackdrop()
    val capture = captureOverlays && backdrop != null
    val completed = if (capture)
        rememberCombinedBackdrop(backdrop, overlayBackdrop) else backdrop
    // The content group stays mounted when recording is enabled or disabled.
    Box(Modifier.fillMaxSize().then(if (capture) Modifier.layerBackdrop(overlayBackdrop) else Modifier),
        content = overlays)
    toast(completed)
}
