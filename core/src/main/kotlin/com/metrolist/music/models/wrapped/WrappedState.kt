/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.models.wrapped

import com.metrolist.innertube.models.AccountInfo
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.SongWithStats

sealed class WrappedScreenType {
    object Welcome : WrappedScreenType()
    object MinutesTease : WrappedScreenType()
    object MinutesReveal : WrappedScreenType()
    object TotalSongs : WrappedScreenType()
    object TopSongReveal : WrappedScreenType()
    object Top5Songs : WrappedScreenType()
    object TotalAlbums : WrappedScreenType()
    object TopAlbumReveal : WrappedScreenType()
    object Top5Albums : WrappedScreenType()
    object TotalArtists : WrappedScreenType()
    object TopArtistReveal : WrappedScreenType()
    object Top5Artists : WrappedScreenType()
    object Playlist : WrappedScreenType()
    object Conclusion : WrappedScreenType()
}

sealed class PlaylistCreationState {
    object Idle : PlaylistCreationState()
    object Creating : PlaylistCreationState()
    object Success : PlaylistCreationState()
}

data class WrappedState(
    val accountInfo: AccountInfo? = null,
    val totalMinutes: Long = 0,
    val topSongs: List<SongWithStats> = emptyList(),
    val topArtists: List<Artist> = emptyList(),
    val top5Albums: List<Album> = emptyList(),
    val topAlbum: Album? = null,
    val uniqueSongCount: Int = 0,
    val uniqueArtistCount: Int = 0,
    val totalAlbums: Int = 0,
    val isDataReady: Boolean = false,
    val trackMap: Map<WrappedScreenType, String?> = emptyMap(),
    val playlistCreationState: PlaylistCreationState = PlaylistCreationState.Idle
)
