package com.metrolist.android.widget

import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.KIND_CACHE
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.historySong
import com.metrolist.shared.db.recentVisibleHistory
import com.metrolist.shared.db.refreshHistoryPlaylist
import com.metrolist.shared.db.refreshUploadedSongs
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.BuiltinPlaylist
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.toSongItem
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

internal data class WidgetPlaylistTarget(
    val key: String,
    val type: String,
    val id: String,
)

internal fun widgetPlaylistTarget(
    id: String,
    isLocal: Boolean,
): WidgetPlaylistTarget {
    val builtin = BuiltinPlaylist.fromId(id)
    if (builtin != null) {
        val type =
            when (builtin) {
                BuiltinPlaylist.LIKED -> PlaylistWidgetReceiver.TARGET_TYPE_LIKED
                BuiltinPlaylist.DOWNLOADED -> PlaylistWidgetReceiver.TARGET_TYPE_DOWNLOADED
                BuiltinPlaylist.TOP -> PlaylistWidgetReceiver.TARGET_TYPE_TOP
                BuiltinPlaylist.WEEKLY, BuiltinPlaylist.MONTHLY -> PlaylistWidgetReceiver.TARGET_TYPE_LOCAL
                else -> PlaylistWidgetReceiver.TARGET_TYPE_COLLECTION
            }
        return WidgetPlaylistTarget(builtin.id, type, builtin.id)
    }
    return WidgetPlaylistTarget(
        key = if (isLocal) "local:$id" else "online:$id",
        type = if (isLocal) PlaylistWidgetReceiver.TARGET_TYPE_LOCAL else PlaylistWidgetReceiver.TARGET_TYPE_ONLINE,
        id = id,
    )
}

internal fun widgetBuiltinPlaylist(
    type: String?,
    id: String,
): BuiltinPlaylist? =
    when (type) {
        PlaylistWidgetReceiver.TARGET_TYPE_LIKED -> BuiltinPlaylist.LIKED
        PlaylistWidgetReceiver.TARGET_TYPE_DOWNLOADED -> BuiltinPlaylist.DOWNLOADED
        PlaylistWidgetReceiver.TARGET_TYPE_TOP -> BuiltinPlaylist.TOP
        else -> BuiltinPlaylist.fromId(id)
    }

internal suspend fun MusicDatabase.widgetBuiltinSongs(builtin: BuiltinPlaylist): List<SongItem> {
    val dao = dao()
    return when (builtin) {
        BuiltinPlaylist.LIKED -> {
            SongLikeState.likedSongsFlow.value
        }

        BuiltinPlaylist.DOWNLOADED -> {
            dao.downloadedSongsByCreateDateAsc().first().map { it.toSongItem() }
        }

        BuiltinPlaylist.TOP -> {
            dao.mostPlayedSongs(0L, limit = 100).first().map { it.toSongItem() }
        }

        BuiltinPlaylist.CACHED -> {
            dao.cachedAudioByKind(KIND_CACHE).first().map { entry ->
                SongItem(
                    id = entry.songId,
                    title = entry.title,
                    thumbnail = entry.thumbnailUrl,
                    artists =
                        entry.artist.split(",").mapNotNull { name ->
                            name.trim().takeIf { it.isNotBlank() && it != "Unknown" }?.let { ArtistTiny(null, it) }
                        },
                    album = entry.album?.let { AlbumTiny(null, it) },
                )
            }
        }

        BuiltinPlaylist.UPLOADED -> {
            try {
                refreshUploadedSongs(GlobalYouTubeRepository.instance)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                dao.uploadedSongsByNameAsc().first().map { it.toSongItem() }
            }
        }

        BuiltinPlaylist.NEW_EPISODES, BuiltinPlaylist.SAVED_EPISODES -> {
            val remote =
                try {
                    if (builtin == BuiltinPlaylist.NEW_EPISODES) {
                        GlobalYouTubeRepository.instance.getNewPodcastEpisodes()
                    } else {
                        GlobalYouTubeRepository.instance.getSavedEpisodes(limit = null).map { it.toSongItem() }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emptyList()
                }
            remote.ifEmpty { dao.savedPodcastEpisodesByCreateDateAsc().first().map { it.toSongItem() } }
        }

        BuiltinPlaylist.HISTORY -> {
            recentVisibleHistory(Int.MAX_VALUE).mapNotNull { historySong(it.id) }
        }

        BuiltinPlaylist.WEEKLY, BuiltinPlaylist.MONTHLY -> {
            refreshHistoryPlaylist(builtin.id)
            dao.playlistSongs(builtin.id).first().map { it.song.toSongItem() }
        }
    }
}
