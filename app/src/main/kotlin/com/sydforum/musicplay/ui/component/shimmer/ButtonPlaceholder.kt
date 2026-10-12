/**
 * MusicPlayer Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.sydforum.musicplay.ui.component.shimmer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

@Composable
fun ButtonPlaceholder(modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .height(ButtonDefaults.MinHeight)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onSurface),
    )
}
