/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.wrapped

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AccountInfo
import com.metrolist.music.constants.ArtistSongSortType
import com.metrolist.music.db.DatabaseDao
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.SongWithStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.util.Calendar
import java.util.UUID

sealed class PlaylistCreationState {
    object Idle : PlaylistCreationState()
    object Creating : PlaylistCreationState()
    object Success : PlaylistCreationState()
}

class WrappedManager(
    private val databaseDao: DatabaseDao,
    private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow(WrappedState())
    val state = _state.asStateFlow()

    fun createPlaylist(imageResName: String) {
        if (_state.value.playlistCreationState != PlaylistCreationState.Idle) return

        _state.update { it.copy(playlistCreationState = PlaylistCreationState.Creating) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val fromTimestamp = Calendar.getInstance().apply {
                        set(WrappedConstants.YEAR, Calendar.JANUARY, 1, 0, 0, 0)
                    }.timeInMillis
                    val toTimestamp = Calendar.getInstance().apply {
                        set(WrappedConstants.YEAR, Calendar.DECEMBER, 31, 23, 59, 59)
                    }.timeInMillis
                    val allSongs = databaseDao.mostPlayedSongsStats(fromTimestamp, toTimeStamp = toTimestamp, limit = -1).first()

                    val playlistId = UUID.randomUUID().toString()

                    val drawableId = context.resources.getIdentifier(imageResName, "drawable", context.packageName)
                    val bitmap = BitmapFactory.decodeResource(context.resources, drawableId)
                    val file = File(context.cacheDir, "$playlistId.png")
                    FileOutputStream(file).use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }

                    val newPlaylist = PlaylistEntity(
                        id = playlistId,
                        name = WrappedConstants.PLAYLIST_NAME,
                        thumbnailUrl = file.toURI().toString(),
                        bookmarkedAt = LocalDateTime.now(),
                        isEditable = true
                    )
                    databaseDao.insert(newPlaylist)

                    val createdPlaylist = databaseDao.playlist(playlistId).first()
                    if (createdPlaylist != null) {
                        val songIds = allSongs.map { it.id }
                        databaseDao.addSongToPlaylist(createdPlaylist, songIds)
                    } else {
                        Timber.tag("WrappedManager")
                            .e("Failed to retrieve created playlist with id: $playlistId")
                    }
                }
                _state.update { it.copy(playlistCreationState = PlaylistCreationState.Success) }
            } catch (e: Exception) {
                Timber.tag("WrappedManager").e(e, "Error saving wrapped playlist")
                _state.update { it.copy(playlistCreationState = PlaylistCreationState.Idle) }
            }
        }
    }

    private suspend fun generatePlaylistMap() {
        val topSongs = _state.value.topSongs
        val topArtists = _state.value.topArtists
        if (topSongs.isEmpty()) {
            Timber.tag("WrappedManager").w("Cannot generate playlist map, top songs list is empty.")
            _state.update { it.copy(trackMap = emptyMap()) }
            return
        }

        withContext(Dispatchers.IO) {
            val playlistMap = mutableMapOf<WrappedScreenType, String>()

            // Intro Part: Random song from top 6-30
            val introSongPool = topSongs.subList(5, topSongs.size)
            val introSong = introSongPool.randomOrNull()?.id ?: topSongs.last().id
            playlistMap[WrappedScreenType.Welcome] = introSong
            playlistMap[WrappedScreenType.MinutesTease] = introSong
            playlistMap[WrappedScreenType.MinutesReveal] = introSong

            // Music Part: Top 1 song
            val topSong = topSongs.first()
            playlistMap[WrappedScreenType.TotalSongs] = topSong.id
            playlistMap[WrappedScreenType.TopSongReveal] = topSong.id
            playlistMap[WrappedScreenType.Top5Songs] = topSong.id

            // Album Part: Random song from top album
            val topAlbum = _state.value.topAlbum
            val albumSong = topAlbum?.let { album ->
                val albumSongs = databaseDao.albumSongs(album.id).first()
                albumSongs.randomOrNull()?.id
            } ?: topSong.id // Fallback to top song if no album songs
            playlistMap[WrappedScreenType.TotalAlbums] = albumSong
            playlistMap[WrappedScreenType.TopAlbumReveal] = albumSong
            playlistMap[WrappedScreenType.Top5Albums] = albumSong

            // Artist Part: Top artist's song with specific rule
            val topArtist = topArtists.firstOrNull()
            val fromTimestamp = Calendar.getInstance().apply {
                set(WrappedConstants.YEAR, Calendar.JANUARY, 1, 0, 0, 0)
            }.timeInMillis
            val toTimestamp = Calendar.getInstance().apply {
                set(WrappedConstants.YEAR, Calendar.DECEMBER, 31, 23, 59, 59)
            }.timeInMillis

            val artistSong = topArtist?.let { artist ->
                val artistTopSongs = databaseDao.artistSongs(
                    artistId = artist.id,
                    sortType = ArtistSongSortType.PLAY_TIME,
                    descending = true,
                    fromTimeStamp = fromTimestamp,
                    toTimeStamp = toTimestamp
                ).first()
                if (artistTopSongs.isNotEmpty()) {
                    val artistTopSong = artistTopSongs.first()
                    if (artistTopSong.id == topSong.id) {
                        // Overlap: Use the artist's second song.
                        // If a second song doesn't exist, use a random song from their list.
                        artistTopSongs.getOrNull(1)?.id ?: artistTopSongs.filter { it.id != topSong.id }.randomOrNull()?.id ?: artistTopSong.id
                    } else {
                        artistTopSong.id
                    }
                } else {
                    // Data anomaly: Fallback to the user's top song.
                    topSong.id
                }
            } ?: topSong.id // Fallback if no top artist.
            playlistMap[WrappedScreenType.TotalArtists] = artistSong
            playlistMap[WrappedScreenType.TopArtistReveal] = artistSong
            playlistMap[WrappedScreenType.Top5Artists] = artistSong

            // End Part
            val endSongPool = topSongs.subList(2, 5)
            val endSong = endSongPool.randomOrNull()?.id ?: topSongs[2].id
            playlistMap[WrappedScreenType.Playlist] = endSong
            playlistMap[WrappedScreenType.Conclusion] = "2-p9DM2Xvsc"

            Timber.tag("WrappedManager").d("Generated Playlist Map: $playlistMap")
            _state.update { it.copy(trackMap = playlistMap) }
        }
    }

    suspend fun prepare() {
        if (_state.value.isDataReady) return
        Timber.tag("WrappedManager").d("Starting Wrapped data preparation")

        val fromTimestamp = Calendar.getInstance().apply {
            set(WrappedConstants.YEAR, Calendar.JANUARY, 1, 0, 0, 0)
        }.timeInMillis

        val toTimestamp = Calendar.getInstance().apply {
            set(WrappedConstants.YEAR, Calendar.DECEMBER, 31, 23, 59, 59)
        }.timeInMillis

        withContext(Dispatchers.IO) {
            val accountInfoDeferred = async { YouTube.accountInfo().getOrNull() }
            val topSongsDeferred = async { databaseDao.mostPlayedSongsStats(fromTimestamp, toTimeStamp = toTimestamp, limit = 30).first() }
            val topArtistsDeferred = async { databaseDao.mostPlayedArtists(fromTimestamp, toTimeStamp = toTimestamp, limit = 5).first() }
            val topAlbumsDeferred = async { databaseDao.mostPlayedAlbums(fromTimestamp, toTimeStamp = toTimestamp, limit = 5).first() }
            val uniqueSongCountDeferred = async { databaseDao.getUniqueSongCountInRange(fromTimestamp, toTimestamp).first() }
            val uniqueArtistCountDeferred = async { databaseDao.getUniqueArtistCountInRange(fromTimestamp, toTimestamp).first() }
            val uniqueAlbumCountDeferred = async { databaseDao.getUniqueAlbumCountInRange(fromTimestamp, toTimestamp).first() }
            val totalPlayTimeMsDeferred = async { databaseDao.getTotalPlayTimeInRange(fromTimestamp, toTimestamp).first() ?: 0L }

            val results = awaitAll(
                accountInfoDeferred,
                topSongsDeferred,
                topArtistsDeferred,
                topAlbumsDeferred,
                uniqueSongCountDeferred,
                uniqueArtistCountDeferred,
                uniqueAlbumCountDeferred,
                totalPlayTimeMsDeferred
            )

            @Suppress("UNCHECKED_CAST")
            val topSongsResult = results[1] as List<SongWithStats>
            @Suppress("UNCHECKED_CAST")
            val topAlbumsResult = results[3] as List<com.metrolist.music.db.entities.Album>
            @Suppress("UNCHECKED_CAST")
            val topArtistsResult = results[2] as List<Artist>
            _state.update {
                it.copy(
                    accountInfo = results[0] as AccountInfo?,
                    topSongs = topSongsResult,
                    topArtists = topArtistsResult,
                    top5Albums = topAlbumsResult,
                    topAlbum = topAlbumsResult.firstOrNull(),
                    uniqueSongCount = results[4] as Int,
                    uniqueArtistCount = results[5] as Int,
                    totalAlbums = results[6] as Int,
                    totalMinutes = (results[7] as Long) / 1000 / 60
                )
            }
        }

        generatePlaylistMap()
        _state.update { it.copy(isDataReady = true) }
        Timber.tag("WrappedManager").d("Wrapped data preparation finished")
    }
}
