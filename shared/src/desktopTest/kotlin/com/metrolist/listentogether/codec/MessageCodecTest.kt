package com.metrolist.listentogether.codec

import com.google.protobuf.ByteString
import com.metrolist.music.listentogether.proto.Listentogether
import com.metrolist.shared.listentogether.network.ApproveJoinPayload
import com.metrolist.shared.listentogether.network.ApproveSuggestionPayload
import com.metrolist.shared.listentogether.network.BufferCompletePayload
import com.metrolist.shared.listentogether.network.BufferReadyPayload
import com.metrolist.shared.listentogether.network.BufferWaitPayload
import com.metrolist.shared.listentogether.network.ClientCapabilities
import com.metrolist.shared.listentogether.network.CreateRoomPayload
import com.metrolist.shared.listentogether.network.ErrorPayload
import com.metrolist.shared.listentogether.network.HostChangedPayload
import com.metrolist.shared.listentogether.network.JoinApprovedPayload
import com.metrolist.shared.listentogether.network.JoinRejectedPayload
import com.metrolist.shared.listentogether.network.JoinRequestPayload
import com.metrolist.shared.listentogether.network.JoinRoomPayload
import com.metrolist.shared.listentogether.network.KickUserPayload
import com.metrolist.shared.listentogether.network.KickedPayload
import com.metrolist.shared.listentogether.network.MessageTypes
import com.metrolist.shared.listentogether.network.PingPayload
import com.metrolist.shared.listentogether.network.PlaybackActionPayload
import com.metrolist.shared.listentogether.network.PongPayload
import com.metrolist.shared.listentogether.network.PortableMessageCodec
import com.metrolist.shared.listentogether.network.ReconnectPayload
import com.metrolist.shared.listentogether.network.ReconnectedPayload
import com.metrolist.shared.listentogether.network.RejectJoinPayload
import com.metrolist.shared.listentogether.network.RejectSuggestionPayload
import com.metrolist.shared.listentogether.network.RoomCreatedPayload
import com.metrolist.shared.listentogether.network.RoomState
import com.metrolist.shared.listentogether.network.ServerCapabilities
import com.metrolist.shared.listentogether.network.ServerRestartingPayload
import com.metrolist.shared.listentogether.network.SuggestTrackPayload
import com.metrolist.shared.listentogether.network.SuggestionApprovedPayload
import com.metrolist.shared.listentogether.network.SuggestionReceivedPayload
import com.metrolist.shared.listentogether.network.SuggestionRejectedPayload
import com.metrolist.shared.listentogether.network.SuggestionSubmittedPayload
import com.metrolist.shared.listentogether.network.SyncStatePayload
import com.metrolist.shared.listentogether.network.TrackInfo
import com.metrolist.shared.listentogether.network.TransferHostPayload
import com.metrolist.shared.listentogether.network.UserDisconnectedPayload
import com.metrolist.shared.listentogether.network.UserInfo
import com.metrolist.shared.listentogether.network.UserJoinedPayload
import com.metrolist.shared.listentogether.network.UserLeftPayload
import com.metrolist.shared.listentogether.network.UserReconnectedPayload
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MessageCodecTest {
    @Test
    fun roomEntryRequiresStateAndMatchingIdentifiersOnBothCodecs() {
        val missing =
            Listentogether.JoinApprovedPayload
                .newBuilder()
                .setRoomCode("ROOM")
                .setUserId("guest")
                .setSessionToken("token")
        val missingReconnect =
            Listentogether.ReconnectedPayload
                .newBuilder()
                .setRoomCode("ROOM")
                .setUserId("guest")
        for (decoder in listOf(codec, portable)) {
            assertFailsWith<IllegalArgumentException> { decoder.decodePayload(MessageTypes.JOIN_APPROVED, missing.build().toByteArray()) }
            assertFailsWith<IllegalArgumentException> {
                decoder.decodePayload(
                    MessageTypes.RECONNECTED,
                    missingReconnect.build().toByteArray(),
                )
            }
            val wrongRoom =
                Listentogether.RoomState
                    .newBuilder()
                    .setRoomCode("OTHER")
                    .setHostId("host")
                    .build()
            assertFailsWith<IllegalArgumentException> {
                decoder.decodePayload(
                    MessageTypes.JOIN_APPROVED,
                    missing
                        .clone()
                        .setState(wrongRoom)
                        .build()
                        .toByteArray(),
                )
            }
            assertEquals(ServerRestartingPayload(0, null), decoder.decodePayload(MessageTypes.SERVER_RESTARTING, byteArrayOf()))
        }
    }

    private val codec = MessageCodec()
    private val portable = PortableMessageCodec()

    @Test
    fun liteMessagesPreserveExistingWireFormatAndUnknownFields() {
        val wire = "0a0470696e671204087b1007".hexToByteArray()
        assertContentEquals(wire, codec.encode(MessageTypes.PING, PingPayload(123L, 7L)))
        val (type, payload) = codec.decode(wire)
        assertEquals(PingPayload(123L, 7L), codec.decodePayload(type, payload))

        val withFutureField = wire + "980601".hexToByteArray()
        assertContentEquals(withFutureField, Listentogether.Envelope.parseFrom(withFutureField).toByteArray())
        val (futureType, futurePayload) = codec.decode(withFutureField)
        assertEquals(PingPayload(123L, 7L), codec.decodePayload(futureType, futurePayload))
        val (nativeType, nativePayload) = portable.decode(withFutureField)
        assertEquals(PingPayload(123L, 7L), portable.decodePayload(nativeType, nativePayload))
    }

    @Test
    fun unknownMessageTypeFailsExplicitly() {
        assertFailsWith<IllegalArgumentException> {
            codec.decodePayload("unknown", byteArrayOf())
        }

        val envelope =
            Listentogether.Envelope
                .newBuilder()
                .setType("unknown")
                .build()
                .toByteArray()
        assertFailsWith<IllegalArgumentException> {
            codec.decode(envelope)
        }
    }

    @Test
    fun rejectsPayloadWrappedUnderWrongMessageType() {
        assertFailsWith<IllegalArgumentException> {
            codec.encode(MessageTypes.CREATE_ROOM, PingPayload(1L, 2L))
        }
    }

    @Test
    fun allClientAndServerMessagesRoundTripSymmetrically() {
        val track = TrackInfo("track", "Track", "Artist", "Album", 180_000L, "artwork", "guest")
        val room =
            RoomState(
                roomCode = "ROOM",
                hostId = "host-id",
                users = listOf(UserInfo("host-id", "Host", true, false)),
                currentTrack = track,
                isPlaying = true,
                position = 12_345L,
                lastUpdate = 67_890L,
                volume = 0.5f,
                queue = listOf(track),
                revision = 4L,
            )
        val playback =
            PlaybackActionPayload(
                action = "sync_queue",
                trackId = "track",
                position = 12_345L,
                trackInfo = track,
                insertNext = true,
                queue = listOf(track),
                queueTitle = "Queue",
                volume = 0.5f,
                serverTime = 67_890L,
                revision = 4L,
                capturedAtServerTime = 67_800L,
            )
        val cases =
            listOf(
                MessageTypes.CREATE_ROOM to CreateRoomPayload("Host"),
                MessageTypes.JOIN_ROOM to JoinRoomPayload("ROOM", "Guest"),
                MessageTypes.LEAVE_ROOM to null,
                MessageTypes.APPROVE_JOIN to ApproveJoinPayload("guest-id"),
                MessageTypes.REJECT_JOIN to RejectJoinPayload("guest-id", "reason"),
                MessageTypes.PLAYBACK_ACTION to playback,
                MessageTypes.BUFFER_READY to BufferReadyPayload("track"),
                MessageTypes.KICK_USER to KickUserPayload("guest-id", "reason"),
                MessageTypes.TRANSFER_HOST to TransferHostPayload("guest-id"),
                MessageTypes.PING to PingPayload(123L, 7L),
                MessageTypes.REQUEST_SYNC to null,
                MessageTypes.RECONNECT to ReconnectPayload("session"),
                MessageTypes.SUGGEST_TRACK to SuggestTrackPayload(track),
                MessageTypes.APPROVE_SUGGESTION to ApproveSuggestionPayload("suggestion"),
                MessageTypes.REJECT_SUGGESTION to RejectSuggestionPayload("suggestion", "reason"),
                MessageTypes.CLIENT_CAPABILITIES to ClientCapabilities(true, true, "client"),
                MessageTypes.ROOM_CREATED to RoomCreatedPayload("ROOM", "host-id", "session"),
                MessageTypes.JOIN_REQUEST to JoinRequestPayload("guest-id", "Guest"),
                MessageTypes.JOIN_APPROVED to JoinApprovedPayload("ROOM", "guest-id", "session", room),
                MessageTypes.JOIN_REJECTED to JoinRejectedPayload("reason"),
                MessageTypes.USER_JOINED to UserJoinedPayload("guest-id", "Guest"),
                MessageTypes.USER_LEFT to UserLeftPayload("guest-id", "Guest"),
                MessageTypes.SYNC_PLAYBACK to playback,
                MessageTypes.BUFFER_WAIT to BufferWaitPayload("track", listOf("guest-id")),
                MessageTypes.BUFFER_COMPLETE to BufferCompletePayload("track"),
                MessageTypes.ERROR to ErrorPayload("code", "message"),
                MessageTypes.PONG to PongPayload(123L, 124L, 125L, 7L),
                MessageTypes.HOST_CHANGED to HostChangedPayload("guest-id", "Guest"),
                MessageTypes.KICKED to KickedPayload("reason"),
                MessageTypes.SYNC_STATE to SyncStatePayload(track, true, 12_345L, 67_890L, listOf(track), 0.5f, 4L),
                MessageTypes.RECONNECTED to ReconnectedPayload("ROOM", "guest-id", room, false),
                MessageTypes.USER_RECONNECTED to UserReconnectedPayload("guest-id", "Guest"),
                MessageTypes.USER_DISCONNECTED to UserDisconnectedPayload("guest-id", "Guest"),
                MessageTypes.SUGGESTION_RECEIVED to SuggestionReceivedPayload("suggestion", "guest-id", "Guest", track),
                MessageTypes.SUGGESTION_APPROVED to SuggestionApprovedPayload("suggestion", track),
                MessageTypes.SUGGESTION_REJECTED to SuggestionRejectedPayload("suggestion", "reason"),
                MessageTypes.SERVER_CAPABILITIES to ServerCapabilities(true, true, "server"),
                MessageTypes.SERVER_RESTARTING to ServerRestartingPayload(5000L, "maintenance"),
                MessageTypes.SERVER_RESTARTING to ServerRestartingPayload(0L),
                MessageTypes.SUGGESTION_SUBMITTED to SuggestionSubmittedPayload("suggestion", track),
                MessageTypes.SUGGESTION_SUBMITTED to SuggestionSubmittedPayload("", TrackInfo("", "", "", duration = 0L)),
            )

        cases.forEach { (messageType, payload) ->
            val encoded = codec.encode(messageType, payload)
            assertContentEquals(encoded, portable.encode(messageType, payload), messageType)
            val (decodedType, payloadBytes) = portable.decode(encoded)
            assertEquals(messageType, decodedType)
            assertEquals(payload, portable.decodePayload(decodedType, payloadBytes), messageType)
            assertEquals(payload, codec.decodePayload(decodedType, payloadBytes), messageType)
        }
    }

    @Test
    fun playbackDefaultsFollowProto3WireDefaults() {
        val play = roundTrip(MessageTypes.PLAYBACK_ACTION, PlaybackActionPayload(action = "play")) as PlaybackActionPayload
        assertEquals(0L, play.position)
        assertNull(play.insertNext)
        assertEquals(emptyList(), play.queue)
        assertEquals(1f, play.volume)

        val seek = roundTrip(MessageTypes.PLAYBACK_ACTION, PlaybackActionPayload(action = "seek", position = 0L)) as PlaybackActionPayload
        assertEquals(0L, seek.position)

        val mute =
            roundTrip(
                MessageTypes.PLAYBACK_ACTION,
                PlaybackActionPayload(action = "set_volume", volume = 0f),
            ) as PlaybackActionPayload
        assertEquals(0f, mute.volume)

        val syncQueue =
            roundTrip(
                MessageTypes.PLAYBACK_ACTION,
                PlaybackActionPayload(action = "sync_queue", queue = emptyList()),
            ) as PlaybackActionPayload
        assertEquals(emptyList(), syncQueue.queue)
    }

    @Test
    fun emptySyncStateHasNoCurrentTrackAndPreservesMute() {
        val bytes =
            Listentogether.SyncStatePayload
                .newBuilder()
                .setLastUpdate(1L)
                .setVolume(0f)
                .build()
                .toByteArray()
        val decoded = codec.decodePayload(MessageTypes.SYNC_STATE, bytes) as SyncStatePayload

        assertNull(decoded.currentTrack)
        assertEquals(0f, decoded.volume)
        assertEquals(decoded, portable.decodePayload(MessageTypes.SYNC_STATE, bytes))
    }

    @Test
    fun emptyJoinedRoomHasNoCurrentTrack() {
        val bytes =
            Listentogether.JoinApprovedPayload
                .newBuilder()
                .setRoomCode("ROOM")
                .setUserId("guest")
                .setSessionToken("session")
                .setState(
                    Listentogether.RoomState
                        .newBuilder()
                        .setRoomCode("ROOM")
                        .setHostId("host")
                        .build(),
                ).build()
                .toByteArray()

        val decoded = codec.decodePayload(MessageTypes.JOIN_APPROVED, bytes) as JoinApprovedPayload

        assertNull(decoded.state.currentTrack)
        assertEquals(decoded, portable.decodePayload(MessageTypes.JOIN_APPROVED, bytes))
    }

    @Test
    fun compressedLargeQueueIsNotTruncated() {
        val compressedCodec = MessageCodec(compressionEnabled = true)
        val queue = List(300) { index -> TrackInfo("track-$index", "Track $index", "Artist", null, 180_000L, null) }
        val encoded =
            compressedCodec.encode(
                MessageTypes.SYNC_PLAYBACK,
                PlaybackActionPayload(action = "sync_queue", queue = queue),
            )

        val (type, bytes) = compressedCodec.decode(encoded)
        val decoded = compressedCodec.decodePayload(type, bytes) as PlaybackActionPayload
        val (portableType, portableBytes) = portable.decode(encoded)
        assertEquals(decoded, portable.decodePayload(portableType, portableBytes))
        val portableCompressed = PortableMessageCodec(compressionEnabled = true)
        val nativeEncoded = portableCompressed.encode(type, decoded)
        val (jvmType, jvmBytes) = compressedCodec.decode(nativeEncoded)
        assertEquals(decoded, compressedCodec.decodePayload(jvmType, jvmBytes))
        assertEquals(300, decoded.queue?.size)
    }

    @Test
    fun rejectsOversizedEnvelope() {
        assertFailsWith<IllegalArgumentException> {
            codec.decode(ByteArray(256 * 1024 + 1))
        }
    }

    @Test
    fun rejectsOversizedOutgoingPayload() {
        val oversized = TrackInfo("track", "x".repeat(2 * 1024 * 1024), "artist", duration = 1L)

        assertFailsWith<IllegalArgumentException> {
            codec.encode(
                MessageTypes.SYNC_PLAYBACK,
                PlaybackActionPayload(action = "change_track", trackInfo = oversized),
            )
        }
    }

    @Test
    fun rejectsOversizedDirectPayloadDecode() {
        assertFailsWith<IllegalArgumentException> {
            codec.decodePayload(MessageTypes.SYNC_PLAYBACK, ByteArray(2 * 1024 * 1024 + 1))
        }
    }

    @Test
    fun rejectsCompressedPayloadBomb() {
        val compressed =
            ByteArrayOutputStream().use { output ->
                GZIPOutputStream(output).use { gzip ->
                    gzip.write(ByteArray(2 * 1024 * 1024 + 1))
                }
                output.toByteArray()
            }
        val envelope =
            Listentogether.Envelope
                .newBuilder()
                .setType(MessageTypes.SYNC_PLAYBACK)
                .setPayload(
                    ByteString.copyFrom(compressed),
                ).setCompressed(true)
                .build()
                .toByteArray()

        assertFailsWith<IllegalArgumentException> {
            codec.decode(envelope)
        }
        assertFailsWith<IllegalArgumentException> {
            portable.decode(envelope)
        }
    }

    private fun roundTrip(
        messageType: String,
        payload: Any?,
    ): Any? {
        val encoded = codec.encode(messageType, payload)
        assertContentEquals(encoded, portable.encode(messageType, payload))
        val (decodedType, payloadBytes) = codec.decode(encoded)
        val decoded = codec.decodePayload(decodedType, payloadBytes)
        assertEquals(decoded, portable.decodePayload(decodedType, payloadBytes))
        return decoded
    }
}
