/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.music.LocalNavController
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.R
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.ui.menu.AlbumMenu
import com.metrolist.music.ui.menu.ArtistMenu
import com.metrolist.music.ui.menu.PlaylistMenu
import com.metrolist.music.ui.menu.YouTubePlaylistMenu
import kotlinx.coroutines.CoroutineScope

@Composable
fun LibraryArtistListItem(
    menuState: MenuState,
    coroutineScope: CoroutineScope,
    artist: Artist,
    modifier: Modifier = Modifier
) {
    val navController = LocalNavController.current
    ArtistListItem(
        artist = artist,
        trailingContent = {
            androidx.compose.material3.IconButton(
                onClick = {
                    menuState.show {
                        ArtistMenu(
                            originalArtist = artist,
                            coroutineScope = coroutineScope,
                            onDismiss = menuState::dismiss
                        )
                    }
                }
            ) {
                Icon(
                    painter = painterResource(R.drawable.more_vert),
                    contentDescription = null
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                navController.navigate("artist/${artist.id}")
            }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryArtistGridItem(
    menuState: MenuState,
    coroutineScope: CoroutineScope,
    artist: Artist,
    modifier: Modifier = Modifier
) {
    val navController = LocalNavController.current
    ArtistGridItem(
        artist = artist,
        fillMaxWidth = true,
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    navController.navigate("artist/${artist.id}")
                },
                onLongClick = {
                    menuState.show {
                        ArtistMenu(
                            originalArtist = artist,
                            coroutineScope = coroutineScope,
                            onDismiss = menuState::dismiss
                        )
                }
            }
        )
)
}

@Composable
fun LibraryAlbumListItem(
    modifier: Modifier = Modifier,
    menuState: MenuState,
    album: Album,
    isActive: Boolean = false,
    isPlaying: Boolean = false
) {
    val navController = LocalNavController.current
    AlbumListItem(
    album = album,
    isActive = isActive,
    isPlaying = isPlaying,
    trailingContent = {
        androidx.compose.material3.IconButton(
            onClick = {
                menuState.show {
                    AlbumMenu(
                        originalAlbum = album,
                        onDismiss = menuState::dismiss
                    )
                }
            }
        ) {
            Icon(
                painter = painterResource(R.drawable.more_vert),
                contentDescription = null
            )
        }
    },
    modifier = modifier
        .fillMaxWidth()
        .clickable {
            navController.navigate("album/${album.id}")
        }
)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryAlbumGridItem(
    modifier: Modifier = Modifier,
    menuState: MenuState,
    coroutineScope: CoroutineScope,
    album: Album,
    isActive: Boolean = false,
    isPlaying: Boolean = false
) {
    val navController = LocalNavController.current
    AlbumGridItem(
    album = album,
    isActive = isActive,
    isPlaying = isPlaying,
    coroutineScope = coroutineScope,
    fillMaxWidth = true,
    modifier = modifier
        .fillMaxWidth()
        .combinedClickable(
            onClick = {
                navController.navigate("album/${album.id}")
            },
            onLongClick = {
                menuState.show {
                    AlbumMenu(
                        originalAlbum = album,
                        onDismiss = menuState::dismiss
                    )
                }
            }
        )
)
}

@Composable
fun LibraryPlaylistListItem(
    menuState: MenuState,
    coroutineScope: CoroutineScope,
    playlist: Playlist,
    modifier: Modifier = Modifier
) {
    val navController = LocalNavController.current
    PlaylistListItem(
    playlist = playlist,
    trailingContent = {
        androidx.compose.material3.IconButton(
            onClick = {
                menuState.show {
                    if (playlist.playlist.isEditable || playlist.songCount != 0) {
                        PlaylistMenu(
                            playlist = playlist,
                            coroutineScope = coroutineScope,
                            onDismiss = menuState::dismiss
                        )
                    } else {
                        playlist.playlist.browseId?.let { browseId ->
                            YouTubePlaylistMenu(
                                playlist = PlaylistItem(
                                    id = browseId,
                                    title = playlist.playlist.name,
                                    author = null,
                                    songCountText = null,
                                    thumbnail = playlist.thumbnails.getOrNull(0) ?: "",
                                    playEndpoint = WatchEndpoint(
                                        playlistId = browseId,
                                        params = playlist.playlist.playEndpointParams
                                    ),
                                    shuffleEndpoint = WatchEndpoint(
                                        playlistId = browseId,
                                        params = playlist.playlist.shuffleEndpointParams
                                    ),
                                    radioEndpoint = WatchEndpoint(
                                        playlistId = "RDAMPL$browseId",
                                        params = playlist.playlist.radioEndpointParams
                                    ),
                                    isEditable = false
                                ),
                                coroutineScope = coroutineScope,
                                onDismiss = menuState::dismiss
                            )
                        }
                    }
                }
            }
        ) {
            Icon(
                painter = painterResource(R.drawable.more_vert),
                contentDescription = null
            )
        }
    },
    modifier = modifier
        .fillMaxWidth()
        .clickable {
            if (!playlist.playlist.isEditable && playlist.songCount == 0 && playlist.playlist.browseId != null)
                navController.navigate("online_playlist/${playlist.playlist.browseId}")
            else
                navController.navigate("local_playlist/${playlist.id}")
        }
)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryPlaylistGridItem(
    menuState: MenuState,
    coroutineScope: CoroutineScope,
    playlist: Playlist,
    modifier: Modifier = Modifier
) {
    val navController = LocalNavController.current
    PlaylistGridItem(
    playlist = playlist,
    fillMaxWidth = true,
    modifier = modifier
        .fillMaxWidth()
        .combinedClickable(
            onClick = {
                if (!playlist.playlist.isEditable && playlist.songCount == 0 && playlist.playlist.browseId != null)
                    navController.navigate("online_playlist/${playlist.playlist.browseId}")
                else
                    navController.navigate("local_playlist/${playlist.id}")
            },
            onLongClick = {
                menuState.show {
                    if (playlist.playlist.isEditable || playlist.songCount != 0) {
                        PlaylistMenu(
                            playlist = playlist,
                            coroutineScope = coroutineScope,
                            onDismiss = menuState::dismiss
                        )
                    } else {
                        playlist.playlist.browseId?.let { browseId ->
                            YouTubePlaylistMenu(
                                playlist = PlaylistItem(
                                    id = browseId,
                                    title = playlist.playlist.name,
                                    author = null,
                                    songCountText = null,
                                    thumbnail = playlist.thumbnails.getOrNull(0) ?: "",
                                    playEndpoint = WatchEndpoint(
                                        playlistId = browseId,
                                        params = playlist.playlist.playEndpointParams
                                    ),
                                    shuffleEndpoint = WatchEndpoint(
                                        playlistId = browseId,
                                        params = playlist.playlist.shuffleEndpointParams
                                    ),
                                    radioEndpoint = WatchEndpoint(
                                        playlistId = "RDAMPL$browseId",
                                        params = playlist.playlist.radioEndpointParams
                                    ),
                                    isEditable = false
                                ),
                                coroutineScope = coroutineScope,
                                onDismiss = menuState::dismiss
                            )
                        }
                    }
                }
            }
        )
)
}
