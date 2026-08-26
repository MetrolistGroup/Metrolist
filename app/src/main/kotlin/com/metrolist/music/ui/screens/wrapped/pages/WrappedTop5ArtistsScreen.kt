/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.wrapped.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.metrolist.music.core.R
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.ui.screens.wrapped.components.AnimatedBackground
import com.metrolist.music.ui.screens.wrapped.components.ShapeType
import com.metrolist.music.ui.theme.bbh_bartle
import kotlinx.coroutines.delay

@Composable
fun WrappedTop5ArtistsScreen(topArtists: List<Artist>, isVisible: Boolean) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            delay(200)
            visible = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedBackground(elementCount = 15, shapeTypes = listOf(ShapeType.Line))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(1000, delayMillis = 200)) + slideInVertically(animationSpec = tween(1000, delayMillis = 200))
            ) {
                Text(
                    text = stringResource(id = R.string.wrapped_top_5_artists_title),
                    fontSize = 48.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Column {
                topArtists.forEachIndexed { index, artist ->
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(animationSpec = tween(600, delayMillis = 400 + (index * 200))) + slideInVertically(animationSpec = tween(600, delayMillis = 400 + (index * 200)))
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}",
                                fontFamily = bbh_bartle,
                                fontSize = 36.sp,
                                color = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.width(40.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            AsyncImage(
                                model = artist.artist.thumbnailUrl,
                                contentDescription = "Artist image",
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = artist.artist.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = stringResource(id = R.string.wrapped_artist_listening_time, (artist.timeListened ?: 0) / 60000),
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
