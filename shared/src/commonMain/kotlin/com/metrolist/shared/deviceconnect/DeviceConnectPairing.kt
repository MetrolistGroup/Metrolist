/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.deviceconnect

import com.metrolist.shared.settings.Settings
import kotlinx.serialization.Serializable

internal const val DEVICE_CONNECT_PROTOCOL_VERSION = 2

internal enum class DeviceConnectRole(
    val wireName: String,
) {
    INITIATOR("initiator"),
    RESPONDER("responder"),
}

internal enum class DeviceConnectPairingPath {
    RECONNECT,
    NEW_PAIR,
}

internal data class DeviceConnectHelloMaterial(
    val role: DeviceConnectRole,
    val deviceId: String,
    val nonce: ByteArray,
    val publicKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as DeviceConnectHelloMaterial

        if (role != other.role) return false
        if (deviceId != other.deviceId) return false
        if (!nonce.contentEquals(other.nonce)) return false
        if (!publicKey.contentEquals(other.publicKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = role.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}

internal fun isSupportedDeviceConnectVersion(version: Int): Boolean = version == DEVICE_CONNECT_PROTOCOL_VERSION

internal fun isValidRemoteHelloCommit(
    commit: HelloCommitPayload,
    expectedRole: DeviceConnectRole,
    localDeviceId: String,
    expectedDiscoveredDeviceId: String? = null,
): Boolean =
    runCatching {
        require(isSupportedDeviceConnectVersion(commit.version)) { "unsupported hello commitment version" }
        require(commit.role == expectedRole.wireName) { "unexpected hello commitment role" }
        require(isValidDeviceId(commit.deviceId) && commit.deviceId != localDeviceId) {
            "invalid hello commitment device id"
        }
        if (expectedDiscoveredDeviceId != null) {
            require(commit.deviceId == expectedDiscoveredDeviceId) { "unexpected peer commitment" }
        }
        val decoded = commit.commitment.fromBase64()
        require(decoded.size == DEVICE_CONNECT_HELLO_COMMITMENT_BYTES) { "invalid hello commitment" }
        require(decoded.toBase64() == commit.commitment) { "non-canonical hello commitment" }
    }.isSuccess

internal fun isValidRemoteHello(
    hello: HelloPayload,
    expectedRole: DeviceConnectRole,
    localDeviceId: String,
    expectedDiscoveredDeviceId: String? = null,
): Boolean =
    runCatching {
        require(isSupportedDeviceConnectVersion(hello.version)) { "unsupported hello version" }
        require(hello.role == expectedRole.wireName) { "unexpected hello role" }
        require(isValidDeviceId(hello.deviceId) && hello.deviceId != localDeviceId) { "invalid hello device id" }
        if (expectedDiscoveredDeviceId != null) require(hello.deviceId == expectedDiscoveredDeviceId) { "unexpected peer" }
        require(hello.nonce.fromBase64().size == DEVICE_CONNECT_HELLO_NONCE_BYTES) { "invalid hello nonce" }
        require(hello.publicKey.fromBase64().size in 1..MAX_HELLO_PUBLIC_KEY_BYTES) { "invalid hello key" }
    }.isSuccess

internal fun deviceConnectPairingPath(
    localHasPair: Boolean,
    peerHasPair: Boolean,
): DeviceConnectPairingPath = if (localHasPair && peerHasPair) DeviceConnectPairingPath.RECONNECT else DeviceConnectPairingPath.NEW_PAIR

internal fun isNewDeviceConnectSequence(
    sequence: Long,
    previousSequence: Long,
): Boolean = sequence > 0 && sequence > previousSequence

internal fun isDeviceConnectPairingMessage(type: String): Boolean =
    type == DcMessageTypes.PAIR_PROOF ||
        type == DcMessageTypes.PAIR_REQUEST ||
        type == DcMessageTypes.PAIR_APPROVE ||
        type == DcMessageTypes.PAIR_COMMIT

internal fun isDeviceConnectMessageAllowedBeforeIdentity(
    type: String,
    peerIdentityVerified: Boolean,
): Boolean = !isDeviceConnectPairingMessage(type) || peerIdentityVerified

internal fun isCurrentDeviceConnectIdentity(
    expected: AccountIdentitySnapshot,
    current: AccountIdentitySnapshot,
): Boolean = expected == current

internal data class DevicePair(
    val peerDeviceId: String,
    val peerDeviceName: String,
    val peerModel: String,
    val accountFingerprint: String,
    val anonymous: Boolean,
    val secret: ByteArray,
    val peerDeviceType: DeviceConnectDeviceType = DeviceConnectDeviceType.UNKNOWN,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as DevicePair

        if (anonymous != other.anonymous) return false
        if (peerDeviceId != other.peerDeviceId) return false
        if (peerDeviceName != other.peerDeviceName) return false
        if (peerModel != other.peerModel) return false
        if (accountFingerprint != other.accountFingerprint) return false
        if (!secret.contentEquals(other.secret)) return false
        if (peerDeviceType != other.peerDeviceType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = anonymous.hashCode()
        result = 31 * result + peerDeviceId.hashCode()
        result = 31 * result + peerDeviceName.hashCode()
        result = 31 * result + peerModel.hashCode()
        result = 31 * result + accountFingerprint.hashCode()
        result = 31 * result + secret.contentHashCode()
        result = 31 * result + peerDeviceType.hashCode()
        return result
    }
}

@Serializable
private data class StoredDevicePairs(
    val version: Int,
    val pairs: List<StoredDevicePair> = emptyList(),
)

@Serializable
private data class StoredDevicePair(
    val peerDeviceId: String,
    val peerDeviceName: String,
    val peerModel: String,
    val accountFingerprint: String,
    val anonymous: Boolean,
    val secret: String,
    val peerDeviceType: String = DeviceConnectDeviceType.UNKNOWN.wireName,
    val usageCount: Long = 0,
)

internal class DevicePairStore(
    private val settings: Settings = Settings.shared,
) {
    fun find(
        peerDeviceId: String,
        identity: AccountIdentitySnapshot,
    ): DevicePair? {
        if (!identity.resolved) return null
        return readForAuthorization()
            .firstOrNull {
                it.peerDeviceId == peerDeviceId &&
                    it.accountFingerprint == identity.fingerprint &&
                    it.anonymous == identity.anonymous
            }?.toDevicePair()
    }

    fun save(
        peerDeviceId: String,
        peerDeviceName: String,
        peerModel: String,
        identity: AccountIdentitySnapshot,
        secret: ByteArray,
        peerDeviceType: DeviceConnectDeviceType = DeviceConnectDeviceType.UNKNOWN,
    ) {
        require(isValidDeviceId(peerDeviceId)) { "invalid peer device id" }
        require(identity.resolved) { "unresolved account identity" }
        require(secret.size == DEVICE_CONNECT_PAIR_SECRET_BYTES) { "invalid pair secret" }
        val pairs = readForWrite()
        val existing =
            pairs.firstOrNull {
                it.peerDeviceId == peerDeviceId &&
                    it.accountFingerprint == identity.fingerprint &&
                    it.anonymous == identity.anonymous
            }
        val saved =
            StoredDevicePair(
                peerDeviceId = peerDeviceId,
                peerDeviceName = sanitizeDeviceName(peerDeviceName).ifBlank { peerDeviceId },
                peerModel = sanitizeDeviceName(peerModel),
                accountFingerprint = identity.fingerprint,
                anonymous = identity.anonymous,
                secret = secret.toBase64(),
                usageCount = existing?.usageCount ?: 0,
                peerDeviceType =
                    if (peerDeviceType == DeviceConnectDeviceType.UNKNOWN) {
                        existing?.peerDeviceType ?: DeviceConnectDeviceType.UNKNOWN.wireName
                    } else {
                        peerDeviceType.wireName
                    },
            )
        write(
            pairs.filterNot {
                it.peerDeviceId == peerDeviceId &&
                    it.accountFingerprint == identity.fingerprint &&
                    it.anonymous == identity.anonymous
            } + saved,
        )
    }

    fun forget(
        peerDeviceId: String,
        identity: AccountIdentitySnapshot,
    ) {
        if (!identity.resolved) return
        write(
            readForWrite().filterNot {
                it.peerDeviceId == peerDeviceId &&
                    it.accountFingerprint == identity.fingerprint &&
                    it.anonymous == identity.anonymous
            },
        )
    }

    fun forgetIfSecret(
        peerDeviceId: String,
        identity: AccountIdentitySnapshot,
        secret: ByteArray,
    ): Boolean {
        if (!identity.resolved || secret.size != DEVICE_CONNECT_PAIR_SECRET_BYTES) return false
        var removed = false
        val kept =
            readForWrite().filterNot { stored ->
                val matches =
                    stored.peerDeviceId == peerDeviceId &&
                        stored.accountFingerprint == identity.fingerprint &&
                        stored.anonymous == identity.anonymous &&
                        stored.toDevicePair()?.secret?.contentEquals(secret) == true
                removed = removed || matches
                matches
            }
        if (removed) write(kept)
        return removed
    }

    fun updateMetadata(
        peerDeviceId: String,
        peerDeviceName: String,
        peerModel: String,
        identity: AccountIdentitySnapshot,
        peerDeviceType: DeviceConnectDeviceType = DeviceConnectDeviceType.UNKNOWN,
    ): Boolean {
        if (!identity.resolved) return false
        val safeName = sanitizeDeviceName(peerDeviceName).ifBlank { peerDeviceId }
        val safeModel = sanitizeDeviceName(peerModel)
        var changed = false
        val updated =
            readForWrite().map { stored ->
                if (stored.peerDeviceId == peerDeviceId &&
                    stored.accountFingerprint == identity.fingerprint &&
                    stored.anonymous == identity.anonymous
                ) {
                    val safeType =
                        if (peerDeviceType == DeviceConnectDeviceType.UNKNOWN) {
                            stored.peerDeviceType
                        } else {
                            peerDeviceType.wireName
                        }
                    changed =
                        changed ||
                        stored.peerDeviceName != safeName ||
                        stored.peerModel != safeModel ||
                        stored.peerDeviceType != safeType
                    stored.copy(peerDeviceName = safeName, peerModel = safeModel, peerDeviceType = safeType)
                } else {
                    stored
                }
            }
        if (changed) write(updated)
        return changed
    }

    fun recordUsage(
        peerDeviceIds: Set<String>,
        identity: AccountIdentitySnapshot,
    ) {
        if (!identity.resolved || peerDeviceIds.isEmpty()) return
        var changed = false
        val pairs =
            readForWrite().map { stored ->
                if (stored.peerDeviceId in peerDeviceIds &&
                    stored.accountFingerprint == identity.fingerprint &&
                    stored.anonymous == identity.anonymous && stored.usageCount < Long.MAX_VALUE
                ) {
                    changed = true
                    stored.copy(usageCount = stored.usageCount + 1)
                } else {
                    stored
                }
            }
        if (changed) write(pairs)
    }

    fun pairsFor(identity: AccountIdentitySnapshot): List<DevicePair> {
        if (!identity.resolved) return emptyList()
        return readForAuthorization()
            .asSequence()
            .filter { it.accountFingerprint == identity.fingerprint && it.anonymous == identity.anonymous }
            .sortedByDescending { it.usageCount }
            .mapNotNull { it.toDevicePair() }
            .toList()
    }

    private fun readForAuthorization(): List<StoredDevicePair> = readStoredPairs()?.pairs.orEmpty()

    private fun readForWrite(): List<StoredDevicePair> =
        checkNotNull(readStoredPairs()?.takeIf { stored -> stored.pairs.all { it.toDevicePair() != null } }) {
            "pair grant store is invalid"
        }.pairs

    private fun readStoredPairs(): StoredDevicePairs? {
        val raw = settings.deviceConnectPairedDevices
        if (raw.isBlank()) return StoredDevicePairs(version = DEVICE_PAIR_STORE_VERSION)
        return runCatching {
            DcJson
                .decodeFromString<StoredDevicePairs>(raw)
                .takeIf { it.version == DEVICE_PAIR_STORE_VERSION }
        }.getOrNull()
    }

    private fun write(pairs: List<StoredDevicePair>) {
        val serialized = DcJson.encodeToString(StoredDevicePairs(version = DEVICE_PAIR_STORE_VERSION, pairs = pairs))
        settings.deviceConnectPairedDevices = serialized
        check(settings.deviceConnectPairedDevices == serialized) { "pair grant persistence verification failed" }
    }
}

private fun StoredDevicePair.toDevicePair(): DevicePair? =
    runCatching {
        require(isValidDeviceId(peerDeviceId)) { "invalid peer device id" }
        val decodedSecret = secret.fromBase64()
        require(decodedSecret.size == DEVICE_CONNECT_PAIR_SECRET_BYTES) { "invalid pair secret" }
        DevicePair(
            peerDeviceId = peerDeviceId,
            peerDeviceName = sanitizeDeviceName(peerDeviceName).ifBlank { peerDeviceId },
            peerModel = sanitizeDeviceName(peerModel),
            accountFingerprint = accountFingerprint,
            anonymous = anonymous,
            secret = decodedSecret,
            peerDeviceType = DeviceConnectDeviceType.fromWireName(peerDeviceType),
        )
    }.getOrNull()

internal fun deviceConnectTranscriptHash(
    local: DeviceConnectHelloMaterial,
    peer: DeviceConnectHelloMaterial,
): ByteArray {
    require(local.role != peer.role) { "duplicate hello role" }
    val initiator = if (local.role == DeviceConnectRole.INITIATOR) local else peer
    val responder = if (local.role == DeviceConnectRole.RESPONDER) local else peer
    validateHelloMaterial(initiator)
    validateHelloMaterial(responder)
    val initiatorCommitment = deviceConnectHelloCommitment(initiator)
    val responderCommitment = deviceConnectHelloCommitment(responder)
    return DeviceCrypto.sha256(
        lengthPrefixed(
            DEVICE_CONNECT_TRANSCRIPT_LABEL.encodeToByteArray(),
            DEVICE_CONNECT_PROTOCOL_VERSION.toByteArray(),
            initiatorCommitment,
            responderCommitment,
            initiator.role.wireName.encodeToByteArray(),
            initiator.deviceId.encodeToByteArray(),
            initiator.nonce,
            initiator.publicKey,
            responder.role.wireName.encodeToByteArray(),
            responder.deviceId.encodeToByteArray(),
            responder.nonce,
            responder.publicKey,
        ),
    )
}

internal fun deviceConnectCodePairingTranscript(
    helloTranscript: ByteArray,
    localRole: DeviceConnectRole,
    local: IdentityPayload,
    peer: IdentityPayload,
): ByteArray {
    val initiator = if (localRole == DeviceConnectRole.INITIATOR) local else peer
    val responder = if (localRole == DeviceConnectRole.RESPONDER) local else peer

    fun metadata(identity: IdentityPayload): ByteArray =
        lengthPrefixed(
            identity.fingerprint.encodeToByteArray(),
            byteArrayOf(
                if (identity.anonymous) 1 else 0,
                if (identity.resolved) 1 else 0,
                if (identity.hasPair) 1 else 0,
                if (identity.supportsCodePairing) 1 else 0,
            ),
        )
    return DeviceCrypto.sha256(
        lengthPrefixed(
            "$DEVICE_CONNECT_TRANSCRIPT_LABEL/mutual-code-pairing".encodeToByteArray(),
            helloTranscript,
            metadata(initiator),
            metadata(responder),
        ),
    )
}

internal fun deviceConnectHelloCommitment(material: DeviceConnectHelloMaterial): ByteArray {
    validateHelloMaterial(material)
    return DeviceCrypto.sha256(
        lengthPrefixed(
            DEVICE_CONNECT_HELLO_COMMITMENT_LABEL.encodeToByteArray(),
            DEVICE_CONNECT_PROTOCOL_VERSION.toByteArray(),
            material.role.wireName.encodeToByteArray(),
            material.deviceId.encodeToByteArray(),
            material.nonce,
            material.publicKey,
        ),
    )
}

internal fun deviceConnectHelloCommitmentMatches(
    material: DeviceConnectHelloMaterial,
    encodedCommitment: String,
): Boolean =
    runCatching {
        val expected = deviceConnectHelloCommitment(material)
        val actual = encodedCommitment.fromBase64()
        actual.size == DEVICE_CONNECT_HELLO_COMMITMENT_BYTES &&
            actual.toBase64() == encodedCommitment &&
            constantTimeEquals(expected, actual)
    }.getOrDefault(false)

internal fun deviceConnectTrafficKey(
    sharedSecret: ByteArray,
    transcriptHash: ByteArray,
    senderRole: DeviceConnectRole,
): ByteArray =
    DeviceCrypto.hkdfSha256(
        ikm = sharedSecret,
        salt = transcriptHash,
        info = "$DEVICE_CONNECT_TRANSCRIPT_LABEL/${senderRole.wireName}".encodeToByteArray(),
    )

internal fun deviceConnectPairSecret(
    sharedSecret: ByteArray,
    transcriptHash: ByteArray,
): ByteArray =
    DeviceCrypto.hkdfSha256(
        ikm = sharedSecret,
        salt = transcriptHash,
        info = "$DEVICE_CONNECT_TRANSCRIPT_LABEL/pair-secret".encodeToByteArray(),
    )

internal fun deviceConnectSas(pairSecret: ByteArray): String {
    val bytes = DeviceCrypto.hmacSha256(pairSecret, "$DEVICE_CONNECT_TRANSCRIPT_LABEL/sas".encodeToByteArray())
    val value =
        ((bytes[0].toLong() and 0xff) shl 24) or
            ((bytes[1].toLong() and 0xff) shl 16) or
            ((bytes[2].toLong() and 0xff) shl 8) or
            (bytes[3].toLong() and 0xff)
    return (value % SAS_MODULUS).toString().padStart(SAS_DIGITS, '0')
}

internal fun deviceConnectProof(
    secret: ByteArray,
    phase: String,
    senderRole: DeviceConnectRole,
    senderDeviceId: String,
    receiverDeviceId: String,
    transcriptHash: ByteArray,
): String =
    DeviceCrypto
        .hmacSha256(
            secret,
            lengthPrefixed(
                DEVICE_CONNECT_TRANSCRIPT_LABEL.encodeToByteArray(),
                phase.encodeToByteArray(),
                senderRole.wireName.encodeToByteArray(),
                senderDeviceId.encodeToByteArray(),
                receiverDeviceId.encodeToByteArray(),
                transcriptHash,
            ),
        ).toBase64()

internal fun deviceConnectProofMatches(
    secret: ByteArray,
    proof: String,
    phase: String,
    senderRole: DeviceConnectRole,
    senderDeviceId: String,
    receiverDeviceId: String,
    transcriptHash: ByteArray,
): Boolean =
    runCatching {
        val expected =
            deviceConnectProof(
                secret = secret,
                phase = phase,
                senderRole = senderRole,
                senderDeviceId = senderDeviceId,
                receiverDeviceId = receiverDeviceId,
                transcriptHash = transcriptHash,
            ).fromBase64()
        val actual = proof.fromBase64()
        expected.size == actual.size && constantTimeEquals(expected, actual)
    }.getOrDefault(false)

private fun constantTimeEquals(
    first: ByteArray,
    second: ByteArray,
): Boolean {
    var difference = 0
    for (index in first.indices) difference = difference or (first[index].toInt() xor second[index].toInt())
    return difference == 0
}

private fun validateHelloMaterial(material: DeviceConnectHelloMaterial) {
    require(isValidDeviceId(material.deviceId)) { "invalid hello device id" }
    require(material.nonce.size == DEVICE_CONNECT_HELLO_NONCE_BYTES) { "invalid hello nonce" }
    require(material.publicKey.size in 1..MAX_HELLO_PUBLIC_KEY_BYTES) { "invalid hello public key" }
}

private fun lengthPrefixed(vararg parts: ByteArray): ByteArray {
    val size = parts.sumOf { Int.SIZE_BYTES + it.size }
    val output = ByteArray(size)
    var offset = 0
    for (part in parts) {
        val length = part.size.toByteArray()
        length.copyInto(output, offset)
        offset += length.size
        part.copyInto(output, offset)
        offset += part.size
    }
    return output
}

private fun Int.toByteArray(): ByteArray =
    byteArrayOf(
        (this ushr 24).toByte(),
        (this ushr 16).toByte(),
        (this ushr 8).toByte(),
        toByte(),
    )

private const val DEVICE_CONNECT_TRANSCRIPT_LABEL = "metrolist-device-connect-v2"
private const val DEVICE_CONNECT_HELLO_COMMITMENT_LABEL = "$DEVICE_CONNECT_TRANSCRIPT_LABEL/hello-commit"
internal const val DEVICE_CONNECT_HELLO_COMMITMENT_BYTES = 32
internal const val DEVICE_CONNECT_HELLO_NONCE_BYTES = 16
internal const val MAX_HELLO_PUBLIC_KEY_BYTES = 512
internal const val DEVICE_CONNECT_PAIR_SECRET_BYTES = 32
internal const val DEVICE_CONNECT_PAIRING_TIMEOUT_MILLIS = 5 * 60 * 1_000L
private const val DEVICE_PAIR_STORE_VERSION = 1
private const val SAS_DIGITS = 6
private const val SAS_MODULUS = 1_000_000L
