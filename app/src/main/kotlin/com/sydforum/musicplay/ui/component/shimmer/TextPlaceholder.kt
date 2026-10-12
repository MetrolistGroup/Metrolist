/**
 * MusicPlayer Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.sydforum.musicplay.ui.component.shimmer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random

@Composable
fun TextPlaceholder(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    shape: CornerBasedShape = RoundedCornerShape(0.dp)
) {
    Box(
        modifier = modifier
            .padding(vertical = 4.dp)
            .height(height)
            .fillMaxWidth(remember { 0.25f + Random.nextFloat() * 0.5f })
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface)
    )
}
