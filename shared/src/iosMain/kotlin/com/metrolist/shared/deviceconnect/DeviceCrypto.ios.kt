package com.metrolist.shared.deviceconnect

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFErrorGetCode
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.Foundation.CFBridgingRetain
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyKeyExchangeResult
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyRef
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDHKeyExchangeStandard
import platform.Security.kSecRandomDefault
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
actual object DeviceCrypto {
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_LEN = 16
    private const val P256_PUBLIC_LEN = 65
    private const val P256_PRIVATE_LEN = 32
    private const val P256_APPLE_PRIVATE_LEN = 97

    private val spkiPrefix = byteArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01,
        0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07,
        0x03, 0x42, 0x00,
    )
    private val pkcs8Prefix = byteArrayOf(
        0x30, 0x81.toByte(), 0x87.toByte(), 0x02, 0x01, 0x00, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(),
        0x48, 0xce.toByte(), 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d,
        0x03, 0x01, 0x07, 0x04, 0x6d, 0x30, 0x6b, 0x02, 0x01, 0x01, 0x04, 0x20,
    )
    private val pkcs8PublicPrefix = byteArrayOf(0xa1.toByte(), 0x44, 0x03, 0x42, 0x00)

    actual fun generateEcKeyPair(): EcKeyPair = memScoped {
        val attrs = mutableCfDictionary().apply {
            set(kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
            set(kSecAttrKeySizeInBits, cfNumber(256))
        }
        val error = alloc<CFErrorRefVar>()
        val privateKey = SecKeyCreateRandomKey(attrs, error.ptr)
            ?: error("Failed to generate EC key: ${error.value.cfErrorMessage()}")
        try {
            val publicKey = SecKeyCopyPublicKey(privateKey) ?: error("Failed to derive public key")
            try {
                val applePrivate = privateKey.externalRepresentation()
                require(applePrivate.size == P256_APPLE_PRIVATE_LEN) { "Unexpected EC private key size" }
                val public = publicKey.externalRepresentation()
                require(public.size == P256_PUBLIC_LEN) { "Unexpected EC public key size" }
                val privateScalar = applePrivate.copyOfRange(P256_PUBLIC_LEN, P256_APPLE_PRIVATE_LEN)
                EcKeyPair(
                    publicKeyUncompressed = spkiPrefix + public,
                    privateKeyPkcs8 = pkcs8Prefix + privateScalar + pkcs8PublicPrefix + public,
                )
            } finally {
                CFRelease(publicKey)
            }
        } finally {
            CFRelease(privateKey)
        }
    }

    actual fun deriveSharedSecret(
        privateKeyPkcs8: ByteArray,
        peerPublicUncompressed: ByteArray,
    ): ByteArray = memScoped {
        val privateKey = importPrivateKey(privateKeyPkcs8)
        try {
            val publicKey = importPublicKey(peerPublicUncompressed)
            try {
                val error = alloc<CFErrorRefVar>()
                val shared = SecKeyCopyKeyExchangeResult(
                    privateKey = privateKey,
                    algorithm = kSecKeyAlgorithmECDHKeyExchangeStandard,
                    publicKey = publicKey,
                    parameters = null,
                    error = error.ptr,
                ) ?: error("ECDH failed: ${error.value.cfErrorMessage()}")
                try {
                    shared.toByteArray()
                } finally {
                    CFRelease(shared)
                }
            } finally {
                CFRelease(publicKey)
            }
        } finally {
            CFRelease(privateKey)
        }
    }

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
    ): ByteArray = IosAesGcm.encrypt(key, plaintext, associatedData)

    actual fun aesGcmDecrypt(
        key: ByteArray,
        data: ByteArray,
        associatedData: ByteArray,
    ): ByteArray = IosAesGcm.decrypt(key, data, associatedData)

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
                CC_SHA256(
                    data = if (data.isEmpty()) null else pinnedData.addressOf(0),
                    len = data.size.toUInt(),
                    md = pinnedResult.addressOf(0).reinterpret(),
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

    private fun importPublicKey(x509: ByteArray): SecKeyRef = memScoped {
        val point = decodeSpkiPublicPoint(x509)
        createEcKey(point, kSecAttrKeyClassPublic)
    }

    private fun importPrivateKey(pkcs8: ByteArray): SecKeyRef = memScoped {
        val applePrivate = decodePkcs8Private(pkcs8)
        createEcKey(applePrivate, kSecAttrKeyClassPrivate)
    }

    private fun createEcKey(bytes: ByteArray, keyClass: CFStringRef?): SecKeyRef = memScoped {
        val attrs = mutableCfDictionary().apply {
            set(kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
            set(kSecAttrKeyClass, keyClass)
            set(kSecAttrKeySizeInBits, cfNumber(256))
        }
        val data = bytes.toCFData()
        try {
            val error = alloc<CFErrorRefVar>()
            SecKeyCreateWithData(data, attrs, error.ptr)
                ?: error("Failed to import EC key: ${error.value.cfErrorMessage()}")
        } finally {
            CFRelease(data)
        }
    }

    private fun decodeSpkiPublicPoint(x509: ByteArray): ByteArray {
        require(x509.size == spkiPrefix.size + P256_PUBLIC_LEN && x509.startsWith(spkiPrefix)) {
            "Unsupported EC public key encoding"
        }
        return x509.copyOfRange(spkiPrefix.size, x509.size)
    }

    private fun decodePkcs8Private(pkcs8: ByteArray): ByteArray {
        val minLen = pkcs8Prefix.size + P256_PRIVATE_LEN + pkcs8PublicPrefix.size + P256_PUBLIC_LEN
        require(pkcs8.size >= minLen && pkcs8.startsWith(pkcs8Prefix)) {
            "Unsupported EC private key encoding"
        }
        val scalar = pkcs8.copyOfRange(pkcs8Prefix.size, pkcs8Prefix.size + P256_PRIVATE_LEN)
        val publicIndex = pkcs8.indexOfSub(pkcs8PublicPrefix, pkcs8Prefix.size + P256_PRIVATE_LEN)
        require(publicIndex >= 0) { "Unsupported EC private key encoding" }
        val publicStart = publicIndex + pkcs8PublicPrefix.size
        require(publicStart + P256_PUBLIC_LEN <= pkcs8.size) { "Unsupported EC private key encoding" }
        val public = pkcs8.copyOfRange(publicStart, publicStart + P256_PUBLIC_LEN)
        require(public.size == P256_PUBLIC_LEN && public[0] == 4.toByte()) { "Unsupported EC private key encoding" }
        return public + scalar
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    private fun ByteArray.indexOfSub(sub: ByteArray, from: Int): Int {
        outer@ for (i in from..size - sub.size) {
            for (j in sub.indices) if (this[i + j] != sub[j]) continue@outer
            return i
        }
        return -1
    }

    private fun SecKeyRef.externalRepresentation(): ByteArray = memScoped {
        val error = alloc<CFErrorRefVar>()
        val data = SecKeyCopyExternalRepresentation(this@externalRepresentation, error.ptr)
            ?: error("Failed to export EC key: ${error.value.cfErrorMessage()}")
        try {
            data.toByteArray()
        } finally {
            CFRelease(data)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toCFData(): platform.CoreFoundation.CFDataRef? =
    usePinned { pinned ->
        CFDataCreate(null, pinned.addressOf(0).reinterpret(), size.toLong())
    }

@OptIn(ExperimentalForeignApi::class)
private fun platform.CoreFoundation.CFDataRef.toByteArray(): ByteArray {
    val len = CFDataGetLength(this).toInt()
    val bytes = ByteArray(len)
    if (len > 0) {
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), CFDataGetBytePtr(this), len.toULong())
        }
    }
    return bytes
}

@OptIn(ExperimentalForeignApi::class)
private fun CFMutableDictionaryRef?.set(key: CFTypeRef?, value: CFTypeRef?) {
    if (this != null && key != null && value != null) CFDictionaryAddValue(this, key, value)
}

@OptIn(ExperimentalForeignApi::class)
private fun cfNumber(value: Int): CFTypeRef? =
    CFBridgingRetain(platform.Foundation.NSNumber(int = value))

@OptIn(ExperimentalForeignApi::class)
private fun mutableCfDictionary(): CFMutableDictionaryRef =
    CFDictionaryCreateMutable(null, 4, null, null) ?: error("Failed to create CFDictionary")

@OptIn(ExperimentalForeignApi::class)
private fun platform.CoreFoundation.CFErrorRef?.cfErrorMessage(): String =
    if (this == null) "unknown error" else "CFError(${platform.CoreFoundation.CFErrorGetCode(this)})"
