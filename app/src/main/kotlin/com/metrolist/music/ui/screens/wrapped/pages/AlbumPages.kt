/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.wrapped.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.db.entities.Album
import com.metrolist.music.ui.screens.wrapped.components.AnimatedBackground
import com.metrolist.music.ui.screens.wrapped.components.ShapeType
import com.metrolist.music.ui.theme.bbh_bartle
import com.metrolist.music.ui.utils.resize
import kotlinx.coroutines.delay

@Composable
fun WrappedTotalAlbumsScreen(uniqueAlbumCount: Int, isVisible: Boolean) {
    val animatedAlbums = remember { Animatable(0f) }
    val textMeasurer = rememberTextMeasurer()
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(isVisible, uniqueAlbumCount) {
        if (isVisible) {
            visible = true
            if (uniqueAlbumCount > 0) {
                animatedAlbums.animateTo(
                    targetValue = uniqueAlbumCount.toFloat(),
                    animationSpec = tween(1500, easing = FastOutSlowInEasing)
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedBackground(shapeTypes = listOf(ShapeType.Circle))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(1000, delayMillis = 200)) + slideInVertically(animationSpec = tween(1000, delayMillis = 200))
            ) {
                Text(
                    text = stringResource(R.string.wrapped_total_albums_title),
                    modifier = Modifier.padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                )
            }
            Spacer(Modifier.height(32.dp))

            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                val density = LocalDensity.current
                val baseStyle = MaterialTheme.typography.displayLarge.copy(
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    fontFamily = bbh_bartle,
                    drawStyle = Stroke(with(density) { 2.dp.toPx() })
                )

                val textStyle = remember(uniqueAlbumCount, maxWidth) {
                    val finalText = uniqueAlbumCount.toString()
                    var style = baseStyle.copy(fontSize = 96.sp)
                    var textWidth = textMeasurer.measure(finalText, style).size.width
                    while (textWidth > constraints.maxWidth) {
                        style = style.copy(fontSize = style.fontSize * 0.95f)
                        textWidth = textMeasurer.measure(finalText, style).size.width
                    }
                    style.copy(lineHeight = style.fontSize * 1.08f)
                }

                Text(
                    text = animatedAlbums.value.toInt().toString(),
                    style = textStyle,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(16.dp))

            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(1000, delayMillis = 600)) + slideInVertically(animationSpec = tween(1000, delayMillis = 600))
            ) {
                Text(
                    text = stringResource(R.string.wrapped_total_albums_subtitle),
                    modifier = Modifier.padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                )
            }
        }
    }
}

@Composable
fun WrappedTopAlbumScreen(topAlbum: Album?, isVisible: Boolean) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(isVisible) {
        if (isVisible) {
            visible = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedBackground(shapeTypes = listOf(ShapeType.Rect))
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
                    text = stringResource(R.string.wrapped_top_album_title),
                    style = TextStyle(
                        fontFamily = bbh_bartle,
                        fontSize = 40.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        lineHeight = 48.sp
                    )
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(1000, delayMillis = 400)) + slideInVertically(animationSpec = tween(1000, delayMillis = 400))
            ) {
                AsyncImage(
                    model = topAlbum?.thumbnailUrl?.resize(512, 512),
                    contentDescription = stringResource(R.string.album_art_for, topAlbum?.title ?: ""),
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
                    text = topAlbum?.title ?: stringResource(id = R.string.wrapped_no_data),
                    fontSize = 24.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(1000, delayMillis = 800)) + slideInVertically(animationSpec = tween(1000, delayMillis = 800))
            ) {
                Text(
                    text = stringResource(R.string.wrapped_album_listening_time, topAlbum?.timeListened?.div(60000) ?: 0),
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun WrappedTop5AlbumsScreen(topAlbums: List<Album>, isVisible: Boolean) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            delay(200)
            visible = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedBackground(shapeTypes = listOf(ShapeType.Circle))
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
                    text = stringResource(R.string.wrapped_top_5_albums_title),
                    style = TextStyle(
                        fontFamily = bbh_bartle,
                        fontSize = 48.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        lineHeight = 56.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Column {
                topAlbums.forEachIndexed { index, album ->
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
                                model = album.thumbnailUrl?.resize(128, 128),
                                contentDescription = stringResource(R.string.album_art_for, album.title),
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = album.title,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    maxLines = 1
                                )
                                Text(
                                    text = stringResource(R.string.wrapped_album_listening_time_minutes, album.timeListened?.div(60000) ?: 0),
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
