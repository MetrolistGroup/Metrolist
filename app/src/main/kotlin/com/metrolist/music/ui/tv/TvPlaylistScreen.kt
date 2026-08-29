/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.tv

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSong
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.YouTubePlaylistQueue
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.component.PlayingIndicator
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.viewmodels.AlbumViewModel
import com.metrolist.music.viewmodels.AutoPlaylistViewModel
import com.metrolist.music.viewmodels.LocalPlaylistViewModel
import com.metrolist.music.viewmodels.OnlinePlaylistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Spotify TV style Playlist & Album View
 *
 * Left pane: Big Album Artwork + Title + Artist/Creator info + Action Buttons:
 *   [Play] (Big Spotify Green button), [Shuffle], [Favorite / Like], [More Options]
 * Right pane: 10-foot navigable D-pad focused track list with track number, playing indicators, and duration.
 */
@Composable
fun TvPlaylistScreen(
    navController: NavController,
    playlistId: Long? = null,
    autoPlaylistType: String? = null,
    onlineBrowseId: String? = null,
    albumId: String? = null,
) {
    when {
        playlistId != null -> TvLocalPlaylistContent(playlistId = playlistId, navController = navController)
        autoPlaylistType != null -> TvAutoPlaylistContent(playlistType = autoPlaylistType, navController = navController)
        onlineBrowseId != null -> TvOnlinePlaylistContent(browseId = onlineBrowseId, navController = navController)
        albumId != null -> TvAlbumContent(albumId = albumId, navController = navController)
    }
}

/** ── Local Playlist ────────────────────────────────────────────────────────── */
@Composable
private fun TvLocalPlaylistContent(
    playlistId: Long,
    navController: NavController,
    viewModel: LocalPlaylistViewModel = hiltViewModel()
) {
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val songs by viewModel.playlistSongs.collectAsStateWithLifecycle()
    val isPlaying by (playerConnection?.isPlaying?.collectAsState() ?: remember { mutableStateOf(false) })
    val mediaMetadata by (playerConnection?.mediaMetadata?.collectAsState() ?: remember { mutableStateOf(null) })
    val scope = rememberCoroutineScope()
    var showMoreMenu by remember { mutableStateOf(false) }

    val isLiked = playlist?.playlist?.bookmarkedAt != null

    val trackList = remember(songs) {
        songs.map { it.song.toMediaMetadata() }
    }

    TvMediaContainer(
        title = playlist?.playlist?.name ?: "Playlist",
        subtitle = "${songs.size} songs",
        artworkUrl = playlist?.thumbnails?.firstOrNull(),
        isLiked = isLiked,
        onToggleLike = {
            playlist?.playlist?.let { entity ->
                database.query {
                    update(entity.toggleLike())
                }
            }
        },
        onPlay = {
            if (songs.isNotEmpty()) {
                playerConnection?.playQueue(
                    ListQueue(
                        title = playlist?.playlist?.name ?: "Playlist",
                        items = songs.map { it.song.toMediaItem() }
                    )
                )
            }
        },
        onShuffle = {
            if (songs.isNotEmpty()) {
                playerConnection?.playQueue(
                    ListQueue(
                        title = playlist?.playlist?.name ?: "Playlist",
                        items = songs.shuffled().map { it.song.toMediaItem() }
                    )
                )
            }
        },
        onMoreOptions = { showMoreMenu = true },
        trackList = trackList,
        currentPlayingId = mediaMetadata?.id,
        isPlaying = isPlaying,
        onTrackClick = { index ->
            playerConnection?.playQueue(
                ListQueue(
                    title = playlist?.playlist?.name ?: "Playlist",
                    items = songs.map { it.song.toMediaItem() },
                    startIndex = index
                )
            )
        }
    )

    if (showMoreMenu && playlist != null) {
        TvPlaylistOptionDialog(
            title = playlist!!.playlist.name,
            options = listOf(
                TvMenuOption("Add All to Queue", R.drawable.queue_music) {
                    playerConnection?.playQueue(
                        ListQueue(
                            title = playlist!!.playlist.name,
                            items = songs.map { it.song.toMediaItem() }
                        )
                    )
                },
                TvMenuOption(if (isLiked) "Remove from Library" else "Save to Library", if (isLiked) R.drawable.favorite else R.drawable.favorite_border) {
                    database.query { update(playlist!!.playlist.toggleLike()) }
                }
            ),
            onDismiss = { showMoreMenu = false }
        )
    }
}

