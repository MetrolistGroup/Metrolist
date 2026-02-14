package com.metrolist.shared.repository

import com.metrolist.shared.db.MetrolistDatabase
import com.metrolist.shared.db.Song
import kotlinx.coroutines.flow.Flow

class MusicRepository(private val database: MetrolistDatabase) {

    fun getAllSongs(): List<Song> {
        return database.metrolistDatabaseQueries.getAllSongs().executeAsList()
    }

    fun getSongById(id: String): Song? {
        return database.metrolistDatabaseQueries.getSongById(id).executeAsOneOrNull()
    }

    fun searchSongs(query: String, limit: Long = 50): List<Song> {
        return database.metrolistDatabaseQueries.searchSongs(query, limit).executeAsList()
    }

    fun insertSong(song: Song) {
        database.metrolistDatabaseQueries.insertSong(
            id = song.id,
            title = song.title,
            duration = song.duration,
            thumbnailUrl = song.thumbnailUrl,
            albumId = song.albumId,
            albumName = song.albumName,
            explicit = song.explicit,
            year = song.year,
            date = song.date,
            dateModified = song.dateModified,
            liked = song.liked,
            likedDate = song.likedDate,
            totalPlayTime = song.totalPlayTime,
            inLibrary = song.inLibrary,
            dateDownload = song.dateDownload,
            isLocal = song.isLocal,
            libraryAddToken = song.libraryAddToken,
            libraryRemoveToken = song.libraryRemoveToken,
            lyricsOffset = song.lyricsOffset
        )
    }

    fun deleteSong(id: String) {
        database.metrolistDatabaseQueries.deleteSong(id)
    }

    fun getAllPlaylists() = database.metrolistDatabaseQueries.getAllPlaylists().executeAsList()

    fun getPlaylistById(id: String) = database.metrolistDatabaseQueries.getPlaylistById(id).executeAsOneOrNull()

    fun getAllArtists() = database.metrolistDatabaseQueries.getAllArtists().executeAsList()

    fun getArtistById(id: String) = database.metrolistDatabaseQueries.getArtistById(id).executeAsOneOrNull()
}
