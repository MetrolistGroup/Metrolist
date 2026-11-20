package com.metrolist.android.viewmodels

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LikedAlbumDownloadsTest {
    @Before
    fun isolateContentPreferences() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        Settings.shared.blockedSongIds = "excluded"
    }

    @After
    fun restoreContentPreferences() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun emptyVisiblePageStillQueuesLaterTracksWhenAlbumIsNewlyLiked() =
        runBlocking {
            assertTrue(shouldAutoDownloadLikedAlbum(true, true, 0, true))
            val available = song("later")
            var reads = 0

            val result =
                songsForLikedAlbumDownload(emptyList(), true) {
                    reads++
                    listOf(song("excluded"), available, song("unavailable").copy(isUnavailable = true))
                }

            assertEquals(1, reads)
            assertEquals(listOf(available), result)
        }

    @Test
    fun terminalPlaylistDoesNotReplaceTheVisibleSongSelectionWithRawRows() =
        runBlocking {
            val available = song("visible")
            var reads = 0
            assertTrue(shouldAutoDownloadLikedAlbum(true, true, 1, false))
            val result =
                songsForLikedAlbumDownload(listOf(available), false) {
                    reads++
                    listOf(song("excluded"), available)
                }

            assertEquals(listOf(available), result)
            assertEquals(0, reads)
        }

    @Test
    fun disabledOptionOrUnlikingDoesNotTriggerDownloads() {
        assertFalse(shouldAutoDownloadLikedAlbum(false, true, 3, true))
        assertFalse(shouldAutoDownloadLikedAlbum(true, false, 3, true))
        assertFalse(shouldAutoDownloadLikedAlbum(true, true, 0, false))
    }

    private fun song(id: String) = SongItem(id, id, null, emptyList(), null)
}
