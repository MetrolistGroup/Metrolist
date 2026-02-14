package com.metrolist.innertube.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class, ExperimentalUnsignedTypes::class)
actual fun sha1(str: String): String {
    val data = str.encodeToByteArray().asUByteArray()
    val digest = UByteArray(CC_SHA1_DIGEST_LENGTH)

    data.usePinned { dataPinned ->
        digest.usePinned { digestPinned ->
            CC_SHA1(dataPinned.addressOf(0), data.size.toUInt(), digestPinned.addressOf(0))
        }
    }

    return digest.toByteArray().toHex()
}
