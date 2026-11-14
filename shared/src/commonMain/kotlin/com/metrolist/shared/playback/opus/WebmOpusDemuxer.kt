package com.metrolist.shared.playback.opus

import kotlin.math.abs

internal data class OpusPacket(
    val bytes: ByteArray,
    val samples: Int,
)

internal data class WebmOpusAudio(
    val channels: Int,
    val preSkip: Int,
    val inputSampleRate: Long,
    val outputGain: Int,
    val packets: List<OpusPacket>,
    val discardSamples: Int,
) {
    val durationSamples: Long get() = packets.sumOf { it.samples.toLong() } - preSkip - discardSamples
}

internal object WebmOpusDemuxer {
    fun demux(bytes: ByteArray): WebmOpusAudio {
        val reader = EbmlReader(bytes)
        val header = reader.element(bytes.size)
        require(header.id == 0x1A45DFA3L) { "Missing EBML header" }
        var webm = false
        reader.children(header) { element ->
            if (element.id == 0x4282L) webm = reader.bytes(element).decodeToString() == "webm"
        }
        require(webm) { "Not a WebM document" }
        val segment = reader.element(bytes.size)
        require(segment.id == SEGMENT) { "Missing WebM segment" }
        var scale = 1_000_000L
        var track: Track? = null
        val packets = mutableListOf<OpusPacket>()
        var firstTimestamp: Long? = null
        var decodedSamples = 0L
        var discardSamples = 0
        reader.children(segment) { element ->
            when (element.id) {
                0x1549A966L -> {
                    reader.children(element) { info ->
                        if (info.id == 0x2AD7B1L) {
                            scale = reader.unsigned(info)
                            require(scale in 1..1_000_000_000L) { "Invalid WebM timestamp scale" }
                        }
                    }
                }

                0x1654AE6BL -> {
                    reader.children(element) { entry ->
                        if (entry.id == 0xAEL) {
                            readTrack(reader, entry)?.let {
                                require(track == null) { "Multiple Opus tracks" }
                                track = it
                            }
                        }
                    }
                }

                CLUSTER -> {
                    val selected = requireNotNull(track) { "WebM tracks must precede clusters" }
                    var clusterTime = 0L
                    reader.children(element, cluster = true) { child ->
                        when (child.id) {
                            0xE7L -> {
                                clusterTime = reader.unsigned(child)
                            }

                            0xA3L, 0xA0L -> {
                                var block = child.takeIf { it.id == 0xA3L }
                                var paddingNs = 0L
                                if (child.id == 0xA0L) {
                                    reader.children(child) { group ->
                                        when (group.id) {
                                            0xA1L -> {
                                                require(block == null) { "Multiple blocks in a block group" }
                                                block = group
                                            }

                                            0x75A2L -> {
                                                paddingNs = reader.signed(group)
                                            }
                                        }
                                    }
                                }
                                val data = requireNotNull(block) { "Missing WebM block" }
                                reader.position = data.start
                                val trackNumber = reader.vint(data.end).first
                                if (trackNumber == selected.number) {
                                    require(discardSamples == 0) { "Discard padding before the final block" }
                                    val relative = reader.integer(2, data.end).toShort().toLong()
                                    val flags = reader.integer(1, data.end).toInt()
                                    require(clusterTime <= Long.MAX_VALUE / scale - relative.coerceAtLeast(0)) { "WebM timestamp overflow" }
                                    val timestamp = (clusterTime + relative) * scale
                                    val origin = firstTimestamp ?: timestamp.also { firstTimestamp = it }
                                    val expected =
                                        decodedSamples / 48_000 * 1_000_000_000L + decodedSamples % 48_000 * 1_000_000_000L / 48_000
                                    val elapsed = timestamp - origin
                                    require(
                                        elapsed >= 0 && abs(elapsed - expected) <= maxOf(scale, 1_000_000L),
                                    ) { "Discontinuous Opus timestamps" }
                                    for (packet in readLaces(reader, data.end, flags)) {
                                        val samples = opusPacketSamples(packet)
                                        packets += OpusPacket(packet, samples)
                                        decodedSamples += samples
                                    }
                                    require(paddingNs >= 0) { "Leading Opus discard padding is unsupported" }
                                    require(paddingNs <= 120_000_000L) { "Invalid Opus discard padding" }
                                    discardSamples = ((paddingNs * 48_000 + 500_000_000L) / 1_000_000_000L).toInt()
                                    require(discardSamples < packets.last().samples) { "Opus discard padding exceeds final packet" }
                                }
                            }
                        }
                    }
                }
            }
        }
        val selected = requireNotNull(track) { "No Opus audio track" }
        require(packets.isNotEmpty()) { "No Opus packets" }
        return WebmOpusAudio(selected.channels, selected.preSkip, selected.rate, selected.gain, packets, discardSamples).also {
            require(it.durationSamples > 0) { "Empty Opus presentation" }
        }
    }

