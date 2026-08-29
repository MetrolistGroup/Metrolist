package com.metrolist.music.ui.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.metrolist.music.R
import com.metrolist.music.db.entities.SongWithStats


@Composable
fun SongSelectDropdown(
    titleT: String,
    songs: List<SongWithStats>,
    selectedSong: MutableState<SongWithStats?>,
) {
    var expanded by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var textFieldWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current


    val filteredSongs = songs.filter { song ->
        song.title.contains(searchText, ignoreCase = true)
    }

    val maxItemsShown = 75
    val visibleSongs = filteredSongs.take(maxItemsShown)
    val remainingCount = filteredSongs.size - visibleSongs.size

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = searchText,
                onValueChange = {
                    searchText = it
                    expanded = true
                    selectedSong.value = null
                },
                label = { Text(titleT) },
                modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned { coordinates ->
                        textFieldWidthPx = coordinates.size.width
                    }
            )

            Spacer(modifier = Modifier.width(8.dp))
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(with(density) { textFieldWidthPx.toDp() }),
            properties = PopupProperties(focusable = false)
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 160.dp) // the scroll "box"
                    .verticalScroll(rememberScrollState())
            ) {
                visibleSongs.forEach { song ->
                    val scrollState = rememberScrollState()
                    DropdownMenuItem(
                        onClick = {
                            searchText = song.title
                            selectedSong.value = song
                            expanded = false
                        },
                        text = {
                            Row (modifier = Modifier.horizontalScroll(scrollState)) {
                                Text(
                                    text = song.title,
                                    maxLines = 1,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                val displayArtists = song.artists.joinToString(" ${stringResource(R.string.and)} ") { it.name }.ifBlank { song.artistName }
                                displayArtists?.let {
                                    Text(
                                        text = it,
                                        maxLines = 1,
                                        color = androidx.compose.ui.graphics.Color.Gray,
                                        overflow = TextOverflow.Ellipsis // Highly recommended for multi-artist names
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (remainingCount > 0) {
                    DropdownMenuItem(
                        onClick = { /* no-op */ },
                        enabled = false,
                        text = {
                            Text(
                                text = stringResource(
                                    R.string.song_dropdown_more_results,
                                    remainingCount,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}