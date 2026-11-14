@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.add_a_song
import com.metrolist.shared.generated.resources.add_anyway
import com.metrolist.shared.generated.resources.clear_search
import com.metrolist.shared.generated.resources.duplicate_song
import com.metrolist.shared.generated.resources.error
import com.metrolist.shared.generated.resources.search
import com.metrolist.shared.generated.resources.search_songs
import com.metrolist.shared.generated.resources.skip
import com.metrolist.shared.generated.resources.song_already_in_playlist
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.ui.icons.MetrolistIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun AddSongSheetContent(
    existingSongIds: Set<String>,
    onDismiss: () -> Unit,
    onAddSong: (SongItem, Boolean) -> Unit,
    songContent: @Composable (SongItem, () -> Unit) -> Unit = { song, onClick ->
        SongListItem(song = song, onClick = onClick, onShowMenu = onClick, showThumbnail = true)
    },
) {
    var query by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var duplicateSong by remember { mutableStateOf<SongItem?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var failed by remember { mutableStateOf(false) }

    fun search(submittedQuery: String) {
        if (submittedQuery.isBlank()) return
        searchJob?.cancel()
        searchJob =
            scope.launch {
                isSearching = true
                failed = false
                try {
                    results =
                        GlobalYouTubeRepository.instance
                            .searchFiltered(
                                submittedQuery,
                                SearchFilter.SONG,
                                recordSearchHistory = !Settings.shared.pauseSearchHistory,
                            ).items
                            .filterIsInstance<SongItem>()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed = true
                } finally {
                    isSearching = false
                }
            }
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.add_a_song),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        val textFieldState = rememberTextFieldState(initialText = query)
        val searchBarState = rememberSearchBarState(initialValue = SearchBarValue.Expanded)
        val latestQuery by rememberUpdatedState(query)

        LaunchedEffect(query) {
            if (textFieldState.text.toString() != query) {
                textFieldState.setTextAndPlaceCursorAtEnd(query)
            }
        }

        LaunchedEffect(textFieldState) {
            snapshotFlow { textFieldState.text.toString() }
                .collect { text ->
                    if (text != latestQuery) {
                        query = text
                    }
                }
        }

        val searchBarColors =
            SearchBarDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Keep inline search editable; the collapsed SearchBar disables the keyboard.
            Surface(
                shape = SearchBarDefaults.inputFieldShape,
                content = {
                    SearchBarDefaults.InputField(
                        textFieldState = textFieldState,
                        searchBarState = searchBarState,
                        onSearch = { search(textFieldState.text.toString()) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(Res.string.search_songs)) },
                        leadingIcon = {
                            Icon(
                                imageVector = MetrolistIcons.Default.Search,
                                contentDescription = null,
                            )
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }, shapes = IconButtonDefaults.shapes()) {
                                    Icon(
                                        imageVector = MetrolistIcons.Default.Clear,
                                        contentDescription = stringResource(Res.string.clear_search),
                                    )
                                }
                            }
                        },
                        colors = searchBarColors.inputFieldColors,
                    )
                },
                modifier = Modifier.weight(1f),
                color = searchBarColors.containerColor,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            )
            Button(
                onClick = { search(query) },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(Res.string.search))
            }
        }
        if (failed) Text(stringResource(Res.string.error), color = MaterialTheme.colorScheme.error)
        if (isSearching) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                ContainedLoadingIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(results, key = { index, song -> "add_${song.id}_$index" }) { _, song ->
                    songContent(song) {
                        if (song.id in existingSongIds) {
                            duplicateSong = song
                        } else {
                            onAddSong(song, false)
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
    PopupContent(duplicateSong) { song ->
        AlertDialog(
            onDismissRequest = { duplicateSong = null },
            title = { Text(stringResource(Res.string.duplicate_song)) },
            text = { Text(stringResource(Res.string.song_already_in_playlist, song.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAddSong(song, true)
                        duplicateSong = null
                        onDismiss()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.add_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { duplicateSong = null }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.skip)) }
            },
        )
    }
}
