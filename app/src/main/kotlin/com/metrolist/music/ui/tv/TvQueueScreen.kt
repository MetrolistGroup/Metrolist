/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.tv

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.ui.component.PlayingIndicator

/**
 * Full-screen queue view for Android TV (Spotify TV layout).
 *
 * Each row has 3 focus zones:
 * [Track Info & Play] - [Drag Reorder Handle] - [Remove Track Button]
 */
@Composable
fun TvQueueScreen(navController: NavController) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val currentQueue by playerConnection.queueWindows.collectAsState()
    val currentIndex = playerConnection.player.currentMediaItemIndex

    val listState = rememberLazyListState()

    // -1 means no item is currently being dragged
    var draggingIndex by remember { mutableIntStateOf(-1) }

    // Track which ROW currently has focus
    var focusedRowIndex by remember { mutableIntStateOf(currentIndex.coerceIn(0, (currentQueue.size - 1).coerceAtLeast(0))) }
    var focusedColIndex by remember { mutableIntStateOf(0) } // 0=song, 1=drag, 2=remove

    LaunchedEffect(currentQueue.size) {
        focusedRowIndex = focusedRowIndex.coerceIn(0, (currentQueue.size - 1).coerceAtLeast(0))
    }

    LaunchedEffect(focusedRowIndex) {
        listState.animateScrollToItem(maxOf(0, focusedRowIndex - 3))
    }

    // Scroll to current track on first load
    LaunchedEffect(Unit) {
        val target = currentIndex.coerceIn(0, (currentQueue.size - 1).coerceAtLeast(0))
        focusedRowIndex = target
        listState.scrollToItem(target)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.BackgroundPureBlack)
    ) {
        // Blurred backdrop from artwork
        AsyncImage(
            model = mediaMetadata?.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(80.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.75f),
                            Color.Black.copy(alpha = 0.9f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 64.dp, vertical = 40.dp),
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(TvColors.SpotifyGreen.copy(alpha = 0.15f))
                ) {
                    Icon(
                        painter = painterResource(R.drawable.queue_music),
                        contentDescription = null,
                        tint = TvColors.SpotifyGreenBright,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Spacer(Modifier.width(16.dp))

                Text(
                    text = "Now Playing & Queue",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TvColors.TextPrimary,
                )

                Spacer(Modifier.width(16.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(TvColors.OverlayLight)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${currentQueue.size} songs",
                        style = MaterialTheme.typography.labelMedium,
                        color = TvColors.TextSecondary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (draggingIndex != -1) {
                    Spacer(Modifier.width(24.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(TvColors.SpotifyGreenBright)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Moving song: Press Up/Down to reorder, OK to place",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 16.dp),
            ) {
                itemsIndexed(
                    items = currentQueue,
                    key = { _, window -> window.uid.hashCode() }
                ) { idx, window ->
                    val isCurrent = window.firstPeriodIndex == currentIndex
                    val isDragging = (draggingIndex == idx)

                    TvQueueItem(
                        index = idx,
                        window = window,
                        isCurrent = isCurrent,
                        isPlaying = isPlaying && isCurrent,
                        isDragging = isDragging,
                        totalItems = currentQueue.size,
                        isFocusedRow = (idx == focusedRowIndex),
                        focusedCol = focusedColIndex,
                        onFocusedCol = { col -> focusedColIndex = col },
                        onMoveRow = { direction ->
                            val newRow = (focusedRowIndex + direction).coerceIn(0, (currentQueue.size - 1).coerceAtLeast(0))
                            if (newRow != focusedRowIndex) focusedRowIndex = newRow
                        },
                        onPlay = {
                            playerConnection.player.seekToDefaultPosition(idx)
                            navController.navigateUp()
                        },
                        onEnterDrag = { draggingIndex = idx },
                        onExitDrag = { draggingIndex = -1 },
                        onMove = { from, to ->
                            playerConnection.player.moveMediaItem(from, to)
                            draggingIndex = to
                            focusedRowIndex = to
                        },
                        onRemove = {
                            playerConnection.player.removeMediaItem(idx)
                            if (focusedRowIndex >= currentQueue.size - 1) {
                                focusedRowIndex = (currentQueue.size - 2).coerceAtLeast(0)
                            }
                        },
                        onFocused = { focusedRowIndex = idx }
                    )
                }
            }
        }
    }
}

@Composable
fun TvQueueItem(
    index: Int,
    window: androidx.media3.common.Timeline.Window,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isDragging: Boolean,
    totalItems: Int,
    isFocusedRow: Boolean,
    focusedCol: Int,           // 0=song, 1=drag, 2=remove
    onFocusedCol: (Int) -> Unit,
    onMoveRow: (Int) -> Unit,
    onPlay: () -> Unit,
    onEnterDrag: () -> Unit,
    onExitDrag: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: () -> Unit,
    onFocused: () -> Unit,
) {
    val title = window.mediaItem.mediaMetadata.title?.toString() ?: ""
    val artist = window.mediaItem.mediaMetadata.artist?.toString() ?: ""
    val thumbnailUri = window.mediaItem.mediaMetadata.artworkUri

    val dragFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    val removeFocus = remember { FocusRequester() }

    var contentFocused by remember { mutableStateOf(false) }
    var dragFocused by remember { mutableStateOf(false) }
    var removeFocused by remember { mutableStateOf(false) }

    LaunchedEffect(isFocusedRow, focusedCol) {
        if (isFocusedRow) {
            when (focusedCol) {
                0 -> contentFocus.requestFocus()
                1 -> dragFocus.requestFocus()
                2 -> removeFocus.requestFocus()
            }
        }
    }

    LaunchedEffect(isDragging) {
        if (isDragging) {
            dragFocus.requestFocus()
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    isDragging -> TvColors.SpotifyGreen.copy(alpha = 0.25f)
                    isCurrent -> TvColors.CardBackgroundElevated
                    else -> TvColors.CardBackground
                }
            )
            .border(
                width = if (isCurrent && !contentFocused) 1.dp else 0.dp,
                color = if (isCurrent && !contentFocused) TvColors.SpotifyGreen.copy(alpha = 0.5f) else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // 1. Content (Playable Track Area)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(if (contentFocused) TvColors.OverlayFocused else Color.Transparent)
                .border(
                    width = if (contentFocused) 2.dp else 0.dp,
                    color = if (contentFocused) Color.White.copy(alpha = 0.8f) else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
                .focusRequester(contentFocus)
                .onFocusChanged { state ->
                    contentFocused = state.isFocused
                    if (state.isFocused) {
                        onFocused()
                        onFocusedCol(0)
                    }
                }
                .focusable()
                .onKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { dragFocus.requestFocus(); true }
                        KeyEvent.KEYCODE_DPAD_UP -> { onMoveRow(-1); true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { onMoveRow(1); true }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { onPlay(); true }
                        else -> false
                    }
                }
                .clickable { onPlay() }
                .padding(8.dp)
        ) {
            // Index Number / Waveform indicator
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.width(36.dp)
            ) {
                if (isPlaying) {
                    PlayingIndicator(
                        color = TvColors.SpotifyGreenBright,
                        modifier = Modifier.height(14.dp),
                        bars = 3,
                        barWidth = 3.dp,
                        cornerRadius = 2.dp
                    )
                } else {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isCurrent) TvColors.SpotifyGreenBright else TvColors.TextTertiary,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Thumbnail
            AsyncImage(
                model = thumbnailUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(TvColors.CardBackgroundElevated)
            )

            Spacer(Modifier.width(16.dp))

            // Title & Artist
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isCurrent) TvColors.SpotifyGreenBright else TvColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (artist.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TvColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        // 2. Drag Handle (Middle)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isDragging -> TvColors.SpotifyGreenBright
                        dragFocused -> TvColors.OverlayFocused
                        else -> Color.Transparent
                    }
                )
                .border(
                    width = if (dragFocused) 2.dp else 0.dp,
                    color = if (dragFocused) Color.White.copy(alpha = 0.8f) else Color.Transparent,
                    shape = CircleShape
                )
                .focusRequester(dragFocus)
                .onFocusChanged { state ->
                    dragFocused = state.isFocused
                    if (state.isFocused) {
                        onFocused()
                        onFocusedCol(1)
                    }
                }
                .focusable()
                .onKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> { contentFocus.requestFocus(); true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { removeFocus.requestFocus(); true }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (isDragging) {
                                if (index > 0) onMove(index, index - 1)
                            } else {
                                onMoveRow(-1)
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (isDragging) {
                                if (index < totalItems - 1) onMove(index, index + 1)
                            } else {
                                onMoveRow(1)
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (isDragging) onExitDrag() else onEnterDrag()
                            true
                        }
                        KeyEvent.KEYCODE_BACK -> {
                            if (isDragging) { onExitDrag(); true } else false
                        }
                        else -> false
                    }
                }
                .clickable { if (isDragging) onExitDrag() else onEnterDrag() }
        ) {
            Icon(
                painter = painterResource(R.drawable.drag_handle),
                contentDescription = "Reorder",
                tint = when {
                    isDragging -> Color.Black
                    dragFocused -> Color.White
                    else -> TvColors.TextSecondary
                },
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(8.dp))

        // 3. Remove Button (Right)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(if (removeFocused) Color(0xFFEF5350).copy(alpha = 0.8f) else Color.Transparent)
                .border(
                    width = if (removeFocused) 2.dp else 0.dp,
                    color = if (removeFocused) Color.White.copy(alpha = 0.8f) else Color.Transparent,
                    shape = CircleShape
                )
                .focusRequester(removeFocus)
                .onFocusChanged { state ->
                    removeFocused = state.isFocused
                    if (state.isFocused) {
                        onFocused()
                        onFocusedCol(2)
                    }
                }
                .focusable()
                .onKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> { dragFocus.requestFocus(); true }
                        KeyEvent.KEYCODE_DPAD_UP -> { onMoveRow(-1); true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { onMoveRow(1); true }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { onRemove(); true }
                        else -> false
                    }
                }
                .clickable { onRemove() }
        ) {
            Icon(
                painter = painterResource(R.drawable.close),
                contentDescription = "Remove",
                tint = if (removeFocused) Color.White else TvColors.TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
