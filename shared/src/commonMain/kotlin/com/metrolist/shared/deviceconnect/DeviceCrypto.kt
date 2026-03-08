/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * DeviceConnect cryptography contract.
 *
 * The actual implementations use platform javax.crypto (Android + Desktop JVM),
 * so no external crypto dependency is required. All functions operate on
 * platform-neutral types (ByteArray / String) so the common code stays pure.
 */

package com.metrolist.shared.deviceconnect

/** Public keys use X.509 encoding despite the publicKeyUncompressed property name; private keys use PKCS#8. */
class EcKeyPair(
    val publicKeyUncompressed: ByteArray,
    val privateKeyPkcs8: ByteArray,
)

expect object DeviceCrypto {
    fun generateEcKeyPair(): EcKeyPair

    fun deriveSharedSecret(
        privateKeyPkcs8: ByteArray,
        peerPublicUncompressed: ByteArray,
    ): ByteArray

    fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray?,
        info: ByteArray,
    ): ByteArray

    /** AES/GCM/NoPadding encryption. Output = 12-byte IV || ciphertext (with GCM tag). */
    fun aesGcmEncrypt(
        key: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray

    fun aesGcmDecrypt(
        key: ByteArray,
        data: ByteArray,
        associatedData: ByteArray,
    ): ByteArray

    fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray

    fun sha256(data: ByteArray): ByteArray

    fun secureRandomBytes(n: Int): ByteArray
}
