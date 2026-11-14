package com.metrolist.desktop.state

import com.metrolist.desktop.state.playback.canResolveDeviceConnectTrack
import com.metrolist.desktop.state.playback.canRestoreConnectedQueue
import com.metrolist.desktop.state.playback.deviceConnectCanReusePlayback
import com.metrolist.desktop.state.playback.isConnectedQueueRemoval
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueSnapshot
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceConnectPlaybackPolicyTest {
    private val song = SongItem(id = "track", title = "Track", thumbnail = null, artists = emptyList(), album = null)
    private val queue =
        MusicQueueSnapshot(
            displayedItems = listOf(song, song.copy()),
            originalItems = listOf(song, song.copy()),
            currentIndex = 1,
        )

    @Test
    fun undoRequiresTheOriginalSessionAndExactOptimisticQueue() {
        assertTrue(canRestoreConnectedQueue("session", "session", queue, queue.copy()))
        assertFalse(canRestoreConnectedQueue("session", "next", queue, queue))
        assertFalse(canRestoreConnectedQueue("session", null, queue, queue))
        assertFalse(canRestoreConnectedQueue("session", "session", queue, queue.copy(currentIndex = 0)))
    }

    @Test
    fun connectedUndoRetainsIdentityAcrossSessionChangesAndEqualLocalRemovals() {
        val first = MusicQueue().apply { setQueue(listOf(song, song.copy())) }.removeAt(setOf(1))!!
        val equalButLocal = MusicQueue().apply { setQueue(listOf(song, song.copy())) }.removeAt(setOf(1))!!
        val later = MusicQueue().apply { setQueue(listOf(song, song.copy(id = "later"))) }.removeAt(setOf(1))!!
        val origins = listOf(WeakReference(first), WeakReference(later))
        assertEquals(first, equalButLocal)
        assertTrue(isConnectedQueueRemoval(first, origins))
        assertTrue(isConnectedQueueRemoval(later, origins))
        assertFalse(isConnectedQueueRemoval(equalButLocal, origins))
    }

    @Test
    fun synchronizedLocalEntriesCannotResolveTargetFilesystemPaths() {
        assertTrue(canResolveDeviceConnectTrack(song))
        assertFalse(canResolveDeviceConnectTrack(song.copy(isLocal = true, endpointParams = "/private/audio.wav")))
        assertFalse(canResolveDeviceConnectTrack(song.copy(id = "local_audio", endpointParams = "file:///private/audio.wav")))
    }

    @Test
    fun coordinatorRetainsPreparedCurrentOccurrenceWhenStartingSync() {
        assertTrue(deviceConnectCanReusePlayback(queue, queue.copy(), song.id, ready = true))
        assertFalse(deviceConnectCanReusePlayback(queue, queue.copy(currentIndex = 0), song.id, ready = true))
    }

    @Test
    fun strippingAccountTokensDoesNotReloadThePreparedLibraryOccurrence() {
        val librarySong = song.copy(libraryAddToken = "add-token", libraryRemoveToken = "remove-token")
        val local =
            queue.copy(
                displayedItems = listOf(librarySong, librarySong.copy()),
                originalItems = listOf(librarySong, librarySong.copy()),
            )
        assertTrue(deviceConnectCanReusePlayback(local, queue, song.id, ready = true))
        assertFalse(deviceConnectCanReusePlayback(local, queue.copy(currentIndex = 0), song.id, ready = true))
        val changed = queue.copy(displayedItems = queue.displayedItems.map { it.copy(endpointParams = "different") })
        assertFalse(deviceConnectCanReusePlayback(local, changed, song.id, ready = true))
    }

    @Test
    fun unavailableOrDifferentPlaybackCannotBeReused() {
        assertFalse(deviceConnectCanReusePlayback(queue, queue, song.id, ready = false))
        assertFalse(deviceConnectCanReusePlayback(queue, queue, "other", ready = true))
        assertFalse(deviceConnectCanReusePlayback(queue, MusicQueueSnapshot(), null, ready = true))
    }

    @Test
    fun preparedCounterpartMustReloadTheCanonicalSongEvenWhenPlayerMetadataMatches() {
        assertFalse(deviceConnectCanReusePlayback(queue, queue, song.id, ready = true, currentResourceId = "counterpart-video"))
        assertTrue(deviceConnectCanReusePlayback(queue, queue, song.id, ready = true, currentResourceId = song.id))
    }

    @Test
    fun restoringDifferentOriginalOrderMustPreserveIncomingQueue() {
        val other = song.copy(id = "other")
        val shuffled = queue.copy(displayedItems = listOf(song, other), originalItems = listOf(other, song), isShuffled = true)
        assertTrue(deviceConnectCanReusePlayback(shuffled, shuffled.copy(), other.id, ready = true))
        assertFalse(deviceConnectCanReusePlayback(shuffled, shuffled.copy(originalItems = listOf(song, other)), other.id, ready = true))
    }
}