/** ── Auto Playlist (Liked Songs) ─────────────────────────────────────────── */
@Composable
private fun TvAutoPlaylistContent(
    playlistType: String,
    navController: NavController,
    viewModel: AutoPlaylistViewModel = hiltViewModel()
) {
    val playerConnection = LocalPlayerConnection.current
    val songs by viewModel.likedSongs.collectAsStateWithLifecycle()
    val isPlaying by (playerConnection?.isPlaying?.collectAsState() ?: remember { mutableStateOf(false) })
    val mediaMetadata by (playerConnection?.mediaMetadata?.collectAsState() ?: remember { mutableStateOf(null) })
    var showMoreMenu by remember { mutableStateOf(false) }

    val trackList = remember(songs) {
        songs.map { it.toMediaMetadata() }
    }

    TvMediaContainer(
        title = "Liked Songs",
        subtitle = "${trackList.size} favorite songs",
        artworkUrl = null,
        isCustomGradientIcon = true,
        isLiked = true,
        onToggleLike = {},
        onPlay = {
            if (trackList.isNotEmpty()) {
                playerConnection?.playQueue(
                    ListQueue(
                        title = "Liked Songs",
                        items = songs.map { it.toMediaItem() }
                    )
                )
            }
        },
        onShuffle = {
            if (trackList.isNotEmpty()) {
                playerConnection?.playQueue(
                    ListQueue(
                        title = "Liked Songs",
                        items = songs.shuffled().map { it.toMediaItem() }
                    )
                )
            }
        },
        onMoreOptions = { showMoreMenu = true },
        trackList = trackList,
        currentPlayingId = mediaMetadata?.id,
        isPlaying = isPlaying,
        onTrackClick = { index ->
            playerConnection?.playQueue(
                ListQueue(
                    title = "Liked Songs",
                    items = songs.map { it.toMediaItem() },
                    startIndex = index
                )
            )
        }
    )

    if (showMoreMenu) {
        TvPlaylistOptionDialog(
            title = "Liked Songs",
            options = listOf(
                TvMenuOption("Play Next", R.drawable.queue_music) {
                    playerConnection?.playQueue(
                        ListQueue(title = "Liked Songs", items = songs.map { it.toMediaItem() })
                    )
                }
            ),
            onDismiss = { showMoreMenu = false }
        )
    }
}

