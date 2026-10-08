/**
 * Native 7xTune Library composition primitives.
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.ui.theme.SevenXTunePalette

@Composable
fun PulseLibraryControls(
    filterContent: @Composable () -> Unit,
    headerContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            SevenXTunePalette.SurfaceRaised.copy(alpha = 0.90f),
                            SevenXTunePalette.Surface.copy(alpha = 0.80f),
                        ),
                    ),
                )
                .border(
                    1.dp,
                    Brush.horizontalGradient(
                        listOf(
                            SevenXTunePalette.Violet.copy(alpha = 0.20f),
                            SevenXTunePalette.ElectricBlue.copy(alpha = 0.08f),
                            SevenXTunePalette.PulsePink.copy(alpha = 0.14f),
                        ),
                    ),
                    shape,
                )
                .padding(top = 2.dp, bottom = 5.dp),
    ) {
        PulseSectionHeader(
            eyebrow = "LIBRARY",
            title = "Browse your collection",
        )
        filterContent()
        Spacer(modifier = Modifier.height(1.dp))
        headerContent()
    }
}

@Composable
fun PulseLibraryHeader(
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            SevenXTunePalette.SurfaceBright.copy(alpha = 0.96f),
                            SevenXTunePalette.Surface.copy(alpha = 0.90f),
                            SevenXTunePalette.MidnightSoft.copy(alpha = 0.98f),
                        ),
                    ),
                )
                .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.sevenx_library_kicker),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                color = SevenXTunePalette.ElectricBlue,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.sevenx_library_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = SevenXTunePalette.TextPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.sevenx_library_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = SevenXTunePalette.TextSecondary,
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    SevenXTunePalette.Violet,
                                    SevenXTunePalette.PulsePink,
                                ),
                            ),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(3) { index ->
                        Box(
                            modifier =
                                Modifier
                                    .width(
                                        when (index) {
                                            0 -> 3.dp
                                            1 -> 5.dp
                                            else -> 3.dp
                                        },
                                    )
                                    .height(
                                        when (index) {
                                            0 -> 10.dp
                                            1 -> 20.dp
                                            else -> 14.dp
                                        },
                                    )
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.92f)),
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.sevenx_library_pulse),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                color = SevenXTunePalette.TextMuted,
            )
        }
    }
}
