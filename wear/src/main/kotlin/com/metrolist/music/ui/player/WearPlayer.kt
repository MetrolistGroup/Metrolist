package com.metrolist.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.material.*
import coil3.compose.AsyncImage
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.layout.ScalingLazyColumn
import com.google.android.horologist.compose.layout.rememberResponsiveColumnState
import com.google.android.horologist.media.ui.components.PlayPauseButton
import com.google.android.horologist.media.ui.components.controls.SeekToNextButton
import com.google.android.horologist.media.ui.components.controls.SeekToPreviousButton
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.core.R
import com.metrolist.music.extensions.metadata
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.ExoDownloadService
import com.metrolist.music.utils.dataStore
import com.metrolist.music.constants.SleepTimerDefaultKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map

/**
 * Wear OS Music Player for Metrolist.
 * Optimized for circular screens, crown support, and Horologist controls.
 */
@OptIn(ExperimentalWearFoundationApi::class, ExperimentalHorologistApi::class)
@Composable
fun WearMusicPlayer(
    onNavigateToSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLibrary: () -> Unit,
    onNavigateToLiked: () -> Unit,
    onNavigateToDownloads: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToVolume: () -> Unit,
    onNavigateToQueue: () -> Unit
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val downloadUtil = LocalDownloadUtil.current
    val pagerState = rememberPagerState(initialPage = 1) { 3 }
    val coroutineScope = rememberCoroutineScope()
    
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val currentSong by playerConnection.currentSong.collectAsStateWithLifecycle()
    
    // Download state for the options page
    val songId = mediaMetadata?.id
    val allDownloads by downloadUtil.downloads.collectAsStateWithLifecycle()
    val download = remember(songId, allDownloads) { songId?.let { allDownloads[it] } }
    
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        when (page) {
            0 -> QueueScreen(playerConnection)
            1 -> NowPlayingScreen(
                metadata = mediaMetadata,
                isPlaying = isPlaying,
                playerConnection = playerConnection,
                onNavigateToVolume = onNavigateToVolume
            )
            2 -> WearOptionsPage(
                onNavigateToSearch = onNavigateToSearch,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToLibrary = onNavigateToLibrary,
                onNavigateToLiked = onNavigateToLiked,
                onNavigateToDownloads = onNavigateToDownloads,
                onNavigateToHistory = onNavigateToHistory,
                onNavigateToQueue = {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(0)
                    }
                },
                metadata = mediaMetadata,
                currentSong = currentSong,
                download = download
            )
        }
    }
}

