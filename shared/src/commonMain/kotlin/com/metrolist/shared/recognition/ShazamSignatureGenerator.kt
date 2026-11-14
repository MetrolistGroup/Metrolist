package com.metrolist.shared.recognition

import kotlin.io.encoding.Base64
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

object ShazamSignatureGenerator {
    private const val SAMPLE_RATE = 16_000
    private const val FFT_SIZE = 2048
    private const val FFT_OUTPUT_SIZE = FFT_SIZE / 2 + 1
    private const val MAX_PEAKS = 255
    private const val MAX_TIME_SECONDS = 12.0
    private const val RING_BUF_SIZE = 256
    private val hanning = DoubleArray(FFT_SIZE) { index -> 0.5 * (1.0 - cos(2.0 * PI * (index + 1).toDouble() / 2049.0)) }

    fun fromI16(samples: ByteArray): String {
        require(samples.size >= 2 && samples.size % 2 == 0) { "samples must be non-empty 16-bit little-endian PCM" }
        val pcm =
            ShortArray(samples.size / 2) { index ->
                val offset = index * 2
                ((samples[offset].toInt() and 0xff) or (samples[offset + 1].toInt() shl 8)).toShort()
            }
        return State().process(pcm)
    }

    private class State {
        private val samplesRing = IntArray(FFT_SIZE)
        private var samplesPos = 0
        private val fftReal = DoubleArray(FFT_SIZE)
        private val fftImaginary = DoubleArray(FFT_SIZE)
        private val fftOutputs = Array(RING_BUF_SIZE) { DoubleArray(FFT_OUTPUT_SIZE) }
        private var fftPos = 0
        private val spreadFfts = Array(RING_BUF_SIZE) { DoubleArray(FFT_OUTPUT_SIZE) }
        private var spreadPos = 0
        private var spreadNumWritten = 0
        private var numSamples = 0
        private val bandPeaks = Array(4) { mutableListOf<FrequencyPeak>() }
        private var totalPeaks = 0

        fun process(pcm: ShortArray): String {
            var offset = 0
            while (offset + 128 <= pcm.size) {
                if (numSamples.toDouble() / SAMPLE_RATE >= MAX_TIME_SECONDS && totalPeaks >= MAX_PEAKS) break
                numSamples += 128
                for (index in offset until offset + 128) {
                    samplesRing[samplesPos] = pcm[index].toInt()
                    samplesPos = (samplesPos + 1) % FFT_SIZE
                }
                doFft()
                doPeakSpreadingAndRecognition()
                offset += 128
            }
            return encodeSignature()
        }

        private fun doFft() {
            for (index in 0 until FFT_SIZE) {
                fftReal[index] = samplesRing[(samplesPos + index) % FFT_SIZE].toDouble() * hanning[index]
            }
            fftImaginary.fill(0.0)
            computeRfft(fftReal, fftImaginary, fftOutputs[fftPos])
            fftPos = (fftPos + 1) % RING_BUF_SIZE
        }

