/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.ui.theme.SevenXTunePalette

@Composable
fun EmptyPlaceholder(
    @DrawableRes icon: Int,
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .fillMaxSize()
                .padding(24.dp),
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
            modifier =
                Modifier
                    .size(84.dp)
                    .background(
                        SevenXTunePalette.SurfaceRaised.copy(alpha = 0.72f),
                        RoundedCornerShape(26.dp),
                    )
                    .border(
                        1.dp,
                        Brush.sweepGradient(
                            listOf(
                                SevenXTunePalette.Violet.copy(alpha = 0.46f),
                                SevenXTunePalette.ElectricBlue.copy(alpha = 0.28f),
                                SevenXTunePalette.PulsePink.copy(alpha = 0.30f),
                                SevenXTunePalette.Violet.copy(alpha = 0.46f),
                            ),
                        ),
                        RoundedCornerShape(26.dp),
                    )
                    .padding(18.dp),
        )

        Spacer(Modifier.height(18.dp))

        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
