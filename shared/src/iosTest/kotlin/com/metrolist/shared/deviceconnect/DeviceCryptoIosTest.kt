package com.metrolist.shared.deviceconnect

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceCryptoIosTest {
    @Test
    fun sha256AndHmacMatchKnownVectors() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            DeviceCrypto.sha256("abc".encodeToByteArray()).toHex(),
        )
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            DeviceCrypto
                .hmacSha256(
                    "key".encodeToByteArray(),
                    "The quick brown fox jumps over the lazy dog".encodeToByteArray(),
                ).toHex(),
        )
    }

    @Test
    fun hkdfMatchesRfc5869() {
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf",
            DeviceCrypto
                .hkdfSha256(
                    ikm = ByteArray(22) { 0x0b },
                    salt = ByteArray(13) { it.toByte() },
                    info = ByteArray(10) { (0xf0 + it).toByte() },
                ).toHex(),
        )
    }
}

private fun ByteArray.toHex(): String = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