        private fun doPeakSpreadingAndRecognition() {
            val lastFftIdx = (fftPos - 1 + RING_BUF_SIZE) % RING_BUF_SIZE
            val spread = spreadFfts[spreadPos]
            fftOutputs[lastFftIdx].copyInto(spread)
            for (pos in 0 until FFT_OUTPUT_SIZE - 2) spread[pos] = maxOf(spread[pos], spread[pos + 1], spread[pos + 2])
            for (pos in 0 until FFT_OUTPUT_SIZE) {
                var maxValue = spread[pos]
                for (offset in intArrayOf(-1, -3, -6)) {
                    val idx = ((spreadPos + offset) % RING_BUF_SIZE + RING_BUF_SIZE) % RING_BUF_SIZE
                    if (spreadFfts[idx][pos] > maxValue) maxValue = spreadFfts[idx][pos]
                    spreadFfts[idx][pos] = maxValue
                }
            }
            spreadPos = (spreadPos + 1) % RING_BUF_SIZE
            spreadNumWritten++
            if (spreadNumWritten < 47) return

            val fftMinus46 = fftOutputs[(fftPos - 46 + RING_BUF_SIZE * 2) % RING_BUF_SIZE]
            val spreadMinus49 = spreadFfts[(spreadPos - 49 + RING_BUF_SIZE * 2) % RING_BUF_SIZE]
            val otherOffsets = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)
            for (binPos in 10 until FFT_OUTPUT_SIZE - 8) {
                val fftValue = fftMinus46[binPos]
                if (fftValue < 1.0 / 64.0 || fftValue < spreadMinus49[binPos]) continue
                var maxNeighbor = 0.0
                for (neighborOffset in intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)) {
                    maxNeighbor = max(maxNeighbor, spreadMinus49[binPos + neighborOffset])
                }
                if (fftValue <= maxNeighbor) continue
                for (otherOffset in otherOffsets) {
                    val idx = ((spreadPos + otherOffset) % RING_BUF_SIZE + RING_BUF_SIZE) % RING_BUF_SIZE
                    maxNeighbor = max(maxNeighbor, spreadFfts[idx][binPos - 1])
                }
                if (fftValue <= maxNeighbor) continue
                val peakMagnitude = ln(max(1.0 / 64.0, fftValue)) * 1477.3 + 6144
                val peakMagnitudeBefore = ln(max(1.0 / 64.0, fftMinus46[binPos - 1])) * 1477.3 + 6144
                val peakMagnitudeAfter = ln(max(1.0 / 64.0, fftMinus46[binPos + 1])) * 1477.3 + 6144
                val correctedBin =
                    binPos * 64.0 +
                        (peakMagnitudeAfter - peakMagnitudeBefore) * 32 / (peakMagnitude * 2 - peakMagnitudeBefore - peakMagnitudeAfter)
                val frequencyHz = correctedBin * (SAMPLE_RATE / 2.0 / 1024.0 / 64.0)
                val band =
                    when {
                        frequencyHz < 250.0 -> continue
                        frequencyHz < 520.0 -> 0
                        frequencyHz < 1450.0 -> 1
                        frequencyHz < 3500.0 -> 2
                        frequencyHz <= 5500.0 -> 3
                        else -> continue
                    }
                bandPeaks[band].add(FrequencyPeak(spreadNumWritten - 46, peakMagnitude.toInt(), correctedBin.toInt()))
                totalPeaks++
            }
        }

        private fun encodeSignature(): String {
            val contentsStream = ByteBufferWriter()
            for (bandId in 0..3) {
                val peaks = bandPeaks[bandId]
                if (peaks.isEmpty()) continue
                val peakBuf = ByteBufferWriter()
                var previous = 0
                for (peak in peaks) {
                    val diff = peak.fftPassNumber - previous
                    if (diff >= 255) {
                        peakBuf.write(0xff)
                        writeLe32(peakBuf, peak.fftPassNumber)
                        previous = peak.fftPassNumber
                    }
                    peakBuf.write(peak.fftPassNumber - previous)
                    writeLe16(peakBuf, peak.peakMagnitude)
                    writeLe16(peakBuf, peak.correctedPeakFrequencyBin)
                    previous = peak.fftPassNumber
                }
                val peakBytes = peakBuf.toByteArray()
                writeLe32(contentsStream, 0x60030040 + bandId)
                writeLe32(contentsStream, peakBytes.size)
                contentsStream.write(peakBytes)
                repeat((4 - peakBytes.size % 4) % 4) { contentsStream.write(0) }
            }
            val contents = contentsStream.toByteArray()
            val header =
                ByteBufferWriter()
                    .apply {
                        writeLe32(this, 0xcafe2580.toInt())
                        writeLe32(this, 0)
                        writeLe32(this, contents.size + 8)
                        writeLe32(this, 0x94119c00.toInt())
                        repeat(3) { writeLe32(this, 0) }
                        writeLe32(this, 3 shl 27)
                        repeat(2) { writeLe32(this, 0) }
                        writeLe32(this, (numSamples + SAMPLE_RATE * 0.24).toInt())
                        writeLe32(this, (15 shl 19) + 0x40000)
                    }.toByteArray()
            val full = ByteBufferWriter()
            full.write(header)
            writeLe32(full, 0x40000000)
            writeLe32(full, contents.size + 8)
            full.write(contents)
            val bytes = full.toByteArray()
            val crc = crc32(bytes, 8).toInt()
            bytes[4] = (crc and 0xff).toByte()
            bytes[5] = ((crc ushr 8) and 0xff).toByte()
            bytes[6] = ((crc ushr 16) and 0xff).toByte()
            bytes[7] = ((crc ushr 24) and 0xff).toByte()
            return "data:audio/vnd.shazam.sig;base64,${Base64.encode(bytes)}"
        }
    }

    private data class FrequencyPeak(
        val fftPassNumber: Int,
        val peakMagnitude: Int,
        val correctedPeakFrequencyBin: Int,
    )

    private fun writeLe32(
        out: ByteBufferWriter,
        value: Int,
    ) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 24) and 0xff)
    }

    private fun writeLe16(
        out: ByteBufferWriter,
        value: Int,
    ) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
    }

    private class ByteBufferWriter {
        private val bytes = mutableListOf<Byte>()

        fun write(value: Int) {
            bytes += value.toByte()
        }

        fun write(values: ByteArray) {
            values.forEach { bytes += it }
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

    private fun crc32(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        var crc = 0xffffffffL
        for (index in offset until bytes.size) {
            crc = crc xor (bytes[index].toLong() and 0xffL)
            repeat(8) {
                crc = if (crc and 1L != 0L) (crc ushr 1) xor 0xedb88320L else crc ushr 1
            }
        }
        return crc xor 0xffffffffL
    }

    private fun computeRfft(
        re: DoubleArray,
        im: DoubleArray,
        output: DoubleArray,
    ) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n ushr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit ushr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len ushr 1
            val angle = -PI / half
            val baseRe = cos(angle)
            val baseIm = sin(angle)
            var i = 0
            while (i < n) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until half) {
                    val u = i + k
                    val v = u + half
                    val oddRe = re[v] * wRe - im[v] * wIm
                    val oddIm = re[v] * wIm + im[v] * wRe
                    val evenRe = re[u]
                    val evenIm = im[u]
                    re[u] = evenRe + oddRe
                    im[u] = evenIm + oddIm
                    re[v] = evenRe - oddRe
                    im[v] = evenIm - oddIm
                    val newWRe = wRe * baseRe - wIm * baseIm
                    wIm = wRe * baseIm + wIm * baseRe
                    wRe = newWRe
                }
                i += len
            }
            len = len shl 1
        }
        for (index in output.indices) {
            output[index] = max((re[index] * re[index] + im[index] * im[index]) / (1 shl 17), 1e-10)
        }
    }
}
