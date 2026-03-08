package com.metrolist.shared.deviceconnect

internal const val DEVICE_CONNECT_PLAYBACK_VERSION = 4
internal const val DEVICE_CONNECT_PLAYBACK_CHUNK_BYTES = 24 * 1024
internal const val DEVICE_CONNECT_MAX_PLAYBACK_BYTES = 8 * 1024 * 1024
internal const val DEVICE_CONNECT_PLAYBACK_TIMEOUT_MS = 10_000L

internal fun chunkDeviceConnectPlayback(bytes: ByteArray): Sequence<PlaybackChunkPayload> {
    require(bytes.isNotEmpty() && bytes.size <= DEVICE_CONNECT_MAX_PLAYBACK_BYTES)
    val messageId = DeviceCrypto.secureRandomBytes(16).toBase64()
    val count = (bytes.size + DEVICE_CONNECT_PLAYBACK_CHUNK_BYTES - 1) / DEVICE_CONNECT_PLAYBACK_CHUNK_BYTES
    return (0 until count).asSequence().map { index ->
        val start = index * DEVICE_CONNECT_PLAYBACK_CHUNK_BYTES
        PlaybackChunkPayload(
            messageId = messageId,
            index = index,
            count = count,
            totalBytes = bytes.size,
            data = bytes.copyOfRange(start, minOf(start + DEVICE_CONNECT_PLAYBACK_CHUNK_BYTES, bytes.size)).toBase64(),
        )
    }
}

internal class DeviceConnectPlaybackAssembler {
    private var first: PlaybackChunkPayload? = null
    private var bytes: ByteArray? = null
    private var nextIndex = 0
    private var deadlineMs = 0L

    val pending: Boolean get() = bytes != null

    fun clear() {
        first = null
        bytes = null
        nextIndex = 0
        deadlineMs = 0L
    }

    fun accept(
        chunk: PlaybackChunkPayload,
        nowMs: Long,
    ): ByteArray? =
        try {
            require(!pending || nowMs < deadlineMs) { "playback assembly timed out" }
            require(chunk.totalBytes in 1..DEVICE_CONNECT_MAX_PLAYBACK_BYTES)
            require(chunk.count == (chunk.totalBytes + DEVICE_CONNECT_PLAYBACK_CHUNK_BYTES - 1) / DEVICE_CONNECT_PLAYBACK_CHUNK_BYTES)
            require(chunk.index in 0 until chunk.count)
            require(chunk.messageId.length == 24 && chunk.messageId.fromBase64().size == 16)
            require(chunk.messageId.fromBase64().toBase64() == chunk.messageId)
            val expectedBytes =
                minOf(
                    DEVICE_CONNECT_PLAYBACK_CHUNK_BYTES,
                    chunk.totalBytes - chunk.index * DEVICE_CONNECT_PLAYBACK_CHUNK_BYTES,
                )
            require(chunk.data.length == (expectedBytes + 2) / 3 * 4)
            val decoded = chunk.data.fromBase64()
            require(decoded.size == expectedBytes && decoded.toBase64() == chunk.data)
            if (!pending) {
                require(chunk.index == 0)
                first = chunk.copy(data = "")
                bytes = ByteArray(chunk.totalBytes)
                deadlineMs = nowMs + DEVICE_CONNECT_PLAYBACK_TIMEOUT_MS
            }
            val header = checkNotNull(first)
            require(chunk.messageId == header.messageId && chunk.count == header.count && chunk.totalBytes == header.totalBytes)
            require(chunk.index == nextIndex) { "out-of-order playback chunk" }
            val target = checkNotNull(bytes)
            decoded.copyInto(target, chunk.index * DEVICE_CONNECT_PLAYBACK_CHUNK_BYTES)
            nextIndex++
            if (nextIndex == chunk.count) target.also { clear() } else null
        } catch (error: Exception) {
            clear()
            throw error
        }
}
