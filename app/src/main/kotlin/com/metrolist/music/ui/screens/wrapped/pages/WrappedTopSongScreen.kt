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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.metrolist.music.R
import com.metrolist.music.db.entities.SongWithStats
import com.metrolist.music.ui.screens.wrapped.components.AnimatedDecorativeElement
import kotlin.random.Random

@Composable
fun WrappedTopSongScreen(topSong: SongWithStats?, isVisible: Boolean) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(isVisible) {
        if (isVisible) {
            visible = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.align(Alignment.TopStart)) {
            repeat(3) {
                AnimatedDecorativeElement(
                    Modifier.padding(start = (Random.nextInt(0, 100)).dp, top = (Random.nextInt(0, 100)).dp).size((Random.nextInt(20, 80)).dp),
                    isVisible
                )
            }
        }
        Box(modifier = Modifier.align(Alignment.BottomEnd)) {
            repeat(4) {
                AnimatedDecorativeElement(
                    Modifier.padding(end = (Random.nextInt(0, 120)).dp, bottom = (Random.nextInt(0, 120)).dp).size((Random.nextInt(20, 90)).dp),
                    isVisible
                )
            }
        }

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
                    text = stringResource(id = R.string.wrapped_top_song_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(1000, delayMillis = 400)) + slideInVertically(animationSpec = tween(1000, delayMillis = 400))
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(topSong?.thumbnailUrl)
                        .build(),
                contentDescription = stringResource(id = R.string.wrapped_top_song_album_art_content_description),
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(1000, delayMillis = 600)) + slideInVertically(animationSpec = tween(1000, delayMillis = 600))
            ) {
                Text(
                text = topSong?.title ?: stringResource(id = R.string.wrapped_no_data),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }

            // Artists are not available in SongWithStats, so this part is removed.
            // A possible improvement would be to fetch artist data separately.

            Spacer(modifier = Modifier.height(8.dp))

            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(1000, delayMillis = 1000)) + slideInVertically(animationSpec = tween(1000, delayMillis = 1000))
            ) {
                Text(
                text = stringResource(id = R.string.wrapped_top_song_listening_time, topSong?.timeListened?.div(60000) ?: 0),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
