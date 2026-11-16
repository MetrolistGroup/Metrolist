package com.metrolist.shared.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

@Composable
internal expect fun MobileMusicVideoSurface(
    modifier: Modifier,
    cornerRadius: Dp,
    fillContainer: Boolean,
    showControls: Boolean,
    onFullscreenClick: (() -> Unit)?,
)
