package com.metrolist.shared.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_MD5
import platform.CoreCrypto.CC_MD5_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
actual fun md5Hex(input: String): String {
    val bytes = input.encodeToByteArray()
    val digest = ByteArray(CC_MD5_DIGEST_LENGTH)
    bytes.usePinned { pinnedBytes ->
        digest.usePinned { pinnedDigest ->
            check(
                CC_MD5(
                    data = if (bytes.isEmpty()) null else pinnedBytes.addressOf(0),
                    len = bytes.size.toUInt(),
                    md = pinnedDigest.addressOf(0).reinterpret(),
                ) != null,
            )
        }
    }
    return digest.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
}
