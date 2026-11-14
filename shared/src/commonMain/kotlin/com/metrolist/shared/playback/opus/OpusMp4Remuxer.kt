package com.metrolist.shared.playback.opus

internal object OpusMp4Remuxer {
    fun remux(webm: ByteArray): ByteArray = remux(WebmOpusDemuxer.demux(webm))

    private fun remux(audio: WebmOpusAudio): ByteArray {
        val packets = audio.packets
        val duration = audio.durationSamples
        val mediaDuration = duration + audio.preSkip
        var chunkOffset = 0
        require(mediaDuration <= UInt.MAX_VALUE.toLong()) { "Opus presentation is too long" }
        val roll = -((3840 + packets.minOf { it.samples } - 1) / packets.minOf { it.samples })
        val output = Mp4Writer()
        output.box("ftyp") {
            text("iso6")
            u32(0)
            text("iso6iso2mp41")
        }
        output.box("moov") {
            fullBox("mvhd") {
                u32(0)
                u32(0)
                u32(48_000)
                u32(duration)
                u32(0x10000)
                u16(0x100)
                zeros(10)
                matrix()
                zeros(24)
                u32(2)
            }
            box("trak") {
                fullBox("tkhd", flags = 7) {
                    u32(0)
                    u32(0)
                    u32(1)
                    u32(0)
                    u32(duration)
                    zeros(8)
                    u16(0)
                    u16(0)
                    u16(0x100)
                    u16(0)
                    matrix()
                    u32(0)
                    u32(0)
                }
                box("edts") {
                    fullBox("elst") {
                        u32(1)
                        u32(duration)
                        u32(audio.preSkip)
                        u16(1)
                        u16(0)
                    }
                }
                box("mdia") {
                    fullBox("mdhd") {
                        u32(0)
                        u32(0)
                        u32(48_000)
                        u32(mediaDuration)
                        u16(0x55c4)
                        u16(0)
                    }
                    fullBox("hdlr") {
                        u32(0)
                        text("soun")
                        zeros(12)
                        u8(0)
                    }
                    box("minf") {
                        fullBox("smhd") { zeros(4) }
                        box("dinf") {
                            fullBox("dref") {
                                u32(1)
                                fullBox("url ", flags = 1) {}
                            }
                        }
                        box("stbl") {
                            fullBox("stsd") {
                                u32(1)
                                box("Opus") {
                                    zeros(6)
                                    u16(1)
                                    zeros(8)
                                    u16(audio.channels)
                                    u16(16)
                                    zeros(4)
                                    u32(48_000L shl 16)
                                    box("dOps") {
                                        u8(0)
                                        u8(audio.channels)
                                        u16(audio.preSkip)
                                        u32(audio.inputSampleRate)
                                        u16(audio.outputGain)
                                        u8(0)
                                    }
                                }
                            }
                            fullBox("stts") {
                                u32(packets.size)
                                packets.forEachIndexed { index, packet ->
                                    u32(1)
                                    u32(packet.samples - if (index == packets.lastIndex) audio.discardSamples else 0)
                                }
                            }
                            fullBox("stsc") {
                                u32(1)
                                u32(1)
                                u32(packets.size)
                                u32(1)
                            }
                            fullBox("stsz") {
                                u32(0)
                                u32(packets.size)
                                packets.forEach { u32(it.bytes.size) }
                            }
                            fullBox("stco") {
                                u32(1)
                                chunkOffset = size
                                u32(0)
                            }
                            fullBox("sgpd", version = 1) {
                                text("roll")
                                u32(2)
                                u32(1)
                                u16(roll)
                            }
                            fullBox("sbgp") {
                                text("roll")
                                u32(1)
                                u32(packets.size)
                                u32(1)
                            }
                        }
                    }
                }
            }
        }
        output.patch32(chunkOffset, (output.size + 8).toLong())
        output.box("mdat") { packets.forEach { bytes(it.bytes) } }
        return output.toByteArray()
    }

    private class Mp4Writer {
        private var data = ByteArray(1024)
        var size = 0
            private set

        fun bytes(value: ByteArray) {
            require(size <= Int.MAX_VALUE - value.size) { "MP4 size overflow" }
            if (size + value.size > data.size) data = data.copyOf(maxOf(size + value.size, data.size * 2))
            value.copyInto(data, size)
            size += value.size
        }

        fun u8(value: Int) = bytes(byteArrayOf(value.toByte()))

        fun u16(value: Int) {
            u8(value ushr 8)
            u8(value)
        }

        fun u32(value: Int) = u32(value.toLong())

        fun u32(value: Long) {
            for (shift in 24 downTo 0 step 8) u8((value ushr shift).toInt())
        }

        fun text(value: String) = bytes(value.encodeToByteArray())

        fun zeros(count: Int) = bytes(ByteArray(count))

        fun matrix() {
            u32(0x10000)
            u32(0)
            u32(0)
            u32(0)
            u32(0x10000)
            u32(0)
            u32(0)
            u32(0)
            u32(0x40000000)
        }

        fun patch32(
            offset: Int,
            value: Long,
        ) {
            for (index in 0..3) data[offset + index] = (value ushr ((3 - index) * 8)).toByte()
        }

        fun box(
            type: String,
            body: Mp4Writer.() -> Unit,
        ) {
            val start = size
            u32(0)
            text(type)
            body()
            patch32(start, (size - start).toLong())
        }

        fun fullBox(
            type: String,
            version: Int = 0,
            flags: Int = 0,
            body: Mp4Writer.() -> Unit,
        ) = box(type) {
            u32((version shl 24) or flags)
            body()
        }

        fun toByteArray(): ByteArray = data.copyOf(size)
    }
}
