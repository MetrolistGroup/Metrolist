package com.metrolist.shared.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.shared.db.HistoryCursor
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.recentVisibleHistory
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.error
import com.metrolist.shared.generated.resources.history_empty
import com.metrolist.shared.generated.resources.liked_songs
import com.metrolist.shared.generated.resources.liked_songs_empty
import com.metrolist.shared.generated.resources.recently_played
import com.metrolist.shared.generated.resources.search
import com.metrolist.shared.model.PlaylistSongSort
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.sortedForLikedPlaylist
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.state.SongLikeState
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private enum class AddSongSource(
    val label: StringResource,
) {
    SEARCH(Res.string.search),
    RECENT(Res.string.recently_played),
    LIKED(Res.string.liked_songs),
}

@Composable
fun AddSongSources(
    database: MusicDatabase,
    isLocalPlaylist: Boolean,
    searchResults: List<SongItem>,
    searchContent: @Composable () -> Unit,
    songList: @Composable (List<SongItem>) -> Unit,
) {
    var source by remember { mutableStateOf(AddSongSource.SEARCH) }
    ChipShelf(
        options = AddSongSource.entries,
        selectedOption = source,
        onSelectionChange = { if (it != null) source = it },
        label = { stringResource(it.label) },
        selectionRequired = true,
        contentPadding = PaddingValues(0.dp),
    )
    key(source) {
        if (source == AddSongSource.SEARCH) {
            searchContent()
            if (searchResults.isNotEmpty()) songList(searchResults)
        } else {
            val selectedSource = source
            val likedSongs by SongLikeState.likedSongsFlow.collectAsState()
            val likedRecency = SongLikeState.likedSongRecency
            val songs by produceState<Result<List<SongItem>>?>(null, database, isLocalPlaylist) {
                value =
                    try {
                        if (selectedSource == AddSongSource.LIKED) {
                            Result.success(emptyList())
                        } else {
                            val found = mutableListOf<SongItem>()
                            var cursor: HistoryCursor? = null
                            do {
                                val page = database.recentVisibleHistory(limit = 100, before = cursor, catalogArtistIds = true)
                                val ids = page.map { it.songId }
                                val byId = database.dao().getSongsByIds(ids).associateBy { it.id }
                                val eligible =
                                    ids
                                        .mapNotNull(byId::get)
                                        .filter { !it.song.isEpisode && (isLocalPlaylist || !it.song.isLocal) }
                                        .map { it.toSongItem() }
                                found += BlockedContent.filterSongs(eligible)
                                cursor = page.lastOrNull()?.let { HistoryCursor(it.playedAt, it.id, it.remoteOrder) }
                            } while (page.size == 100 && found.size < 100)
                            Result.success(found.take(100))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
            }
            val result =
                if (selectedSource == AddSongSource.LIKED) {
                    Result.success(
                        BlockedContent.filterSongs(
                            likedSongs
                                .filter { !it.isPodcast && (isLocalPlaylist || !it.isLocal) }
                                .sortedForLikedPlaylist(PlaylistSongSort.DATE_ADDED, SortDirection.DESCENDING, likedRecency),
                        ),
                    )
                } else {
                    songs
                }
            val items = result?.getOrNull()
            when {
                result == null -> {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        ContainedLoadingIndicator()
                    }
                }

                items == null -> {
                    Text(stringResource(Res.string.error), color = MaterialTheme.colorScheme.error)
                }

                items.isEmpty() -> {
                    Text(
                        stringResource(
                            if (selectedSource == AddSongSource.LIKED) Res.string.liked_songs_empty else Res.string.history_empty,
                        ),
                    )
                }

                else -> {
                    songList(items)
                }
            }
        }
    }
}
