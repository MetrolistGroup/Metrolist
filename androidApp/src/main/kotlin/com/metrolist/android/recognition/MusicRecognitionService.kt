package com.metrolist.android.recognition

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.RecognitionHistory
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.recognition_failed
import com.metrolist.shared.generated.resources.recognition_microphone_permission_not_granted
import com.metrolist.shared.generated.resources.recognition_network_error
import com.metrolist.shared.generated.resources.recognition_no_match_try_clearer_section
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.recognition.RecognitionResult
import com.metrolist.shared.recognition.RecognitionStatus
import com.metrolist.shared.recognition.ShazamSignatureGenerator
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.getString
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.time.Clock

object MusicRecognitionService : KoinComponent {
    private const val LOG_TAG = "MusicRecognition"
    private const val INPUT_SAMPLE_RATE = 44_100
    private const val FINGERPRINT_SAMPLE_RATE = 16_000
    private const val RECORDING_DURATION_MS = 12_000L
    private val _status = MutableStateFlow<RecognitionStatus>(RecognitionStatus.Ready)
    val status: StateFlow<RecognitionStatus> = _status.asStateFlow()
    private val _audioLevel = MutableStateFlow(0f)

    /** Microphone level normalized to 0..1. */
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()
    private val database: MusicDatabase by inject()
    private val mutex = Mutex()
    private val applicationScope: CoroutineScope by inject()

    @Volatile
    private var activeSession = Any()

    @Volatile
    private var cancelRequested = false

    fun hasRecordPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun reset() {
        cancelRequested = true
        activeSession = Any()
        _status.value = RecognitionStatus.Ready
    }

    @SuppressLint("MissingPermission")
    suspend fun recognize(context: Context): RecognitionStatus =
        withContext(Dispatchers.IO) {
            if (!hasRecordPermission(context)) {
                return@withContext RecognitionStatus
                    .Error(getString(Res.string.recognition_microphone_permission_not_granted))
                    .also { _status.value = it }
            }
            if (!mutex.tryLock()) {
                val current = _status.value
                if (current is RecognitionStatus.Listening || current is RecognitionStatus.Processing) {
                    return@withContext current
                }
                mutex.lock()
            }
            try {
                cancelRequested = false
                val session = Any()
                activeSession = session
                _status.value = RecognitionStatus.Listening
                val audio = recordAudio()
                if (cancelRequested) {
                    return@withContext RecognitionStatus.Ready.also { _status.value = it }
                }
                _status.value = RecognitionStatus.Processing
                val resampled = resample16BitMono(audio, INPUT_SAMPLE_RATE, FINGERPRINT_SAMPLE_RATE)
                val signature = ShazamSignatureGenerator.fromI16(resampled)
                val sampleDurationMs = (resampled.size / 2) * 1000L / FINGERPRINT_SAMPLE_RATE
                val result = ShazamRecognitionClient.recognize(signature, sampleDurationMs).getOrThrow()
                if (cancelRequested) {
                    return@withContext RecognitionStatus.Ready.also { _status.value = it }
                }
                currentCoroutineContext().ensureActive()
                completeRecognition(
                    result = result,
                    save = {
                        database.dao().insert(
                            RecognitionHistory(
                                trackId = result.trackId.ifBlank { "${result.title}:${result.artist}" },
                                title = result.title,
                                artist = result.artist,
                                album = result.album,
                                coverArtUrl = result.coverArtUrl,
                                coverArtHqUrl = result.coverArtHqUrl,
                                genre = result.genre,
                                releaseDate = result.releaseDate,
                                label = result.label,
                                shazamUrl = result.shazamUrl,
                                appleMusicUrl = result.appleMusicUrl,
                                spotifyUrl = result.spotifyUrl,
                                isrc = result.isrc,
                                youtubeVideoId = result.youtubeVideoId,
                                recognizedAt = Clock.System.now().toLocalDateTime(TimeZone.UTC),
                                liked = result.youtubeVideoId?.let { SongLikeState.isLiked(it) } == true,
                            ),
                        )
                    },
                    resolve = { if (session === activeSession) resolveYoutubeVideoId(result) else null },
                    updateVideoId = { videoId ->
                        val trackId = result.trackId.ifBlank { "${result.title}:${result.artist}" }
                        database.dao().updateRecognitionHistoryVideoId(trackId, videoId)
                        database.dao().updateRecognitionHistoryLikedByTrack(trackId, SongLikeState.isLiked(videoId))
                    },
                    publish = { updated ->
                        if (session === activeSession && !cancelRequested) _status.value = updated
                    },
                    enrichmentScope = applicationScope,
                )
            } catch (e: CancellationException) {
                _status.value = RecognitionStatus.Ready
                throw e
            } catch (e: Exception) {
                if (cancelRequested) {
                    RecognitionStatus.Ready
                } else if (e is NoRecognitionMatchException) {
                    RecognitionStatus.NoMatch(getString(Res.string.recognition_no_match_try_clearer_section))
                } else {
                    Timber.tag(LOG_TAG).e(e, "Music recognition failed")
                    RecognitionStatus.Error(userFacingMessage(e))
                }.also { _status.value = it }
            } finally {
                mutex.unlock()
            }
        }