/** ── Online YouTube Playlist ─────────────────────────────────────────────── */
@Composable
private fun TvOnlinePlaylistContent(
    browseId: String,
    navController: NavController,
    viewModel: OnlinePlaylistViewModel = hiltViewModel()
) {
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val songs by viewModel.playlistSongs.collectAsStateWithLifecycle()
    val dbPlaylist by viewModel.dbPlaylist.collectAsStateWithLifecycle()
    val isPlaying by (playerConnection?.isPlaying?.collectAsState() ?: remember { mutableStateOf(false) })
    val mediaMetadata by (playerConnection?.mediaMetadata?.collectAsState() ?: remember { mutableStateOf(null) })
    val scope = rememberCoroutineScope()
    var showMoreMenu by remember { mutableStateOf(false) }

    val currentPlaylist = playlist ?: return
    val isLiked = dbPlaylist?.playlist?.bookmarkedAt != null

    val trackList = remember(songs) {
        songs.map { it.toMediaMetadata() }
    }

    TvMediaContainer(
        title = currentPlaylist.title,
        subtitle = "${currentPlaylist.author?.name ?: "Playlist"} • ${songs.size} tracks",
        artworkUrl = currentPlaylist.thumbnail,
        isLiked = isLiked,
        onToggleLike = {
            if (dbPlaylist != null) {
                database.transaction {
                    val current = dbPlaylist!!.playlist
                    update(current, currentPlaylist)
                    update(current.toggleLike())
                }
            } else {
                scope.launch(Dispatchers.IO) {
                    val playlistEntity = PlaylistEntity(
                        name = currentPlaylist.title,
                        browseId = currentPlaylist.id,
                        thumbnailUrl = currentPlaylist.thumbnail,
                        isEditable = currentPlaylist.isEditable,
                    ).toggleLike()
                    val songMetadata = songs.map { it.toMediaMetadata() }
                    database.withTransaction {
                        insert(playlistEntity)
                        songMetadata.onEach { insert(it) }
                        val songIds = songMetadata.map { it.id to it.setVideoId }
                        val created = database.playlistBlocking(playlistEntity.id)
                        if (created != null) {
                            database.addSongsToPlaylist(created, songIds)
                        }
                    }
                }
            }
        },
        onPlay = {
            if (songs.isNotEmpty()) {
                playerConnection?.playQueue(
                    YouTubePlaylistQueue(
                        playlistId = currentPlaylist.id,
                        playlistTitle = currentPlaylist.title,
                        initialSongs = songs,
                        startIndex = 0
                    )
                )
            }
        },
        onShuffle = {
            if (songs.isNotEmpty()) {
                playerConnection?.playQueue(
                    YouTubePlaylistQueue(
                        playlistId = currentPlaylist.id,
                        playlistTitle = currentPlaylist.title,
                        initialSongs = songs.shuffled(),
                        startIndex = 0
                    )
                )
            }
        },
        onMoreOptions = { showMoreMenu = true },
        trackList = trackList,
        currentPlayingId = mediaMetadata?.id,
        isPlaying = isPlaying,
        onTrackClick = { index ->
            playerConnection?.playQueue(
                YouTubePlaylistQueue(
                    playlistId = currentPlaylist.id,
                    playlistTitle = currentPlaylist.title,
                    initialSongs = songs,
                    startIndex = index
                )
            )
        }
    )

    if (showMoreMenu) {
        TvPlaylistOptionDialog(
            title = currentPlaylist.title,
            options = listOf(
                TvMenuOption(if (isLiked) "Remove from Library" else "Save to Library", if (isLiked) R.drawable.favorite else R.drawable.favorite_border) {
                    // Handled above
                },
                TvMenuOption("Add to Queue", R.drawable.queue_music) {
                    playerConnection?.playQueue(
                        YouTubePlaylistQueue(
                            playlistId = currentPlaylist.id,
                            playlistTitle = currentPlaylist.title,
                            initialSongs = songs,
                            startIndex = 0
                        )
                    )
                }
            ),
            onDismiss = { showMoreMenu = false }
        )
    }
}

/** ── Album Detail ────────────────────────────────────────────────────────── */
@Composable
private fun TvAlbumContent(
    albumId: String,
    navController: NavController,
    viewModel: AlbumViewModel = hiltViewModel()
) {
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val albumWithSongs by viewModel.albumWithSongs.collectAsStateWithLifecycle()
    val isPlaying by (playerConnection?.isPlaying?.collectAsState() ?: remember { mutableStateOf(false) })
    val mediaMetadata by (playerConnection?.mediaMetadata?.collectAsState() ?: remember { mutableStateOf(null) })
    val isBookmarked by (albumWithSongs?.album?.bookmarkedAt?.let { remember(it) { mutableStateOf(true) } }
        ?: remember { mutableStateOf(false) })
    var showMoreMenu by remember { mutableStateOf(false) }

    val songs = albumWithSongs?.songs ?: emptyList()
    val trackList = remember(songs) {
        songs.map { it.toMediaMetadata() }
    }

    val albumTitle = albumWithSongs?.album?.title ?: "Album"
    val artistName = albumWithSongs?.artists?.joinToString { it.name } ?: "Artist"

    TvMediaContainer(
        title = albumTitle,
        subtitle = "$artistName • ${songs.size} songs",
        artworkUrl = albumWithSongs?.album?.thumbnailUrl,
        isLiked = isBookmarked,
        onToggleLike = {
            albumWithSongs?.album?.let { album ->
                database.query { update(album.toggleLike()) }
            }
        },
        onPlay = {
            if (songs.isNotEmpty()) {
                playerConnection?.playQueue(
                    ListQueue(
                        title = albumTitle,
                        items = songs.map { it.toMediaItem() }
                    )
                )
            }
        },
        onShuffle = {
            if (songs.isNotEmpty()) {
                playerConnection?.playQueue(
                    ListQueue(
                        title = albumTitle,
                        items = songs.shuffled().map { it.toMediaItem() }
                    )
                )
            }
        },
        onMoreOptions = { showMoreMenu = true },
        trackList = trackList,
        currentPlayingId = mediaMetadata?.id,
        isPlaying = isPlaying,
        onTrackClick = { index ->
            playerConnection?.playQueue(
                ListQueue(
                    title = albumTitle,
                    items = songs.map { it.toMediaItem() },
                    startIndex = index
                )
            )
        }
    )

    if (showMoreMenu) {
        TvPlaylistOptionDialog(
            title = albumTitle,
            options = listOf(
                TvMenuOption("Play All", R.drawable.play) {
                    playerConnection?.playQueue(ListQueue(title = albumTitle, items = songs.map { it.toMediaItem() }))
                },
                TvMenuOption(if (isBookmarked) "Remove from Library" else "Save to Library", if (isBookmarked) R.drawable.favorite else R.drawable.favorite_border) {
                    albumWithSongs?.album?.let { album -> database.query { update(album.toggleLike()) } }
                }
            ),
            onDismiss = { showMoreMenu = false }
        )
    }
}

