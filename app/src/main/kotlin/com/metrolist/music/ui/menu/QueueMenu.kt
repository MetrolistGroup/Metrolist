/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import coil3.compose.AsyncImage
import com.metrolist.music.LocalNavController
import com.metrolist.innertube.YouTube
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.LocalSyncUtils
import com.metrolist.music.R
import com.metrolist.music.constants.ListItemHeight
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.ExoDownloadService
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.component.BottomSheetState
import com.metrolist.music.ui.component.ListDialog
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.ui.component.MediaMetadataListItem
import com.metrolist.music.ui.component.NewAction
import com.metrolist.music.ui.component.NewActionGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

@Composable
fun QueueMenu(
    mediaMetadata: MediaMetadata?,
    playerBottomSheetState: BottomSheetState,
    onShowDetailsDialog: () -> Unit,
    onDismiss: () -> Unit,
) {
    mediaMetadata ?: return
    val navController = LocalNavController.current
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val coroutineScope = rememberCoroutineScope()
    val syncUtils = LocalSyncUtils.current

    val librarySong by database.song(mediaMetadata.id).collectAsStateWithLifecycle(initialValue = null)
    val download by LocalDownloadUtil.current.getDownload(mediaMetadata.id)
        .collectAsStateWithLifecycle(initialValue = null)

    var refetchIconDegree by remember { mutableFloatStateOf(0f) }
    val rotationAnimation by animateFloatAsState(
        targetValue = refetchIconDegree,
        animationSpec = tween(durationMillis = 800),
        label = "",
    )

    val artists = remember(mediaMetadata.artists) {
        mediaMetadata.artists.filter { it.id != null }
    }

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    AddToPlaylistDialog(
        isVisible = showChoosePlaylistDialog,
        onGetSong = { playlist ->
            database.withTransaction {
                insert(mediaMetadata)
            }
            coroutineScope.launch(Dispatchers.IO) {
                playlist.playlist.browseId?.let { YouTube.addToPlaylist(it, mediaMetadata.id) }
            }
            listOf(mediaMetadata.id)
        },
        onGetSongIds = { listOf(mediaMetadata.id) },
        onDismiss = {
            showChoosePlaylistDialog = false
        }
    )

    var showSelectArtistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showSelectArtistDialog) {
        ListDialog(
            onDismiss = { showSelectArtistDialog = false },
        ) {
            items(artists) { artist ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(ListItemHeight)
                        .clickable {
                            navController.navigate("artist/${artist.id}")
                            showSelectArtistDialog = false
                            playerBottomSheetState.collapseSoft()
                            onDismiss()
                        }
                        .padding(horizontal = 12.dp),
                ) {
                    Box(
                        modifier = Modifier.padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = null,
                            contentDescription = null,
                            modifier = Modifier
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
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }

    // Song header with like button (for episodes, this toggles save for later)
    val isEpisode = librarySong?.song?.isEpisode == true || mediaMetadata.isEpisode
    val isFavorite = if (isEpisode) librarySong?.song?.inLibrary != null else librarySong?.song?.liked == true
    MediaMetadataListItem(
        mediaMetadata = mediaMetadata,
        trailingContent = {
            IconButton(
                onClick = {
                    coroutineScope.launch(Dispatchers.IO) {
                        database.transaction {
                            if (librarySong == null) {
                                insert(mediaMetadata)
                            }
                        }
                        val dbSong = database.song(mediaMetadata.id).firstOrNull()
                        dbSong?.let { songWithArtists ->
                            val songEntity = songWithArtists.song
                            if (songEntity.isEpisode) {
                                // Episode: toggle save for later
                                val isCurrentlySaved = songEntity.inLibrary != null
                                database.query {
                                    update(songEntity.copy(inLibrary = if (isCurrentlySaved) null else java.time.LocalDateTime.now()))
                                }
                                launch {
                                    if (isCurrentlySaved) {
                                        val setVideoIdEntity = database.getSetVideoId(songEntity.id)
                                        val setVideoId = setVideoIdEntity?.setVideoId
                                        if (setVideoId != null) {
                                            YouTube.removeEpisodeFromSavedEpisodes(songEntity.id, setVideoId).onSuccess {
                                                timber.log.Timber.d("[EPISODE_SAVE] Removed episode from Episodes for Later: ${songEntity.id}")
                                            }.onFailure { e ->
                                                timber.log.Timber.e(e, "[EPISODE_SAVE] Failed to remove episode: ${songEntity.id}")
                                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                                    android.widget.Toast.makeText(context, R.string.error_episode_remove, android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    } else {
                                        YouTube.addEpisodeToSavedEpisodes(songEntity.id).onSuccess {
                                            timber.log.Timber.d("[EPISODE_SAVE] Saved episode to Episodes for Later: ${songEntity.id}")
                                        }.onFailure { e ->
                                            timber.log.Timber.e(e, "[EPISODE_SAVE] Failed to save episode: ${songEntity.id}")
                                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                                android.widget.Toast.makeText(context, R.string.error_episode_save, android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Regular song: toggle like
                                val s = songEntity.toggleLike()
                                database.query {
                                    update(s)
                                }
                                syncUtils.likeSong(s)
                            }
                        }
                    }
                },
            ) {
                Icon(
                    painter = painterResource(
                        if (isFavorite) R.drawable.favorite
                        else R.drawable.favorite_border
                    ),
                    tint = if (isFavorite) MaterialTheme.colorScheme.error
                    else LocalContentColor.current,
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
        contentPadding = PaddingValues(
            start = 0.dp,
            top = 0.dp,
            end = 0.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
        ),
    ) {
        // Quick actions grid
        item {
            NewActionGrid(
                actions = listOf(
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.radio),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        text = stringResource(R.string.start_radio),
                        onClick = {
                            onDismiss()
                            val currentMediaId = playerConnection.player.currentMediaItemIndex.let {
                                playerConnection.player.getMediaItemAt(it).mediaId
                            }
                            if (mediaMetadata.id == currentMediaId) {
                                playerConnection.startRadioSeamlessly()
                            } else {
                                playerConnection.playQueue(
                                    YouTubeQueue.radio(mediaMetadata)
                                )
                            }
                        }
                    ),
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.playlist_add),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        text = stringResource(R.string.add_to_playlist),
                        onClick = { showChoosePlaylistDialog = true }
                    ),
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.share),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        text = stringResource(R.string.share),
                        onClick = {
                            onDismiss()
                            val intent = Intent().apply {
                                action = Intent.ACTION_SEND
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "https://music.youtube.com/watch?v=${mediaMetadata.id}"
                                )
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    )
                ),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp)
            )
        }

        // Play next / Add to queue
        item {
            Material3MenuGroup(
                items = listOf(
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
                            librarySong?.let {
                                playerConnection.playNext(it.toMediaItem())
                            } ?: run {
                                playerConnection.playNext(mediaMetadata.toMediaItem())
                            }
                        }
                    ),
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
                            librarySong?.let {
                                playerConnection.addToQueue(it.toMediaItem())
                            } ?: run {
                                playerConnection.addToQueue(mediaMetadata.toMediaItem())
                            }
                        }
                    )
                )
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        // Download section
        item {
            Material3MenuGroup(
                items = listOf(
                    when (download?.state) {
                        Download.STATE_COMPLETED -> {
                            Material3MenuItemData(
                                title = {
                                    Text(text = stringResource(R.string.remove_download))
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.offline),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                onClick = {
                                    DownloadService.sendRemoveDownload(
                                        context,
                                        ExoDownloadService::class.java,
                                        mediaMetadata.id,
                                        false,
                                    )
                                }
                            )
                        }

                        Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.downloading)) },
                                icon = {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                },
                                onClick = {
                                    DownloadService.sendRemoveDownload(
                                        context,
                                        ExoDownloadService::class.java,
                                        mediaMetadata.id,
                                        false,
                                    )
                                }
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
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                onClick = {
                                    database.transaction {
                                        insert(mediaMetadata)
                                    }
                                    val downloadRequest =
                                        DownloadRequest
                                            .Builder(mediaMetadata.id, mediaMetadata.id.toUri())
                                            .setCustomCacheKey(mediaMetadata.id)
                                            .setData(mediaMetadata.title.toByteArray())
                                            .build()
                                    DownloadService.sendAddDownload(
                                        context,
                                        ExoDownloadService::class.java,
                                        downloadRequest,
                                        false,
                                    )
                                }
                            )
                        }
                    }
                )
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        // Navigation section (Artist, Album)
        item {
            Material3MenuGroup(
                items = buildList {
                    if (artists.isNotEmpty()) {
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.view_artist)) },
                                description = {
                                    Text(
                                        text = mediaMetadata.artists.joinToString { it.name },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.artist),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                onClick = {
                                    if (mediaMetadata.artists.size == 1) {
                                        navController.navigate("artist/${mediaMetadata.artists[0].id}")
                                        playerBottomSheetState.collapseSoft()
                                        onDismiss()
                                    } else {
                                        showSelectArtistDialog = true
                                    }
                                }
                            )
                        )
                    }
                    if (mediaMetadata.album != null) {
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.view_album)) },
                                description = {
                                    Text(
                                        text = mediaMetadata.album.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.album),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                onClick = {
                                    navController.navigate("album/${mediaMetadata.album.id}")
                                    playerBottomSheetState.collapseSoft()
                                    onDismiss()
                                }
                            )
                        )
                    }
                }
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        // Details and refetch section
        item {
            Material3MenuGroup(
                items = buildList {
                    add(
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
                                coroutineScope.launch(Dispatchers.IO) {
                                    YouTube.queue(listOf(mediaMetadata.id)).onSuccess {
                                        val newSong = it.firstOrNull()
                                        if (newSong != null && librarySong != null) {
                                            database.transaction {
                                                update(librarySong!!, newSong.toMediaMetadata())
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    )
                    add(
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.details)) },
                            description = { Text(text = stringResource(R.string.details_desc)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.info),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            onClick = {
                                onShowDetailsDialog()
                                onDismiss()
                            }
                        )
                    )
                }
            )
        }
    }
}
