package com.metrolist.shared.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.all
import com.metrolist.shared.generated.resources.download_filter_in_playlist
import com.metrolist.shared.generated.resources.download_filter_not_in_playlist
import com.metrolist.shared.generated.resources.download_filter_unavailable
import com.metrolist.shared.generated.resources.playlist
import com.metrolist.shared.generated.resources.refresh
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.ui.components.ExpressiveFilterChip
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.ExpressiveSelectionMenu
import com.metrolist.shared.ui.utils.carouselScroll
import com.metrolist.shared.viewmodels.DownloadPlaylistFilter
import com.metrolist.shared.viewmodels.supportsDownloadPlaylistFilter
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource

@Composable
fun rememberDownloadPlaylistFilter(database: MusicDatabase): DownloadPlaylistFilter {
    val scope = rememberCoroutineScope()
    return remember(database, scope) {
        DownloadPlaylistFilter(
            scope,
            localMembership = { id -> database.dao().playlistSongs(id).map { rows -> rows.mapTo(mutableSetOf()) { it.song.id } } },
            remoteMembership = { id -> GlobalYouTubeRepository.instance.playlistSongIds(id, allowOfflineFallback = false, refresh = true) },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadPlaylistFilterBar(
    filter: DownloadPlaylistFilter,
    database: MusicDatabase,
    modifier: Modifier = Modifier,
) {
    val state by filter.state.collectAsState()
    val playlists by remember(database) {
        database.dao().playlistsByNameAsc().map { rows ->
            rows
                .map { it.playlist }
                .filter { it.supportsDownloadPlaylistFilter() }
                .distinctBy { if (it.isLocal) it.id else it.browseId?.removePrefix("VL") ?: it.id }
        }
    }.collectAsState(emptyList())
    var expanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().carouselScroll(scrollState).horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                ExpressiveFilterChip(
                    selected = state.playlist != null,
                    onClick = { expanded = true },
                    modifier = Modifier.menuAnchorSemantics(expanded),
                    label = {
                        Text(
                            state.playlist?.name ?: stringResource(Res.string.playlist),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
                ExpressiveSelectionMenu<PlaylistEntity?>(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    options = listOf(null) + playlists,
                    selectedOption = state.playlist,
                    onOptionSelected = filter::select,
                    optionLabel = { it?.name ?: stringResource(Res.string.all) },
                )
            }
            if (state.playlist != null) {
                ExpressiveFilterChip(
                    selected = state.songIds != null && !state.exclude,
                    onClick = { filter.setExcluded(false) },
                    label = { Text(stringResource(Res.string.download_filter_in_playlist)) },
                )
                ExpressiveFilterChip(
                    selected = state.songIds != null && state.exclude,
                    onClick = { filter.setExcluded(true) },
                    label = { Text(stringResource(Res.string.download_filter_not_in_playlist)) },
                )
                IconButton(onClick = filter::refresh, enabled = !state.loading, shapes = IconButtonDefaults.shapes()) {
                    Icon(MetrolistIcons.Outlined.Refresh, stringResource(if (state.failed) Res.string.retry else Res.string.refresh))
                }
            }
        }
        if (state.loading) ContainedLoadingIndicator()
        if (state.failed) Text(stringResource(Res.string.download_filter_unavailable), color = MaterialTheme.colorScheme.error)
    }
}
