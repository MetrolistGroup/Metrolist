/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.content.ArtistState
import com.metrolist.desktop.state.content.HomeState
import com.metrolist.desktop.state.content.LibraryState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.storage.CachedSongEntry
import com.metrolist.desktop.storage.DownloadManager
import com.metrolist.desktop.storage.DownloadedFile
import com.metrolist.desktop.storage.SongCacheManager
import com.metrolist.desktop.ui.components.CollectionCoverEditor
import com.metrolist.desktop.ui.components.PlaylistContextMenu
import com.metrolist.desktop.ui.components.SongContextMenu
import com.metrolist.desktop.ui.components.SongSwipeActions
import com.metrolist.desktop.utils.AppLogger
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.playlistSongs
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.collectionPlayCounts
import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.entities.withStoredDownloadMetadata
import com.metrolist.shared.db.refreshUploadedSongs
import com.metrolist.shared.db.synchronizeUploadedSongs
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cached_songs_empty
import com.metrolist.shared.generated.resources.cached_songs_empty_hint
import com.metrolist.shared.generated.resources.cached_songs_subtitle
import com.metrolist.shared.generated.resources.cached_songs_title
import com.metrolist.shared.generated.resources.downloaded_songs_empty
import com.metrolist.shared.generated.resources.downloaded_songs_empty_hint
import com.metrolist.shared.generated.resources.downloaded_songs_subtitle
import com.metrolist.shared.generated.resources.downloaded_songs_title
import com.metrolist.shared.generated.resources.edit_playlist_cover
import com.metrolist.shared.generated.resources.error_occurred
import com.metrolist.shared.generated.resources.liked_songs_empty
import com.metrolist.shared.generated.resources.liked_songs_empty_hint
import com.metrolist.shared.generated.resources.liked_songs_subtitle
import com.metrolist.shared.generated.resources.liked_songs_title
import com.metrolist.shared.generated.resources.local_file
import com.metrolist.shared.generated.resources.remove
import com.metrolist.shared.generated.resources.top_playlist_empty
import com.metrolist.shared.generated.resources.top_playlist_empty_hint
import com.metrolist.shared.generated.resources.top_playlist_subtitle
import com.metrolist.shared.generated.resources.top_playlist_title
import com.metrolist.shared.generated.resources.try_again
import com.metrolist.shared.generated.resources.upload_action
import com.metrolist.shared.generated.resources.uploaded_song_to_youtube_music
import com.metrolist.shared.generated.resources.uploaded_songs_empty
import com.metrolist.shared.generated.resources.uploaded_songs_empty_hint
import com.metrolist.shared.generated.resources.uploaded_songs_subtitle
import com.metrolist.shared.generated.resources.uploaded_songs_title
import com.metrolist.shared.generated.resources.uploading_action
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.CollectionMeta
import com.metrolist.shared.model.CollectionSort
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.HiddenLibraryItem
import com.metrolist.shared.settings.HiddenLibraryItemType
import com.metrolist.shared.settings.HiddenLibraryItems
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.components.CollectionHeaderNavigation
import com.metrolist.shared.ui.components.LoadingScreen
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.localizedErrorMessage
import com.metrolist.shared.ui.menu.cursorContextMenu
import com.metrolist.shared.ui.menu.rememberCursorContextMenuState
import com.metrolist.shared.ui.navigation.LocalNavigator
import com.metrolist.shared.ui.screens.StoredPlaylistContent
import com.metrolist.shared.ui.utils.rememberFileSizeFormatter
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.core.context.GlobalContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun LocalPlaylistScreen(type: String) {
    when (type) {
        "liked" -> LikedPlaylistScreen()
        "top100" -> TopPlaylistScreen()
        "downloaded" -> DownloadedPlaylistScreen()
        "cached" -> CachedPlaylistScreen()
        "uploaded" -> UploadedPlaylistScreen()
        else -> TopPlaylistScreen()
    }
}

@Composable
private fun LikedPlaylistScreen() {
    val songs = SongLikeState.likedSongsForDisplay

    LaunchedEffect(Unit) {
        if (runCatching { GlobalYouTubeRepository.instance.canManagePlaylists() }.getOrDefault(false)) {
            SongLikeState.refreshLikesFromRemote(force = false)
        }
    }

    if (SongLikeState.isRefreshing && songs.isEmpty()) {
        val loadingNavigator = LocalNavigator.current
        CollectionHeaderNavigation({ loadingNavigator.goBack() }) { LoadingScreen() }
    } else {
        LocalPlaylistLayout(
            type = "liked",
            title = stringResource(Res.string.liked_songs_title),
            subtitle = stringResource(Res.string.liked_songs_subtitle),
            icon = MetrolistIcons.Filled.Favorite,
            songs = songs,
            emptyMessage = stringResource(Res.string.liked_songs_empty),
            emptyHint = stringResource(Res.string.liked_songs_empty_hint),
            onArtistClick = ArtistState::fetchArtistData,
            hasMoreSongs = SongLikeState.hasPartialLikes,
            loadCompleteSongs = SongLikeState::awaitCompleteLikes,
            onReorderSongs =
                if (SongLikeState.hasPartialLikes) {
                    null
                } else {
                    { reordered -> SongLikeState.replaceLikesLocally(reordered, orderCustomized = true) }
                },
        )
    }
}

