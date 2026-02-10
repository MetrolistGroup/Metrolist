/**
 * Music Recognition Feature
 * 
 * This feature is based on the original MusicRecognizer project by Aleksey Saenko.
 * Original project: https://github.com/aleksey-saenko/MusicRecognizer
 * 
 * Special thanks to Aleksey Saenko for the music recognition implementation.
 */

package com.metrolist.music.recognition

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.metrolist.shazamkit.Shazam
import com.metrolist.shazamkit.models.RecognitionResult
import com.metrolist.shazamkit.models.RecognitionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder

/**
 * Service for recognizing music using audio fingerprinting.
 * Records audio from the microphone, generates a Shazam-compatible fingerprint,
 * and sends it to the Shazam API for recognition.
 */
object MusicRecognitionService {
    
    // Recording parameters
    private const val RECORDING_SAMPLE_RATE = 44100
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    // Recording duration: 10 seconds for better recognition accuracy
    // Original MusicRecognizer uses: 3s -> 6s -> 9s -> 10s fallback
    // We use 10s directly to match the fallback duration for maximum compatibility
    private const val RECORDING_DURATION_MS = 10000L
    
    private val _recognitionStatus = MutableStateFlow<RecognitionStatus>(RecognitionStatus.Ready)
    val recognitionStatus: StateFlow<RecognitionStatus> = _recognitionStatus.asStateFlow()
    
    fun hasRecordPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Start the music recognition process.
     * Records audio, generates fingerprint, and queries Shazam API.
     */
    @SuppressLint("MissingPermission")
    suspend fun recognize(context: Context): RecognitionStatus = withContext(Dispatchers.IO) {
        if (!hasRecordPermission(context)) {
            return@withContext RecognitionStatus.Error("Microphone permission not granted")
        }
        
        _recognitionStatus.value = RecognitionStatus.Listening
        
        try {
            // Step 1: Record audio
            val audioData = recordAudio()
            
            _recognitionStatus.value = RecognitionStatus.Processing
            
            // Step 2: Convert to mono if needed and resample to 16kHz
            val decodedAudio = DecodedAudio(
                data = audioData,
                channelCount = 1,
                sampleRate = RECORDING_SAMPLE_RATE,
                pcmEncoding = AUDIO_FORMAT
            )
            
            val resampledAudio = AudioResampler.resample(
                decodedAudio, 
                VibraSignature.REQUIRED_SAMPLE_RATE
            ).getOrElse { error ->
                _recognitionStatus.value = RecognitionStatus.Error("Failed to resample audio: ${error.message}")
                return@withContext _recognitionStatus.value
            }
            
            // Verify format
            require(
                resampledAudio.channelCount == 1 &&
                resampledAudio.sampleRate == VibraSignature.REQUIRED_SAMPLE_RATE &&
                resampledAudio.pcmEncoding == AudioFormat.ENCODING_PCM_16BIT &&
                ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN &&
                resampledAudio.data.isNotEmpty() && 
                resampledAudio.data.size % 2 == 0
            ) { "Invalid audio format for fingerprint generation" }
            
            // Step 3: Generate fingerprint using native library
            val signature = try {
                VibraSignature.fromI16(resampledAudio.data)
            } catch (e: Exception) {
                _recognitionStatus.value = RecognitionStatus.Error("Failed to generate fingerprint: ${e.message}")
                return@withContext _recognitionStatus.value
            }
            
            // Step 4: Send to Shazam API
            val sampleDurationMs = (resampledAudio.data.size / 2) * 1000L / VibraSignature.REQUIRED_SAMPLE_RATE
            
            val result = Shazam.recognize(signature, sampleDurationMs)
            
            result.fold(
                onSuccess = { recognitionResult ->
                    _recognitionStatus.value = RecognitionStatus.Success(recognitionResult)
                },
                onFailure = { error ->
                    val message = error.message ?: "Unknown error"
                    _recognitionStatus.value = if (message.contains("No match", ignoreCase = true)) {
                        RecognitionStatus.NoMatch("No matches found. Try again with clearer audio.")
                    } else {
                        RecognitionStatus.Error(message)
                    }
                }
            )
            
            _recognitionStatus.value
        } catch (e: Exception) {
            _recognitionStatus.value = RecognitionStatus.Error(e.message ?: "Recognition failed")
            _recognitionStatus.value
        }
    }
    
    @SuppressLint("MissingPermission")
    private suspend fun recordAudio(): ByteArray = withContext(Dispatchers.IO) {
        val bufferSize = AudioRecord.getMinBufferSize(
            RECORDING_SAMPLE_RATE, 
            CHANNEL_CONFIG, 
            AUDIO_FORMAT
        )
        
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            RECORDING_SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
        
        val outputStream = ByteArrayOutputStream()
        val buffer = ByteArray(bufferSize)
        val startTime = System.currentTimeMillis()
        
        try {
            audioRecord.startRecording()
            
            while (System.currentTimeMillis() - startTime < RECORDING_DURATION_MS && isActive) {
                val bytesRead = audioRecord.read(buffer, 0, bufferSize)
                if (bytesRead > 0) {
                    outputStream.write(buffer, 0, bytesRead)
                }
            }
        } finally {
            audioRecord.stop()
            audioRecord.release()
        }
        
        outputStream.toByteArray()
    }
    
    fun reset() {
        _recognitionStatus.value = RecognitionStatus.Ready
    }
}
