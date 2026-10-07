package com.metrolist.music.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.music.R
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.ui.theme.SevenXTuneShapes
import com.metrolist.music.ui.utils.resize

@Composable
fun SpeedDialGridItem(
    item: YTItem,
    isPinned: Boolean,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    isPlaying: Boolean = false,
) {
    val shape = SevenXTuneShapes.medium
    Box(
        modifier = modifier.fillMaxWidth().aspectRatio(1f).clip(shape).border(
            width = if (isActive) 1.5.dp else 1.dp,
            brush = Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = if (isActive) 0.70f else 0.30f),
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                )
            ),
            shape = shape,
        ),
    ) {
        ItemThumbnail(
            thumbnailUrl = item.thumbnail?.resize(200, 200),
            isActive = isActive,
            isPlaying = isPlaying,
            shape = if (item is ArtistItem) CircleShape else RoundedCornerShape(ThumbnailCornerRadius),
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.18f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.46f),
                        Color.Black.copy(alpha = 0.88f),
                    )
                )
            )
        )
        if (isPinned) {
            Box(
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.90f))
                    .padding(horizontal = 7.dp, vertical = 5.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_push_pin),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Row(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(9.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (item !is SongItem) {
                Box(
                    modifier = Modifier.padding(start = 6.dp).size(28.dp).clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.42f))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.navigate_next),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
