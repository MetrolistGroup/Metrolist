package com.metrolist.shared.soulseek

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.Inflater

/** Thrown for malformed data from the server or a peer. Peers are untrusted, so every read is bounds-checked. */
class SoulseekProtocolException(
    message: String,
) : Exception(message)

/** Soulseek message codes used by [SoulseekClient]. Layouts follow the Nicotine+ protocol notes and aioslsk. */
internal object Codes {
    const val LOGIN = 1
    const val SET_LISTEN_PORT = 2
    const val GET_PEER_ADDRESS = 3
    const val CONNECT_TO_PEER = 18
    const val FILE_SEARCH = 26
    const val SET_STATUS = 28
    const val PING = 32
    const val SHARED_FOLDERS_FILES = 35
    const val TOGGLE_PARENT_SEARCH = 71
    const val ACCEPT_CHILDREN = 100
    const val CANNOT_CONNECT = 1001

    // Peer initialization messages have a one-byte code.
    const val PIERCE_FIREWALL = 0
    const val PEER_INIT = 1

    const val PEER_SEARCH_REPLY = 9
    const val PEER_TRANSFER_REQUEST = 40
    const val PEER_TRANSFER_REPLY = 41
    const val PEER_TRANSFER_QUEUE = 43
    const val PEER_PLACE_IN_QUEUE_REPLY = 44
    const val PEER_UPLOAD_FAILED = 46
    const val PEER_TRANSFER_QUEUE_FAILED = 50

    const val ATTR_BITRATE = 0
    const val ATTR_DURATION = 1
    const val ATTR_VBR = 2
    const val ATTR_SAMPLE_RATE = 4
    const val ATTR_BIT_DEPTH = 5
}

/** Builds one message payload in Soulseek's little-endian encoding. */
internal class MessageWriter {
    private val out = ByteArrayOutputStream()

    fun u8(value: Int) = apply { out.write(value and 0xFF) }

    fun u32(value: Long) = apply { repeat(4) { out.write(((value shr (8 * it)) and 0xFF).toInt()) } }

    fun u32(value: Int) = u32(value.toLong() and 0xFFFFFFFFL)

    fun u64(value: Long) = apply { repeat(8) { out.write(((value shr (8 * it)) and 0xFF).toInt()) } }

    fun bool(value: Boolean) = u8(if (value) 1 else 0)

    fun string(value: String) =
        apply {
            val bytes = value.toByteArray(Charsets.UTF_8)
            u32(bytes.size)
            out.write(bytes)
        }

    fun bytes(): ByteArray = out.toByteArray()

    /** Frames a server or peer message: length, four-byte code, payload. */
    fun frame(code: Int): ByteArray = frame(MessageWriter().u32(code).bytes() + bytes())

    /** Frames a peer initialization message, whose code is a single byte. */
    fun frameInit(code: Int): ByteArray = frame(byteArrayOf(code.toByte()) + bytes())

    private fun frame(body: ByteArray): ByteArray = MessageWriter().u32(body.size).bytes() + body
}

