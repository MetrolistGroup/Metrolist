package com.metrolist.shared.ui.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

@Composable
internal actual fun MobileMusicVideoSurface(
    modifier: Modifier,
    cornerRadius: Dp,
    fillContainer: Boolean,
    showControls: Boolean,
    onFullscreenClick: (() -> Unit)?,
) = Box(modifier)
