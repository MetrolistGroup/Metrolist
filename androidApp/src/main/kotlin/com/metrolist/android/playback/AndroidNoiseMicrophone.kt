/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.playback

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import com.metrolist.android.App
import com.metrolist.shared.equalizer.NoiseMicrophone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/** Captures the local microphone for feed-forward noise reduction; no sample is sent anywhere. */
internal object AndroidNoiseMicrophone : NoiseMicrophone {
    @Volatile private var permissionLauncher: ActivityResultLauncher<String>? = null

    @Volatile private var job: Job? = null

    fun attach(launcher: ActivityResultLauncher<String>) {
        permissionLauncher = launcher
    }

    override val isSupported: Boolean
        get() = App.instance.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(App.instance, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun requestPermission() {
        if (!hasPermission()) permissionLauncher?.launch(Manifest.permission.RECORD_AUDIO)
    }

    @SuppressLint("MissingPermission")
    override fun start(
        sampleRate: Int,
        onSamples: (ShortArray, Int, Int) -> Unit,
    ): Boolean {
        if (job?.isActive == true) return true
        if (!hasPermission()) return false
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val buffer = ShortArray(max(minBuffer, sampleRate / 50) / 2)
        val recorder =
            try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    buffer.size * 2,
                )
            } catch (_: Exception) {
                return false
            }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        job =
            scope.launch {
                try {
                    recorder.startRecording()
                    while (isActive) {
                        val read =
                            recorder.read(
                                buffer,
                                0,
                                buffer.size,
                                AudioRecord.READ_BLOCKING,
                            )
                        if (read <= 0) break
                        onSamples(buffer, read, recorder.sampleRate)
                    }
                } catch (_: Exception) {
                    // Capture ended; the recorder is released below.
                } finally {
                    runCatching { recorder.stop() }
                    recorder.release()
                    scope.cancel()
                }
            }
        return true
    }

    override fun stop() {
        job?.cancel()
        job = null
    }
}
