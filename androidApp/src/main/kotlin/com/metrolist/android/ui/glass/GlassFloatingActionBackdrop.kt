package com.metrolist.android.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.android.ui.theme.colorPalette
import com.metrolist.android.ui.glass.backdrop.Backdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.LayerBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.layerBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import com.metrolist.android.utils.toast.BottomNavigationToastPlacement

/** A live list capture owned by the page, with floating controls drawn as separate siblings. */
@Composable
fun rememberGlassFloatingActionBackdrop(settings: GlassSettings = rememberGlassSettings()): LayerBackdrop? {
    val backdrop = rememberLayerBackdrop()
    val capture = glassContentCaptureEnabled(settings)
    return backdrop.takeIf { settings.usesGlassMaterial(GlassArea.ActionButtons) && capture }
}

/** Apply only to the content sibling: capturing the actions themselves would create feedback. */
@Composable
fun Modifier.glassFloatingActionContent(backdrop: LayerBackdrop?): Modifier =
    if (backdrop == null) this else layerBackdrop(backdrop).background(colorPalette().background0)

/** Export the content capture only to the floating actions that sit outside it. */
@Composable
fun GlassFloatingActions(backdrop: Backdrop?, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalGlassContainerBackdrop provides (backdrop ?: LocalGlassContainerBackdrop.current),
        LocalGlassMaterialSource provides (backdrop ?: LocalGlassMaterialSource.current),
        content = content,
    )
}

/** Floating actions clear the destination's chrome in both native and Glass modes. */
@Composable
fun glassFloatingActionsBottomPadding(
    nativePadding: Dp,
    windowInsets: WindowInsets,
): Dp {
    val density = LocalDensity.current
    val safeInsets = windowInsets.union(WindowInsets.safeDrawing)
    val contentBottom = with(density) { safeInsets.getBottom(density).toDp() }
    val contentCoordinates = (LocalGlassContainerBackdrop.current as? LayerBackdrop)?.layerCoordinates
        ?.takeIf { it.isAttached }
    val chromeTop = BottomNavigationToastPlacement.topInWindow()
    val measuredBottom = if (contentCoordinates != null && chromeTop != null) {
        with(density) {
            (contentCoordinates.boundsInWindow().bottom - chromeTop).coerceAtLeast(0f).toDp() + 8.dp
        }
    } else 0.dp
    return maxOf(nativePadding, contentBottom, measuredBottom)
}
