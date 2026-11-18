package com.metrolist.android.playback

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadedAlbumQueueTest {
    @Before
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @After
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun fullAlbumCanBeAddedNextWithoutDroppingDownloadsOrChangingCurrentOccurrence() {
        val current = song("current")
        val next = song("next")
        val downloaded = song("downloaded", unavailable = true)
        val missing = song("missing", unavailable = true)
        val album = listOf(downloaded, missing, next, downloaded)
        val playable = BlockedContent.filterPlayableSongs(downloadedAlbumQueueSongs(album, setOf(downloaded.id), remotePlayback = false))
        val queue = MusicQueue().apply { setQueue(listOf(current, next)) }
        val currentKey =
            queue.state.value.entries[queue.currentIndex.value]
                .key
        queue.playNext(playable)
        assertEquals(listOf("current", "downloaded", "next", "downloaded", "next"), queue.items.value.map { it.id })
        assertEquals(
            currentKey,
            queue.state.value.entries[queue.currentIndex.value]
                .key,
        )
        assertTrue(downloaded.isUnavailable)
        assertTrue(missing.isUnavailable)
    }

    @Test
    fun remoteQueueDoesNotUseThisDevicesDownloadedCopies() {
        val downloaded = song("downloaded", unavailable = true)
        val online = song("online")
        val album = listOf(downloaded, online, downloaded)
        val playable = BlockedContent.filterPlayableSongs(downloadedAlbumQueueSongs(album, setOf(downloaded.id), remotePlayback = true))
        val queue = MusicQueue().apply { setQueue(listOf(song("current"))) }
        queue.addToQueue(playable)
        assertEquals(listOf("current", "online"), queue.items.value.map { it.id })
    }

    @Test
    fun removedDownloadStaysUnavailableForTheNextMenuAction() {
        val downloaded = song("downloaded", unavailable = true)
        val online = song("online")
        val album = listOf(downloaded, online)
        assertEquals(2, BlockedContent.filterPlayableSongs(downloadedAlbumQueueSongs(album, setOf(downloaded.id), false)).size)
        val afterRemoval = BlockedContent.filterPlayableSongs(downloadedAlbumQueueSongs(album, emptySet(), false))
        assertEquals(listOf(online), afterRemoval)
    }

    private fun song(
        id: String,
        unavailable: Boolean = false,
    ) = SongItem(id, id, null, emptyList(), null, isUnavailable = unavailable)
}
