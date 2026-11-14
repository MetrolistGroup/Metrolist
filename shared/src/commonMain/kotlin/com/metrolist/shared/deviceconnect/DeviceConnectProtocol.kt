/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * DeviceConnect wire protocol.
 *
 * Transport: WebSocket text frames carrying a JSON [DcWire] envelope. The payload
 * is either:
 *  - cleartext JSON for [DcMessageTypes.HELLO_COMMIT] followed by
 *    [DcMessageTypes.HELLO], which reveals the committed ephemeral ECDH material, or
 *  - base64( IV(12) || AES-GCM ciphertext ) of a JSON payload (every other message).
 *
 * This is an independent protocol from ListenTogether (whose proto is read-only);
 * we reuse the *patterns* (envelope + kotlinx-serialization) only.
 */

package com.metrolist.shared.deviceconnect

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object DcMessageTypes {
    const val HELLO_COMMIT = "hello_commit"
    const val HELLO = "hello"
    const val IDENTITY = "identity"
    const val PAIR_PROOF = "pair_proof"
    const val PAIR_REQUEST = "pair_request"
    const val PAIR_APPROVE = "pair_approve"
    const val PAIR_COMMIT = "pair_commit"
    const val AUTH_RESULT = "auth_result"
    const val DEVICE_INFO = "device_info"
    const val PLAYBACK = "playback"
    const val PING = "ping"
    const val PONG = "pong"
    const val BYE = "bye"

    val ENCRYPTED_TYPES =
        setOf(
            IDENTITY,
            PAIR_PROOF,
            PAIR_REQUEST,
            PAIR_APPROVE,
            PAIR_COMMIT,
            AUTH_RESULT,
            DEVICE_INFO,
            PLAYBACK,
            PING,
            PONG,
            BYE,
        )
}

/** Encrypted sequence numbers increase per sender; zero is reserved for cleartext handshakes. */
@Serializable
data class DcWire(
    val type: String,
    val payload: String,
    val version: Int = 0,
    val encrypted: Boolean = false,
    val seq: Long = 0,
)

@Serializable
data class HelloCommitPayload(
    val version: Int,
    val role: String,
    val deviceId: String,
    val commitment: String,
)

@Serializable
data class HelloPayload(
    val version: Int,
    val role: String,
    val deviceId: String,
    val nonce: String,
    val publicKey: String,
)

@Serializable
data class IdentityPayload(
    val fingerprint: String,
    val accountProof: String = "",
    val anonymous: Boolean,
    val resolved: Boolean,
    val hasPair: Boolean,
    val supportsCodePairing: Boolean = false,
)

@Serializable
data class PairProofPayload(
    val deviceId: String,
    val proof: String,
)

@Serializable
data class PairRequestPayload(
    val deviceId: String,
    val proof: String,
)

@Serializable
data class PairApprovalPayload(
    val deviceId: String,
    val proof: String,
)

@Serializable
data class PairCommitPayload(
    val deviceId: String,
    val proof: String,
)

@Serializable
data class AuthResultPayload(
    val accepted: Boolean,
    val reason: String = "",
)

@Serializable
data class DeviceInfoPayload(
    val deviceId: String,
    val deviceName: String,
    val model: String,
    val playbackVersion: Int = 0,
    val supportsPlaybackClock: Boolean = false,
    val supportsPlaybackPitch: Boolean = false,
    val deviceType: String = DeviceConnectDeviceType.UNKNOWN.wireName,
)

@Serializable
internal data class PlaybackChunkPayload(
    val messageId: String,
    val index: Int,
    val count: Int,
    val totalBytes: Int,
    val data: String,
)

@Serializable
data class PingPayload(
    val t: Long,
)

@Serializable
data class PongPayload(
    val t: Long,
)

@Serializable
data class ByePayload(
    val reason: String = "",
)

val DcJson =
    Json {
        ignoreUnknownKeys = true
    }

fun decodePayload(
    type: String,
    json: String,
): Any? =
    when (type) {
        DcMessageTypes.HELLO_COMMIT -> DcJson.decodeFromString<HelloCommitPayload>(json)
        DcMessageTypes.HELLO -> DcJson.decodeFromString<HelloPayload>(json)
        DcMessageTypes.IDENTITY -> DcJson.decodeFromString<IdentityPayload>(json)
        DcMessageTypes.PAIR_PROOF -> DcJson.decodeFromString<PairProofPayload>(json)
        DcMessageTypes.PAIR_REQUEST -> DcJson.decodeFromString<PairRequestPayload>(json)
        DcMessageTypes.PAIR_APPROVE -> DcJson.decodeFromString<PairApprovalPayload>(json)
        DcMessageTypes.PAIR_COMMIT -> DcJson.decodeFromString<PairCommitPayload>(json)
        DcMessageTypes.AUTH_RESULT -> DcJson.decodeFromString<AuthResultPayload>(json)
        DcMessageTypes.DEVICE_INFO -> DcJson.decodeFromString<DeviceInfoPayload>(json)
        DcMessageTypes.PLAYBACK -> DcJson.decodeFromString<PlaybackChunkPayload>(json)
        DcMessageTypes.PING -> DcJson.decodeFromString<PingPayload>(json)
        DcMessageTypes.PONG -> DcJson.decodeFromString<PongPayload>(json)
        DcMessageTypes.BYE -> DcJson.decodeFromString<ByePayload>(json)
        else -> null
    }