/**
 * Master 2-pane Spotify TV Media Layout (Left: Hero & Controls, Right: Track List)
 */
@Composable
private fun TvMediaContainer(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    isCustomGradientIcon: Boolean = false,
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onMoreOptions: () -> Unit,
    trackList: List<MediaMetadata>,
    currentPlayingId: String?,
    isPlaying: Boolean,
    onTrackClick: (Int) -> Unit
) {
    val playButtonFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try {
            playButtonFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.BackgroundDark)
            .padding(horizontal = 48.dp, vertical = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp)
    ) {
        // ── Left Column: Artwork + Details + Primary Action Buttons ──────────
        Column(
            modifier = Modifier
                .weight(0.38f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start
        ) {
            // Artwork Card
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .shadow(16.dp, RoundedCornerShape(16.dp))
                    .background(TvColors.CardBackgroundElevated),
                contentAlignment = Alignment.Center
            ) {
                if (isCustomGradientIcon) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    colors = listOf(Color(0xFF450AF5), Color(0xFFC4EFD9))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.favorite),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(72.dp)
                        )
                    }
                } else {
                    AsyncImage(
                        model = artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Title
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TvColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(6.dp))

            // Subtitle
            Text(
                text = subtitle,
                style = MaterialTheme.typography.titleMedium,
                color = TvColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(28.dp))

            // ── Controls Row (Play, Shuffle, Like, More Options) ─────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Play Button (Large Spotify Green Circle)
                TvCircleActionButton(
                    iconRes = R.drawable.play,
                    contentDescription = "Play",
                    isPrimary = true,
                    focusRequester = playButtonFocusRequester,
                    onClick = onPlay
                )

                // Shuffle
                TvCircleActionButton(
                    iconRes = R.drawable.shuffle,
                    contentDescription = "Shuffle",
                    onClick = onShuffle
                )

                // Like / Heart
                TvCircleActionButton(
                    iconRes = if (isLiked) R.drawable.favorite else R.drawable.favorite_border,
                    contentDescription = "Like",
                    tint = if (isLiked) Color(0xFFE91E63) else Color.White,
                    onClick = onToggleLike
                )

                // More Options (...)
                TvCircleActionButton(
                    iconRes = R.drawable.more_vert,
                    contentDescription = "More options",
                    onClick = onMoreOptions
                )
            }
        }

        // ── Right Column: Interactive Track list ─────────────────────────────
        Column(
            modifier = Modifier
                .weight(0.62f)
                .fillMaxHeight()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(trackList) { index, item ->
                    val isTrackActive = item.id == currentPlayingId
                    TvTrackRow(
                        index = index + 1,
                        mediaMetadata = item,
                        isActive = isTrackActive,
                        isPlaying = isTrackActive && isPlaying,
                        onClick = { onTrackClick(index) }
                    )
                }
            }
        }
    }
}

