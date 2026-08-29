/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.metrolist.innertube.YouTube
import com.metrolist.music.LocalNavController
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalArtistNameAliases
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.LocalSyncUtils
import com.metrolist.music.R
import com.metrolist.music.constants.ListItemHeight
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.db.entities.SpeedDialItem
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.ExoDownloadService
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.component.ListDialog
import com.metrolist.music.ui.component.LocalBottomSheetPageState
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.ui.component.NewAction
import com.metrolist.music.ui.component.NewActionGrid
import com.metrolist.music.ui.utils.ShowMediaInfo
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.utils.ArtistNameAliases
import com.metrolist.music.utils.joinByBullet
import com.metrolist.music.utils.makeTimeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDateTime

@SuppressLint("MutableCollectionMutableState")
@Composable
fun YouTubeSongMenu(
    song: SongItem,
    onDismiss: () -> Unit,
    onHistoryRemoved: () -> Unit = {}
) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val librarySong by database.song(song.id).collectAsStateWithLifecycle(initialValue = null)
    val download by LocalDownloadUtil.current.getDownload(song.id).collectAsStateWithLifecycle(initialValue = null)
    val coroutineScope = rememberCoroutineScope()
    val syncUtils = LocalSyncUtils.current
    val listenTogetherManager = LocalListenTogetherManager.current
    val isPinned by database.speedDialDao.isPinned(song.id).collectAsStateWithLifecycle(initialValue = false)
    val artistNameAliases = LocalArtistNameAliases.current
    val artists = remember(song.artists, artistNameAliases) {
        song.artists.mapNotNull {
            it.id?.let { artistId ->
                MediaMetadata.Artist(
                    id = artistId,
                    name = ArtistNameAliases.resolve(artistNameAliases, artistId, it.name),
                )
            }
        }
    }

    var showChoosePlaylistDialog by rememberSaveable {  
        mutableStateOf(false)  
    }  

    AddToPlaylistDialog(
        isVisible = showChoosePlaylistDialog,
        onGetSong = { playlist ->
            database.withTransaction {
                insert(song.toMediaMetadata())
            }
            coroutineScope.launch(Dispatchers.IO) {
                playlist.playlist.browseId?.let { browseId ->
                    YouTube.addToPlaylist(browseId, song.id)
                }
            }
            listOf(song.id)
        },
        onGetSongIds = { listOf(song.id) },
        onDismiss = { showChoosePlaylistDialog = false }
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
                    modifier =  
                    Modifier  
                        .height(ListItemHeight)  
                        .clickable {  
                            navController.navigate("artist/${artist.id}")  
                            showSelectArtistDialog = false  
                            onDismiss()  
                        }  
                        .padding(horizontal = 12.dp),  
                ) {  
                    Box(  
                        contentAlignment = Alignment.CenterStart,  
                        modifier =  
                        Modifier  
                            .fillParentMaxWidth()  
                            .height(ListItemHeight)  
                            .clickable {  
                                navController.navigate("artist/${artist.id}")  
                                showSelectArtistDialog = false  
                                onDismiss()  
                            }  
                            .padding(horizontal = 24.dp),  
                    ) {  
                        Text(  
                            text = artist.name,  
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

    ListItem(  
        content = {
            Text(
                text = song.title,
                modifier = Modifier.basicMarquee(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },  
        supportingContent = {  
            Text(  
                text = joinByBullet(
                    song.artists.joinToString {
                        ArtistNameAliases.resolve(artistNameAliases, it.id, it.name)
                    },
                    song.duration?.let { makeTimeString(it * 1000L) },
                )
            )  
        },  
        leadingContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(ListThumbnailSize)
                    .clip(RoundedCornerShape(ThumbnailCornerRadius))
            ) {
                AsyncImage(
                    model = song.thumbnail.resize(200, 200),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ThumbnailCornerRadius))
                )
            }
        },
        trailingContent = {
            // For episodes, show saved state and toggle save for later
            val isEpisode = song.isEpisode
            val isFavorite = if (isEpisode) librarySong?.song?.inLibrary != null else librarySong?.song?.liked == true
            IconButton(
                onClick = {
                    if (isEpisode) {
                        // Episode: toggle save for later
                        val currentLibrarySong = librarySong
                        val isCurrentlySaved = currentLibrarySong?.song?.inLibrary != null
                        val shouldBeSaved = !isCurrentlySaved

                        // Update local database first (optimistic update)
                        database.query {
                            if (currentLibrarySong != null) {
                                update(currentLibrarySong.song.copy(inLibrary = if (shouldBeSaved) LocalDateTime.now() else null))
                            } else {
                                insert(song.toMediaMetadata().toSongEntity().copy(inLibrary = LocalDateTime.now(), isEpisode = true))
                            }
                        }

                        // Sync with YouTube (handles login check internally)
                        coroutineScope.launch(Dispatchers.IO) {
                            val setVideoId = if (isCurrentlySaved) song.setVideoId ?: database.getSetVideoId(song.id)?.setVideoId else null
                            syncUtils.saveEpisode(song.id, shouldBeSaved, setVideoId)
                        }
                    } else {
                        // Regular song: toggle like
                        database.transaction {
                            librarySong.let { librarySong ->
                                val s: SongEntity
                                if (librarySong == null) {
                                    insert(song.toMediaMetadata(), SongEntity::toggleLike)
                                    s = song.toMediaMetadata().toSongEntity().let(SongEntity::toggleLike)
                                } else {
                                    s = librarySong.song.toggleLike()
                                    update(s)
                                }
                                syncUtils.likeSong(s)
                            }
                        }
                    }
                },
            ) {
                Icon(
                    painter = painterResource(if (isFavorite) R.drawable.favorite else R.drawable.favorite_border),
                    tint = if (isFavorite) MaterialTheme.colorScheme.error else LocalContentColor.current,
                    contentDescription = null,
                )
            }
        },  
    )  

    HorizontalDivider()

    Spacer(modifier = Modifier.height(12.dp))

    val bottomSheetPageState = LocalBottomSheetPageState.current
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    val isGuest = listenTogetherManager?.isInRoom == true && !listenTogetherManager.isHost

    LazyColumn(
        contentPadding = PaddingValues(
            start = 0.dp,
            top = 0.dp,
            end = 0.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
        ),
    ) {
        item {
            NewActionGrid(
                actions = listOfNotNull(
                    if (!isGuest) {
                        NewAction(
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.playlist_play),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            text = stringResource(R.string.play_next),
                            onClick = {
                                playerConnection.playNext(song.copy(thumbnail = song.thumbnail.resize(544,544)).toMediaItem())
                                onDismiss()
                            }
                        )
                    } else null,
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
                        onClick = {
                            showChoosePlaylistDialog = true
                        }
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
                            val intent = Intent().apply {
                                action = Intent.ACTION_SEND
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, song.shareLink)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                            onDismiss()
                        }
                    )
                ),
                columns = if (isGuest) 2 else 3,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp)
            )
        }

        item {
            Material3MenuGroup(
                items = listOfNotNull(
                    if (listenTogetherManager != null && listenTogetherManager.isInRoom && !listenTogetherManager.isHost) {
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.suggest_to_host)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.queue_music),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                val durationMs = if (song.duration != null && song.duration!! > 0) song.duration!! * 1000L else 180000L
                                val trackInfo = com.metrolist.music.listentogether.TrackInfo(
                                    id = song.id,
                                    title = song.title,
                                    artist = artists.joinToString(", ") { it.name },
                                    album = song.album?.name,
                                    duration = durationMs,
                                    thumbnail = song.thumbnail
                                )
                                listenTogetherManager.suggestTrack(trackInfo)
                                onDismiss()
                            }
                        )
                    } else null,
                    if (!isGuest) {
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.start_radio)) },
                            description = { Text(text = stringResource(R.string.start_radio_desc)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.radio),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                playerConnection.playQueue(YouTubeQueue.radio(song.toMediaMetadata()))
                                onDismiss()
                            }
                        )
                    } else null,
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
                                playerConnection.addToQueue(song.toMediaItem())
                                onDismiss()
                            }
                        )
                    } else null
                )
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            Material3MenuGroup(
                items = buildList {
                    // Save/Remove for Later option for podcast episodes
                    if (song.isEpisode) {
                        if (song.setVideoId != null) {
                            // Episode is saved - show remove option
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.remove_episode_from_saved)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.remove),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        // Update local database first (optimistic update)
                                        database.query {
                                            librarySong?.song?.let { update(it.copy(inLibrary = null)) }
                                        }
                                        // Sync with YouTube (handles login check internally)
                                        syncUtils.saveEpisode(song.id, false, song.setVideoId)
                                        onDismiss()
                                    }
                                )
                            )
                        } else {
                            // Episode not saved - show save option
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.save_episode_for_later)) },
                                    description = { Text(text = stringResource(R.string.save_episode_for_later_desc)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.playlist_add),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        // Update local database first (optimistic update)
                                        database.query {
                                            if (librarySong != null) {
                                                update(librarySong!!.song.copy(inLibrary = java.time.LocalDateTime.now()))
                                            } else {
                                                insert(song.toMediaMetadata().toSongEntity().copy(inLibrary = java.time.LocalDateTime.now(), isEpisode = true))
                                            }
                                        }
                                        // Sync with YouTube (handles login check internally)
                                        syncUtils.saveEpisode(song.id, true, null)
                                        onDismiss()
                                    }
                                )
                            )
                        }
                    }
                    if (song.historyRemoveToken != null) {
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.remove_from_history)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.delete),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    coroutineScope.launch {
                                        Timber.d("[HISTORY_REMOVE] Removing song ${song.id} from YTM history")
                                        YouTube.feedback(listOf(song.historyRemoveToken!!))
                                            .onSuccess {
                                                Timber.d("[HISTORY_REMOVE] Successfully removed from YTM history")
                                            }
                                            .onFailure { e ->
                                                Timber.e(e, "[HISTORY_REMOVE] Failed to remove from YTM history")
                                            }
                                        delay(500)
                                        onHistoryRemoved()
                                        onDismiss()
                                    }
                                }
                            )
                        )
                    }
                    add(
                        Material3MenuItemData(
                            title = { 
                                Text(
                                    text = if (isPinned) stringResource(R.string.unpin_from_speed_dial) else stringResource(R.string.pin_to_speed_dial)
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
                                        database.speedDialDao.delete(song.id)
                                    } else {
                                        database.speedDialDao.insert(SpeedDialItem.fromYTItem(song))
                                    }
                                }
                                onDismiss()
                            }
                        )
                    )
                    add(
                        Material3MenuItemData(
                            title = {
                                Text(text = if (librarySong?.song?.inLibrary != null) stringResource(R.string.remove_from_library) else stringResource(R.string.add_to_library))
                            },
                            description = { Text(text = stringResource(R.string.add_to_library_desc)) },
                            icon = {
                                Icon(
                                    painter = painterResource(if (librarySong?.song?.inLibrary != null) R.drawable.library_add_check else R.drawable.library_add),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                val isInLibrary = librarySong?.song?.inLibrary != null

                                // Use the new reliable method that fetches fresh tokens
                                coroutineScope.launch {
                                    YouTube.toggleSongLibrary(song.id, !isInLibrary)
                                }

                                if (isInLibrary) {
                                    database.query {
                                        inLibrary(song.id, null)
                                    }
                                } else {
                                    database.transaction {
                                        insert(song.toMediaMetadata())
                                        inLibrary(song.id, LocalDateTime.now())
                                        addLibraryTokens(
                                            song.id,
                                            song.libraryAddToken,
                                            song.libraryRemoveToken
                                        )
                                    }
                                }
                            }
                        )
                    )
                }
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            Material3MenuGroup(
                items = listOf(
                    when (download?.state) {
                        Download.STATE_COMPLETED -> {
                            Material3MenuItemData(
                                title = {
                                    Text(
                                        text = stringResource(R.string.remove_download)
                                    )
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.offline),
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    DownloadService.sendRemoveDownload(
                                        context,
                                        ExoDownloadService::class.java,
                                        song.id,
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
                                        song.id,
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
                                    )
                                },
                                onClick = {
                                    database.transaction {
                                        insert(song.toMediaMetadata())
                                    }
                                    val downloadRequest = DownloadRequest
                                        .Builder(song.id, song.id.toUri())
                                        .setCustomCacheKey(song.id)
                                        .setData(song.title.toByteArray())
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

        item {
            // Check if this is a podcast episode (album ID doesn't start with MPREb_)
            val isPodcast = song.album?.let { !it.id.startsWith("MPREb_") } ?: false

            Material3MenuGroup(
                items = buildList {
                    // Don't show "View Artist" for podcasts - only show "View Podcast"
                    if (artists.isNotEmpty() && !isPodcast) {
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.view_artist)) },
                                description = {
                                    Text(
                                        text = song.artists.joinToString {
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
                                }
                            )
                        )
                    }
                    song.album?.let { album ->
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(if (isPodcast) R.string.view_podcast else R.string.view_album)) },
                                description = { Text(text = album.name) },
                                icon = {
                                    Icon(
                                        painter = painterResource(if (isPodcast) R.drawable.mic else R.drawable.album),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    if (isPodcast) {
                                        navController.navigate("online_podcast/${album.id}")
                                    } else {
                                        navController.navigate("album/${album.id}")
                                    }
                                    onDismiss()
                                }
                            )
                        )
                    }
                    add(
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.details)) },
                            description = { Text(text = stringResource(R.string.details_desc)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.info),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                onDismiss()
                                bottomSheetPageState.show {
                                    ShowMediaInfo(song.id)
                                }
                            }
                        )
                    )
                }
            )
        }
    }
}
