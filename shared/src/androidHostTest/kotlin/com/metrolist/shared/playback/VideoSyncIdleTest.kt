package com.metrolist.shared.playback

import android.app.Application
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.shared.youtube.playback.AndroidContextProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class VideoSyncIdleTest {
    @Test
    fun pausedLoadingAndFinishedVideoDoNotKeepSamplingTheAudioClock() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val originalContext = ReflectionHelpers.getStaticField<Context?>(AndroidContextProvider::class.java, "applicationContext")
            AndroidContextProvider.applicationContext = RuntimeEnvironment.getApplication()
            var requested = false
            var state = Player.STATE_READY
            var samples = 0
            val backend =
                Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
                    when (method.name) {
                        "setPlayWhenReady" -> {
                            requested = args!![0] as Boolean
                            Unit
                        }

                        "getPlayWhenReady" -> {
                            requested
                        }

                        "getPlaybackState" -> {
                            state
                        }

                        "isPlaying" -> {
                            requested && state == Player.STATE_READY
                        }

                        "getCurrentPosition" -> {
                            0L
                        }

                        "play" -> {
                            requested = true
                            Unit
                        }

                        "pause" -> {
                            requested = false
                            Unit
                        }

                        "setPlaybackSpeed", "release" -> {
                            Unit
                        }

                        else -> {
                            error("Unexpected video call: ${method.name}")
                        }
                    }
                } as ExoPlayer
            val video = VideoPlayer({}, {}, null, { _, _ -> }, {}, false)
            ReflectionHelpers.setField(video, "exoPlayer", backend)
            ReflectionHelpers.setField(video, "positionProvider", {
                samples++
                0L
            })
            try {
                video.play(startPaused = true)
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(0, samples)
                video.resume()
                runCurrent()
                assertTrue(samples > 0)
                video.pause()
                val pausedSamples = samples
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(pausedSamples, samples)
                video.resume()
                runCurrent()
                state = Player.STATE_ENDED
                advanceTimeBy(50)
                runCurrent()
                val endedSamples = samples
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(endedSamples, samples)
                state = Player.STATE_IDLE
                video.play(startPaused = false)
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(endedSamples, samples)
            } finally {
                video.close()
                ReflectionHelpers.setStaticField(AndroidContextProvider::class.java, "applicationContext", originalContext)
                Dispatchers.resetMain()
            }
        }
}
