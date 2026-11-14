package com.metrolist.shared.playlist

import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PlaylistEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal fun storedPlaylistMembership(
    database: MusicDatabase,
    repository: YouTubeRepository,
    playlists: List<PlaylistEntity>,
    songIds: Set<String>,
): Flow<Set<String>> =
    channelFlow {
        if (songIds.isEmpty()) return@channelFlow
        val permits = Semaphore(4)
        playlists.forEach { playlist ->
            launch {
                val containsSongs =
                    try {
                        val cached = songIds.all { database.dao().checkInPlaylist(playlist.id, it) > 0 }
                        val remoteId = remotePlaylistId(playlist, playlist.id)
                        when {
                            cached -> {
                                true
                            }

                            remoteId == null -> {
                                false
                            }

                            else -> {
                                permits.withPermit {
                                    if (songIds.size == 1) {
                                        repository.playlistContainsSong(remoteId, songIds.single())
                                    } else {
                                        repository.playlistSongIds(remoteId).containsAll(songIds)
                                    }
                                }
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        false
                    }
                if (containsSongs) send(playlist.id)
            }
        }
    }.runningFold(emptySet()) { membership, playlistId -> membership + playlistId }
