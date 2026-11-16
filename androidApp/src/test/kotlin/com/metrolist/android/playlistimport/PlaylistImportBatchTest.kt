package com.metrolist.android.playlistimport

import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.playlistimport.ImportProgressState
import com.metrolist.shared.playlistimport.ImportSourceType
import com.metrolist.shared.playlistimport.ImportedPlaylistData
import com.metrolist.shared.playlistimport.PlaylistImportManager
import com.metrolist.shared.spotifyimport.SpotifyImportManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistImportBatchTest {
    @Test
    fun selectedPlaylistsKeepTheirNamesTracksAndCoversAndImportSequentially() =
        runTest {
            for (provider in listOf(ImportSourceType.SPOTIFY, ImportSourceType.APPLE_MUSIC)) {
                val playlists = preparePlaylistBatch(loadedPlaylists(), provider.displayName, provider)
                val imported = mutableListOf<String>()
                var running = false
                val result =
                    executePlaylistBatch(playlists, "Import playlists", {}) { data, destination, _ ->
                        assertFalse(running)
                        running = true
                        assertSame(PlaylistImportManager.Destination.NewSynced, destination)
                        assertEquals(provider, data.provider)
                        assertEquals(provider.displayName, data.source)
                        assertEquals(listOf("${data.title} song"), data.tracks.map { it.title })
                        assertEquals("Album", data.tracks.single().album)
                        assertEquals(120L, data.tracks.single().durationSeconds)
                        assertEquals("https://example.com/${data.title}.jpg", data.coverUrl)
                        delay(1)
                        imported += data.title
                        running = false
                        Result.success(completed(data))
                    }
                assertEquals(listOf("First", "Second", "Third"), imported)
                assertEquals(imported, result.completed.map { it.playlistTitle })
                assertEquals(3, result.importedTracks)
                assertTrue(result.failures.isEmpty())
            }
        }

    @Test
    fun failureKeepsLaterPlaylistsAndPartialCountsWithoutFinishingTheRequestEarly() =
        runTest {
            val playlists = preparePlaylistBatch(loadedPlaylists(), "Spotify", ImportSourceType.SPOTIFY)
            val attempted = mutableListOf<String>()
            val progress = mutableListOf<ImportProgressState>()
            val failure =
                PlaylistImportManager.IncompleteImportException(
                    PlaylistEntity(id = "partial", name = "Second"),
                    importedSongCount = 1,
                    failedSongCount = 1,
                    cause = IllegalStateException("Save failed"),
                )
            val result =
                executePlaylistBatch(playlists, "Import playlists", progress::add) { data, _, update ->
                    attempted += data.title
                    update(ImportProgressState.ResolvingTracks(0, data.tracks.size, data.title, "Artist"))
                    if (data.title == "Second") {
                        update(ImportProgressState.Failed("Save failed", failure))
                        Result.failure(failure)
                    } else {
                        completed(data).let {
                            update(it)
                            Result.success(it)
                        }
                    }
                }
            assertEquals(listOf("First", "Second", "Third"), attempted)
            assertEquals(listOf("First", "Third"), result.completed.map { it.playlistTitle })
            assertEquals(listOf("Second" to failure), result.failures)
            assertEquals(3, result.importedTracks)
            assertEquals(3, progress.size)
            assertTrue(progress.all { it is ImportProgressState.ResolvingTracks })
        }

    @Test
    fun thrownFailureDoesNotDropRemainingPlaylists() =
        runTest {
            val playlists = preparePlaylistBatch(loadedPlaylists(), "Apple Music", ImportSourceType.APPLE_MUSIC)
            val failure = IllegalStateException("Load failed")
            val result =
                executePlaylistBatch(playlists, "Import playlists", {}) { data, _, _ ->
                    if (data.title == "First") throw failure
                    Result.success(completed(data))
                }
            assertEquals(listOf("Second", "Third"), result.completed.map { it.playlistTitle })
            assertEquals(listOf("First" to failure), result.failures)
        }

    @Test
    fun cancellationStopsTheBatchAndReportsSongsAlreadySaved() =
        runTest {
            val playlists = preparePlaylistBatch(loadedPlaylists(), "Spotify", ImportSourceType.SPOTIFY)
            val attempted = mutableListOf<String>()
            val progress = mutableListOf<ImportProgressState>()
            val cancellation = CancellationException("Cancelled")
            val result =
                runCatching {
                    executePlaylistBatch(playlists, "Import playlists", progress::add) { data, _, _ ->
                        attempted += data.title
                        if (data.title == "Second") throw cancellation
                        Result.success(completed(data))
                    }
                }
            assertSame(cancellation, result.exceptionOrNull())
            assertEquals(listOf("First", "Second"), attempted)
            assertEquals(listOf(ImportProgressState.Cancelled(1, "Import playlists")), progress)
        }

    private fun loadedPlaylists() =
        listOf("First", "Second", "Third").map { name ->
            SpotifyImportManager.LoadedPlaylist(
                uri = name,
                name = name,
                image = "https://example.com/$name.jpg",
                tracks = listOf(SpotifyImportManager.ImportTrack("$name song", "Artist", "Album", 120L)),
                reachedTrackLimit = false,
            )
        }

    private fun completed(data: ImportedPlaylistData) =
        ImportProgressState.Completed(
            playlistTitle = data.title,
            totalSourceTracks = data.tracks.size,
            resolvedTracks = data.tracks.size,
            importedTracks = data.tracks.size,
            destinationName = data.title,
        )
}
