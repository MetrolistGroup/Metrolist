package com.metrolist.shared.listentogether.network

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PortableMessageCodecTest {
    @Test
    fun defaultRestartAndEmptyStateAreValidProtoMessages() {
        assertEquals(ServerRestartingPayload(0, null), codec.decodePayload(MessageTypes.SERVER_RESTARTING, byteArrayOf()))
        val state = codec.decodePayload(MessageTypes.SYNC_STATE, byteArrayOf()) as SyncStatePayload
        assertNull(state.currentTrack)
        assertEquals(emptyList(), state.queue)
    }

    @Test
    fun excessiveFieldRepetitionIsRejectedBeforeAllocatingTheWholePayload() {
        val repetitions = ByteArray(100_000 * 2) { if (it % 2 == 0) 8 else 1 }
        assertFailsWith<IllegalArgumentException> { codec.decodePayload(MessageTypes.PING, repetitions) }
        assertEquals(PingPayload(2, 0), codec.decodePayload(MessageTypes.PING, byteArrayOf(8, 1, 8, 2)))
    }

    private val codec = PortableMessageCodec()

    @Test
    fun readsKnownWireAndIgnoresFutureFields() {
        val ping = "0a0470696e671204087b1007".hexToByteArray()
        assertContentEquals(ping, codec.encode(MessageTypes.PING, PingPayload(123, 7)))
        val (type, payload) = codec.decode(ping + "980601".hexToByteArray())
        assertEquals(PingPayload(123, 7), codec.decodePayload(type, payload + "a1060100000000000000".hexToByteArray()))
    }

    @Test
    fun preservesProto3DefaultsAndSignedInt64() {
        val play = codec.decodePayload(MessageTypes.PLAYBACK_ACTION, "0a04706c6179".hexToByteArray()) as PlaybackActionPayload
        assertNull(play.trackInfo)
        assertEquals(0L, play.position)
        assertEquals(0f, play.volume)
        assertEquals(emptyList(), play.queue)
        assertNull(play.insertNext)
        val (type, bytes) = codec.decode(codec.encode(MessageTypes.PING, PingPayload(Long.MIN_VALUE, -1)))
        assertEquals(PingPayload(Long.MIN_VALUE, -1), codec.decodePayload(type, bytes))
    }

    @Test
    fun compressedQueueRoundTripsWithSizeLimits() {
        val compressed = PortableMessageCodec(compressionEnabled = true)
        val track = TrackInfo("song", "Song", "Artist", duration = 180_000)
        val state = SyncStatePayload(track, true, 100, 200, List(300) { track }, 0f, 5)
        val (type, bytes) = codec.decode(compressed.encode(MessageTypes.SYNC_STATE, state))
        assertEquals(state, codec.decodePayload(type, bytes))
        assertFailsWith<IllegalArgumentException> { codec.decode(ByteArray(256 * 1024 + 1)) }
        assertFailsWith<IllegalArgumentException> { codec.decodePayload(type, ByteArray(2 * 1024 * 1024 + 1)) }
        val bomb = compressListenTogetherPayload(ByteArray(2 * 1024 * 1024 + 1))
        assertFailsWith<IllegalArgumentException> { decompressListenTogetherPayload(bomb, 2 * 1024 * 1024) }
        assertFailsWith<IllegalArgumentException> { decompressListenTogetherPayload(bomb.copyOf(bomb.size - 4), 3 * 1024 * 1024) }
    }

    @Test
    fun negotiatedFeatureFieldsRoundTrip() {
        val track = TrackInfo("song", "Song", "Artist", duration = 0)
        listOf(
            MessageTypes.PING to PingPayload(1, 2, true, "song", 3_000, 4_000, true, 0xFFFF_FFFFL),
            MessageTypes.PONG to PongPayload(1, 2, 3, 4, true, -250, true),
            MessageTypes.BUFFER_READY to BufferReadyPayload("song", 9),
            MessageTypes.SYNC_PLAYBACK to
                PlaybackActionPayload(
                    PlaybackActions.CHANGE_TRACK,
                    "song",
                    position = 0,
                    queue = emptyList(),
                    volume = 1f,
                    revision = 3,
                    replaceQueue = true,
                    originUserId = "host",
                    hostSequence = 8,
                ),
            MessageTypes.CLIENT_CAPABILITIES to ClientCapabilities(true, true, "1", ProtocolFeatures.supported),
            MessageTypes.SERVER_CAPABILITIES to ServerCapabilities(true, true, "2", listOf("request_id"), canHost = true),
            MessageTypes.SERVER_RESTARTING to ServerRestartingPayload(5_000, "Server restarting"),
            MessageTypes.SUGGESTION_SUBMITTED to SuggestionSubmittedPayload("sug_1", track),
            MessageTypes.SYNC_STATE to SyncStatePayload(track, true, 1, 2, emptyList(), 0f, 3, "Mix"),
        ).forEach { (type, payload) ->
            val envelope = codec.decodeEnvelope(codec.encode(type, payload, requestId = 42))
            assertEquals(42, envelope.requestId)
            assertEquals(payload, codec.decodePayload(envelope.type, envelope.payload), type)
        }
        val ack = codec.decodeEnvelope(codec.encode(MessageTypes.ACK, null, requestId = 7))
        assertEquals(7, ack.requestId)
        assertNull(codec.decodePayload(ack.type, ack.payload))
    }

    @Test
    fun roomStateHashMatchesServer() {
        val queue = listOf(TrackInfo("next", "", "", duration = 0), TrackInfo("später", "", "", duration = 0))
        assertEquals(1_549_424_594L, roomStateHash("current", queue))
        assertEquals(852_952_723L, roomStateHash(null, emptyList()))
    }

    @Test
    fun rejectsMalformedFieldsAndWrongPayloads() {
        listOf("00", "0a80", "0880808080808080808002", "0affffffffffffffffff01", "0d01", "0b").forEach { hex ->
            assertFailsWith<IllegalArgumentException>(hex) { codec.decodePayload(MessageTypes.PING, hex.hexToByteArray()) }
        }
        assertFailsWith<IllegalArgumentException> { codec.encode(MessageTypes.CREATE_ROOM, PingPayload(1, 2)) }
        assertFailsWith<IllegalArgumentException> { codec.decode("0a07666f726569676e".hexToByteArray()) }
        assertFailsWith<IllegalArgumentException> { codec.decodePayload(MessageTypes.JOIN_APPROVED, byteArrayOf()) }
        assertEquals(SyncStatePayload(null, false, 0L, 0L, emptyList(), 0f), codec.decodePayload(MessageTypes.SYNC_STATE, byteArrayOf()))
    }
}
