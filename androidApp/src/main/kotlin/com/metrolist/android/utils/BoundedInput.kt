package com.metrolist.android.utils

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun InputStream.readBoundedText(maxBytes: Int): String? = readBoundedBytes(maxBytes)?.toString(Charsets.UTF_8)

internal fun InputStream.readBoundedBytes(maxBytes: Int): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, maxBytes - output.size() + 1))
        if (count < 0) return output.toByteArray()
        if (output.size() + count > maxBytes) return null
        output.write(buffer, 0, count)
    }
}