    private data class Track(
        val number: Long,
        val channels: Int,
        val preSkip: Int,
        val rate: Long,
        val gain: Int,
    )

    private fun readTrack(
        reader: EbmlReader,
        entry: Element,
    ): Track? {
        var number = 0L
        var type = 0L
        var codec = ""
        var privateData = byteArrayOf()
        var delay: Long? = null
        var channels = 1L
        var encoded = false
        var timestampScale = 1.0
        reader.children(entry) { child ->
            when (child.id) {
                0xD7L -> {
                    number = reader.unsigned(child)
                }

                0x83L -> {
                    type = reader.unsigned(child)
                }

                0x86L -> {
                    codec = reader.bytes(child).decodeToString()
                }

                0x63A2L -> {
                    privateData = reader.bytes(child)
                }

                0x56AAL -> {
                    delay = reader.unsigned(child)
                }

                0x6D80L -> {
                    encoded = true
                }

                0x23314FL -> {
                    timestampScale = reader.float(child)
                }

                0xE1L -> {
                    reader.children(child) { audio ->
                        if (audio.id == 0x9FL) channels = reader.unsigned(audio)
                    }
                }
            }
        }
        if (type != 2L || codec != "A_OPUS") return null
        require(number > 0 && !encoded && timestampScale == 1.0) { "Unsupported Opus track encoding" }
        require(privateData.size == 19 && privateData.copyOfRange(0, 8).decodeToString() == "OpusHead") { "Invalid OpusHead" }
        require(privateData[8].toInt() == 1 && privateData[18].toInt() == 0) { "Unsupported Opus channel mapping" }
        require(channels in 1..2 && privateData[9].toInt() == channels.toInt()) { "Unsupported Opus channel count" }

        fun little(
            offset: Int,
            size: Int,
        ): Long =
            (0 until size).fold(0L) { value, index ->
                value or ((privateData[offset + index].toLong() and 0xff) shl (index * 8))
            }
        val preSkip = little(10, 2).toInt()
        delay?.let { require(abs(it - preSkip * 1_000_000_000L / 48_000) <= 20_834L) { "Opus codec delay differs from pre-skip" } }
        return Track(number, channels.toInt(), preSkip, little(12, 4), little(16, 2).toInt())
    }

    private fun readLaces(
        reader: EbmlReader,
        end: Int,
        flags: Int,
    ): List<ByteArray> {
        val mode = flags and 6
        if (mode == 0) return listOf(reader.remaining(end))
        val count = reader.integer(1, end).toInt() + 1
        val sizes = mutableListOf<Int>()
        when (mode) {
            2 -> {
                repeat(count - 1) {
                    var size = 0
                    do {
                        val part = reader.integer(1, end).toInt()
                        size += part
                    } while (part == 255)
                    sizes += size
                }
            }

            4 -> {
                require((end - reader.position) % count == 0) { "Invalid fixed Opus lacing" }
                repeat(count - 1) { sizes += (end - reader.position) / count }
            }

            6 -> {
                if (count > 1) {
                    val first = reader.vint(end).first
                    require(first <= Int.MAX_VALUE) { "Opus lace size overflow" }
                    sizes += first.toInt()
                    repeat(count - 2) {
                        val (value, width) = reader.vint(end)
                        val size = sizes.last().toLong() + value - ((1L shl (7 * width - 1)) - 1)
                        require(size in 1..Int.MAX_VALUE.toLong()) { "Invalid EBML Opus lace size" }
                        sizes += size.toInt()
                    }
                }
            }
        }
        val remaining = end - reader.position
        val used = sizes.sumOf { it.toLong() }
        require(used < remaining) { "Opus laces exceed block" }
        sizes += remaining - used.toInt()
        return sizes.map { size ->
            require(size > 0) { "Empty Opus lace" }
            reader.remaining(reader.position + size)
        }
    }

