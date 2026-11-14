package com.metrolist.shared.playback

interface SeekableMediaSource {
    val mediaSize: Long?
    val mediaPosition: Long
    val bufferedBytePosition: Long get() = mediaPosition

    fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    fun readAtMediaPosition(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    fun seekToMediaPosition(position: Long): Long

    fun updatePlaybackPosition(positionMs: Long)

    fun close()
}
