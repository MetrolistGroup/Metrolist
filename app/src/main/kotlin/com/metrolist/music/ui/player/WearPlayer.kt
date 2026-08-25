package com.metrolist.music.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material.*
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.audio.ui.components.actions.NextButton
import com.google.android.horologist.audio.ui.components.actions.PlayPauseButton
import com.google.android.horologist.audio.ui.components.actions.PreviousButton
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * REESCRITURA COMPLETA: Reproductor Nativo Wear OS 4+ para Metrolist.
 * Optimizada para pantallas circulares, soporte de corona y controles Horologist.
 */
@OptIn(ExperimentalWearFoundationApi::class, ExperimentalHorologistApi::class)
@Composable
fun WearMusicPlayer() {
    val playerConnection = LocalPlayerConnection.current ?: return
    val pagerState = rememberPagerState(initialPage = 1) { 2 }
    
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    
    // Sustituye Scaffold de móvil por androidx.wear.compose.material.Scaffold
    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
    ) {
        // Configura HorizontalPager para deslizar entre 'Cola de canciones' y 'Reproductor'
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> QueueScreen(playerConnection)
                1 -> NowPlayingScreen(
                    metadata = mediaMetadata,
                    isPlaying = isPlaying,
                    playerConnection = playerConnection
                )
            }
        }
    }
}

@OptIn(ExperimentalHorologistApi::class)
@Composable
fun NowPlayingScreen(
    metadata: MediaMetadata?,
    isPlaying: Boolean,
    playerConnection: com.metrolist.music.playback.PlayerConnection
) {
    // Lógica de progreso (Polling de posición actual)
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                position = playerConnection.player.currentPosition
                playerConnection.player.duration.takeIf { it > 0 }?.let { duration = it }
                delay(500) // Polling optimizado para batería de reloj
            }
        }
    }

    val effectiveDuration = if (duration > 0) duration else (metadata?.duration?.toLong()?.times(1000L) ?: 0L)
    val progress = if (effectiveDuration > 0) position.toFloat() / effectiveDuration.toFloat() else 0f

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Implementa CircularProgressIndicator que rodea el borde de la pantalla
        CircularProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxSize().padding(2.dp),
            strokeWidth = 4.dp,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Título de canción
            Text(
                text = metadata?.title ?: "No se está reproduciendo",
                style = MaterialTheme.typography.button,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            
            // Artista
            Text(
                text = metadata?.artist ?: "Artista desconocido",
                style = MaterialTheme.typography.caption2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Controles Multimedia Horologist con iconos grandes
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val canSkipPrevious by playerConnection.canSkipPrevious.collectAsStateWithLifecycle()
                PreviousButton(
                    onClick = { playerConnection.seekToPrevious() },
                    enabled = canSkipPrevious,
                    modifier = Modifier.size(ButtonDefaults.DefaultButtonSize)
                )

                PlayPauseButton(
                    onPlayClick = { playerConnection.play() },
                    onPauseClick = { playerConnection.pause() },
                    playing = isPlaying,
                    modifier = Modifier.size(ButtonDefaults.LargeButtonSize)
                )

                val canSkipNext by playerConnection.canSkipNext.collectAsStateWithLifecycle()
                NextButton(
                    onClick = { playerConnection.seekToNext() },
                    enabled = canSkipNext,
                    modifier = Modifier.size(ButtonDefaults.DefaultButtonSize)
                )
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            
            // Optimización de espacio: CompactChip para acciones secundarias
            CompactChip(
                onClick = { /* Invocar menú de opciones */ },
                label = { Text("Opciones") },
                colors = ChipDefaults.secondaryChipColors(),
                icon = { Icon(painterResource(R.drawable.more_vert), contentDescription = null) }
            )
        }
    }
}

@OptIn(ExperimentalWearFoundationApi::class)
@Composable
fun QueueScreen(playerConnection: com.metrolist.music.playback.PlayerConnection) {
    val queueWindows by playerConnection.queueWindows.collectAsStateWithLifecycle(emptyList())
    val listState = rememberScalingLazyListState()

    // Implementa ScalingLazyColumn para curvatura y rotaryScrollable para soporte de corona
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .rotaryScrollable(listState),
        state = listState,
        autoCentering = AutoCenteringParams(itemIndex = 0)
    ) {
        item {
            ListHeader {
                Text("Cola de reproducción")
            }
        }
        
        itemsIndexed(queueWindows) { index, window ->
            val metadata = window.mediaItem.metadata
            Chip(
                onClick = { playerConnection.player.seekTo(index, 0) },
                label = { 
                    Text(
                        text = metadata?.title ?: "Sin título",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    ) 
                },
                secondaryLabel = {
                    Text(
                        text = metadata?.artist ?: "Artista desconocido",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.music_note),
                        contentDescription = null,
                        modifier = Modifier.size(ChipDefaults.IconSize)
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
