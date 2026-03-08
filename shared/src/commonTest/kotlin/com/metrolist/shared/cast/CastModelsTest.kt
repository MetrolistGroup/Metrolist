package com.metrolist.shared.cast

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastModelsTest {
    @Test
    fun receiverCheckpointBelongsToTheCurrentQueueOccurrence() {
        val song = SongItem("song", "Song", null, emptyList(), null)
        val queue = MusicQueue()
        queue.setQueue(listOf(song, song))
        val remote =
            CastState(
                status = CastConnectionStatus.CONNECTED,
                device = CastDevice("receiver", "Receiver", CastProtocol.FCAST, "host", 46899),
                queueEntry =
                    queue.state.value.entries
                        .first(),
                positionMs = 60_000L,
                isPlaying = true,
            )
        assertEquals(60_000L, remote.forQueueEntry(queue.state.value)?.positionMs)
        assertTrue(remote.forQueueEntry(queue.state.value)!!.isPlaying)
        queue.addToQueue(listOf(song))
        assertEquals(remote, remote.forQueueEntry(queue.state.value))
        queue.next()
        assertNull(remote.forQueueEntry(queue.state.value))
        queue.skipTo(0)
        assertNull(remote.copy(status = CastConnectionStatus.DISCONNECTED).forQueueEntry(queue.state.value))
        queue.setQueue(listOf(song))
        assertNull(remote.forQueueEntry(queue.state.value))
    }

    @Test
    fun explicitRemoteStopSuppressesReceiverEnd() {
        assertFalse(acceptedCastPlaybackEnd(receiverHasEnded = true, remoteStopRequested = true))
        assertTrue(acceptedCastPlaybackEnd(receiverHasEnded = true, remoteStopRequested = false))
    }

    @Test
    fun playbackEndsOnlyAfterPlayingStopsAtTrackEnd() {
        assertTrue(castPlaybackEndedNearDuration(wasPlaying = true, isStopped = true, positionMs = 9_000, durationMs = 10_000))
        assertFalse(castPlaybackEndedNearDuration(wasPlaying = true, isStopped = false, positionMs = 9_000, durationMs = 10_000))
        assertFalse(castPlaybackEndedNearDuration(wasPlaying = true, isStopped = true, positionMs = 5_000, durationMs = 10_000))
        assertFalse(castPlaybackEndedNearDuration(wasPlaying = false, isStopped = true, positionMs = 10_000, durationMs = 10_000))
    }

    @Test
    fun castEndIsHandledOncePerRemoteMediaGeneration() {
        val ended =
            CastState(
                status = CastConnectionStatus.CONNECTED,
                device = CastDevice("id", "Nest", CastProtocol.GOOGLE_CAST, "127.0.0.1", 8009),
                hasEnded = true,
                mediaGeneration = 2L,
            )
        assertTrue(shouldHandleCastPlaybackEnd(ended, handledGeneration = null))
        assertFalse(shouldHandleCastPlaybackEnd(ended, handledGeneration = 2L))
        assertFalse(
            shouldHandleCastPlaybackEnd(ended.copy(status = CastConnectionStatus.DISCONNECTED, device = null), handledGeneration = null),
        )
        assertTrue(shouldHandleCastPlaybackEnd(ended.copy(mediaGeneration = 3L), handledGeneration = 2L))
    }
}
