package com.metrolist.android.playback

import android.app.Application
import androidx.media3.common.Player
import com.metrolist.android.ui.screens.applyPlaybackFloatSetting
import com.metrolist.shared.playback.PauseOnMuteController
import com.metrolist.shared.playback.SleepTimerController
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.settings.SettingsChangeEffect
import com.metrolist.shared.ui.settings.SettingsPlatform
import com.metrolist.shared.ui.settings.SharedSettingsHost
import com.metrolist.shared.youtube.benchmark.PlaybackBenchmarkRunner
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class MusicServicePlaybackTest {
    @Before
    fun isolateSettings() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @After
    fun restoreSettings() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun varispeedSettingsCallbackAppliesLinkedPitchToConnectedPlayback() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val service = Robolectric.buildService(MusicService::class.java).get()
            val player = ExoPlaybackPlayer(service)
            try {
                ReflectionHelpers.setField(service, "player", player)
                val connection = PlayerConnection(service, backgroundScope)
                ReflectionHelpers.setField(connection, "musicService", service)
                val host =
                    SharedSettingsHost(
                        SettingsPlatform.ANDROID,
                        onClose = {},
                        onFloatSettingChanged = { effect, value -> applyPlaybackFloatSetting(connection, effect, value) },
                    )
                connection.setPlaybackSpeed(1.5f)
                connection.setPlaybackPitch(1f)
                Settings.shared.varispeed = true
                Settings.shared.playbackPitch = Settings.shared.playbackSpeed
                host.onFloatSettingChanged(SettingsChangeEffect.PLAYBACK_PITCH, Settings.shared.playbackPitch)
                assertEquals(1.5f, player.playbackSpeed.value, 0f)
                assertEquals(1.5f, player.playbackPitch.value, 0f)
            } finally {
                player.releaseAndAwaitScope()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun serviceRestoresSavedTempoBeforeUiAttachmentAndAttachmentPreservesActiveTempo() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val service = Robolectric.buildService(MusicService::class.java).get()
            val player = ExoPlaybackPlayer(service)
            val observationScope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
            try {
                Settings.shared.playbackSpeed = 1.5f
                Settings.shared.playbackPitch = 0.75f
                ReflectionHelpers.setField(service, "player", player)
                service.restorePlaybackParameters()
                assertEquals(1.5f, player.playbackSpeed.value, 0f)
                assertEquals(0.75f, player.playbackPitch.value, 0f)
                val session = MediaSessionPlayer(player, MutableStateFlow(Player.REPEAT_MODE_OFF), scope = backgroundScope)
                assertEquals(1.5f, session.playbackParameters.speed, 0f)
                assertEquals(0.75f, session.playbackParameters.pitch, 0f)
                player.setTempoPitch(1.25f, 1.1f)
                val connection = PlayerConnection(service, backgroundScope)
                ReflectionHelpers.setField(connection, "observationScope", observationScope)
                ReflectionHelpers.callInstanceMethod<Unit>(
                    connection,
                    "observeService",
                    ReflectionHelpers.ClassParameter.from(MusicService::class.java, service),
                )
                assertEquals(1.25f, player.playbackSpeed.value, 0f)
                assertEquals(1.1f, player.playbackPitch.value, 0f)
                session.release()
            } finally {
                observationScope.cancel()
                player.releaseAndAwaitScope()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun sleepTimerPauseCancelsMuteResumeThroughTheServiceCommandCallback() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val service = Robolectric.buildService(MusicService::class.java).get()
            ReflectionHelpers.setField(service, "playbackBenchmark\$delegate", lazyOf(PlaybackBenchmarkRunner({ error("No benchmark") })))
            val player = ExoPlaybackPlayer(service, onPlaybackCommand = service::onPlaybackCommand)
            val session = MediaSessionPlayer(player, MutableStateFlow(Player.REPEAT_MODE_OFF), scope = backgroundScope)
            try {
                val mute = ReflectionHelpers.getField<PauseOnMuteController>(service, "pauseOnMute")
                var resumes = 0

                fun volume(
                    muted: Boolean,
                    playing: Boolean,
                ) = mute.onVolumeChanged(muted, true, playing, player::pause) { resumes++ }
                volume(false, true)
                volume(true, true)
                assertTrue(mute.isPausedByMute.value)
                volume(false, false)
                assertTrue(resumes == 1)
                volume(true, true)
                assertTrue(mute.isPausedByMute.value)
                val timer =
                    SleepTimerController(
                        backgroundScope,
                        MusicSleepTimerPlayer(player, session::pause) {},
                        elapsedRealtimeMs = { testScheduler.currentTime },
                    )
                timer.start(1_000L, false)
                runCurrent()
                advanceTimeBy(1_000L)
                runCurrent()
                assertFalse(timer.state.value.isActive)
                assertFalse(mute.isPausedByMute.value)
                volume(false, false)
                assertTrue(resumes == 1)
            } finally {
                session.release()
                player.releaseAndAwaitScope()
                Dispatchers.resetMain()
            }
        }
}
