package com.metrolist.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.shared.player.MusicPlayer
import com.metrolist.shared.repository.MusicRepository
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetrolistApp() {
    val repository: MusicRepository = koinInject()
    val player: MusicPlayer = koinInject()

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Metrolist") }
                )
            }
        ) { paddingValues ->
            HomeScreen(
                repository = repository,
                player = player,
                modifier = Modifier.padding(paddingValues)
            )
        }
    }
}

@Composable
fun HomeScreen(
    repository: MusicRepository,
    player: MusicPlayer,
    modifier: Modifier = Modifier
) {
    var songs by remember { mutableStateOf(emptyList<com.metrolist.shared.db.Song>()) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        songs = repository.getAllSongs()
    }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { query ->
                searchQuery = query
                songs = if (query.isEmpty()) {
                    repository.getAllSongs()
                } else {
                    repository.searchSongs(query)
                }
            },
            label = { Text("Search songs") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(songs) { song ->
                SongItem(
                    song = song,
                    onClick = {
                        // Play song
                        /* player.setMediaItem(
                            MediaItem(
                                id = song.id,
                                title = song.title,
                                artist = "",
                                album = song.albumName,
                                duration = song.duration.toLong(),
                                artworkUrl = song.thumbnailUrl,
                                streamUrl = "" // would need to fetch from YouTube
                            )
                        )
                        player.play() */
                    }
                )
            }
        }
    }
}

@Composable
fun SongItem(
    song: com.metrolist.shared.db.Song,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium
            )
            song.albumName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${song.duration / 60}:${(song.duration % 60).toString().padStart(2, '0')}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
