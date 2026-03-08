package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import kotlinx.coroutines.sync.Mutex

internal class PlaylistContinuation {
    val mutex = Mutex()
    var delta: PlaylistPage? = null
}

@ConsistentCopyVisibility
data class PlaylistPage internal constructor(
    val sections: Map<String, List<YTItem>>,
    internal val pending: List<ContinuationBranch>,
    internal val seen: Set<String>,
    internal val session: InnerTube.SessionSnapshot,
    internal val album: AlbumItem? = null,
    val isOffline: Boolean = false,
    internal val continuation: PlaylistContinuation = PlaylistContinuation(),
    internal val membershipSnapshot: YouTubePlaylistMembership.Snapshot? = null,
) {
    val hasMore: Boolean get() = pending.isNotEmpty()
    val songs: List<SongItem> = sections.playlistSongs()
}

internal fun PlaylistPage.withAlbumMetadata(): PlaylistPage {
    val album = album ?: return this
    val albumTiny = AlbumTiny(album.id, album.title)
    val year = album.year?.toIntOrNull()
    var trackNumber = 0
    val total = songs.size.takeUnless { hasMore }
    return copy(
        sections =
            sections.mapValues { (_, items) ->
                items.map { item ->
                    if (item is SongItem) {
                        val track = ++trackNumber
                        if (item.album == albumTiny && item.trackNumber == track && item.totalTrackCount == total &&
                            (item.artists.isNotEmpty() || album.artists.isEmpty()) &&
                            (!item.thumbnail.isNullOrBlank() || album.thumbnail == null) &&
                            item.year == year
                        ) {
                            return@map item
                        }
                        item.copy(
                            album = albumTiny,
                            artists = item.artists.ifEmpty { album.artists },
                            thumbnail = item.thumbnail?.takeIf(String::isNotBlank) ?: album.thumbnail,
                            year = year,
                            trackNumber = track,
                            totalTrackCount = total,
                        )
                    } else {
                        item
                    }
                }
            },
    )
}

fun shouldLoadPlaylistPage(
    lastVisibleSongIndex: Int,
    loadedSongCount: Int,
): Boolean = loadedSongCount > 0 && lastVisibleSongIndex >= (loadedSongCount - minOf(25, loadedSongCount / 4)).coerceAtLeast(1) - 1

fun AlbumPage.playlistPageForPlayback(selectedOnly: Boolean): PlaylistPage? = playlistPage.takeUnless { selectedOnly }
