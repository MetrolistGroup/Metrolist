package com.metrolist.shared.deviceconnect

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
actual object DeviceCrypto {
    actual fun generateEcKeyPair(): EcKeyPair = deviceConnectUnavailable()

    actual fun deriveSharedSecret(
        privateKeyPkcs8: ByteArray,
        peerPublicUncompressed: ByteArray,
    ): ByteArray = deviceConnectUnavailable()

    actual fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray?,
        info: ByteArray,
    ): ByteArray {
        val prk = hmacSha256(salt ?: ByteArray(CC_SHA256_DIGEST_LENGTH), ikm)
        return hmacSha256(prk, info + byteArrayOf(1))
    }

    actual fun aesGcmEncrypt(
        key: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray = deviceConnectUnavailable()

    actual fun aesGcmDecrypt(
        key: ByteArray,
        data: ByteArray,
        associatedData: ByteArray,
    ): ByteArray = deviceConnectUnavailable()

    actual fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val result = ByteArray(CC_SHA256_DIGEST_LENGTH)
        key.usePinned { pinnedKey ->
            data.usePinned { pinnedData ->
                result.usePinned { pinnedResult ->
                    CCHmac(
                        algorithm = kCCHmacAlgSHA256,
                        key = if (key.isEmpty()) null else pinnedKey.addressOf(0),
                        keyLength = key.size.toULong(),
                        data = if (data.isEmpty()) null else pinnedData.addressOf(0),
                        dataLength = data.size.toULong(),
                        macOut = pinnedResult.addressOf(0),
                    )
                }
            }
        }
        return result
    }

    actual fun sha256(data: ByteArray): ByteArray {
        val result = ByteArray(CC_SHA256_DIGEST_LENGTH)
        data.usePinned { pinnedData ->
            result.usePinned { pinnedResult ->
                check(
                    CC_SHA256(
                        data = if (data.isEmpty()) null else pinnedData.addressOf(0),
                        len = data.size.toUInt(),
                        md = pinnedResult.addressOf(0).reinterpret(),
                    ) != null,
                )
            }
        }
        return result
    }

    actual fun secureRandomBytes(n: Int): ByteArray {
        require(n >= 0)
        val bytes = ByteArray(n)
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                check(SecRandomCopyBytes(kSecRandomDefault, n.toULong(), pinned.addressOf(0)) == errSecSuccess)
            }
        }
        return bytes
    }
}

// shortcut: Device Connect is hidden on iOS; implement native ECDH and AES-GCM before enabling it.
private fun deviceConnectUnavailable(): Nothing = throw UnsupportedOperationException("Device Connect cryptography is unavailable on iOS")
