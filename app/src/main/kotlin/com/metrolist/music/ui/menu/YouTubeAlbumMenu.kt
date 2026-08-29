/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.metrolist.innertube.YouTube
import com.metrolist.music.LocalNavController
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalArtistNameAliases
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.ListItemHeight
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SpeedDialItem
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.ExoDownloadService
import com.metrolist.music.playback.queues.YouTubeAlbumRadio
import com.metrolist.music.ui.component.ListDialog
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.ui.component.NewAction
import com.metrolist.music.ui.component.NewActionGrid
import com.metrolist.music.ui.component.SongListItem
import com.metrolist.music.ui.component.YouTubeListItem
import com.metrolist.music.utils.ArtistNameAliases
import com.metrolist.music.utils.reportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MutableCollectionMutableState")
@Composable
fun YouTubeAlbumMenu(
    albumItem: AlbumItem,
    onDismiss: () -> Unit,
) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val listenTogetherManager = LocalListenTogetherManager.current
    val isGuest = listenTogetherManager?.isInRoom == true && !listenTogetherManager.isHost
    val album by database.albumWithSongs(albumItem.id).collectAsStateWithLifecycle(initialValue = null)
    val isPinned by database.speedDialDao.isPinned(albumItem.id).collectAsStateWithLifecycle(initialValue = false)
    val artistNameAliases = LocalArtistNameAliases.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        database.album(albumItem.id).collect { album ->
            if (album == null) {
                YouTube
                    .album(albumItem.id)
                    .onSuccess { albumPage ->
                        database.transaction {
                            insert(albumPage)
                        }
                    }.onFailure {
                        reportException(it)
                    }
            }
        }
    }

    var downloadState by remember {
        mutableIntStateOf(Download.STATE_STOPPED)
    }

    LaunchedEffect(album) {
        val songs = album?.songs?.map { it.id } ?: return@LaunchedEffect
        downloadUtil.downloads.collect { downloads ->
            downloadState =
                if (songs.all { downloads[it]?.state == Download.STATE_COMPLETED }) {
                    Download.STATE_COMPLETED
                } else if (songs.all {
                        downloads[it]?.state == Download.STATE_QUEUED ||
                            downloads[it]?.state == Download.STATE_DOWNLOADING ||
                            downloads[it]?.state == Download.STATE_COMPLETED
                    }
                ) {
                    Download.STATE_DOWNLOADING
                } else {
                    Download.STATE_STOPPED
                }
        }
    }

    var showChoosePlaylistDialog by rememberSaveable {
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
                    album?.album?.playlistId?.let { addPlaylistId ->
                        YouTube.addPlaylistToPlaylist(playlistId, addPlaylistId)
                    }
                }
            }
            album?.songs?.map { it.id }.orEmpty()
        },
        onGetSongIds = { album?.songs?.map { it.id }.orEmpty() },
        onDismiss = { showChoosePlaylistDialog = false }
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
                    content = { Text(text = stringResource(R.string.already_in_playlist)) },
                    leadingContent = {
                        Image(
                            painter = painterResource(R.drawable.close),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                            modifier = Modifier.size(ListThumbnailSize),
                        )
                    },
                    modifier = Modifier.clickable { showErrorPlaylistAddDialog = false },
                )
            }

            items(notAddedList) { song ->
                SongListItem(song = song)
            }
        }
    }

    var showSelectArtistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showSelectArtistDialog) {
        ListDialog(
            onDismiss = { showSelectArtistDialog = false },
        ) {
            items(
                items = album?.artists.orEmpty().distinctBy { it.id },
                key = { "menu_yt_album_artist_${it.id}" },
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
                        contentAlignment = Alignment.CenterStart,
                        modifier =
                            Modifier
                                .fillParentMaxWidth()
                                .height(ListItemHeight)
                                .clickable {
                                    showSelectArtistDialog = false
                                    onDismiss()
                                    navController.navigate("artist/${artist.id}")
                                }.padding(horizontal = 24.dp),
                    ) {
                        Text(
                            text = ArtistNameAliases.resolve(artistNameAliases, artist.id, artist.name),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    YouTubeListItem(
        item = albumItem,
        badges = {},
        trailingContent = {
            IconButton(
                onClick = {
                    database.query {
                        album?.album?.toggleLike()?.let(::update)
                    }
                },
            ) {
                Icon(
                    painter = painterResource(if (album?.album?.bookmarkedAt != null) R.drawable.favorite else R.drawable.favorite_border),
                    tint = if (album?.album?.bookmarkedAt != null) MaterialTheme.colorScheme.error else LocalContentColor.current,
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
                                    album?.songs?.let { songs ->
                                        if (songs.isNotEmpty()) {
                                            playerConnection.playQueue(YouTubeAlbumRadio(albumItem.playlistId))
                                        }
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
                                    album?.songs?.let { songs ->
                                        if (songs.isNotEmpty()) {
                                            playerConnection.playQueue(YouTubeAlbumRadio(albumItem.playlistId))
                                        }
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
                                        putExtra(Intent.EXTRA_TEXT, albumItem.shareLink)
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
                                    album
                                        ?.songs
                                        ?.map { it.toMediaItem() }
                                        ?.let(playerConnection::playNext)
                                    onDismiss()
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
                                    album
                                        ?.songs
                                        ?.map { it.toMediaItem() }
                                        ?.let(playerConnection::addToQueue)
                                    onDismiss()
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
                                        database.speedDialDao.delete(albumItem.id)
                                    } else {
                                        database.speedDialDao.insert(SpeedDialItem.fromYTItem(albumItem))
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
                            Download.STATE_COMPLETED -> {
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
                                        album?.songs?.forEach { song ->
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

                            Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.downloading)) },
                                    icon = {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    },
                                    onClick = {
                                        album?.songs?.forEach { song ->
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
                                        album?.songs?.forEach { song ->
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

        albumItem.artists?.let { artists ->
            item { Spacer(modifier = Modifier.height(12.dp)) }
            item {
                Material3MenuGroup(
                    items =
                        listOf(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.view_artist)) },
                                description = {
                                    Text(
                                        text = artists.joinToString {
                                            ArtistNameAliases.resolve(artistNameAliases, it.id, it.name)
                                        },
                                    )
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.artist),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    if (artists.size == 1) {
                                        navController.navigate("artist/${artists[0].id}")
                                        onDismiss()
                                    } else {
                                        showSelectArtistDialog = true
                                    }
                                },
                            ),
                        ),
                )
            }
        }
    }
}
