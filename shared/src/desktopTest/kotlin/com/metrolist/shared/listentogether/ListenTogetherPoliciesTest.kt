package com.metrolist.shared.listentogether

import com.metrolist.shared.listentogether.network.PlaybackActionPayload
import com.metrolist.shared.listentogether.network.PlaybackActions
import com.metrolist.shared.listentogether.network.RoomState
import com.metrolist.shared.listentogether.network.TrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ListenTogetherPoliciesTest {
    private val first = track("first")
    private val second = track("second")

    @Test
    fun `revision filter accepts legacy and equal revisions but rejects stale revisions`() {
        val filter = PlaybackRevisionFilter(4)

        assertTrue(filter.accept(0))
        assertTrue(filter.accept(4))
        assertTrue(filter.accept(7))
        assertFalse(filter.accept(6))
        assertEquals(7, filter.revision)
    }

    @Test
    fun `versioned queue action uses canonical server snapshot`() {
        val state = room(queue = listOf(first))
        val result =
            ListenTogetherProtocolReducer.reduce(
                state,
                PlaybackActionPayload(
                    action = PlaybackActions.QUEUE_ADD,
                    trackInfo = track("ignored-delta"),
                    queue = listOf(second),
                    revision = 8,
                ),
            )

        assertEquals(listOf(second), result.queue)
        assertEquals(8, result.revision)
    }

    @Test
    fun `legacy queue action applies its delta`() {
        val result =
            ListenTogetherProtocolReducer.reduce(
                room(queue = listOf(first)),
                PlaybackActionPayload(
                    action = PlaybackActions.QUEUE_ADD,
                    trackInfo = second,
                    insertNext = true,
                ),
            )

        assertEquals(listOf(second, first), result.queue)
    }

    @Test
    fun `queue and volume actions preserve the playback time anchor`() {
        val state = room(queue = listOf(first)).copy(lastUpdate = 10)
        val queued =
            ListenTogetherProtocolReducer.reduce(
                state,
                PlaybackActionPayload(
                    action = PlaybackActions.SYNC_QUEUE,
                    queue = listOf(second),
                    serverTime = 100,
                    revision = 4,
                ),
            )
        val volume =
            ListenTogetherProtocolReducer.reduce(
                queued,
                PlaybackActionPayload(
                    action = PlaybackActions.SET_VOLUME,
                    volume = 0.5f,
                    serverTime = 200,
                    revision = 5,
                ),
            )

        assertEquals(10, volume.lastUpdate)
        assertEquals(0.5f, volume.volume)
    }

    @Test
    fun `moderation is trimmed and case insensitive`() {
        val original = setOf("Blocked User")

        assertTrue(ListenTogetherModeration.isBlocked(" blocked user ", original))
        assertSame(original, ListenTogetherModeration.add("BLOCKED USER", original))
        assertEquals(emptySet(), ListenTogetherModeration.remove("blocked user", original))
    }

    @Test
    fun `canonical upcoming queue preserves server order and duplicate occurrences`() {
        assertEquals(
            listOf(track("current"), first, first.copy(title = "duplicate"), second),
            canonicalUpcomingQueue(listOf(track("current"), first, first.copy(title = "duplicate"), second)),
        )
    }

    @Test
    fun `drift policy ignores jitter soft corrects bounded drift and seeks large drift`() {
        assertIs<DriftCorrection.None>(driftCorrection(1_000, 1_049, true, true))
        val soft = assertIs<DriftCorrection.Soft>(driftCorrection(1_000, 1_500, true, true))
        assertEquals(1.015f, soft.speed)
        assertEquals(33_333L, soft.durationMs)
        assertEquals(2_000, assertIs<DriftCorrection.Seek>(driftCorrection(1_000, 2_000, true, true)).positionMs)
        assertIs<DriftCorrection.Seek>(driftCorrection(1_000, 1_500, false, true))
    }

    private fun room(queue: List<TrackInfo>) =
        RoomState(
            roomCode = "ROOM",
            hostId = "host",
            users = emptyList(),
            currentTrack = null,
            isPlaying = false,
            position = 0,
            lastUpdate = 10,
            queue = queue,
            revision = 3,
        )

    private fun track(id: String) = TrackInfo(id, id, "artist", duration = 1_000)
}
