/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.metrolist.music.constants.AudioQuality

@Composable
fun AudioQualityLabel(
    audioQuality: AudioQuality,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    val label = when (audioQuality) {
        AudioQuality.AUTO -> "AUTO"
        AudioQuality.HIGH -> "HIGH"
        AudioQuality.LOW -> "LOW"
        AudioQuality.LOSSLESS -> "LOSSLESS"
        AudioQuality.HI_RES_LOSSLESS -> "HI-RES"
    }

    Text(
        text = label,
        color = textColor,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
