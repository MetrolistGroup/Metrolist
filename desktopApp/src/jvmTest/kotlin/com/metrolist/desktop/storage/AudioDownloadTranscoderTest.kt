package com.metrolist.desktop.storage

import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioDownloadTranscoderTest {
    @Test
    fun convertsAudioToTaggedM4a() {
        val directory = Files.createTempDirectory("metrolist-audio-transcode-").toFile()
        val source = directory.resolve("source.wav")
        val destination = directory.resolve("Song by Artist.m4a")
        try {
            writeTestWave(source)
            val song =
                SongItem(
                    id = "videoId",
                    title = "Song",
                    thumbnail = null,
                    artists = listOf(ArtistTiny(id = null, name = "Artist")),
                    album = AlbumTiny(id = null, name = "Album"),
                    year = 2026,
                    trackNumber = 2,
                    totalTrackCount = 10,
                    genre = "Electronic",
                )

            runBlocking { AudioDownloadTranscoder.transcodeToM4a(source, destination, song) }
            assertTrue(destination.length() > 1024)
            val grabber = FFmpegFrameGrabber(destination)
            try {
                grabber.start()
                assertEquals("aac", grabber.audioCodecName)
                assertEquals("Song", grabber.getMetadata("title"))
                assertEquals("Artist", grabber.getMetadata("artist"))
                assertEquals("Album", grabber.getMetadata("album"))
                assertEquals("2026", grabber.getMetadata("date"))
                assertEquals("2/10", grabber.getMetadata("track"))
                assertEquals("Electronic", grabber.getMetadata("genre"))
                assertEquals("https://music.youtube.com/watch?v=videoId", grabber.getMetadata("comment"))
            } finally {
                runCatching { grabber.stop() }
                runCatching { grabber.release() }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cancelledTranscodeDoesNotPublishOrLeavePartialM4a() =
        runBlocking {
            val directory = Files.createTempDirectory("metrolist-cancel-transcode-").toFile()
            val source = directory.resolve("source.wav")
            val destination = directory.resolve("cancelled.m4a")
            try {
                writeTestWave(source)
                val job = Job()
                assertFailsWith<CancellationException> {
                    withContext(job) {
                        AudioDownloadTranscoder.transcodeToM4a(
                            source,
                            destination,
                            SongItem(id = "video", title = "Song", thumbnail = null, artists = emptyList(), album = null),
                        ) { job.cancel() }
                    }
                }
                assertFalse(destination.exists())
                assertEquals(listOf(source), directory.listFiles()!!.toList())
            } finally {
                directory.deleteRecursively()
            }
        }

    private fun writeTestWave(file: File) {
        val sampleRate = 44_100
        val frameCount = sampleRate / 5
        val samples = ByteArray(frameCount * 2 * 2)
        repeat(frameCount) { frame ->
            val sample = (sin(2.0 * PI * 440.0 * frame / sampleRate) * Short.MAX_VALUE * 0.2).toInt().toShort()
            repeat(2) { channel ->
                val offset = (frame * 2 + channel) * 2
                samples[offset] = sample.toInt().toByte()
                samples[offset + 1] = (sample.toInt() shr 8).toByte()
            }
        }
        val format = AudioFormat(sampleRate.toFloat(), 16, 2, true, false)
        AudioInputStream(ByteArrayInputStream(samples), format, frameCount.toLong()).use { stream ->
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file)
        }
    }
}
