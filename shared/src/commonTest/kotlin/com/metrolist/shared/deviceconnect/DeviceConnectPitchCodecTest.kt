package com.metrolist.shared.deviceconnect

import com.metrolist.shared.model.SongItem
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceConnectPitchCodecTest {
    @Test
    fun pitchSurvivesCommandsSnapshotsTransfersAndScalarUpdates() {
        val command = DcPlaybackCommand(DcPlaybackAction.PITCH, pitch = 0.75f)
        val snapshot = DcPlaybackSnapshot("session", 1, 1, 1, DcLocalPlaybackState(speed = 1.25f, pitch = 0.75f))
        val messages =
            listOf(
                DcSyncMessage(DcSyncMessageType.COMMAND, command = command),
                DcSyncMessage(DcSyncMessageType.STATE, snapshot = snapshot),
                DcSyncMessage(DcSyncMessageType.TRANSFER, snapshot = snapshot),
                DcSyncMessage(DcSyncMessageType.UPDATE, speed = 1.25f, pitch = 0.75f),
            )
        for (message in messages) {
            assertEquals(message, DcJson.decodeFromString<DcSyncMessage>(DcJson.encodeToString(message)))
        }
    }

    @Test
    fun absentPitchAndCapabilityKeepLegacyDefaults() {
        val info =
            DcJson.decodeFromString<DeviceInfoPayload>(
                """{"deviceId":"old","deviceName":"Old","model":"desktop","playbackVersion":4}""",
            )
        assertFalse(info.supportsPlaybackPitch)
        assertNull(DcJson.decodeFromString<DcLocalPlaybackState>("""{"speed":1.25}""").pitch)
        assertNull(DcJson.decodeFromString<DcSyncMessage>("""{"type":"UPDATE","speed":1.25}""").pitch)
        val command = DcJson.decodeFromString<DcPlaybackCommand>("""{"action":"SPEED","speed":1.25}""")
        assertNull(command.pitch)
        assertEquals(0.75f, reduceDcPlayback(DcLocalPlaybackState(pitch = 0.75f), command)!!.playback.pitch)
    }

    @Test
    fun legacyReadersIgnorePitchAndCapabilityFields() {
        val state = DcLocalPlaybackState(speed = 1.25f, pitch = 0.75f)
        assertEquals(1.25f, DcJson.decodeFromString<LegacyPlayback>(DcJson.encodeToString(state)).speed)
        val update = DcSyncMessage(DcSyncMessageType.UPDATE, speed = 1.5f, pitch = 0.75f)
        assertEquals(1.5f, DcJson.decodeFromString<LegacyPlayback>(DcJson.encodeToString(update)).speed)
        val info = DeviceInfoPayload("new", "New", "desktop", playbackVersion = 4, supportsPlaybackPitch = true)
        assertEquals(4, DcJson.decodeFromString<LegacyDeviceInfo>(DcJson.encodeToString(info)).playbackVersion)
    }

    @Serializable
    private data class LegacyPlayback(
        val speed: Float = 1f,
    )

    @Serializable
    private data class LegacyDeviceInfo(
        val playbackVersion: Int = 0,
    )

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
