package com.metrolist.shared.playback

import com.metrolist.shared.api.playlistSongs
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Queues the whole playlist behind an artist shelf, falling back to the loaded rows when it cannot load quickly. */
suspend fun fullArtistShelfQueue(
    song: SongItem,
    sectionTitle: String,
    sections: Map<String, List<YTItem>>,
    sectionBrowseIds: Map<String, Pair<String?, String?>>,
    loadPlaylist: suspend (String) -> List<SongItem> = { id ->
        GlobalYouTubeRepository.instance
            .getPlaylistResult(id, allowOfflineFallback = false)
            .getOrThrow()
            .playlistSongs()
    },
): List<SongItem> {
    val loaded =
        sections[sectionTitle]
            ?.filterIsInstance<SongItem>()
            ?.takeIf { songs -> songs.any { it.id == song.id } } ?: return listOf(song)
    val playlistId = sectionBrowseIds[sectionTitle]?.first?.takeIf { it.startsWith("VL") } ?: return loaded
    val full =
        try {
            withTimeoutOrNull(SHELF_PLAYLIST_TIMEOUT_MS) { loadPlaylist(playlistId) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    return full?.takeIf { songs -> songs.size > loaded.size && songs.any { it.id == song.id } } ?: loaded
}

private const val SHELF_PLAYLIST_TIMEOUT_MS = 3_000L
