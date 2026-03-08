package com.metrolist.android.playback

import android.os.Bundle
import android.provider.MediaStore
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem

internal enum class VoiceSearchFocus { ANY, SONG, ARTIST, ALBUM, PLAYLIST }

internal data class VoiceSearchRequest(
    val query: String,
    val focus: VoiceSearchFocus = VoiceSearchFocus.ANY,
    val artist: String = "",
    val album: String = "",
    val title: String = "",
    val playlist: String = "",
) {
    val matchQuery: String
        get() =
            when (focus) {
                VoiceSearchFocus.SONG -> title
                VoiceSearchFocus.ARTIST -> artist
                VoiceSearchFocus.ALBUM -> album
                VoiceSearchFocus.PLAYLIST -> playlist
                VoiceSearchFocus.ANY -> ""
            }.ifBlank { query }.trim()

    val searchQuery: String
        get() =
            listOf(matchQuery, artist.takeIf { focus == VoiceSearchFocus.SONG || focus == VoiceSearchFocus.ALBUM }.orEmpty())
                .filter(String::isNotBlank)
                .distinct()
                .joinToString(" ")

    fun <T : YTItem> bestMatch(candidates: List<T>): T? =
        candidates
            .mapNotNull { candidate ->
                val artists =
                    when (candidate) {
                        is SongItem -> candidate.artists.map { it.name }
                        is AlbumItem -> candidate.artists.map { it.name }
                        else -> emptyList()
                    }
                val correctType =
                    when (focus) {
                        VoiceSearchFocus.ANY -> {
                            candidate is SongItem || candidate is ArtistItem || candidate is AlbumItem ||
                                candidate is PlaylistItem
                        }

                        VoiceSearchFocus.SONG -> {
                            candidate is SongItem
                        }

                        VoiceSearchFocus.ARTIST -> {
                            candidate is ArtistItem
                        }

                        VoiceSearchFocus.ALBUM -> {
                            candidate is AlbumItem
                        }

                        VoiceSearchFocus.PLAYLIST -> {
                            candidate is PlaylistItem
                        }
                    }
                if (!correctType) return@mapNotNull null
                if (artist.isNotBlank() && focus in listOf(VoiceSearchFocus.SONG, VoiceSearchFocus.ALBUM) &&
                    artists.none { VoiceSearchMatcher.score(artist, it) >= VoiceSearchMatcher.STRONG_MATCH_THRESHOLD }
                ) {
                    return@mapNotNull null
                }
                if (focus == VoiceSearchFocus.SONG && album.isNotBlank() && candidate is SongItem &&
                    VoiceSearchMatcher.score(album, candidate.album?.name.orEmpty()) < VoiceSearchMatcher.STRONG_MATCH_THRESHOLD
                ) {
                    return@mapNotNull null
                }
                candidate to VoiceSearchMatcher.score(matchQuery, candidate.title, artists)
            }.filter { it.second >= VoiceSearchMatcher.STRONG_MATCH_THRESHOLD }
            .maxByOrNull { it.second }
            ?.first
}

@Suppress("DEPRECATION")
internal fun voiceSearchRequest(
    query: String,
    extras: Bundle?,
): VoiceSearchRequest =
    VoiceSearchRequest(
        query = query.trim(),
        focus =
            when (extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)) {
                MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> VoiceSearchFocus.ARTIST
                MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> VoiceSearchFocus.ALBUM
                MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE -> VoiceSearchFocus.PLAYLIST
                MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> VoiceSearchFocus.SONG
                else -> VoiceSearchFocus.ANY
            },
        artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST).orEmpty().trim(),
        album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM).orEmpty().trim(),
        title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE).orEmpty().trim(),
        playlist = extras?.getString(MediaStore.EXTRA_MEDIA_PLAYLIST).orEmpty().trim(),
    )

internal fun isVoiceSearchController(
    packageName: String,
    verified: Boolean,
    assistantPackages: Set<String>,
): Boolean = verified && packageName in assistantPackages
