package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.playlist.LibraryDeletionTracker
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryDeletionTest {
    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        LibraryDeletionTracker.clear()
    }

    @Test
    fun sparseRemoteAlbumKeepsKnownArtists() {
        val local = AlbumItem("album", "Album", null, listOf(ArtistTiny(null, "Unlinked artist")))
        val sparse = local.copy(artists = emptyList())
        assertEquals(local.artists, (mergeLibraryItems(listOf(local), listOf(sparse)).single() as AlbumItem).artists)
        val refreshed = local.copy(artists = listOf(ArtistTiny("artist", "Updated artist")))
        assertEquals(refreshed.artists, (mergeLibraryItems(listOf(local), listOf(refreshed)).single() as AlbumItem).artists)
    }

    @AfterTest
    fun tearDown() {
        LibraryDeletionTracker.clear()
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun tracksDeletedPlaylistWithAndWithoutVLPrefix() {
        LibraryDeletionTracker.trackDeletedPlaylist("VLPL12345", timestamp = 1_000_000_000L)
        assertTrue(LibraryDeletionTracker.isDeleted("playlist:PL12345", now = 1_000_000_000L))

        val playlistItem = PlaylistItem(id = "PL12345", title = "Test Playlist", thumbnail = null)
        val playlistItemWithVL = PlaylistItem(id = "VLPL12345", title = "Test Playlist", thumbnail = null)
        val otherPlaylist = PlaylistItem(id = "PL99999", title = "Keep Playlist", thumbnail = null)

        assertTrue(LibraryDeletionTracker.isDeleted(playlistItem, now = 1_000_000_000L))
        assertTrue(LibraryDeletionTracker.isDeleted(playlistItemWithVL, now = 1_000_000_000L))
        assertFalse(LibraryDeletionTracker.isDeleted(otherPlaylist, now = 1_000_000_000L))

        val filtered = LibraryDeletionTracker.filterDeleted(listOf(playlistItem, otherPlaylist, playlistItemWithVL), now = 1_000_000_000L)
        assertEquals(listOf(otherPlaylist), filtered)
    }

    @Test
    fun mergeLibraryItemsKeepsLocalSortMetadataOnRemoteItems() {
        val local =
            listOf(
                AlbumItem(
                    id = "album",
                    title = "Album",
                    thumbnail = null,
                    artists = listOf(ArtistTiny("artist", "Artist")),
                    songCount = 10,
                    duration = 600,
                    playtime = 1200,
                    libraryAddedAt = 100,
                    libraryUpdatedAt = 200,
                ),
            )
        val remote = listOf(AlbumItem("album", "Album", null, listOf(ArtistTiny("artist", "Artist"))))

        val merged = mergeLibraryItems(local, remote).single() as AlbumItem
        assertEquals(10, merged.songCount)
        assertEquals(600, merged.duration)
        assertEquals(1200, merged.playtime)
        assertEquals(100, merged.libraryAddedAt)
        assertEquals(200, merged.libraryUpdatedAt)
    }

    @Test
    fun mergeLibraryItemsKeepsLocalPlaylistArtworkState() {
        val local =
            PlaylistItem(
                id = "PL1",
                title = "Local playlist",
                thumbnail = null,
                isLocal = true,
                artworkThumbnails = listOf("track-1", "track-2", "track-3", "track-4"),
            )
        val remote = PlaylistItem(id = "PL1", title = "Remote playlist", thumbnail = "generated-collage")

        val merged = mergeLibraryItems(listOf(local), listOf(remote)).single() as PlaylistItem

        assertTrue(merged.isLocal)
        assertEquals("generated-collage", merged.thumbnail)
        assertEquals(local.artworkThumbnails, merged.artworkThumbnails)
        assertEquals(null, merged.customThumbnail)

        val explicitCover = local.copy(thumbnail = "cover.jpg", customThumbnail = "cover.jpg")
        val mergedExplicitCover = mergeLibraryItems(listOf(explicitCover), listOf(remote)).single() as PlaylistItem
        assertEquals("cover.jpg", mergedExplicitCover.thumbnail)
        assertEquals("cover.jpg", mergedExplicitCover.customThumbnail)
    }

    @Test
    fun mergeLibraryItemsKeepsFreshRemoteCoversAndFallsBackToStoredArtwork() {
        val stored = PlaylistItem("PL1", "Stored", "stored-cover")
        val remote = stored.copy(thumbnail = "https://mosaic.scdn.co/640/current.jpg")

        fun merged(
            local: PlaylistItem,
            account: PlaylistItem,
        ) = (mergeLibraryItems(listOf(local), listOf(account)).single() as PlaylistItem).thumbnail

        assertEquals(remote.thumbnail, merged(stored, remote))
        assertEquals(stored.thumbnail, merged(stored, remote.copy(thumbnail = null)))
        assertEquals("custom-cover", merged(stored.copy(customThumbnail = "custom-cover"), remote))
    }

    @Test
    fun mergeLibraryItemsMarksDownloadedAccountCollections() {
        val remote =
            listOf(
                PlaylistItem(id = "VLPL1", title = "Account playlist", thumbnail = null),
                PlaylistItem(id = "PL2", title = "Partial playlist", thumbnail = null),
                AlbumItem("album", "Album", null, listOf(ArtistTiny("artist", "Artist"))),
            )

        val merged = mergeLibraryItems(emptyList(), remote, setOf("playlist:PL1", "album:album"))

        assertTrue((merged[0] as PlaylistItem).isDownloaded)
        assertFalse((merged[1] as PlaylistItem).isDownloaded)
        assertTrue((merged[2] as AlbumItem).isDownloaded)
    }

    @Test
    fun mergeLibraryItemsExcludesDuplicates() {
        val local = listOf(PlaylistItem(id = "PL1", title = "Local Playlist", thumbnail = null))
        val remote =
            listOf(
                PlaylistItem(id = "PL1", title = "Remote Playlist", thumbnail = null),
                PlaylistItem(id = "PL2", title = "Remote 2", thumbnail = null),
            )

        val merged = mergeLibraryItems(local, remote)
        assertEquals(2, merged.size)
        assertEquals("PL1", merged[0].id)
        assertEquals("PL2", merged[1].id)
    }

    @Test
    fun deletionExpiresAfterTimeout() {
        val baseTime = 1_000_000_000L
        LibraryDeletionTracker.trackDeletedPlaylist("PL_timeout", timestamp = baseTime)

        assertTrue(LibraryDeletionTracker.isDeleted("playlist:PL_timeout", now = baseTime + 300_000L))
        assertFalse(LibraryDeletionTracker.isDeleted("playlist:PL_timeout", now = baseTime + 660_000L))
    }

    @Test
    fun reconcileRemovesKeysConfirmedAbsentFromRemote() {
        LibraryDeletionTracker.trackDeletedPlaylist("PL_confirmed", timestamp = 1_000_000_000L)
        LibraryDeletionTracker.trackDeletedPlaylist("PL_pending", timestamp = 1_000_000_000L)

        assertTrue(LibraryDeletionTracker.isDeleted("playlist:PL_confirmed", now = 1_000_000_000L))
        assertTrue(LibraryDeletionTracker.isDeleted("playlist:PL_pending", now = 1_000_000_000L))

        LibraryDeletionTracker.reconcile(setOf("playlist:PL_pending"))

        assertFalse(LibraryDeletionTracker.isDeleted("playlist:PL_confirmed", now = 1_000_000_000L))
        assertTrue(LibraryDeletionTracker.isDeleted("playlist:PL_pending", now = 1_000_000_000L))
    }

    @Test
    fun clearPersistsEmptyState() {
        LibraryDeletionTracker.trackDeletedPlaylist("PL_to_clear", timestamp = 1_000_000_000L)
        assertTrue(LibraryDeletionTracker.isDeleted("playlist:PL_to_clear", now = 1_000_000_000L))

        LibraryDeletionTracker.clear()
        assertFalse(LibraryDeletionTracker.isDeleted("playlist:PL_to_clear", now = 1_000_000_000L))
    }

    @Test
    fun onPlaylistDeletedUpdatesUiWithoutTrackingInDeletionTracker() =
        runTest {
            val job = Job()
            val testScope = CoroutineScope(coroutineContext + job)
            try {
                val viewModel = LibraryViewModel(testScope, database = null)
                val playlist = PlaylistItem(id = "VLPL_local", title = "Local", thumbnail = null)

                viewModel.onItemDeleted(playlist)

                assertFalse(LibraryDeletionTracker.isDeleted("playlist:PL_local", now = 1_000_000_000L))
                assertFalse(LibraryDeletionTracker.isDeleted("playlist:VLPL_local", now = 1_000_000_000L))
                assertFalse(LibraryDeletionTracker.isDeleted(playlist, now = 1_000_000_000L))

                viewModel.onPlaylistDeleted("VLPL_local_2")

                assertFalse(LibraryDeletionTracker.isDeleted("playlist:PL_local_2", now = 1_000_000_000L))
                assertFalse(LibraryDeletionTracker.isDeleted("playlist:VLPL_local_2", now = 1_000_000_000L))
            } finally {
                job.cancelAndJoin()
            }
        }

    @Test
    fun staleRefreshCannotRepublishDeletedPlaylist() =
        runTest {
            val job = Job()
            val testScope = CoroutineScope(coroutineContext + job)
            try {
                val viewModel = LibraryViewModel(testScope, database = null)
                val deleted = PlaylistItem(id = "VLPL_deleted", title = "Deleted", thumbnail = null)
                val retained = PlaylistItem(id = "VLPL_retained", title = "Retained", thumbnail = null)
                val staleItems = listOf(deleted, retained)

                assertTrue(viewModel.publishRefreshedItems(staleItems, 0L))
                viewModel.onPlaylistDeleted(deleted.id)
                assertEquals(listOf(retained), viewModel.uiState.value.items)

                assertFalse(viewModel.publishRefreshedItems(staleItems, 0L))
                assertEquals(listOf(retained), viewModel.uiState.value.items)
            } finally {
                job.cancelAndJoin()
            }
        }
}
