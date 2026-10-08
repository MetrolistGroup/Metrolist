package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.random.Random

data class PulseTrack(
    val song: SongItem,
    val reason: String,
)

@HiltViewModel
class PulseViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
    ) : ViewModel() {
        private val _tracks = MutableStateFlow<List<PulseTrack>>(emptyList())
        val tracks: StateFlow<List<PulseTrack>> = _tracks

        private val _isLoading = MutableStateFlow(false)
        val isLoading: StateFlow<Boolean> = _isLoading

        private val _isLoadingMore = MutableStateFlow(false)
        val isLoadingMore: StateFlow<Boolean> = _isLoadingMore

        private val seenIds = linkedSetOf<String>()

        fun loadInitial() {
            if (_isLoading.value || _tracks.value.isNotEmpty()) return
            viewModelScope.launch(Dispatchers.IO) {
                _isLoading.value = true
                try {
                    val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                    val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)

                    val now = LocalDateTime.now()
                    val familiarSongs = database
                        .mostPlayedSongs(
                            fromTimeStamp = now.minusWeeks(12),
                            limit = 24,
                            offset = 0,
                            toTimeStamp = now,
                        ).first()
                        .filterNot { hideVideoSongs && it.song.isVideo }

                    val recentSongs = database
                        .recentlyPlayedSongs(limit = 24)
                        .first()
                        .filterNot { hideVideoSongs && it.song.isVideo }

                    val likedSongs = database
                        .likedSongsByCreateDateAsc()
                        .first()
                        .asReversed()
                        .take(24)
                        .filterNot { hideVideoSongs && it.song.isVideo }

                    val seedSongs =
                        (recentSongs + likedSongs + familiarSongs)
                            .filterNot { hideVideoSongs && it.song.isVideo }
                            .distinctBy { it.id }
                            .take(8)

                    val relatedTracks =
                        coroutineScope {
                            seedSongs.map { seed ->
                                async {
                                    val endpoint =
                                        YouTube
                                            .next(WatchEndpoint(videoId = seed.id))
                                            .getOrNull()
                                            ?.relatedEndpoint
                                            ?: return@async emptyList<PulseTrack>()

                                    YouTube
                                        .related(endpoint)
                                        .getOrNull()
                                        ?.songs
                                        .orEmpty()
                                        .asSequence()
                                        .filterNot { it.id == seed.id }
                                        .filterNot { hideExplicit && it.explicit }
                                        .filterNot { hideVideoSongs && it.isVideoSong }
                                        .take(12)
                                        .map {
                                            PulseTrack(
                                                song = it,
                                                reason = "Because you listened to ${seed.title}",
                                            )
                                        }.toList()
                                }
                            }.awaitAll().flatten()
                        }

                    val familiarityIds =
                        (recentSongs + likedSongs + familiarSongs)
                            .map { it.id }
                            .toSet()

                    val ranked =
                        relatedTracks
                            .groupBy { it.song.id }
                            .mapNotNull { (_, matches) ->
                                val track = matches.firstOrNull() ?: return@mapNotNull null
                                val score =
                                    (matches.size * 2.0) +
                                        if (familiarityIds.contains(track.song.id)) 2.0 else 0.0 +
                                        Random.nextDouble(0.0, 1.2)
                                track to score
                            }
                            .sortedByDescending { it.second }
                            .map { it.first }
                            .take(24)

                    val finalTracks =
                        if (ranked.isNotEmpty()) {
                            diversify(ranked)
                        } else {
                            loadHomeFallback(
                                hideExplicit = hideExplicit,
                                hideVideoSongs = hideVideoSongs,
                            )
                        }

                    seenIds.clear()
                    seenIds.addAll(finalTracks.map { it.song.id })
                    _tracks.value = finalTracks
                } catch (e: Exception) {
                    Timber.tag("PulseViewModel").e(e, "Failed to build Pulse feed")
                } finally {
                    _isLoading.value = false
                }
            }
        }

        fun loadMore(anchorId: String?) {
            if (anchorId.isNullOrBlank() || _isLoadingMore.value || _tracks.value.isEmpty()) return
            viewModelScope.launch(Dispatchers.IO) {
                _isLoadingMore.value = true
                try {
                    val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                    val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
                    val endpoint =
                        YouTube
                            .next(WatchEndpoint(videoId = anchorId))
                            .getOrNull()
                            ?.relatedEndpoint
                            ?: return@launch

                    val related = YouTube.related(endpoint).getOrNull()?.songs.orEmpty()
                    val additions =
                        related
                            .filterNot { it.id in seenIds }
                            .filterNot { hideExplicit && it.explicit }
                            .filterNot { hideVideoSongs && it.isVideoSong }
                            .take(12)
                            .map {
                                PulseTrack(
                                    song = it,
                                    reason = context.getString(R.string.pulse_reason_current_lane),
                                )
                            }

                    if (additions.isNotEmpty()) {
                        additions.forEach { seenIds.add(it.song.id) }
                        _tracks.value = _tracks.value + diversify(additions, _tracks.value)
                    }
                } catch (e: Exception) {
                    Timber.tag("PulseViewModel").w(e, "Failed to extend Pulse feed")
                } finally {
                    _isLoadingMore.value = false
                }
            }
        }

        fun refresh() {
            seenIds.clear()
            _tracks.value = emptyList()
            loadInitial()
        }

        private fun diversify(
            input: List<PulseTrack>,
            existing: List<PulseTrack> = emptyList(),
        ): List<PulseTrack> {
            if (input.size < 2) return input

            val remaining = input.toMutableList()
            val result = mutableListOf<PulseTrack>()
            var lastArtistId = existing.lastOrNull()?.song?.artists?.firstOrNull()?.id

            while (remaining.isNotEmpty()) {
                val nextIndex =
                    remaining.indexOfFirst { track ->
                        val artistId = track.song.artists.firstOrNull()?.id
                        artistId == null || artistId != lastArtistId
                    }.let { if (it >= 0) it else 0 }

                val next = remaining.removeAt(nextIndex)
                result += next
                lastArtistId = next.song.artists.firstOrNull()?.id
            }

            return result
        }

        private suspend fun loadHomeFallback(
            hideExplicit: Boolean,
            hideVideoSongs: Boolean,
        ): List<PulseTrack> {
            val home = YouTube.home().getOrNull() ?: return emptyList()
            return home.sections
                .flatMap { it.items }
                .filterIsInstance<SongItem>()
                .filterNot { hideExplicit && it.explicit }
                .filterNot { hideVideoSongs && it.isVideoSong }
                .distinctBy { it.id }
                .shuffled()
                .take(24)
                .map {
                    PulseTrack(
                        song = it,
                        reason = context.getString(R.string.pulse_reason_fresh),
                    )
                }
        }
    }
