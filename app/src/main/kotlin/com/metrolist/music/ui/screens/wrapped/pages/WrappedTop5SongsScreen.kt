/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.wrapped.pages

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.db.entities.SongWithStats
import com.metrolist.music.ui.screens.wrapped.components.AnimatedBackground
import com.metrolist.music.ui.screens.wrapped.components.ShapeType
import com.metrolist.music.ui.theme.bbh_bartle
import kotlinx.coroutines.delay

@Composable
fun WrappedTop5SongsScreen(topSongs: List<SongWithStats>, isVisible: Boolean) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            delay(200)
            visible = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedBackground(elementCount = 25, shapeTypes = listOf(ShapeType.Rect))
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
                    text = stringResource(id = R.string.wrapped_top_5_songs_title),
                    fontSize = 48.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Column {
                topSongs.forEachIndexed { index, song ->
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
                                model = song.thumbnailUrl,
                                contentDescription = "Album art",
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = song.title,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = song.artistName ?: "",
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