/** Reads one message payload; throws [SoulseekProtocolException] instead of reading past the end. */
internal class MessageReader(
    bytes: ByteArray,
) {
    private val buffer: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    val remaining: Int get() = buffer.remaining()

    private fun need(count: Int) {
        if (count < 0 || buffer.remaining() < count) throw SoulseekProtocolException("Message ended early")
    }

    fun u8(): Int {
        need(1)
        return buffer.get().toInt() and 0xFF
    }

    fun u32(): Long {
        need(4)
        return buffer.int.toLong() and 0xFFFFFFFFL
    }

    fun u64(): Long {
        need(8)
        return buffer.long
    }

    fun bool(): Boolean = u8() != 0

    /** The unread rest of the message. */
    fun rest(): ByteArray = ByteArray(buffer.remaining()).also { buffer.get(it) }

    fun string(): String {
        val length = u32()
        if (length > MAX_STRING_BYTES) throw SoulseekProtocolException("String too long")
        need(length.toInt())
        val bytes = ByteArray(length.toInt()).also { buffer.get(it) }
        return decodeString(bytes)
    }

    /** IPv4 addresses travel as four bytes in reverse order. */
    fun ip(): String {
        need(4)
        val bytes = ByteArray(4).also { buffer.get(it) }
        return bytes.reversed().joinToString(".") { (it.toInt() and 0xFF).toString() }
    }

    /** A count prefix for an array whose items take at least [minItemBytes] each, so a bogus count fails fast. */
    fun count(minItemBytes: Int): Int {
        val count = u32()
        if (count * minItemBytes > buffer.remaining()) throw SoulseekProtocolException("Array count exceeds message")
        return count.toInt()
    }

    companion object {
        const val MAX_STRING_BYTES = 64 * 1024L
        private val windows1252: Charset = Charset.forName("windows-1252")

        // Old clients send Windows-1252; fall back to it when bytes are not valid UTF-8.
        fun decodeString(bytes: ByteArray): String =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                String(bytes, windows1252)
            }
    }
}

/** A file in a peer's search reply. */
data class SoulseekFileResult(
    val filename: String,
    val size: Long,
    val extension: String,
    val attributes: Map<Int, Int>,
)

/** One peer's answer to a search. */
data class SoulseekSearchReply(
    val username: String,
    val ticket: Long,
    val files: List<SoulseekFileResult>,
    val hasFreeSlots: Boolean,
    val averageSpeed: Long,
    val queueLength: Long,
)

internal object SoulseekCodec {
    /** Largest message accepted from the network; search replies are the biggest and stay far below this. */
    const val MAX_MESSAGE_BYTES = 32 * 1024 * 1024

    /** Reads one length-prefixed message, or null at end of stream. */
    fun readFrame(input: DataInputStream): ByteArray? {
        val header = readExactly(input, 4) ?: return null
        val length = MessageReader(header).u32()
        if (length > MAX_MESSAGE_BYTES) throw SoulseekProtocolException("Message too large: $length bytes")
        return readExactly(input, length.toInt())
    }

    /** [DataInputStream.readFully] that returns null at end of stream (readNBytes needs Android 13). */
    fun readExactly(
        input: DataInputStream,
        count: Int,
    ): ByteArray? =
        try {
            ByteArray(count).also { input.readFully(it) }
        } catch (_: EOFException) {
            null
        }

    fun write(
        output: OutputStream,
        bytes: ByteArray,
    ) {
        output.write(bytes)
        output.flush()
    }

    /** Parses a peer search reply (code 9), whose payload after the code is zlib-compressed. */
    fun parseSearchReply(compressed: ByteArray): SoulseekSearchReply {
        val reader = MessageReader(inflate(compressed))
        val username = reader.string()
        val ticket = reader.u32()
        val files = List(reader.count(minItemBytes = 21)) { readFile(reader) }
        val freeSlots = reader.bool()
        val speed = reader.u32()
        val queue = reader.u32()
        // Locked results and an unknown field may follow; they are ignored.
        return SoulseekSearchReply(username, ticket, files, freeSlots, speed, queue)
    }

    private fun readFile(reader: MessageReader): SoulseekFileResult {
        reader.u8() // Always 1.
        val filename = reader.string()
        val size = reader.u64()
        val extension = reader.string()
        val attributes = buildMap { repeat(reader.count(minItemBytes = 8)) { put(reader.u32().toInt(), reader.u32().toInt()) } }
        return SoulseekFileResult(filename, size, extension, attributes)
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(chunk)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(chunk, 0, count)
                if (out.size() > MAX_MESSAGE_BYTES) throw SoulseekProtocolException("Search reply too large")
            }
        } catch (e: java.util.zip.DataFormatException) {
            throw SoulseekProtocolException("Corrupt search reply: ${e.message}")
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }
}
