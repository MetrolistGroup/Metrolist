package com.metrolist.android.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.metrolist.android.ui.glass.backdrop.backdrops.layerBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberLayerBackdrop

/** Records page and chrome before the sibling mini-player, independently of modal captures. */
@Composable
internal fun GlassNavigationContent(
    host: GlassMenuHostState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = rememberLayerBackdrop()
    SideEffect { host.miniPlayerBackdrop = source.takeIf { enabled } }
    DisposableEffect(host, source) {
        onDispose { if (host.miniPlayerBackdrop === source) host.miniPlayerBackdrop = null }
    }
    Box(Modifier.fillMaxSize()
        .then(if (enabled) Modifier.graphicsLayer().layerBackdrop(source) else Modifier)
        .then(modifier), content = content)
}
