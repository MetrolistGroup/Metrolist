package com.metrolist.shared.playback.ffmpeg

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine

internal fun openAudioLine(
    format: AudioFormat,
    bufferSize: Int,
): SourceDataLine =
    openAudioLine(
        format,
        bufferSize,
        System.getProperty("os.name"),
        System.getProperty(SourceDataLine::class.java.name),
        { AudioSystem.getMixerInfo().toList() },
        { mixer, info ->
            (if (mixer == null) AudioSystem.getLine(info) else AudioSystem.getMixer(mixer).getLine(info)) as SourceDataLine
        },
        Thread::sleep,
    )

internal fun openAudioLine(
    format: AudioFormat,
    bufferSize: Int,
    osName: String,
    configuredLine: String?,
    mixers: () -> List<Mixer.Info>,
    getLine: (Mixer.Info?, DataLine.Info) -> SourceDataLine,
    sleep: (Long) -> Unit,
): SourceDataLine {
    var failure: Exception? = null
    for (outputFormat in audioOutputFormats(format, osName)) {
        for (attempt in 0..3) {
            if (attempt > 0) sleep(100L shl (attempt - 1))
            for (mixer in audioOutputMixers(osName, configuredLine, mixers())) {
                val outputBufferSize = bufferSize * outputFormat.frameSize / format.frameSize
                var line: SourceDataLine? = null
                var opened = false
                try {
                    val info = DataLine.Info(SourceDataLine::class.java, outputFormat)
                    line = getLine(mixer, info)
                    line.open(outputFormat, outputBufferSize)
                    opened = true
                    return line
                } catch (error: Exception) {
                    if (error !is LineUnavailableException && error !is IllegalArgumentException) throw error
                    failure = error
                } finally {
                    if (!opened) {
                        runCatching { line?.close() }
                    }
                }
            }
        }
    }
    throw LineUnavailableException().apply { failure?.let(::initCause) }
}

internal fun audioOutputFormats(
    format: AudioFormat,
    osName: String,
): List<AudioFormat> =
    if (osName.startsWith("Windows", ignoreCase = true) && format.channels == 2 && format.sampleSizeInBits == 16) {
        listOf(format, AudioFormat(format.sampleRate, 16, 4, true, format.isBigEndian))
    } else {
        listOf(format)
    }

internal fun audioOutputMixers(
    osName: String,
    configuredLine: String?,
    mixers: List<Mixer.Info>,
): List<Mixer.Info?> {
    if (!configuredLine.isNullOrBlank()) return listOf(null)
    if (!osName.equals("Linux", ignoreCase = true)) return listOf(null) + mixers
    // Do not bypass an available system route with Java Sound's default, which can select hardware or loopback devices.
    val systemMixers =
        mixers.filter { it.name.endsWith(" [default]") } +
            mixers.filter { it.name.endsWith(" [pipewire]") || it.name.endsWith(" [pulse]") }
    return systemMixers.ifEmpty { listOf(null) }
}

fun Throwable.isAudioOutputUnavailable(): Boolean =
    generateSequence(this) { it.cause?.takeUnless { cause -> cause === it } }.any { it is LineUnavailableException }
