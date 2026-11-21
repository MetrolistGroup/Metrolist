package com.metrolist.shared.deviceconnect

import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceConnectRecommendationLockTest {
    private val seed = SongItem("seed", "Seed", null, emptyList(), null)

    @Test
    fun replaceQueueCarriesRecommendationLockAndExplicitRepeat() {
        val state = DcLocalPlaybackState(repeatMode = DcRepeatMode.ALL)
        val command =
            DcPlaybackCommand(
                DcPlaybackAction.REPLACE_QUEUE,
                songs = listOf(seed),
                index = 0,
                finite = true,
                recommendationsLocked = true,
                repeatMode = DcRepeatMode.OFF,
            )

        val next = reduceDcPlayback(state, command)!!.playback

        assertTrue(next.recommendationsLocked)
        assertEquals(DcRepeatMode.OFF, next.repeatMode)
    }

    @Test
    fun radioQueueClearsLockAndKeepsRepeatWhenNoOverrideIsSent() {
        val state = DcLocalPlaybackState(recommendationsLocked = true, repeatMode = DcRepeatMode.ALL)
        val command =
            DcPlaybackCommand(
                DcPlaybackAction.REPLACE_QUEUE,
                songs = listOf(seed),
                index = 0,
                finite = false,
                explicitRadioQueue = true,
                recommendationsLocked = false,
            )

        val next = reduceDcPlayback(state, command)!!.playback

        assertFalse(next.recommendationsLocked)
        assertEquals(DcRepeatMode.ALL, next.repeatMode)
    }

    @Test
    fun lockSurvivesWireRoundTripAndOlderPeersDefaultToUnlocked() {
        val state = DcLocalPlaybackState(recommendationsLocked = true, repeatMode = DcRepeatMode.OFF)
        val message = DcSyncMessage(DcSyncMessageType.STATE, snapshot = DcPlaybackSnapshot("session", 1, 1, 1, state.forWire()))

        assertEquals(message, DcJson.decodeFromString<DcSyncMessage>(DcJson.encodeToString(message)))
        assertFalse(DcJson.decodeFromString<DcLocalPlaybackState>("""{"finite":true}""").recommendationsLocked)
    }
}
