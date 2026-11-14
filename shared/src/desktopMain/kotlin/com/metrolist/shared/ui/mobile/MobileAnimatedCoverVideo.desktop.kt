package com.metrolist.shared.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

@Composable
internal actual fun MobileAnimatedCoverVideo(
    url: String,
    headers: Map<String, String>,
    playing: Boolean,
    onError: (() -> Unit)?,
    onReadyChanged: ((Boolean) -> Unit)?,
    modifier: Modifier,
) {
    LaunchedEffect(url) { onError?.invoke() }
}
