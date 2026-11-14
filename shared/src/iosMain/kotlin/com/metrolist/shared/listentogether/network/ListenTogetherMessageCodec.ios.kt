package com.metrolist.shared.listentogether.network

import okio.Buffer
import okio.GzipSink
import okio.GzipSource
import okio.buffer
import okio.use

internal actual fun createListenTogetherMessageCodec(): ListenTogetherMessageCodec = PortableMessageCodec()

internal actual fun compressListenTogetherPayload(data: ByteArray): ByteArray {
    val output = Buffer()
    GzipSink(output).buffer().use { it.write(data) }
    return output.readByteArray()
}

internal actual fun decompressListenTogetherPayload(
    data: ByteArray,
    limit: Int,
): ByteArray {
    try {
        return GzipSource(Buffer().write(data)).use { gzip ->
            val output = Buffer()
            while (true) {
                val count = gzip.read(output, minOf(8192L, limit - output.size + 1L))
                if (count == -1L) break
                require(output.size <= limit) { "Compressed Listen Together payload exceeds the size limit" }
            }
            output.readByteArray()
        }
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid compressed Listen Together payload", error)
    }
}
