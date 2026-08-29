/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

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
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import coil3.compose.AsyncImage
import com.metrolist.music.LocalNavController
import com.metrolist.innertube.YouTube
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.LocalSyncUtils
import com.metrolist.music.R
import com.metrolist.music.constants.ListItemHeight
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.PlaylistSong
import com.metrolist.music.db.entities.PodcastEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SpeedDialItem
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.ExoDownloadService
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.ListDialog
import com.metrolist.music.ui.component.LocalBottomSheetPageState
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.ui.component.NewAction
import com.metrolist.music.ui.component.NewActionGrid
import com.metrolist.music.ui.component.SongListItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.ui.utils.ShowMediaInfo
import com.metrolist.music.viewmodels.CachePlaylistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDateTime

@Composable
fun SongMenu(
    originalSong: Song,
    event: Event? = null,
    playlistSong: PlaylistSong? = null,
    playlistBrowseId: String? = null,
    onDismiss: () -> Unit,
    isFromCache: Boolean = false,
) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val songState = database.song(originalSong.id).collectAsStateWithLifecycle(initialValue = originalSong)
    val song = songState.value ?: originalSong
    val download by LocalDownloadUtil.current
        .getDownload(originalSong.id)
        .collectAsStateWithLifecycle(initialValue = null)
    val coroutineScope = rememberCoroutineScope()
    val syncUtils = LocalSyncUtils.current
    val listenTogetherManager = LocalListenTogetherManager.current
    val scope = rememberCoroutineScope()
    var refetchIconDegree by remember { mutableFloatStateOf(0f) }

    val cacheViewModel = hiltViewModel<CachePlaylistViewModel>()

    val rotationAnimation by animateFloatAsState(
        targetValue = refetchIconDegree,
        animationSpec = tween(durationMillis = 800),
        label = "",
    )

    val isPinned by database.speedDialDao.isPinned(song.id).collectAsStateWithLifecycle(initialValue = false)

    // Podcast subscription state for episodes
    val podcastEntity by produceState<PodcastEntity?>(initialValue = null, song) {
        val podcastId = song.song.albumId
        if (song.song.isEpisode && podcastId != null) {
            database.podcast(podcastId).collect { value = it }
        }
    }
    val isPodcastSubscribed = podcastEntity?.bookmarkedAt != null

    var showEditDialog by rememberSaveable {
        mutableStateOf(false)
    }

    val TextFieldValueSaver: Saver<TextFieldValue, *> =
        Saver(
            save = { it.text },
            restore = { text -> TextFieldValue(text, TextRange(text.length)) },
        )

    var titleField by rememberSaveable(stateSaver = TextFieldValueSaver) {
        mutableStateOf(TextFieldValue(song.song.title))
    }

    var artistField by rememberSaveable(stateSaver = TextFieldValueSaver) {
        mutableStateOf(
            TextFieldValue(
                song.orderedArtists.joinToString(", ") { it.name },
            ),
        )
    }

    if (showEditDialog) {
        TextFieldDialog(
            icon = {
                Icon(
                    painter = painterResource(R.drawable.edit),
                    contentDescription = null,
                )
            },
            title = {
                Text(text = stringResource(R.string.edit_song))
            },
            textFields =
                listOf(
                    stringResource(R.string.song_title) to titleField,
                    stringResource(R.string.artist_name) to artistField,
                ),
            onTextFieldsChange = { index, newValue ->
                if (index == 0) {
                    titleField = newValue
                } else {
                    artistField = newValue
                }
            },
            onDoneMultiple = { values ->
                val newTitle = values[0]
                val newArtistNames =
                    values[1]
                        .split(',')
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .distinct()
                val artistsChanged = newArtistNames != song.orderedArtists.map { it.name }

                coroutineScope.launch {
                    database.withTransaction {
                        update(song.song.copy(title = newTitle))
                        if (artistsChanged) {
                            val replacementArtists =
                                newArtistNames.map { name ->
                                    artistByName(name)
                                        ?: ArtistEntity(
                                            id = ArtistEntity.generateArtistId(),
                                            name = name,
                                            isLocal = true,
                                        )
                                }
                            replaceSongArtists(song.id, replacementArtists)
                        }
                    }
                    database.song(song.id).first()?.let(playerConnection::refreshSongMetadata)

                    showEditDialog = false
                    onDismiss()
                }
            },
            onDismiss = { showEditDialog = false },
        )
    }

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var showErrorPlaylistAddDialog by rememberSaveable {
        mutableStateOf(false)
    }

    AddToPlaylistDialog(
        isVisible = showChoosePlaylistDialog,
        onGetSong = { playlist ->
            coroutineScope.launch(Dispatchers.IO) {
                playlist.playlist.browseId?.let { browseId ->
                    YouTube.addToPlaylist(browseId, song.id)
                }
            }
            listOf(song.id)
        },
        onGetSongIds = { listOf(song.id) },
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

            items(listOf(song)) { song ->
                SongListItem(song = song)
            }
        }
    }

    var showSelectArtistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var showDeleteUploadedDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var isDeleting by remember { mutableStateOf(false) }

    if (showDeleteUploadedDialog) {
        DefaultDialog(
            onDismiss = { if (!isDeleting) showDeleteUploadedDialog = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.delete),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.delete_uploaded_song)) },
            buttons = {
                TextButton(
                    onClick = { showDeleteUploadedDialog = false },
                    enabled = !isDeleting,
                ) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(
                    onClick = {
                        val entityId = song.song.uploadEntityId
                        if (entityId == null) {
                            Toast
                                .makeText(
                                    context,
                                    R.string.delete_uploaded_song_failed,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            showDeleteUploadedDialog = false
                            return@TextButton
                        }
                        isDeleting = true
                        coroutineScope.launch(Dispatchers.IO) {
                            YouTube
                                .deleteUploadedSong(entityId)
                                .onSuccess {
                                    database.query {
                                        delete(song.song)
                                    }
                                    withContext(Dispatchers.Main) {
                                        Toast
                                            .makeText(
                                                context,
                                                R.string.delete_uploaded_song_success,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        isDeleting = false
                                        showDeleteUploadedDialog = false
                                        onDismiss()
                                    }
                                }.onFailure {
                                    withContext(Dispatchers.Main) {
                                        Toast
                                            .makeText(
                                                context,
                                                R.string.delete_uploaded_song_failed,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        isDeleting = false
                                        showDeleteUploadedDialog = false
                                    }
                                }
                        }
                    },
                    enabled = !isDeleting,
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
        ) {
            Text(
                text = stringResource(R.string.delete_uploaded_song_confirm),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (showSelectArtistDialog) {
        ListDialog(
            onDismiss = { showSelectArtistDialog = false },
        ) {
            items(
                items = song.orderedArtists.distinctBy { it.id },
                key = { "menu_song_artist_${it.id}" },
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

    SongListItem(
        song = song,
        badges = {},
        trailingContent = {
            // For episodes, show saved state and toggle save for later
            val isEpisode = song.song.isEpisode
            val isFavorite = if (isEpisode) song.song.inLibrary != null else song.song.liked
            IconButton(
                onClick = {
                    if (isEpisode) {
                        // Episode: toggle save for later (same pattern as songs)
                        val isCurrentlySaved = song.song.inLibrary != null
                        database.query {
                            update(
                                song.song.copy(
                                    inLibrary = if (isCurrentlySaved) null else LocalDateTime.now(),
                                    isEpisode = true,
                                ),
                            )
                        }
                        coroutineScope.launch(Dispatchers.IO) {
                            if (isCurrentlySaved) {
                                val setVideoIdEntity = database.getSetVideoId(song.id)
                                val setVideoId = setVideoIdEntity?.setVideoId
                                if (setVideoId != null) {
                                    YouTube
                                        .removeEpisodeFromSavedEpisodes(song.id, setVideoId)
                                        .onSuccess {
                                            Timber.d("[EPISODE_SAVE] Removed episode from Episodes for Later: ${song.id}")
                                        }.onFailure { e ->
                                            Timber.e(e, "[EPISODE_SAVE] Failed to remove episode: ${song.id}")
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, R.string.error_episode_remove, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                }
                            } else {
                                YouTube
                                    .addEpisodeToSavedEpisodes(song.id)
                                    .onSuccess {
                                        Timber.d("[EPISODE_SAVE] Saved episode to Episodes for Later: ${song.id}")
                                    }.onFailure { e ->
                                        Timber.e(e, "[EPISODE_SAVE] Failed to save episode: ${song.id}")
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, R.string.error_episode_save, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                            }
                        }
                    } else {
                        // Regular song: toggle like
                        val s = song.song.toggleLike()
                        database.query {
                            update(s)
                        }
                        syncUtils.likeSong(s)
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
                    listOf(
                        NewAction(
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.edit),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            text = stringResource(R.string.edit),
                            onClick = {
                                titleField = TextFieldValue(song.song.title)
                                artistField =
                                    TextFieldValue(
                                        song.orderedArtists.joinToString(", ") { it.name },
                                    )
                                showEditDialog = true
                            },
                        ),
                        NewAction(
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.playlist_add),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            text = stringResource(R.string.add_to_playlist),
                            onClick = { showChoosePlaylistDialog = true },
                        ),
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
                                        putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${song.id}")
                                    }
                                context.startActivity(Intent.createChooser(intent, null))
                            },
                        ),
                    ),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                columns = if (isGuest) 2 else 3,
            )
        }
        item {
            Material3MenuGroup(
                items =
                    listOfNotNull(
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
                                    val durationMs = if (song.song.duration > 0) song.song.duration.toLong() * 1000 else 180000L
                                    val trackInfo =
                                        com.metrolist.music.listentogether.TrackInfo(
                                            id = song.id,
                                            title = song.song.title,
                                            artist = song.orderedArtists.joinToString(", ") { it.name },
                                            album = song.song.albumName,
                                            duration = durationMs,
                                            thumbnail = song.thumbnailUrl,
                                        )
                                    listenTogetherManager.suggestTrack(trackInfo)
                                    onDismiss()
                                },
                            )
                        } else {
                            null
                        },
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
                                    onDismiss()
                                    playerConnection.playQueue(YouTubeQueue.radio(song.toMediaMetadata()))
                                },
                            )
                        } else {
                            null
                        },
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
                                    playerConnection.playNext(song.toMediaItem())
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
                                    playerConnection.addToQueue(song.toMediaItem())
                                },
                            )
                        } else {
                            null
                        },
                    ),
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            Material3MenuGroup(
                items =
                    buildList {
                        add(
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
                                            database.speedDialDao.delete(song.id)
                                        } else {
                                            database.speedDialDao.insert(
                                                SpeedDialItem(
                                                    id = song.id,
                                                    title = song.song.title,
                                                    subtitle = song.artists.joinToString(", ") { it.name },
                                                    subtitleIds = song.artists.joinToString(", ") { it.id },
                                                    thumbnailUrl = song.song.thumbnailUrl,
                                                    type = "SONG",
                                                    explicit = song.song.explicit,
                                                    albumId = song.album?.id,
                                                    albumName = song.album?.title
                                                ),
                                            )
                                        }
                                    }
                                    onDismiss()
                                },
                            ),
                        )
                        // For episodes, use "Save for later" / "Remove from saved" (Episodes for Later playlist)
                        // For regular songs, use "Add to library"
                        if (song.song.isEpisode) {
                            val isEpisodeSaved = song.song.inLibrary != null
                            add(
                                Material3MenuItemData(
                                    title = {
                                        Text(
                                            text =
                                                stringResource(
                                                    if (isEpisodeSaved) {
                                                        R.string.remove_episode_from_saved
                                                    } else {
                                                        R.string.save_episode_for_later
                                                    },
                                                ),
                                        )
                                    },
                                    description = { Text(text = stringResource(R.string.episodes_for_later)) },
                                    icon = {
                                        Icon(
                                            painter =
                                                painterResource(
                                                    if (isEpisodeSaved) {
                                                        R.drawable.library_add_check
                                                    } else {
                                                        R.drawable.library_add
                                                    },
                                                ),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val shouldBeSaved = !isEpisodeSaved

                                            // Update local database first (optimistic update)
                                            database.query {
                                                update(
                                                    song.song.copy(
                                                        inLibrary = if (shouldBeSaved) LocalDateTime.now() else null,
                                                        isEpisode = true,
                                                    ),
                                                )
                                            }

                                            // Sync with YouTube (handles login check internally)
                                            val setVideoId = if (isEpisodeSaved) database.getSetVideoId(song.id)?.setVideoId else null
                                            syncUtils.saveEpisode(song.id, shouldBeSaved, setVideoId)
                                        }
                                        onDismiss()
                                    },
                                ),
                            )
                        } else {
                            add(
                                Material3MenuItemData(
                                    title = {
                                        Text(
                                            text =
                                                stringResource(
                                                    if (song.song.inLibrary == null) {
                                                        R.string.add_to_library
                                                    } else {
                                                        R.string.remove_from_library
                                                    },
                                                ),
                                        )
                                    },
                                    description = { Text(text = stringResource(R.string.add_to_library_desc)) },
                                    icon = {
                                        Icon(
                                            painter =
                                                painterResource(
                                                    if (song.song.inLibrary == null) {
                                                        R.drawable.library_add
                                                    } else {
                                                        R.drawable.library_add_check
                                                    },
                                                ),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        val currentSong = song.song
                                        val isInLibrary = currentSong.inLibrary != null
                                        val token =
                                            if (isInLibrary) currentSong.libraryRemoveToken else currentSong.libraryAddToken

                                        token?.let {
                                            coroutineScope.launch {
                                                YouTube.feedback(listOf(it))
                                            }
                                        }

                                        database.query {
                                            update(song.song.toggleLibrary())
                                        }
                                    },
                                ),
                            )
                        }
                        if (event != null) {
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
                                        onDismiss()
                                        database.query {
                                            delete(event)
                                        }
                                    },
                                ),
                            )
                        }
                        if (playlistSong != null) {
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.remove_from_playlist)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.delete),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        playlistSong.let { ps ->
                                            val capturedSetVideoId = ps.map.setVideoId
                                            database.transaction {
                                                move(
                                                    ps.map.playlistId,
                                                    ps.map.position,
                                                    Int.MAX_VALUE
                                                )
                                                delete(ps.map.copy(position = Int.MAX_VALUE))
                                            }
                                            playlistBrowseId?.let { browseId ->
                                                syncUtils.scheduleRemoveFromPlaylist(
                                                    browseId,
                                                    ps.map.songId,
                                                    ps.map.playlistId
                                                ) {
                                                    capturedSetVideoId
                                                }
                                            }
                                            onDismiss()
                                        }
                                    },
                                ),
                            )
                        }
                        if (isFromCache) {
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.remove_from_cache)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.delete),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        onDismiss()
                                        cacheViewModel.removeSongFromCache(song.id)
                                    },
                                ),
                            )
                        }
                        // Delete uploaded song option
                        if (song.song.isUploaded) {
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.delete_uploaded_song)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.delete),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        showDeleteUploadedDialog = true
                                    },
                                ),
                            )
                        }
                    },
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            Material3MenuGroup(
                items =
                    listOf(
                        when (download?.state) {
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
                                        DownloadService.sendRemoveDownload(
                                            context,
                                            ExoDownloadService::class.java,
                                            song.id,
                                            false,
                                        )
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
                                        DownloadService.sendRemoveDownload(
                                            context,
                                            ExoDownloadService::class.java,
                                            song.id,
                                            false,
                                        )
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
                                    },
                                )
                            }
                        },
                    ),
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            Material3MenuGroup(
                items =
                    buildList {
                        // Don't show "View Artist" for podcast episodes
                        if (!song.song.isEpisode) {
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.view_artist)) },
                                    description = { Text(text = song.orderedArtists.joinToString { it.name }) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.artist),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        if (song.orderedArtists.size == 1) {
                                            navController.navigate("artist/${song.orderedArtists[0].id}")
                                            onDismiss()
                                        } else {
                                            showSelectArtistDialog = true
                                        }
                                    },
                                ),
                            )
                        }
                        if (song.song.albumId != null) {
                            // Show "View Podcast" for episodes, "View Album" for songs
                            val isPodcast = song.song.isEpisode
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(if (isPodcast) R.string.view_podcast else R.string.view_album)) },
                                    description = {
                                        song.song.albumName?.let {
                                            Text(text = it)
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            painter = painterResource(if (isPodcast) R.drawable.mic else R.drawable.album),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        onDismiss()
                                        if (isPodcast) {
                                            navController.navigate("online_podcast/${song.song.albumId}")
                                        } else {
                                            navController.navigate("album/${song.song.albumId}")
                                        }
                                    },
                                ),
                            )
                        }
                        // Subscribe to podcast option for episodes
                        song.song.albumId?.takeIf { song.song.isEpisode }?.let { podcastId ->
                            add(
                                Material3MenuItemData(
                                    title = {
                                        Text(
                                            text =
                                                stringResource(
                                                    if (isPodcastSubscribed) {
                                                        R.string.subscribed
                                                    } else {
                                                        R.string.subscribe_to_podcast
                                                    },
                                                ),
                                        )
                                    },
                                    description = {
                                        song.song.albumName?.let {
                                            Text(text = it)
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            painter =
                                                painterResource(
                                                    if (isPodcastSubscribed) {
                                                        R.drawable.library_add_check
                                                    } else {
                                                        R.drawable.library_add
                                                    },
                                                ),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        Timber.d("[PODCAST_LIB] Toggling podcast save for: $podcastId")
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val existingPodcast = podcastEntity
                                            val isCurrentlySaved = existingPodcast?.bookmarkedAt != null

                                            // Call the API to save/unsave on YTM
                                            YouTube
                                                .savePodcast(podcastId, !isCurrentlySaved)
                                                .onSuccess {
                                                    Timber.d("[PODCAST_LIB] savePodcast API success!")
                                                }.onFailure { e ->
                                                    Timber.e(e, "[PODCAST_LIB] savePodcast API failed")
                                                }

                                            // Update local database
                                            if (existingPodcast != null) {
                                                Timber.d("[PODCAST_LIB] Updating existing podcast")
                                                database.query {
                                                    update(existingPodcast.toggleBookmark())
                                                }
                                            } else {
                                                Timber.d("[PODCAST_LIB] Creating new podcast entry")
                                                database.query {
                                                    insert(
                                                        PodcastEntity(
                                                            id = podcastId,
                                                            title = song.song.albumName ?: "Unknown Podcast",
                                                            author = song.artists.firstOrNull()?.name,
                                                            thumbnailUrl = song.song.thumbnailUrl,
                                                        ).toggleBookmark(),
                                                    )
                                                }
                                            }
                                        }
                                        onDismiss()
                                    },
                                ),
                            )
                        }
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
                                    scope.launch(Dispatchers.IO) {
                                        YouTube.queue(listOf(song.id)).onSuccess {
                                            val newSong = it.firstOrNull()
                                            if (newSong != null) {
                                                database.transaction {
                                                    update(song, newSong.toMediaMetadata())
                                                }
                                            }
                                        }
                                    }
                                },
                            ),
                        )
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
                                },
                            ),
                        )
                    },
            )
        }
    }
}