/** Focus-aware Track Row in TV Playlist Screen */
@Composable
private fun TvTrackRow(
    index: Int,
    mediaMetadata: MediaMetadata,
    isActive: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    isFocused -> TvColors.OverlayFocused
                    isActive -> TvColors.SpotifyGreen.copy(alpha = 0.12f)
                    else -> Color.Transparent
                }
            )
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .focusable(interactionSource = interactionSource)
            .onKeyEvent {
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    onClick()
                    true
                } else false
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Track number or playing indicator
        Box(
            modifier = Modifier.width(36.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (isPlaying) {
                PlayingIndicator(
                    color = TvColors.SpotifyGreenBright,
                    modifier = Modifier.height(16.dp),
                    bars = 3,
                    barWidth = 3.dp,
                    cornerRadius = 2.dp
                )
            } else {
                Text(
                    text = "$index",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isActive) TvColors.SpotifyGreenBright else TvColors.TextTertiary
                )
            }
        }

        // Small thumbnail
        AsyncImage(
            model = mediaMetadata.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.DarkGray)
        )

        Spacer(Modifier.width(16.dp))

        // Title and Artist
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mediaMetadata.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isActive || isFocused) TvColors.SpotifyGreenBright else TvColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = mediaMetadata.artists.joinToString { it.name },
                style = MaterialTheme.typography.bodySmall,
                color = TvColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Duration (if available)
        mediaMetadata.duration?.let { durationSec ->
            if (durationSec > 0) {
                Text(
                    text = makeTimeString(durationSec * 1000L),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvColors.TextSecondary
                )
            }
        }
    }
}

/** Focus-aware Circular TV Action Button */
@Composable
private fun TvCircleActionButton(
    iconRes: Int,
    contentDescription: String,
    isPrimary: Boolean = false,
    focusRequester: FocusRequester? = null,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val size = if (isPrimary) 60.dp else 48.dp
    val bgColor = when {
        isFocused -> if (isPrimary) Color.White else TvColors.OverlayFocused
        isPrimary -> TvColors.SpotifyGreenBright
        else -> TvColors.OverlayLight
    }
    val iconTint = when {
        isFocused && isPrimary -> Color.Black
        isPrimary -> Color.Black
        isFocused -> Color.White
        else -> tint
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bgColor)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = CircleShape
            )
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .focusable(interactionSource = interactionSource)
            .onKeyEvent {
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    onClick()
                    true
                } else false
            }
            .clickable(onClick = onClick)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(if (isPrimary) 30.dp else 24.dp)
        )
    }
}

data class TvMenuOption(
    val title: String,
    val iconRes: Int,
    val onClick: () -> Unit
)

/** TV-Native D-pad Navigable Dialog for Playlist More Options */
@Composable
private fun TvPlaylistOptionDialog(
    title: String,
    options: List<TvMenuOption>,
    onDismiss: () -> Unit
) {
    val focusRequesters = remember(options) { List(options.size) { FocusRequester() } }

    LaunchedEffect(Unit) {
        if (focusRequesters.isNotEmpty()) {
            try {
                focusRequesters[0].requestFocus()
            } catch (_: Exception) {}
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(TvColors.CardBackgroundElevated)
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TvColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                options.forEachIndexed { index, option ->
                    val interactionSource = remember { MutableInteractionSource() }
                    val isFocused by interactionSource.collectIsFocusedAsState()

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isFocused) TvColors.SpotifyGreenBright else TvColors.CardBackground)
                            .focusRequester(focusRequesters[index])
                            .focusable(interactionSource = interactionSource)
                            .onKeyEvent {
                                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                    (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)) {
                                    option.onClick()
                                    onDismiss()
                                    true
                                } else false
                            }
                            .clickable {
                                option.onClick()
                                onDismiss()
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Icon(
                            painter = painterResource(option.iconRes),
                            contentDescription = null,
                            tint = if (isFocused) Color.Black else TvColors.SpotifyGreenBright,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = option.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isFocused) Color.Black else TvColors.TextPrimary
                        )
                    }
                }
            }
        }
    }
}
