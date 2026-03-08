package com.metrolist.shared.ui.screens

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.saved_to_library
import com.metrolist.shared.generated.resources.unable_to_load_podcast
import com.metrolist.shared.generated.resources.unable_to_update_podcast
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.toSongItem
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.PodcastLibrary
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.koin.mp.KoinPlatform

internal class PodcastSearchActions(
    val play: () -> Unit,
    val save: () -> Unit,
    val isSaved: Boolean,
    val isSaving: Boolean,
    val isLoading: Boolean,
    val snackbar: SnackbarHostState,
)

@Composable
internal fun rememberPodcastSearchActions(podcast: PodcastItem): PodcastSearchActions {
    val database = remember { KoinPlatform.getKoin().get<MusicDatabase>() }
    val savedPodcast by remember(database, podcast.id) { database.dao().podcast(podcast.id) }.collectAsState(null)
    val playback = LocalPlaybackController.current
    val scope = rememberCoroutineScope()
    val snackbar = remember(podcast.id) { SnackbarHostState() }
    var isSaving by remember(podcast.id) { mutableStateOf(false) }
    var isLoading by remember(podcast.id) { mutableStateOf(false) }
    val isSaved = savedPodcast?.bookmarkedAt != null

    return PodcastSearchActions(
        play = {
            if (!isLoading) {
                isLoading = true
                scope.launch {
                    try {
                        val episode = GlobalYouTubeRepository.instance.getLatestPodcastEpisode(podcast.id)
                        if (episode != null) {
                            playback.play(episode.toSongItem(podcast.title))
                        } else {
                            snackbar.showSnackbar(getString(Res.string.unable_to_load_podcast))
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Logger.w("Search", "Podcast playback loading failed", failure)
                        snackbar.showSnackbar(getString(Res.string.unable_to_load_podcast))
                    } finally {
                        isLoading = false
                    }
                }
            }
        },
        save = {
            if (!isSaving && !isSaved) {
                isSaving = true
                scope.launch {
                    try {
                        PodcastLibrary.setPodcastSaved(database, podcast, true).getOrThrow()
                        snackbar.showSnackbar(getString(Res.string.saved_to_library))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Logger.w("Search", "Podcast saving failed", failure)
                        snackbar.showSnackbar(getString(Res.string.unable_to_update_podcast))
                    } finally {
                        isSaving = false
                    }
                }
            }
        },
        isSaved = isSaved,
        isSaving = isSaving,
        isLoading = isLoading,
        snackbar = snackbar,
    )
}
