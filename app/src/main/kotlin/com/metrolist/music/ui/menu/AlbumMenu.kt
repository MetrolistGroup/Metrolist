/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
import androidx.media3.exoplayer.offline.Download.STATE_DOWNLOADING
import androidx.media3.exoplayer.offline.Download.STATE_QUEUED
import androidx.media3.exoplayer.offline.Download.STATE_STOPPED
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import coil3.compose.AsyncImage
import com.metrolist.music.LocalNavController
import com.metrolist.innertube.YouTube
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.ListItemHeight
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SpeedDialItem
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.ExoDownloadService
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.AlbumListItem
import com.metrolist.music.ui.component.ListDialog
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.ui.component.NewAction
import com.metrolist.music.ui.component.NewActionGrid
import com.metrolist.music.ui.component.SongListItem
import com.metrolist.music.ui.menu.ExportDialog
import com.metrolist.music.utils.PlaylistExporter
import com.metrolist.music.utils.getExportFileUri
import com.metrolist.music.utils.saveToPublicDocuments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@SuppressLint("MutableCollectionMutableState")
@Composable
fun AlbumMenu(
    originalAlbum: Album,
    onDismiss: () -> Unit,
) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val listenTogetherManager = LocalListenTogetherManager.current
    val isGuest = listenTogetherManager?.isInRoom == true && !listenTogetherManager.isHost
    val scope = rememberCoroutineScope()
    val libraryAlbum by database.album(originalAlbum.id).collectAsStateWithLifecycle(initialValue = originalAlbum)
    val album = libraryAlbum ?: originalAlbum
    var songs by remember {
        mutableStateOf(emptyList<Song>())
    }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        database.albumSongs(album.id).collect {
            songs = it
        }
    }

    var downloadState by remember {
        mutableIntStateOf(STATE_STOPPED)
    }

    LaunchedEffect(songs) {
        if (songs.isEmpty()) return@LaunchedEffect
        downloadUtil.downloads.collect { downloads ->
            downloadState =
                if (songs.all { downloads[it.id]?.state == STATE_COMPLETED }) {
                    STATE_COMPLETED
                } else if (songs.all {
                        downloads[it.id]?.state == STATE_QUEUED ||
                            downloads[it.id]?.state == STATE_DOWNLOADING ||
                            downloads[it.id]?.state == STATE_COMPLETED
                    }
                ) {
                    STATE_DOWNLOADING
                } else {
                    STATE_STOPPED
                }
        }
    }

    var refetchIconDegree by remember { mutableFloatStateOf(0f) }

    val rotationAnimation by animateFloatAsState(
        targetValue = refetchIconDegree,
        animationSpec = tween(durationMillis = 800),
        label = "",
    )

    val isPinned by database.speedDialDao.isPinned(album.id).collectAsStateWithLifecycle(initialValue = false)

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var showSelectArtistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var showErrorPlaylistAddDialog by rememberSaveable {
        mutableStateOf(false)
    }

    val notAddedList by remember {
        mutableStateOf(mutableListOf<Song>())
    }

    AddToPlaylistDialog(
        isVisible = showChoosePlaylistDialog,
        onGetSong = { playlist ->
            coroutineScope.launch(Dispatchers.IO) {
                playlist.playlist.browseId?.let { playlistId ->
                    album.album.playlistId?.let { addPlaylistId ->
                        YouTube.addPlaylistToPlaylist(playlistId, addPlaylistId)
                    }
                }
            }
            songs.map { it.id }
        },
        onGetSongIds = { songs.map { it.id } },
        onDismiss = {
            showChoosePlaylistDialog = false
        },
    )

    if (showErrorPlaylistAddDialog) {
        ListDialog(
            onDismiss = {
                showErrorPlaylistAddDialog = false
                onDismiss()
            },
        ) {
            item {
                ListItem(
                    title = stringResource(R.string.already_in_playlist),
                    thumbnailContent = {
                        Image(
                            painter = painterResource(R.drawable.close),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                            modifier = Modifier.size(ListThumbnailSize),
                        )
                    },
                    modifier =
                        Modifier
                            .clickable { showErrorPlaylistAddDialog = false },
                )
            }

            items(notAddedList) { song ->
                SongListItem(song = song)
            }
        }
    }

    if (showSelectArtistDialog) {
        ListDialog(
            onDismiss = { showSelectArtistDialog = false },
        ) {
            items(
                items = album.artists.distinctBy { it.id },
                key = { "menu_album_artist_${it.id}" },
            ) { artist ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .height(ListItemHeight)
                            .clickable {
                                navController.navigate("artist/${artist.id}")
                                showSelectArtistDialog = false
                                onDismiss()
                            }.padding(horizontal = 12.dp),
                ) {
                    Box(
                        modifier = Modifier.padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = artist.thumbnailUrl,
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .size(ListThumbnailSize)
                                    .clip(CircleShape),
                        )
                    }
                    Text(
                        text = artist.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }

    AlbumListItem(
        album = album,
        showLikedIcon = false,
        badges = {},
        trailingContent = {
            IconButton(
                onClick = {
                    database.query {
                        update(album.album.toggleLike())
                    }
                },
            ) {
                Icon(
                    painter = painterResource(if (album.album.bookmarkedAt != null) R.drawable.favorite else R.drawable.favorite_border),
                    tint = if (album.album.bookmarkedAt != null) MaterialTheme.colorScheme.error else LocalContentColor.current,
                    contentDescription = null,
                )
            }
        },
    )

    HorizontalDivider()

    Spacer(modifier = Modifier.height(12.dp))

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    LazyColumn(
        contentPadding =
            PaddingValues(
                start = 0.dp,
                top = 0.dp,
                end = 0.dp,
                bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
            ),
    ) {
        item {
            NewActionGrid(
                actions =
                    listOfNotNull(
                        if (!isGuest) {
                            NewAction(
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.play),
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                text = stringResource(R.string.play),
                                onClick = {
                                    onDismiss()
                                    if (songs.isNotEmpty()) {
                                        playerConnection.playQueue(
                                            ListQueue(
                                                title = album.album.title,
                                                items = songs.map(Song::toMediaItem),
                                            ),
                                        )
                                    }
                                },
                            )

                            NewAction(
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.shuffle),
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                text = stringResource(R.string.shuffle),
                                onClick = {
                                    onDismiss()
                                    if (songs.isNotEmpty()) {
                                        album.album.playlistId?.let { playlistId ->
                                            playerConnection.service.getAutomix(playlistId)
                                        }
                                        playerConnection.playQueue(
                                            ListQueue(
                                                title = album.album.title,
                                                items = songs.shuffled().map(Song::toMediaItem),
                                            ),
                                        )
                                    }
                                },
                            )
                        } else {
                            null
                        },
                        NewAction(
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.share),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            text = stringResource(R.string.share),
                            onClick = {
                                onDismiss()
                                val intent =
                                    Intent().apply {
                                        action = Intent.ACTION_SEND
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/playlist?list=${album.album.playlistId}")
                                    }
                                context.startActivity(Intent.createChooser(intent, null))
                            },
                        ),
                    ),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                columns = if (isGuest) 1 else 3,
            )
        }
        item {
            Material3MenuGroup(
                items =
                    listOfNotNull(
                        if (!isGuest) {
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.play_next)) },
                                description = { Text(text = stringResource(R.string.play_next_desc)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.playlist_play),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    onDismiss()
                                    playerConnection.playNext(songs.map { it.toMediaItem() })
                                },
                            )
                        } else {
                            null
                        },
                        if (!isGuest) {
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.add_to_queue)) },
                                description = { Text(text = stringResource(R.string.add_to_queue_desc)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.queue_music),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    onDismiss()
                                    playerConnection.addToQueue(songs.map { it.toMediaItem() })
                                },
                            )
                        } else {
                            null
                        },
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.add_to_playlist)) },
                            description = { Text(text = stringResource(R.string.add_to_playlist_desc)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.playlist_add),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                showChoosePlaylistDialog = true
                            },
                        ),
                        Material3MenuItemData(
                            title = {
                                Text(
                                    text = if (isPinned) stringResource(R.string.unpin_from_speed_dial) else stringResource(R.string.pin_to_speed_dial),
                                )
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(if (isPinned) R.drawable.remove else R.drawable.add),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    if (isPinned) {
                                        database.speedDialDao.delete(album.id)
                                    } else {
                                        database.speedDialDao.insert(
                                            SpeedDialItem(
                                                id = album.id,
                                                secondaryId = album.album.playlistId,
                                                title = album.album.title,
                                                subtitle = album.artists.joinToString(", ") { it.name },
                                                subtitleIds = album.artists.joinToString(", ") { it.id },
                                                thumbnailUrl = album.album.thumbnailUrl,
                                                type = "ALBUM",
                                                explicit = album.album.explicit,
                                            ),
                                        )
                                    }
                                }
                                onDismiss()
                            },
                        ),
                    ),
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            Material3MenuGroup(
                items =
                    listOf(
                        when (downloadState) {
                            STATE_COMPLETED -> {
                                Material3MenuItemData(
                                    title = {
                                        Text(
                                            text = stringResource(R.string.remove_download),
                                        )
                                    },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.offline),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        songs.forEach { song ->
                                            DownloadService.sendRemoveDownload(
                                                context,
                                                ExoDownloadService::class.java,
                                                song.id,
                                                false,
                                            )
                                        }
                                    },
                                )
                            }

                            STATE_QUEUED, STATE_DOWNLOADING -> {
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.downloading)) },
                                    icon = {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    },
                                    onClick = {
                                        songs.forEach { song ->
                                            DownloadService.sendRemoveDownload(
                                                context,
                                                ExoDownloadService::class.java,
                                                song.id,
                                                false,
                                            )
                                        }
                                    },
                                )
                            }

                            else -> {
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.action_download)) },
                                    description = { Text(text = stringResource(R.string.download_desc)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.download),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        songs.forEach { song ->
                                            val downloadRequest =
                                                DownloadRequest
                                                    .Builder(song.id, song.id.toUri())
                                                    .setCustomCacheKey(song.id)
                                                    .setData(song.song.title.toByteArray())
                                                    .build()
                                            DownloadService.sendAddDownload(
                                                context,
                                                ExoDownloadService::class.java,
                                                downloadRequest,
                                                false,
                                            )
                                        }
                                    },
                                )
                            }
                        },
                    ),
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            // Export album as a playlist (CSV/M3U)
            var showExportDialog by remember { mutableStateOf(false) }
            Material3MenuGroup(
                items =
                    listOf(
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.export_playlist)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.share),
                                    contentDescription = null,
                                )
                            },
                            onClick = { showExportDialog = true },
                        ),
                    ),
            )

            val exportPlaylistStr = stringResource(R.string.export_playlist)

            if (showExportDialog) {
                ExportDialog(
                    onDismiss = { showExportDialog = false },
                    onShare = { format ->
                        val playlistSongs =
                            songs.map { s ->
                                com.metrolist.music.db.entities.PlaylistSong(
                                    map =
                                        com.metrolist.music.db.entities.PlaylistSongMap(
                                            songId = s.id,
                                            playlistId = album.id,
                                            position = 0,
                                        ),
                                    song = s,
                                )
                            }
                        val result =
                            when (format) {
                                "csv" -> PlaylistExporter.exportPlaylistAsCSV(context, album.album.title, playlistSongs)
                                "m3u" -> PlaylistExporter.exportPlaylistAsM3U(context, album.album.title, playlistSongs)
                                else -> Result.failure(IllegalArgumentException("Unknown format"))
                            }
                        result
                            .onSuccess { file ->
                                val uri = getExportFileUri(context, file)
                                val mimeType = if (format == "csv") "text/csv" else "audio/x-mpegurl"
                                val shareIntent =
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = mimeType
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                context.startActivity(Intent.createChooser(shareIntent, exportPlaylistStr))
                            }.onFailure {
                                Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                            }
                        showExportDialog = false
                    },
                    onSave = { format ->
                        val playlistSongs =
                            songs.map { s ->
                                com.metrolist.music.db.entities.PlaylistSong(
                                    map =
                                        com.metrolist.music.db.entities.PlaylistSongMap(
                                            songId = s.id,
                                            playlistId = album.id,
                                            position = 0,
                                        ),
                                    song = s,
                                )
                            }
                        val export =
                            when (format) {
                                "csv" -> PlaylistExporter.exportPlaylistAsCSV(context, album.album.title, playlistSongs)
                                "m3u" -> PlaylistExporter.exportPlaylistAsM3U(context, album.album.title, playlistSongs)
                                else -> Result.failure(IllegalArgumentException("Unknown format"))
                            }
                        export
                            .onSuccess { file ->
                                val mimeType = if (format == "csv") "text/csv" else "audio/x-mpegurl"
                                val save = saveToPublicDocuments(context, file, mimeType)
                                save
                                    .onSuccess { Toast.makeText(context, R.string.export_success, Toast.LENGTH_SHORT).show() }
                                    .onFailure { Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show() }
                            }.onFailure {
                                Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                            }
                        showExportDialog = false
                    },
                )
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            Material3MenuGroup(
                items =
                    listOf(
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.view_artist)) },
                            description = { Text(text = album.artists.joinToString { it.name }) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.artist),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                if (album.artists.size == 1) {
                                    navController.navigate("artist/${album.artists[0].id}")
                                    onDismiss()
                                } else {
                                    showSelectArtistDialog = true
                                }
                            },
                        ),
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.refetch)) },
                            description = { Text(text = stringResource(R.string.refetch_desc)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.sync),
                                    contentDescription = null,
                                    modifier = Modifier.graphicsLayer(rotationZ = rotationAnimation),
                                )
                            },
                            onClick = {
                                refetchIconDegree -= 360
                                scope.launch(Dispatchers.IO) {
                                    YouTube.album(album.id).onSuccess {
                                        database.transaction {
                                            update(album.album, it, album.artists)
                                        }
                                    }
                                }
                            },
                        ),
                    ),
            )
        }
    }
}
