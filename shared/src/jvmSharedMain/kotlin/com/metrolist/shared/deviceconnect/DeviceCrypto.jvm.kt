/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.deviceconnect

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

actual object DeviceCrypto {
    private const val EC_CURVE = "secp256r1"
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_LEN = 128

    // AES-GCM IVs and keys require a cryptographic random source.
    private val secureRandom = SecureRandom()

    actual fun generateEcKeyPair(): EcKeyPair {
        val kg = KeyPairGenerator.getInstance("EC")
        kg.initialize(ECGenParameterSpec(EC_CURVE))
        val kp = kg.generateKeyPair()
        return EcKeyPair(
            publicKeyUncompressed = kp.public.encoded,
            privateKeyPkcs8 = kp.private.encoded,
        )
    }

    actual fun deriveSharedSecret(
        privateKeyPkcs8: ByteArray,
        peerPublicUncompressed: ByteArray,
    ): ByteArray {
        val kf = KeyFactory.getInstance("EC")
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8))
        val pub = kf.generatePublic(X509EncodedKeySpec(peerPublicUncompressed))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        return ka.generateSecret()
    }

    actual fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray?,
        info: ByteArray,
    ): ByteArray {
        val prk = hmacSha256(salt ?: ByteArray(32), ikm)
        return hmacSha256(prk, info + byteArrayOf(1))
    }

    actual fun aesGcmEncrypt(
        key: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val iv = ByteArray(GCM_IV_LEN).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LEN, iv))
        cipher.updateAAD(associatedData)
        val ct = cipher.doFinal(plaintext)
        return iv + ct
    }

    actual fun aesGcmDecrypt(
        key: ByteArray,
        data: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        require(data.size > GCM_IV_LEN) { "ciphertext too short" }
        val iv = data.copyOfRange(0, GCM_IV_LEN)
        val ct = data.copyOfRange(GCM_IV_LEN, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LEN, iv))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ct)
    }

    actual fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    actual fun secureRandomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }
}