@OptIn(ExperimentalHorologistApi::class)
@Composable
fun NowPlayingScreen(
    metadata: MediaMetadata?,
    isPlaying: Boolean,
    playerConnection: com.metrolist.music.playback.PlayerConnection,
    onNavigateToVolume: () -> Unit
) {
    val context = LocalContext.current
    
    val currentSong by playerConnection.currentSong.collectAsStateWithLifecycle()
    
    // Sleep timer duration from DataStore
    val sleepDuration by remember {
        context.dataStore.data.map { it[SleepTimerDefaultKey] ?: 30f }
    }.collectAsStateWithLifecycle(initialValue = 30f)

    // Progress logic (polling current position)
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                position = playerConnection.player.currentPosition
                playerConnection.player.duration.takeIf { it > 0 }?.let { duration = it }
                delay(500)
            }
        }
    }

    val effectiveDuration = if (duration > 0) duration else (metadata?.duration?.toLong()?.times(1000L) ?: 0L)
    val progress = if (effectiveDuration > 0) position.toFloat() / effectiveDuration.toFloat() else 0f

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Background Album Art with Scrim
        Box(modifier = Modifier.fillMaxSize()) {
            metadata?.thumbnailUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.35f)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.4f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.7f)
                            )
                        )
                    )
            )
        }

        // Integrated Progress Ring
        CircularProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxSize().padding(4.dp),
            strokeWidth = 3.dp,
            indicatorColor = MaterialTheme.colors.primary,
            trackColor = MaterialTheme.colors.onSurface.copy(alpha = 0.15f)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Track Info Section (Top)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 28.dp)
            ) {
                Text(
                    text = metadata?.title ?: stringResource(R.string.no_song_playing),
                    style = MaterialTheme.typography.title3.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.1.sp
                    ),
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(iterations = Int.MAX_VALUE)
                )
                
                Text(
                    text = metadata?.artists?.joinToString { it.name } ?: stringResource(R.string.widget_recognizer_unknown_artist),
                    style = MaterialTheme.typography.caption2.copy(
                        color = MaterialTheme.colors.secondary,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(iterations = Int.MAX_VALUE)
                )
            }

            // Main Media Controls (Center)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val canSkipPrevious by playerConnection.canSkipPrevious.collectAsStateWithLifecycle()
                SeekToPreviousButton(
                    onClick = { playerConnection.seekToPrevious() },
                    enabled = canSkipPrevious,
                    modifier = Modifier.size(36.dp)
                )

                PlayPauseButton(
                    onPlayClick = { playerConnection.play() },
                    onPauseClick = { playerConnection.pause() },
                    playing = isPlaying,
                    modifier = Modifier.size(60.dp)
                )

                val canSkipNext by playerConnection.canSkipNext.collectAsStateWithLifecycle()
                SeekToNextButton(
                    onClick = { playerConnection.seekToNext() },
                    enabled = canSkipNext,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Bottom Action Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Like Button
                val isLiked = currentSong?.song?.liked == true
                Button(
                    onClick = { playerConnection.toggleLike() },
                    colors = ButtonDefaults.secondaryButtonColors(),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(if (isLiked) R.drawable.ic_heart else R.drawable.ic_heart_outline),
                        contentDescription = null,
                        tint = if (isLiked) MaterialTheme.colors.error else MaterialTheme.colors.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Volume Button
                Button(
                    onClick = onNavigateToVolume,
                    colors = ButtonDefaults.secondaryButtonColors(),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.volume_up),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Sleep Button
                val isSleepActive = playerConnection.service.sleepTimer?.isActive == true
                Button(
                    onClick = { 
                        if (isSleepActive) {
                            playerConnection.service.sleepTimer?.clear()
                        } else {
                            playerConnection.service.sleepTimer?.start(sleepDuration.toInt())
                        }
                    },
                    colors = if (isSleepActive)
                        ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.secondary, contentColor = MaterialTheme.colors.onSecondary)
                    else
                        ButtonDefaults.secondaryButtonColors(),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(if (isSleepActive) R.drawable.bedtime else R.drawable.timer),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalHorologistApi::class)
@Composable
fun WearOptionsPage(
    onNavigateToSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLibrary: () -> Unit,
    onNavigateToLiked: () -> Unit,
    onNavigateToDownloads: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToQueue: () -> Unit,
    metadata: MediaMetadata?,
    currentSong: com.metrolist.music.db.entities.Song?,
    download: Download?
) {
    val context = LocalContext.current
    val columnState = rememberResponsiveColumnState()
    
    val isDownloaded = currentSong?.song?.isDownloaded == true || download?.state == Download.STATE_COMPLETED
    val isDownloading = download?.state == Download.STATE_DOWNLOADING || download?.state == Download.STATE_QUEUED
    val downloadPercent = download?.percentDownloaded ?: 0f

    ScalingLazyColumn(
        columnState = columnState,
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            ListHeader {
                Text(stringResource(R.string.options))
            }
        }

        item {
            Chip(
                onClick = onNavigateToSearch,
                label = { Text(stringResource(R.string.search)) },
                icon = { Icon(painterResource(R.drawable.search), contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        // Contextual Download Option
        if (metadata != null) {
            item {
                Chip(
                    onClick = {
                        val songId = metadata.id
                        if (isDownloaded || isDownloading) {
                            DownloadService.sendRemoveDownload(
                                context,
                                ExoDownloadService::class.java,
                                songId,
                                false
                            )
                        } else {
                            val downloadRequest = DownloadRequest.Builder(songId, songId.toUri())
                                .setCustomCacheKey(songId)
                                .setData(metadata.title.toByteArray())
                                .build()
                            DownloadService.sendAddDownload(
                                context,
                                ExoDownloadService::class.java,
                                downloadRequest,
                                false
                            )
                        }
                    },
                    label = { 
                        Text(
                            text = when {
                                isDownloaded -> stringResource(R.string.offline)
                                isDownloading -> stringResource(R.string.downloading)
                                else -> stringResource(R.string.action_download)
                            }
                        ) 
                    },
                    secondaryLabel = {
                        if (isDownloading) {
                            Text("${downloadPercent.toInt()}%")
                        }
                    },
                    icon = { 
                        Icon(
                            painter = painterResource(
                                when {
                                    isDownloaded -> R.drawable.done
                                    isDownloading -> R.drawable.close
                                    else -> R.drawable.download
                                }
                            ), 
                            contentDescription = null,
                            tint = if (isDownloaded) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface
                        ) 
                    },
                    colors = if (isDownloaded) 
                        ChipDefaults.secondaryChipColors() 
                    else 
                        ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        item {
            Chip(
                onClick = onNavigateToQueue,
                label = { Text(stringResource(R.string.playback_queue)) },
                icon = { Icon(painterResource(R.drawable.list), contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = onNavigateToLiked,
                label = { Text(stringResource(R.string.liked)) },
                icon = { Icon(painterResource(R.drawable.ic_heart), contentDescription = null, tint = MaterialTheme.colors.error) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = onNavigateToDownloads,
                label = { Text(stringResource(R.string.offline)) },
                icon = { Icon(painterResource(R.drawable.cached), contentDescription = null, tint = MaterialTheme.colors.primary) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = onNavigateToLibrary,
                label = { Text(stringResource(R.string.filter_library)) },
                icon = { Icon(painterResource(R.drawable.library_music), contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = onNavigateToHistory,
                label = { Text(stringResource(R.string.history)) },
                icon = { Icon(painterResource(R.drawable.history), contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = onNavigateToSettings,
                label = { Text(stringResource(R.string.settings)) },
                icon = { Icon(painterResource(R.drawable.settings), contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Spacer(Modifier.height(40.dp))
        }
    }
}

@OptIn(ExperimentalWearFoundationApi::class, ExperimentalHorologistApi::class)
@Composable
fun QueueScreen(playerConnection: com.metrolist.music.playback.PlayerConnection) {
    val queueWindows by playerConnection.queueWindows.collectAsStateWithLifecycle(emptyList())
    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsStateWithLifecycle()
    val columnState = rememberResponsiveColumnState()
    val focusRequester = remember { FocusRequester() }

    ScalingLazyColumn(
        columnState = columnState,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
    ) {
        item {
            ListHeader {
                Text(stringResource(R.string.playback_queue))
            }
        }
        
        itemsIndexed(queueWindows) { index, window ->
            val metadata = window.mediaItem.metadata
            val isCurrent = index == currentWindowIndex
            
            Chip(
                onClick = { playerConnection.player.seekTo(index, 0) },
                label = { 
                    Text(
                        text = metadata?.title ?: stringResource(R.string.untitled),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                    ) 
                },
                secondaryLabel = {
                    Text(
                        text = (if (isCurrent) "Reproduciendo ahora • " else "") + 
                               (metadata?.artists?.joinToString { it.name } ?: stringResource(R.string.widget_recognizer_unknown_artist)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isCurrent) MaterialTheme.colors.primary else MaterialTheme.colors.secondary
                    )
                },
                icon = {
                    val iconRes = if (isCurrent) R.drawable.play else R.drawable.music_note
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(ChipDefaults.IconSize),
                        tint = if (isCurrent) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface
                    )
                },
                colors = if (isCurrent) 
                    ChipDefaults.gradientBackgroundChipColors() 
                else 
                    ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        item {
            Spacer(Modifier.height(40.dp))
        }
    }

    LaunchedEffect(currentWindowIndex) {
        if (currentWindowIndex >= 0) {
            // Scroll to the current playing item
            columnState.state.scrollToItem(currentWindowIndex + 1)
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