    private const val SEGMENT = 0x18538067L
    private const val CLUSTER = 0x1F43B675L
    private val segmentChildren = setOf(0x114D9B74L, 0x1549A966L, 0x1654AE6BL, CLUSTER, 0x1C53BB6BL, 0x1941A469L, 0x1043A770L, 0x1254C367L)

    private data class Element(
        val id: Long,
        val start: Int,
        val end: Int,
        val unknown: Boolean,
    )

    private class EbmlReader(
        val data: ByteArray,
    ) {
        var position = 0

        fun integer(
            size: Int,
            end: Int,
        ): Long {
            require(size in 1..8 && position <= end - size) { "Truncated WebM integer" }
            var value = 0L
            repeat(size) { value = (value shl 8) or (data[position++].toLong() and 0xff) }
            return value
        }

        fun vint(
            end: Int,
            id: Boolean = false,
        ): Pair<Long, Int> {
            require(position < end) { "Truncated EBML variable integer" }
            val first = data[position].toInt() and 0xff
            require(first != 0) { "Invalid EBML variable integer" }
            val width = first.countLeadingZeroBits() - 23
            require(width in 1..if (id) 4 else 8) { "Invalid EBML integer width" }
            val value = integer(width, end)
            return (if (id) value else value and ((1L shl (7 * width)) - 1)) to width
        }

        fun element(end: Int): Element {
            val id = vint(end, id = true).first
            val (size, width) = vint(end)
            val unknown = size == (1L shl (7 * width)) - 1
            require(!unknown || id == SEGMENT || id == CLUSTER) { "Unknown size on a WebM leaf" }
            require(unknown || size <= end - position) { "Truncated WebM element" }
            return Element(id, position, if (unknown) end else position + size.toInt(), unknown)
        }

        fun children(
            parent: Element,
            cluster: Boolean = false,
            consume: (Element) -> Unit,
        ) {
            position = parent.start
            while (position < parent.end) {
                val start = position
                val child = element(parent.end)
                if (cluster && parent.unknown && child.id in segmentChildren) {
                    position = start
                    return
                }
                consume(child)
                if (!child.unknown) position = child.end
            }
            require(position == parent.end) { "Invalid WebM container length" }
        }

        fun bytes(element: Element): ByteArray = data.copyOfRange(element.start, element.end)

        fun unsigned(element: Element): Long {
            position = element.start
            return integer(element.end - element.start, element.end).also { require(it >= 0) { "WebM integer overflow" } }
        }

        fun signed(element: Element): Long {
            val value = unsignedBytes(element)
            val shift = 64 - (element.end - element.start) * 8
            return value shl shift shr shift
        }

        private fun unsignedBytes(element: Element): Long {
            position = element.start
            return integer(element.end - element.start, element.end)
        }

        fun float(element: Element): Double =
            when (element.end - element.start) {
                4 -> Float.fromBits(unsignedBytes(element).toInt()).toDouble()
                8 -> Double.fromBits(unsignedBytes(element))
                else -> error("Invalid WebM float")
            }

        fun remaining(end: Int): ByteArray {
            require(position < end && end <= data.size) { "Empty or truncated Opus packet" }
            return data.copyOfRange(position, end).also { position = end }
        }
    }
}

/** RFC 6716 section 3.1 packet duration at the Opus 48 kHz clock. */
internal fun opusPacketSamples(packet: ByteArray): Int {
    require(packet.isNotEmpty()) { "Empty Opus packet" }
    val toc = packet[0].toInt() and 0xff
    val frameSamples =
        when {
            toc and 0x80 != 0 -> 120 shl ((toc shr 3) and 3)
            toc and 0x60 == 0x60 -> if (toc and 8 != 0) 960 else 480
            (toc shr 3) and 3 == 3 -> 2880
            else -> 480 shl ((toc shr 3) and 3)
        }
    val frames =
        when (toc and 3) {
            0 -> {
                1
            }

            1, 2 -> {
                2
            }

            else -> {
                require(packet.size > 1) { "Truncated Opus frame count" }
                packet[1].toInt() and 0x3f
            }
        }
    return (frameSamples * frames).also { require(frames > 0 && it <= 5760) { "Invalid Opus packet duration" } }
}
