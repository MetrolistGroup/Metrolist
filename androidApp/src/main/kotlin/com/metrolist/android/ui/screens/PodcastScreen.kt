package com.metrolist.android.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.metrolist.android.LocalMiniPlayerBottomInset
import com.metrolist.android.playback.LocalPlayerConnection
import com.metrolist.android.playback.SongDownloadManager
import com.metrolist.android.ui.download.rememberDownloadPermissionGate
import com.metrolist.android.ui.menu.PodcastMenu
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.removed_from_library
import com.metrolist.shared.generated.resources.saved_to_library
import com.metrolist.shared.generated.resources.unable_to_load_podcast
import com.metrolist.shared.generated.resources.unable_to_update_podcast
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.toSongItem
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.PodcastLibrary
import com.metrolist.shared.ui.components.PopupContent
import com.metrolist.shared.ui.screens.PodcastContent
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.utils.ShareUrls
import com.metrolist.shared.viewmodels.PodcastPager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@Composable
fun PodcastScreen(
    podcastId: String,
    onNavigateBack: () -> Unit,
    onChannelClick: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val database: MusicDatabase = koinInject()
    val downloadManager: SongDownloadManager = koinInject()
    val requireDownloadPermission = rememberDownloadPermissionGate()
    val pager =
        remember(podcastId) {
            PodcastPager(scope, GlobalYouTubeRepository.instance.podcastPageLoader(podcastId))
        }
    val paging by pager.state.collectAsState()
    val page = paging.page
    var isSaving by remember(podcastId) { mutableStateOf(false) }
    var showPodcastMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(pager) { pager.loadMore() }
    DisposableEffect(pager) { onDispose { pager.cancel() } }
    LaunchedEffect(pager, database) {
        try {
            database.dao().savedPodcastEpisodesByCreateDateAsc().collect { songs ->
                pager.updateSavedEpisodes(
                    songs.map {
                        EpisodeItem(id = it.id, title = it.title, thumbnail = it.thumbnailUrl, saved = true)
                    },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Logger.w("PodcastScreen", "Saved episode membership loading failed", failure)
        }
    }

    fun withAllEpisodes(action: (List<SongItem>) -> Unit) {
        scope.launch {
            try {
                val songs = mutableListOf<SongItem>()
                GlobalYouTubeRepository.instance.getPodcastPages(podcastId, initialPage = page).collect { delta ->
                    songs += delta.episodes.map { it.toSongItem(delta.podcast.title) }
                }
                action(songs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Logger.w("PodcastScreen", "Podcast loading failed", failure)
                snackbarHostState.showSnackbar(getString(Res.string.unable_to_load_podcast))
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        val currentPage = page
        val currentSong by playerConnection?.currentSong?.collectAsState() ?: remember { mutableStateOf(null) }
        val isPlaying by playerConnection?.isPlaying?.collectAsState() ?: remember { mutableStateOf(false) }
        PodcastContent(
            page = currentPage,
            isLoading = paging.isLoading || (page == null && paging.error == null),
            isSaving = isSaving,
            error = paging.error?.let { stringResource(Res.string.unable_to_load_podcast) },
            onBackClick = onNavigateBack,
            onChannelClick = onChannelClick,
            onRetry = pager::retry,
            onLoadMore = pager::loadMore,
            currentEpisodeId = currentSong?.id,
            isPlaying = isPlaying,
            onPlay = { withAllEpisodes { playerConnection?.playQueue(it, queueTitle = currentPage?.podcast?.title, finite = true) } },
            onToggleSaved = {
                currentPage?.let { loaded ->
                    scope.launch {
                        val saved = !loaded.saved
                        isSaving = true
                        PodcastLibrary
                            .setPodcastSaved(database, loaded.podcast, saved)
                            .onSuccess {
                                pager.updatePage { it.copy(saved = saved) }
                                snackbarHostState.showSnackbar(
                                    getString(if (saved) Res.string.saved_to_library else Res.string.removed_from_library),
                                )
                            }.onFailure {
                                snackbarHostState.showSnackbar(
                                    it.message ?: getString(Res.string.unable_to_update_podcast),
                                )
                            }
                        isSaving = false
                    }
                }
            },
            onPodcastMenuClick = { showPodcastMenu = true },
            onEpisodeClick = { episode ->
                playerConnection?.playSong(episode.toSongItem(currentPage?.podcast?.title), "")
            },
            onToggleEpisodeSaved = { episode ->
                scope.launch {
                    PodcastLibrary.setEpisodeSaved(database, episode, !episode.saved).onSuccess { updated ->
                        pager.updatePage { latest ->
                            latest.copy(episodes = latest.episodes.map { if (it.id == updated.id) updated else it })
                        }
                    }
                }
            },
            onPlayNext = { playerConnection?.addToQueue(it.toSongItem(currentPage?.podcast?.title), playNext = true) },
            onAddToQueue = { playerConnection?.addToQueue(it.toSongItem(currentPage?.podcast?.title)) },
            onDownload = { episode ->
                requireDownloadPermission {
                    scope.launch { downloadManager.download(episode.toSongItem(currentPage?.podcast?.title)) }
                }
            },
            onShare = { episode ->
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, ShareUrls.song(episode.id))
                        },
                        null,
                    ),
                )
            },
            contentPadding = PaddingValues(bottom = LocalMiniPlayerBottomInset.current),
        )
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))

        PopupContent(currentPage.takeIf { showPodcastMenu }) { currentPage ->
            PodcastMenu(
                podcast = currentPage.podcast,
                onDismiss = { showPodcastMenu = false },
                onPlay = { withAllEpisodes { playerConnection?.playQueue(it, queueTitle = currentPage.podcast.title, finite = true) } },
                onShuffle = { withAllEpisodes { playerConnection?.shuffleQueue(it, currentPage.podcast.title, finite = true) } },
                // Episodes load newest first.
                onPlayOldestFirst = {
                    withAllEpisodes { playerConnection?.playQueue(it.asReversed(), queueTitle = currentPage.podcast.title, finite = true) }
                },
                onPlayNext = { withAllEpisodes { playerConnection?.addToQueue(it, playNext = true) } },
                onAddToQueue = { withAllEpisodes { playerConnection?.addToQueue(it) } },
                onShare = {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, ShareUrls.podcast(currentPage.podcast.id))
                            },
                            null,
                        ),
                    )
                },
            )
        }
    }
}
