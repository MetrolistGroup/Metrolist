package com.metrolist.shared.deviceconnect

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceConnectRadioPlaybackTest {
    @Test
    fun explicitRadioMarkerIsCommittedWithAcceptedQueueReplacement() {
        val song = SongItem("seed", "Seed", null, emptyList(), null)
        val change =
            reduceDcPlayback(
                DcLocalPlaybackState(),
                DcPlaybackCommand(
                    action = DcPlaybackAction.REPLACE_QUEUE,
                    songs = listOf(song),
                    index = 0,
                    finite = false,
                    explicitRadioQueue = true,
                ),
            )

        assertEquals(true, change?.playback?.explicitRadioQueue)
    }

    @Test
    fun ordinaryNonFiniteQueueDoesNotInheritExplicitRadioMarker() {
        val song = SongItem("seed", "Seed", null, emptyList(), null)
        val queue = MusicQueue().apply { setQueue(listOf(song)) }.snapshot()
        val change =
            reduceDcPlayback(
                DcLocalPlaybackState(queue = queue, finite = false, explicitRadioQueue = true),
                DcPlaybackCommand(
                    action = DcPlaybackAction.REPLACE_QUEUE,
                    songs = listOf(song),
                    index = 0,
                    finite = false,
                ),
            )

        assertEquals(false, change?.playback?.explicitRadioQueue)
    }
}