@Composable
private fun TopPlaylistScreen() {
    val stats = PlayerState.topSongs
    val songs =
        stats.map { stat ->
            SongItem(
                id = stat.songId,
                title = stat.title,
                artists = stat.artists.split(", ").map { ArtistTiny(id = null, name = it) },
                thumbnail = stat.thumbnailUrl ?: "https://img.youtube.com/vi/${stat.songId}/maxresdefault.jpg",
                album = null,
            )
        }

    LocalPlaylistLayout(
        type = "top100",
        title = stringResource(Res.string.top_playlist_title),
        subtitle = stringResource(Res.string.top_playlist_subtitle),
        icon = MetrolistIcons.Outlined.Star,
        songs = songs,
        emptyMessage = stringResource(Res.string.top_playlist_empty),
        emptyHint = stringResource(Res.string.top_playlist_empty_hint),
        extraInfo = { index, _ ->
            stats.getOrNull(index)?.playCount?.let {
                Text(it.toString(), style = MaterialTheme.typography.bodyMedium)
            }
        },
    )
}

@Composable
private fun DownloadedPlaylistScreen() {
    val database = remember { GlobalContext.get().get<MusicDatabase>() }
    val files = LibraryState.downloadedFiles
    val snapshot by produceState(emptyList<SongItem>() to emptyMap<String, CollectionMeta>(), files) {
        value =
            withContext(Dispatchers.IO) {
                val storedSongs = database.dao().getSongsByIds(files.map { it.songId })
                downloadedCollectionSnapshot(files, storedSongs)
            }
    }
    LocalPlaylistLayout(
        type = "downloaded",
        title = stringResource(Res.string.downloaded_songs_title),
        subtitle = stringResource(Res.string.downloaded_songs_subtitle),
        icon = MetrolistIcons.Outlined.Download,
        songs = snapshot.first,
        emptyMessage = stringResource(Res.string.downloaded_songs_empty),
        emptyHint = stringResource(Res.string.downloaded_songs_empty_hint),
        collectionMetadata = snapshot.second,
        onRemoveSongs = { ids -> DownloadManager.deleteDownloadsAndAwait(ids) },
        onClearCollection = DownloadManager::clearAllDownloads,
        extraInfo = { _, _ ->
            Icon(
                MetrolistIcons.Outlined.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        },
    )
}

@Composable
private fun CachedPlaylistScreen() {
    val formatFileSize = rememberFileSizeFormatter()
    val snapshot by produceState(Triple(emptyList<CachedSongEntry>(), emptyList<SongItem>(), 0L)) {
        withContext(Dispatchers.IO) {
            SongCacheManager.cachedSongs.collect {
                val entries = SongCacheManager.getCachedSongs()
                value = Triple(entries, entries.map(CachedSongEntry::toSafeSongItem), SongCacheManager.getCacheSizeMB())
            }
        }
    }
    val (entries, songs, sizeMb) = snapshot
    val sizeById = entries.associate { it.songId to it.fileSize }

    LocalPlaylistLayout(
        type = "cached",
        title = stringResource(Res.string.cached_songs_title),
        subtitle = stringResource(Res.string.cached_songs_subtitle, String.format("%.1f", sizeMb.toDouble())),
        icon = MetrolistIcons.Outlined.OfflinePin,
        songs = songs,
        emptyMessage = stringResource(Res.string.cached_songs_empty),
        emptyHint = stringResource(Res.string.cached_songs_empty_hint),
        collectionMetadata = entries.associate { it.songId to CollectionMeta(it.cachedAt, it.fileSize) },
        onRemoveSongs = SongCacheManager::removeSongsFromCache,
        onClearCollection = { SongCacheManager.clearCache() },
        extraInfo = { _, song ->
            sizeById[song.id]?.let { size ->
                Text(
                    text = formatFileSize(size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
        },
    )
}

@Composable
private fun UploadedPlaylistScreen() {
    val database = remember { GlobalContext.get().get<MusicDatabase>() }
    val scope = rememberCoroutineScope()
    val uploaded = stringResource(Res.string.uploaded_song_to_youtube_music)
    val fileFilterLabel = stringResource(Res.string.local_file)
    var songs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isUploading by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }

    suspend fun refresh(force: Boolean = true) {
        isLoading = songs.isEmpty()
        loadFailed = false
        try {
            withContext(Dispatchers.IO) { database.refreshUploadedSongs(GlobalYouTubeRepository.instance, force = force) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            loadFailed = true
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        launch { refresh(force = false) }
        database.dao().uploadedSongsByNameAsc().collect { stored ->
            songs = stored.map { it.toSongItem() }
            if (stored.isNotEmpty()) isLoading = false
        }
    }
    if (isLoading) {
        val loadingNavigator = LocalNavigator.current
        CollectionHeaderNavigation({ loadingNavigator.goBack() }) { LoadingScreen() }
        return
    }

    LocalPlaylistLayout(
        type = "uploaded",
        title = stringResource(Res.string.uploaded_songs_title),
        subtitle = stringResource(Res.string.uploaded_songs_subtitle),
        icon = MetrolistIcons.Outlined.CloudUpload,
        songs = songs,
        emptyMessage = stringResource(if (loadFailed) Res.string.error_occurred else Res.string.uploaded_songs_empty),
        emptyHint = if (loadFailed) "" else stringResource(Res.string.uploaded_songs_empty_hint),
        onEmptyRetry =
            if (loadFailed) {
                {
                    scope.launch { refresh() }
                }
            } else {
                null
            },
        headerActionLabel = stringResource(if (isUploading) Res.string.uploading_action else Res.string.upload_action),
        headerActionIcon = MetrolistIcons.Outlined.CloudUpload,
        isHeaderActionLoading = isUploading,
        onHeaderAction = {
            chooseUploadAudioFile(fileFilterLabel)?.let { file ->
                scope.launch {
                    isUploading = true
                    val result =
                        try {
                            withContext(Dispatchers.IO) {
                                GlobalYouTubeRepository.instance.uploadSong(file.name, file.length()) {
                                    file.inputStream().toByteReadChannel(Dispatchers.IO)
                                }
                            }
                        } finally {
                            isUploading = false
                        }
                    if (!result.success) {
                        AppState.snackbarHostState.showSnackbar(result.localizedErrorMessage())
                        return@launch
                    }
                    val knownIds = songs.mapTo(mutableSetOf()) { it.id }
                    launch { AppState.snackbarHostState.showSnackbar(uploaded) }
                    GlobalYouTubeRepository.instance.awaitProcessedUpload(knownIds) {
                        val processed = it
                        scope.launch { database.synchronizeUploadedSongs(processed) }
                        loadFailed = false
                    }
                }
            }
        },
    )
}

@Composable
private fun LocalPlaylistLayout(
    type: String,
    title: String,
    subtitle: String,
    icon: ImageVector,
    songs: List<SongItem>,
    emptyMessage: String,
    emptyHint: String,
    onEmptyRetry: (() -> Unit)? = null,
    onArtistClick: ((String) -> Unit)? = null,
    extraInfo: (@Composable (Int, SongItem) -> Unit)? = null,
    headerActionLabel: String? = null,
    headerActionIcon: ImageVector? = null,
    isHeaderActionLoading: Boolean = false,
    onHeaderAction: (() -> Unit)? = null,
    collectionMetadata: Map<String, CollectionMeta>? = null,
    onRemoveSongs: (suspend (Set<String>) -> Unit)? = null,
    onClearCollection: (suspend () -> Unit)? = null,
    onReorderSongs: ((List<SongItem>) -> Unit)? = null,
    hasMoreSongs: Boolean = false,
    loadCompleteSongs: (suspend () -> List<SongItem>?)? = null,
) {
    val database = remember { GlobalContext.get().get<MusicDatabase>() }
    var countSort by remember(type) { mutableStateOf(false) }
    val countIds = remember(collectionMetadata, countSort) { if (countSort) collectionMetadata?.keys?.toList().orEmpty() else emptyList() }
    val playCounts by remember(database, countIds) {
        if (countIds.isEmpty()) flowOf(emptyMap()) else database.collectionPlayCounts(countIds)
    }.collectAsState(emptyMap())

    var editingCover by remember(type) { mutableStateOf(false) }
    if (editingCover) CollectionCoverEditor(type, onDismiss = { editingCover = false })
    val navigator = LocalNavigator.current
    val collectionMenu = rememberCursorContextMenuState()
    val songMenu = rememberCursorContextMenuState()
    var menuSong by remember { mutableStateOf<SongItem?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .cursorContextMenu(collectionMenu, openOnSecondaryClick = false)
            .cursorContextMenu(songMenu, openOnSecondaryClick = false),
    ) {
        StoredPlaylistContent(
            onBackClick = { navigator.goBack() },
            trackContainer = { song, interactionInProgress, content ->
                SongSwipeActions(song, enabled = !interactionInProgress, content = content)
            },
            id = "local:$type",
            title = title,
            subtitle = subtitle,
            icon = icon,
            songs = songs,
            downloadFilterDatabase = database.takeIf { type == "downloaded" },
            emptyContent = { EmptyState(icon, emptyMessage, emptyHint, onEmptyRetry) },
            listState = HomeState.rememberListState("local_$type"),
            onCollectionMenu = {
                collectionMenu.open()
            },
            onTrackMenu = {
                menuSong = it
                songMenu.open()
            },
            onArtistClick = onArtistClick,
            extraInfo = extraInfo,
            headerAction =
                if (headerActionLabel != null && headerActionIcon != null && onHeaderAction != null) {
                    {
                        TextButton(onClick = onHeaderAction, enabled = !isHeaderActionLoading, shapes = ButtonDefaults.shapes()) {
                            Icon(headerActionIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(headerActionLabel)
                        }
                    }
                } else {
                    null
                },
            collectionMetadata = collectionMetadata,
            playCounts = playCounts,
            onCollectionSortChange = { countSort = it == CollectionSort.MOST_PLAYED },
            onRemoveSongs = onRemoveSongs?.let { action -> { ids -> launchCollectionMutation { action(ids) } } },
            onClearCollection = onClearCollection?.let { action -> { launchCollectionMutation(action) } },
            onReorderSongs = onReorderSongs,
            hasMoreSongs = hasMoreSongs,
            loadCompleteSongs = loadCompleteSongs,
            onDownload = { DownloadManager.downloadAll(it) },
            onAddToPlaylist = { PlayerState.showAddToPlaylistSongs = it },
        )

        PlaylistContextMenu(
            playlist = PlaylistItem(id = "auto:$type", title = title, thumbnail = null, author = subtitle),
            songs = songs.takeUnless { hasMoreSongs }.orEmpty(),
            fallbackIcon = icon,
            expanded = collectionMenu.expanded,
            offset = collectionMenu.offset,
            onDismiss = collectionMenu::dismiss,
            onHideFromLibrary = { HiddenLibraryItems.hide(HiddenLibraryItem(HiddenLibraryItemType.AUTO, type, title)) },
            additionalActions =
                listOf(
                    PlayerMenuAction(
                        icon = MetrolistIcons.Default.Edit,
                        title = stringResource(Res.string.edit_playlist_cover),
                        onClick = {
                            collectionMenu.dismiss()
                            editingCover = true
                        },
                    ),
                ),
        )
        menuSong?.let { song ->
            SongContextMenu(
                song = song,
                expanded = songMenu.expanded,
                offset = songMenu.offset,
                onRemoveFromPlaylist = onRemoveSongs?.let { action -> { launchCollectionMutation { action(setOf(song.id)) } } },
                removeLabel = stringResource(Res.string.remove),
                onDismiss = {
                    songMenu.dismiss()
                },
            )
        }
    }
}

internal fun downloadedCollectionSnapshot(
    files: List<DownloadedFile>,
    storedSongs: List<Song> = emptyList(),
): Pair<List<SongItem>, Map<String, CollectionMeta>> {
    val storedById = storedSongs.associateBy { it.id }
    return files.map { it.toSongItem().withStoredDownloadMetadata(storedById[it.songId]) } to
        files.associate {
            it.songId to
                CollectionMeta(
                    timestamp = it.downloadDate,
                    fileSize = File(it.audioFilePath).length() + (it.thumbnailFilePath?.let(::File)?.length() ?: 0L),
                )
        }
}

private fun launchCollectionMutation(action: suspend () -> Unit) {
    AppState.scope.launch {
        try {
            withContext(Dispatchers.IO) { action() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.error("LocalPlaylistScreen", "Collection deletion failed", e)
            AppState.snackbarHostState.showSnackbar(getString(Res.string.error_occurred))
        }
    }
}

private fun chooseUploadAudioFile(description: String): File? {
    val chooser = JFileChooser()
    chooser.fileFilter = FileNameExtensionFilter(description, *YouTubeRepository.SUPPORTED_UPLOAD_EXTENSIONS.toTypedArray())
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

private fun CachedSongEntry.toSafeSongItem(): SongItem {
    val safeThumbnail =
        runCatching {
            thumbnailPath
                ?.let(::File)
                ?.takeIf { it.exists() && it.length() > 0 }
                ?.toURI()
                ?.toString()
        }.getOrNull()
    return SongItem(
        id = songId,
        title = title,
        artists = listOf(ArtistTiny(id = null, name = artist)),
        thumbnail = safeThumbnail,
        album = null,
        isExplicit = explicitness == true,
        explicitness = explicitness,
    )
}

@Composable
private fun EmptyState(
    icon: ImageVector,
    message: String,
    hint: String,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        Text(message, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        if (onRetry != null) {
            TextButton(onClick = onRetry, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.try_again)) }
        }
    }
}
