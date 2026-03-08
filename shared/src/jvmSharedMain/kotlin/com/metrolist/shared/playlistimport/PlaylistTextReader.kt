package com.metrolist.shared.playlistimport

import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

fun readPlaylistText(
    input: InputStream,
    maxCharacters: Int = PlaylistImportManager.MAX_CSV_CHARACTERS,
): String {
    val bounded = BoundedInputStream(input, maxCharacters * 4L + 4L)
    val stream = PushbackInputStream(bounded, 3)
    val prefix = ByteArray(3)
    val prefixSize = stream.read(prefix)
    val charset =
        when {
            prefixSize >= 3 && prefix[0] == 0xEF.toByte() && prefix[1] == 0xBB.toByte() && prefix[2] == 0xBF.toByte() -> {
                if (prefixSize > 3) stream.unread(prefix, 3, prefixSize - 3)
                StandardCharsets.UTF_8
            }

            prefixSize >= 2 && prefix[0] == 0xFF.toByte() && prefix[1] == 0xFE.toByte() -> {
                if (prefixSize > 2) stream.unread(prefix, 2, prefixSize - 2)
                StandardCharsets.UTF_16LE
            }

            prefixSize >= 2 && prefix[0] == 0xFE.toByte() && prefix[1] == 0xFF.toByte() -> {
                if (prefixSize > 2) stream.unread(prefix, 2, prefixSize - 2)
                StandardCharsets.UTF_16BE
            }

            prefixSize >= 2 && prefix[0] == 0.toByte() && prefix[1] != 0.toByte() -> {
                stream.unread(prefix, 0, prefixSize)
                StandardCharsets.UTF_16BE
            }

            prefixSize >= 2 && prefix[0] != 0.toByte() && prefix[1] == 0.toByte() -> {
                stream.unread(prefix, 0, prefixSize)
                StandardCharsets.UTF_16LE
            }

            else -> {
                if (prefixSize > 0) stream.unread(prefix, 0, prefixSize)
                StandardCharsets.UTF_8
            }
        }
    val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    return InputStreamReader(stream, decoder).use { reader ->
        val output = StringBuilder()
        val buffer = CharArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            check(output.length + read <= maxCharacters) { "Playlist file is too large" }
            output.appendRange(buffer, 0, read)
        }
        output.toString()
    }
}

private class BoundedInputStream(
    input: InputStream,
    private val limit: Long,
) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) add(1) }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = super.read(buffer, offset, length).also { if (it > 0) add(it.toLong()) }

    private fun add(bytes: Long) {
        count += bytes
        check(count <= limit) { "Playlist file is too large" }
    }
}
