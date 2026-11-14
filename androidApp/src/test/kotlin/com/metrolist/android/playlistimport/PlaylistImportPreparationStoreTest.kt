package com.metrolist.android.playlistimport

import com.metrolist.shared.playlistimport.ImportedPlaylistData
import com.metrolist.shared.playlistimport.PlaylistImportManager
import com.metrolist.shared.playlistimport.UniversalPlaylistParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistImportPreparationStoreTest {
    @Test
    fun csvPreparationRetainsRowsUntilMappingAndValidatesMappedReferences() {
        val data =
            UniversalPlaylistParser.prepareFile(
                "Album;Title;Artist;URL\nAlbum;Song;Artist;https://youtu.be/abcdefghijk",
                "playlist.csv",
                "Playlist",
                "File",
            )
        val store = PlaylistImportPreparationStore()
        store.publish(PlaylistImportPreparationResult("csv", PlaylistImportPreparationType.IMPORT_DATA, Result.success(data)))
        val retained = store.current("csv")!!.result.getOrThrow() as ImportedPlaylistData
        val csv = retained.csv!!
        val mapping = csv.guessMapping().copy(album = -1)
        val tracks = csv.tracks(mapping)
        assertEquals("Song", tracks.single().title)
        assertEquals("", tracks.single().album)
        assertFalse(containsAndroidInaccessibleReference(tracks))
        store.replaceImportData(retained.copy(tracks = tracks, csv = null), mapping)
        val mapped = store.current("csv")!!.result.getOrThrow() as ImportedPlaylistData
        assertNull(mapped.csv)
        assertEquals("abcdefghijk", mapped.tracks.single().videoId)
        assertTrue(containsAndroidInaccessibleReference(tracks.map { it.copy(videoId = null, directReference = "../song.mp3") }))
        assertTrue(store.restorePrevious())
        val restored = store.current("csv")!!
        assertSame(data, restored.result.getOrThrow())
        assertSame(csv, (restored.result.getOrThrow() as ImportedPlaylistData).csv)
        assertEquals(mapping, restored.csvMapping)
        assertFalse(store.restorePrevious())

        val revisedMapping = mapping.copy(album = 0)
        val revised = retained.copy(tracks = csv.tracks(revisedMapping), csv = null)
        store.replaceImportData(revised, revisedMapping)
        assertEquals("Album", (store.current("csv")!!.result.getOrThrow() as ImportedPlaylistData).tracks.single().album)
        assertTrue(store.restorePrevious())
        assertEquals(revisedMapping, store.current("csv")!!.csvMapping)
        store.clear("csv")
        assertNull(store.current("csv"))
    }

    @Test
    fun accountPreparationSurvivesLoadingAndBackUntilExplicitlyDiscarded() {
        val store = PlaylistImportPreparationStore()
        var closed = 0
        val account = AutoCloseable { closed++ }
        val selection = PlaylistImportPreparationResult("account", PlaylistImportPreparationType.ACCOUNT_PLAYLISTS, Result.success(account))
        store.publish(selection)

        val previous = store.take()
        assertFalse(store.hasResult())
        assertEquals(0, closed)
        val data = ImportedPlaylistData("Playlist", emptyList(), source = "Account")
        store.publish(PlaylistImportPreparationResult("tracks", PlaylistImportPreparationType.IMPORT_DATA, Result.success(data), previous))
        store.clear("account")
        assertTrue(store.hasResult())
        assertEquals(0, closed)

        assertTrue(store.restorePrevious())
        assertSame(selection, store.current("account"))
        assertSame(account, store.current("account")!!.result.getOrThrow())
        assertEquals(0, closed)

        val retried = store.take()
        store.publish(PlaylistImportPreparationResult("retry", PlaylistImportPreparationType.IMPORT_DATA, Result.success(data), retried))
        store.clear("retry")
        store.clear()
        assertEquals(1, closed)
        assertFalse(store.restorePrevious())
    }

    @Test
    fun discardingFailedPreparationClosesItsPreviousAccountSession() {
        val store = PlaylistImportPreparationStore()
        var closed = false
        val selection =
            PlaylistImportPreparationResult(
                "account",
                PlaylistImportPreparationType.ACCOUNT_PLAYLISTS,
                Result.success(AutoCloseable { closed = true }),
            )
        store.publish(selection)
        val previous = store.take()
        store.publish(
            PlaylistImportPreparationResult(
                "failure",
                PlaylistImportPreparationType.IMPORT_DATA,
                Result.failure(IllegalStateException("Load failed")),
                previous,
            ),
        )
        assertFalse(closed)
        store.clear("failure")
        assertTrue(closed)
        assertNull(store.result.value)
    }

    @Test
    fun providerReferencesWithMetadataDoNotRejectAnAndroidPlaylist() {
        val providerTracks =
            listOf(
                PlaylistImportManager.CsvTrack("Song", "Artist", null, "spotify:track:abc"),
                PlaylistImportManager.CsvTrack("Song", "Artist", null, "https://music.apple.com/us/song/example/123"),
                PlaylistImportManager.CsvTrack("Song", "Artist", null, "https://www.deezer.com/track/456"),
            )

        assertFalse(containsAndroidInaccessibleReference(providerTracks))
        assertTrue(
            containsAndroidInaccessibleReference(
                listOf(PlaylistImportManager.CsvTrack("Song", "Artist", null, "file:///music/song.mp3")),
            ),
        )
        assertTrue(
            containsAndroidInaccessibleReference(
                listOf(PlaylistImportManager.CsvTrack("", "", null, "https://www.deezer.com/track/456")),
            ),
        )
    }

    @Test
    fun successfulPreparationReplaysUntilDestinationFlowConsumesIt() {
        val store = PlaylistImportPreparationStore()
        val expected =
            PlaylistImportPreparationResult(
                requestId = "request-1",
                type = PlaylistImportPreparationType.SPOTIFY_TRACKS,
                result = Result.success("prepared"),
            )

        store.publish(expected)

        assertEquals("prepared", store.current("request-1")?.result?.getOrThrow())
        assertNull(store.current("stale-request"))
        assertEquals("prepared", store.current("request-1")?.result?.getOrThrow())

        val destination = ImportedPlaylistData("destination", emptyList(), source = "Spotify", sourcePlaylistCount = 2)
        store.replaceImportData(destination)
        assertEquals(PlaylistImportPreparationType.IMPORT_DATA, store.current("request-1")?.type)
        assertEquals(destination, store.current("request-1")?.result?.getOrThrow())

        assertTrue(store.restorePrevious())
        assertSame(expected, store.current("request-1"))
        assertFalse(store.restorePrevious())
        store.replaceImportData(destination)
        store.clear()
        assertNull(store.result.value)
        assertNull(store.current("request-1"))

        store.publish(expected.copy(requestId = "request-2"))
        assertEquals("prepared", store.current("request-2")?.result?.getOrThrow())
        store.clear("request-1")
        assertEquals("prepared", store.current("request-2")?.result?.getOrThrow())
        store.clear("request-2")
        assertNull(store.result.value)
    }
}
