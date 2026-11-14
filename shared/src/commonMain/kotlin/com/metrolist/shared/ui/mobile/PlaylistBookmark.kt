package com.metrolist.shared.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.playlist.setStoredPlaylistBookmarked
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal data class PlaylistBookmark(
    val isBookmarked: Boolean,
    val toggle: (() -> Unit)?,
)

@Composable
internal fun rememberPlaylistBookmark(
    playlist: PlaylistItem,
    database: MusicDatabase,
    scope: CoroutineScope,
    onError: () -> Unit,
): PlaylistBookmark {
    val stored by remember(database, playlist.id) {
        combine(database.dao().playlist(playlist.id), database.dao().playlistByBrowseId(playlist.id.removePrefix("VL"))) { local, remote ->
            local ?: remote
        }
    }.collectAsState(null)
    var saving by remember(playlist.id) { mutableStateOf(false) }
    val bookmarked = stored?.playlist?.bookmarkedAt != null
    return PlaylistBookmark(
        isBookmarked = bookmarked,
        toggle =
            if (playlist.isLocal || playlist.isEditable || playlist.id == "LM") {
                null
            } else {
                {
                    if (!saving) {
                        saving = true
                        scope.launch {
                            try {
                                setStoredPlaylistBookmarked(
                                    database,
                                    GlobalYouTubeRepository.instance,
                                    playlist.id,
                                    playlist,
                                    !bookmarked,
                                    playlist.songCount?.toIntOrNull(),
                                ).onFailure { onError() }
                            } finally {
                                saving = false
                            }
                        }
                    }
                }
            },
    )
}
