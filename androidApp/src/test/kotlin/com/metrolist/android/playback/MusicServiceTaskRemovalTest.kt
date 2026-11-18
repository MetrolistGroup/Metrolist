package com.metrolist.android.playback

import android.app.Application
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.benchmark.PlaybackBenchmarkRunner
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class MusicServiceTaskRemovalTest {
    private class ControlledPlayer(
        private val playback: Int,
    ) : SimpleBasePlayer(Looper.getMainLooper()) {
        var requested = true
        var pauses = 0

        override fun getState(): State =
            State
                .Builder()
                .setAvailableCommands(
                    Player.Commands
                        .Builder()
                        .add(Player.COMMAND_PLAY_PAUSE)
                        .build(),
                ).setPlaylist(listOf(MediaItemData.Builder("song").build()))
                .setPlayWhenReady(requested, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(playback)
                .build()

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            requested = playWhenReady
            if (!playWhenReady) pauses++
            return Futures.immediateVoidFuture()
        }
    }

    @Test
    fun disabledTaskClearKeepsReadyAndBufferingSessions() =
        runTest {
            for (state in listOf(Player.STATE_READY, Player.STATE_BUFFERING)) {
                withSession(false, state) { service, sessionPlayer, _ ->
                    service.onTaskRemoved(null)
                    runCurrent()
                    advanceTimeBy(3_600_000)
                    runCurrent()
                    assertFalse(shadowOf(service).isStoppedBySelf)
                    assertEquals(0, sessionPlayer.pauses)
                }
            }
        }

    @Test
    fun disabledTaskClearKeepsLoadingDemandBeforeForegroundPromotion() =
        runTest {
            withSession(false, Player.STATE_BUFFERING, foreground = false) { service, sessionPlayer, _ ->
                service.onTaskRemoved(null)
                runCurrent()
                advanceTimeBy(3_600_000)
                runCurrent()
                assertFalse(shadowOf(service).isStoppedBySelf)
                assertEquals(0, sessionPlayer.pauses)
            }
        }

    @Test
    fun enabledTaskClearStopsReadyAndBufferingSessions() =
        runTest {
            for (state in listOf(Player.STATE_READY, Player.STATE_BUFFERING)) {
                withSession(true, state) { service, sessionPlayer, _ ->
                    service.onTaskRemoved(null)
                    runCurrent()
                    assertTrue(shadowOf(service).isStoppedBySelf)
                    assertEquals(1, sessionPlayer.pauses)
                }
            }
        }

    @Test
    fun disabledTaskClearKeepsPausedSessionForGracePeriod() =
        runTest {
            withSession(false, Player.STATE_READY, requested = false) { service, _, _ ->
                service.onTaskRemoved(null)
                runCurrent()
                advanceTimeBy(59_999)
                runCurrent()
                assertFalse(shadowOf(service).isStoppedBySelf)
                advanceTimeBy(1)
                runCurrent()
                assertTrue(shadowOf(service).isStoppedBySelf)
            }
        }

    @Test
    fun resumedDemandCancelsTaskClearGracePeriod() =
        runTest {
            withSession(false, Player.STATE_READY, requested = false) { service, _, required ->
                service.onTaskRemoved(null)
                runCurrent()
                advanceTimeBy(30_000)
                required.value = true
                runCurrent()
                advanceTimeBy(3_600_000)
                runCurrent()
                assertFalse(shadowOf(service).isStoppedBySelf)
            }
        }

    // Real service callback and Media3 session; only demand and notification foreground state are controlled.
    // The full service dependency graph is deliberately not started.
    private suspend fun TestScope.withSession(
        setting: Boolean,
        state: Int,
        requested: Boolean = true,
        foreground: Boolean = true,
        block: suspend TestScope.(MusicService, ControlledPlayer, MutableStateFlow<Boolean>) -> Unit,
    ) {
        val previous = Settings.sharedOverrideForTest()
        Settings.replaceSharedForTest(Settings(MapSettings()))
        Settings.shared.stopMusicOnTaskClear = setting
        val service = Robolectric.buildService(MusicService::class.java).get()
        val internal = ExoPlaybackPlayer(service)
        val sessionPlayer = ControlledPlayer(state).apply { this.requested = requested }
        val session =
            MediaLibrarySession
                .Builder(service, sessionPlayer, object : MediaLibrarySession.Callback {})
                .setId("$setting-$state-$requested")
                .build()
        val required = MutableStateFlow(requested)
        val removed = ReflectionHelpers.getField<MutableStateFlow<Boolean>>(service, "taskRemoved")
        val lifetime =
            backgroundScope.launch {
                observePlaybackServiceLifetime(removed, required, MutableStateFlow(setting)) {
                    service.pauseAllPlayersAndStopSelf()
                }
            }
        try {
            ReflectionHelpers.setField(service, "player", internal)
            ReflectionHelpers.setField(service, "playbackBenchmark\$delegate", lazyOf(PlaybackBenchmarkRunner({ error("unused") })))
            service.addSession(session)
            val manager = ReflectionHelpers.callInstanceMethod<Any>(service, "getMediaNotificationManager")
            ReflectionHelpers.setField(manager, "startedInForeground", foreground)
            runCurrent()
            block(service, sessionPlayer, required)
        } finally {
            lifetime.cancel()
            service.removeSession(session)
            session.release()
            sessionPlayer.release()
            internal.releaseAndAwaitScope()
            for (field in listOf("serviceScope", "podcastWriterScope")) {
                ReflectionHelpers.getField<CoroutineScope>(service, field).cancel()
            }
            Settings.replaceSharedForTest(previous)
        }
    }
}