    private suspend fun userFacingMessage(error: Exception): String =
        when (error) {
            is RecognitionUserException -> error.message?.takeIf { it.isNotBlank() } ?: getString(Res.string.recognition_failed)
            is IOException -> getString(Res.string.recognition_network_error)
            else -> getString(Res.string.recognition_failed)
        }

    @SuppressLint("MissingPermission")
    private suspend fun recordAudio(): ByteArray =
        withContext(Dispatchers.IO) {
            val minBuffer = AudioRecord.getMinBufferSize(INPUT_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = minBuffer.coerceAtLeast(INPUT_SAMPLE_RATE / 2)
            val recorder =
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    INPUT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            val output = ByteArrayOutputStream(INPUT_SAMPLE_RATE * 2 * 12)
            val buffer = ByteArray(bufferSize)
            val started = SystemClock.elapsedRealtime()
            try {
                recorder.startRecording()
                while (SystemClock.elapsedRealtime() - started < RECORDING_DURATION_MS && coroutineContext.isActive && !cancelRequested) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    check(read > 0) { "AudioRecord.read returned $read" }
                    output.write(buffer, 0, read)
                    _audioLevel.value = computeAudioLevel(buffer, read)
                }
            } finally {
                _audioLevel.value = 0f
                runCatching { recorder.stop() }
                recorder.release()
            }
            output.toByteArray()
        }

    private fun computeAudioLevel(
        buffer: ByteArray,
        read: Int,
    ): Float {
        val sampleCount = read / 2
        if (sampleCount == 0) return 0f
        val samples = ByteBuffer.wrap(buffer, 0, read).order(ByteOrder.LITTLE_ENDIAN)
        var sumSquares = 0.0
        repeat(sampleCount) {
            val sample = samples.short.toDouble()
            sumSquares += sample * sample
        }
        val rms = sqrt(sumSquares / sampleCount) / 32768.0
        return (sqrt(rms) * 1.6).coerceIn(0.0, 1.0).toFloat()
    }

    internal suspend fun resolveYoutubeVideoId(result: RecognitionResult): String? {
        val query = listOf(result.title, result.artist).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null
        return try {
            for (filter in listOf(SearchFilter.SONG, SearchFilter.VIDEO)) {
                GlobalYouTubeRepository.instance
                    .searchFiltered(query, filter)
                    .items
                    .filterIsInstance<SongItem>()
                    .firstOrNull { it.matchesRecognition(result) }
                    ?.let { return it.id }
            }
            GlobalYouTubeRepository.instance
                .search(query, recordSearchHistory = false)
                .filterIsInstance<SongItem>()
                .firstOrNull { it.matchesRecognition(result) }
                ?.id
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.tag(LOG_TAG).w(error, "Recognized song playback lookup failed")
            null
        }
    }

    private fun resample16BitMono(
        input: ByteArray,
        inputRate: Int,
        outputRate: Int,
    ): ByteArray {
        if (inputRate == outputRate) return input
        val inputSamples = ShortArray(input.size / 2)
        ByteBuffer
            .wrap(input)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(inputSamples)
        val ratio = outputRate.toDouble() / inputRate.toDouble()
        val outputSamples = ShortArray((inputSamples.size * ratio).toInt())
        for (index in outputSamples.indices) {
            val source = index / ratio
            val base = source.toInt().coerceIn(0, inputSamples.lastIndex)
            val next = (base + 1).coerceAtMost(inputSamples.lastIndex)
            val fraction = source - base
            outputSamples[index] = (inputSamples[base] * (1.0 - fraction) + inputSamples[next] * fraction).toInt().toShort()
        }
        return ByteArray(outputSamples.size * 2).also {
            ByteBuffer
                .wrap(it)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .put(outputSamples)
        }
    }
}
