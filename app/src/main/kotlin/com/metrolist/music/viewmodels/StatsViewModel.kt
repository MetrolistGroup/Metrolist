/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.Artist
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.LastMonthlyMostPlaylistSyncKey
import com.metrolist.music.constants.LastWeeklyMostPlaylistSyncKey
import com.metrolist.music.constants.ShowMostStatsPlaylistsKey
import com.metrolist.music.constants.StatPeriod
import com.metrolist.music.constants.statToPeriod
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.ui.screens.OptionStats
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import com.metrolist.music.utils.reportException
import com.metrolist.music.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.collections.emptyList

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase,
) : ViewModel() {
    private val periodicMostPlaylistSyncMutex = Mutex()
    val selectedOption = MutableStateFlow(OptionStats.CONTINUOUS)
    val indexChips = MutableStateFlow(0)
    private val showMostStatsPlaylists =
        context.dataStore.data
            .map { it[ShowMostStatsPlaylistsKey] ?: true }
            .distinctUntilChanged()

    val mostPlayedSongsStats =
        combine(
            selectedOption,
            indexChips,
            context.dataStore.data.map { it[HideVideoSongsKey] ?: false }.distinctUntilChanged()
        ) { first, second, third -> Triple(first, second, third) }
            .flatMapLatest { (selection, t, hideVideoSongs) ->
                database
                    .mostPlayedSongsStats(
                        fromTimeStamp = statToPeriod(selection, t),
                        limit = -1,
                        toTimeStamp =
                        if (selection == OptionStats.CONTINUOUS || t == 0) {
                            LocalDateTime.now()
                        } else {
                            statToPeriod(selection, t - 1)
                        },
                    ).map { songs ->
                        if (hideVideoSongs) songs.filter { !it.isVideo } else songs
                    }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val mostPlayedSongs =
        combine(
            selectedOption,
            indexChips,
            context.dataStore.data.map { it[HideVideoSongsKey] ?: false }.distinctUntilChanged()
        ) { first, second, third -> Triple(first, second, third) }
            .flatMapLatest { (selection, t, hideVideoSongs) ->
                database
                    .mostPlayedSongs(
                        fromTimeStamp = statToPeriod(selection, t),
                        limit = -1,
                        toTimeStamp =
                        if (selection == OptionStats.CONTINUOUS || t == 0) {
                            LocalDateTime.now()
                        } else {
                            statToPeriod(selection, t - 1)
                        },
                    ).map { songs ->
                        if (hideVideoSongs) songs.filter { !it.song.isVideo } else songs
                    }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val mostPlayedArtists =
        combine(
            selectedOption,
            indexChips,
        ) { first, second -> Pair(first, second) }
            .flatMapLatest { (selection, t) ->
                database
                    .mostPlayedArtists(
                        statToPeriod(selection, t),
                        limit = -1,
                        toTimeStamp =
                        if (selection == OptionStats.CONTINUOUS || t == 0) {
                            LocalDateTime.now()
                        } else {
                            statToPeriod(selection, t - 1)
                        },
                    ).map { artists ->
                        artists.filter { it.artist.isYouTubeArtist }
                    }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val mostPlayedAlbums =
        combine(
            selectedOption,
            indexChips,
        ) { first, second -> Pair(first, second) }
            .flatMapLatest { (selection, t) ->
                database.mostPlayedAlbums(
                    statToPeriod(selection, t),
                    limit = -1,
                    toTimeStamp =
                    if (selection == OptionStats.CONTINUOUS || t == 0) {
                        LocalDateTime.now()
                    } else {
                        statToPeriod(selection, t - 1)
                    },
                )
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val firstEvent =
        database
            .firstEvent()
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val selectedArtists = mutableStateListOf<Artist>() // Current artist selection

    val filteredSongs = combine(
        mostPlayedSongsStats, // Unfiltered songs
        snapshotFlow { selectedArtists.toList() } // Selected artists
    ) { songs, selected ->
        if (selected.isEmpty()) {
            songs
        } else {
            songs.filter { song ->
                song.artists.any { artist -> selected.any { it.id == artist.id } }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val filteredArtists = combine(
        mostPlayedArtists, // Unfiltered list of artists
        snapshotFlow { selectedArtists.toList() } // Selected artists
    ) { artists, selected ->
        if (selected.isEmpty()) {
            artists
        } else {
            artists.filter { artist ->
                selected.any { it.id == artist.artist.id }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val filteredAlbums = combine(
        mostPlayedAlbums, // Unfiltered list of albums
        snapshotFlow { selectedArtists.toList() } // Selected artists
    ) { albums, selected ->
        if (selected.isEmpty()) {
            albums
        } else {
            albums.filter { album ->
                album.artists.any { artist ->
                    selected.any { it.id == artist.id }
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun transferSongStats(fromSongId: String, toSongId: String, onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            try {
                database.transferSongStats(fromSongId, toSongId)
                syncMostPlaylistsIfNeeded(force = true)
                onDone?.invoke()
            } catch (t: Throwable) {
                reportException(t)
            }
        }
    }

    val weeklyMostPlaylist =
        showMostStatsPlaylists.flatMapLatest { isEnabled ->
            if (isEnabled) {
                database.playlist(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID)
            } else {
                flowOf(null)
            }
        }
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val monthlyMostPlaylist =
        showMostStatsPlaylists.flatMapLatest { isEnabled ->
            if (isEnabled) {
                database.playlist(PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID)
            } else {
                flowOf(null)
            }
        }
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val recapPlaylists =
        database
            .playlistsByNameAsc()
            .map { playlists ->
                playlists.filter { playlist ->
                    playlist.playlist.browseId != null &&
                        playlist.playlist.name.contains("recap", ignoreCase = true)
                }
            }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun syncMostPlaylistsIfNeeded(force: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            periodicMostPlaylistSyncMutex.withLock {
                val now = LocalDateTime.now()
                val nowEpochMillis = System.currentTimeMillis()
                val preferences = context.dataStore.data.first()
                val hideVideoSongs = preferences[HideVideoSongsKey] ?: false
                val shouldShowMostStatsPlaylists = preferences[ShowMostStatsPlaylistsKey] ?: true

                if (!shouldShowMostStatsPlaylists) {
                    clearMostPlaylists()
                    return@withLock
                }

                val weeklyPlaylistExists =
                    database.playlist(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID).first() != null
                val monthlyPlaylistExists =
                    database.playlist(PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID).first() != null

                val shouldSyncWeekly =
                    force || !weeklyPlaylistExists || isWeeklySyncDue(
                        lastSyncMillis = preferences[LastWeeklyMostPlaylistSyncKey],
                        now = now,
                    )
                val shouldSyncMonthly =
                    force || !monthlyPlaylistExists || isMonthlySyncDue(
                        lastSyncMillis = preferences[LastMonthlyMostPlaylistSyncKey],
                        now = now,
                    )

                if (!shouldSyncWeekly && !shouldSyncMonthly) {
                    return@withLock
                }

                if (shouldSyncWeekly) {
                    syncMostPlaylist(
                        playlistId = PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID,
                        playlistName = context.getString(R.string.weekly_most_playlist_name),
                        fromTimeStamp = StatPeriod.WEEK_1.toLocalDateTime(),
                        hideVideoSongs = hideVideoSongs,
                        now = now,
                    )
                }

                if (shouldSyncMonthly) {
                    syncMostPlaylist(
                        playlistId = PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID,
                        playlistName = context.getString(R.string.monthly_most_playlist_name),
                        fromTimeStamp = StatPeriod.MONTH_1.toLocalDateTime(),
                        hideVideoSongs = hideVideoSongs,
                        now = now,
                    )
                }

                // Only write "last sync" when it was a scheduled sync, not a forced rebuild
                if (!force) {
                    context.safeDataStoreEdit { settings ->
                        if (shouldSyncWeekly) settings[LastWeeklyMostPlaylistSyncKey] = nowEpochMillis
                        if (shouldSyncMonthly) settings[LastMonthlyMostPlaylistSyncKey] = nowEpochMillis
                    }
                }
            }
        }
    }

    private suspend fun clearMostPlaylists() {
        database.withTransaction {
            clearPlaylist(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID)
            clearPlaylist(PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID)
            delete(
                PlaylistEntity(
                    id = PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID,
                    name = "",
                ),
            )
            delete(
                PlaylistEntity(
                    id = PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID,
                    name = "",
                ),
            )
        }
    }

    private fun isWeeklySyncDue(
        lastSyncMillis: Long?,
        now: LocalDateTime,
    ): Boolean {
        if (lastSyncMillis == null || lastSyncMillis <= 0L) return true

        val lastSyncAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastSyncMillis), ZoneId.systemDefault())
        return !lastSyncAt.plusWeeks(1).isAfter(now)
    }

    private fun isMonthlySyncDue(
        lastSyncMillis: Long?,
        now: LocalDateTime,
    ): Boolean {
        if (lastSyncMillis == null || lastSyncMillis <= 0L) return true

        val lastSyncAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastSyncMillis), ZoneId.systemDefault())
        return !lastSyncAt.plusMonths(1).isAfter(now)
    }

    private suspend fun syncMostPlaylist(
        playlistId: String,
        playlistName: String,
        fromTimeStamp: LocalDateTime,
        hideVideoSongs: Boolean,
        now: LocalDateTime,
    ) {
        val songs =
            database
                .mostPlayedSongs(
                    fromTimeStamp = fromTimeStamp,
                    limit = -1,
                    toTimeStamp = now,
                ).first()
                .let { mostPlayedSongs ->
                    if (hideVideoSongs) {
                        mostPlayedSongs.filter { !it.song.isVideo }
                    } else {
                        mostPlayedSongs
                    }
                }.distinctBy { it.song.id }

        val existingPlaylist = database.playlist(playlistId).first()?.playlist
        val playlistEntity =
            existingPlaylist?.copy(
                name = playlistName,
                isEditable = true,
                bookmarkedAt = existingPlaylist.bookmarkedAt ?: now,
                lastUpdateTime = now,
            ) ?: PlaylistEntity(
                id = playlistId,
                name = playlistName,
                isEditable = true,
                bookmarkedAt = now,
                lastUpdateTime = now,
            )

        if (existingPlaylist == null) {
            database.insert(playlistEntity)
        } else {
            database.update(playlistEntity)
        }

        database.clearPlaylist(playlistId)

        val fullPlaylist = database.playlist(playlistId).first()
        if (fullPlaylist != null) {
            database.addSongsToPlaylist(fullPlaylist, songs.map { it.id to null })
        }
    }

    init {
        viewModelScope.launch {
            mostPlayedArtists.collect { artists ->
                artists
                    .map { it.artist }
                    .filter {
                        it.thumbnailUrl == null || Duration.between(
                            it.lastUpdateTime,
                            LocalDateTime.now()
                        ) > Duration.ofDays(10)
                    }.forEach { artist ->
                        YouTube.artist(artist.id).onSuccess { artistPage ->
                            database.query {
                                update(artist, artistPage)
                            }
                        }
                    }
            }
        }
        viewModelScope.launch {
            mostPlayedAlbums.collect { albums ->
                albums
                    .filter {
                        it.album.songCount == 0
                    }.forEach { album ->
                        YouTube
                            .album(album.id)
                            .onSuccess { albumPage ->
                                database.query {
                                    update(album.album, albumPage, album.artists)
                                }
                            }.onFailure {
                                reportException(it)
                                if (it.message?.contains("NOT_FOUND") == true) {
                                    database.query {
                                        delete(album.album)
                                    }
                                }
                            }
                    }
            }
        }
    }
}
