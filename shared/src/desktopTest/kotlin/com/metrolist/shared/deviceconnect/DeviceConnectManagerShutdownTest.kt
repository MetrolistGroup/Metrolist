package com.metrolist.shared.deviceconnect

import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DeviceConnectManagerShutdownTest {
    @Test
    fun previouslyEnabledInstallationRestoresWithoutDevtoolsAndNormalDisableStopsIt() =
        runBlocking {
            val backing =
                MapSettings().apply {
                    putBoolean("deviceConnectEnabled", true)
                    putBoolean("deviceConnectExperimentalEnabled", false)
                }
            val settings = Settings(backing)
            val manager = testManager(settings)
            try {
                assertFalse(settings.devtoolsUnlocked)
                manager.restoreIfEnabled(Unit)
                assertTrue(manager.state.value.serverRunning)
                val playback = ShutdownPlaybackPort()
                manager.remote.attach(playback)

                settings.setDevtoolsEnabled(true)
                settings.setDevtoolsEnabled(false)
                assertTrue(manager.state.value.serverRunning)
                assertTrue(settings.deviceConnectEnabled)

                manager.disable()
                withTimeout(5.seconds) { manager.state.first { !it.serverRunning } }
                assertTrue(playback.state.playing)
                assertFalse(settings.deviceConnectEnabled)
                assertFalse(manager.remote.isActive)
                assertFalse(manager.remote.intercept(DcPlaybackCommand(DcPlaybackAction.PAUSE)))
                assertTrue(
                    manager.state.value.pairingRequests
                        .isEmpty(),
                )
            } finally {
                manager.release()
            }
        }

    @Test
    fun disablingDeviceConnectPreservesLocalPlaybackAndReleasesControls() =
        runBlocking {
            val settings = Settings(MapSettings())
            val manager = testManager(settings)
            val playback = ShutdownPlaybackPort()
            try {
                manager.enable(Unit)
                manager.remote.attach(playback)

                manager.disable()
                withTimeout(5.seconds) { manager.state.first { !it.enabled } }

                assertTrue(playback.state.playing)
                assertFalse(manager.remote.isActive)
                assertFalse(manager.remote.intercept(DcPlaybackCommand(DcPlaybackAction.PAUSE)))
            } finally {
                manager.release()
            }
        }

    @Test
    fun renamingEnabledDeviceRestartsDiscoveryWithTheLatestName() =
        runBlocking {
            val settings = Settings(MapSettings())
            val manager =
                DeviceConnectManager(
                    settings = settings,
                    identityProvider = ::testIdentity,
                    localInfoProvider = {
                        DeviceInfoPayload(
                            "local-device",
                            settings.deviceConnectDeviceName.ifBlank { "Local" },
                            "Test",
                        )
                    },
                )
            try {
                manager.enable(Unit)
                withTimeout(5.seconds) {
                    manager.state.first { state ->
                        state.log.count { it.message == "Service discovery started." } == 1
                    }
                }

                manager.updateDeviceName("Renamed")

                withTimeout(5.seconds) {
                    manager.state.first { state ->
                        state.localDeviceName == "Renamed" &&
                            state.log.count { it.message == "Service discovery started." } == 2
                    }
                }
                Unit
            } finally {
                manager.release()
            }
        }

    @Test
    fun renamingDuringStartupRestartsDiscoveryWithLatestName() =
        runBlocking {
            val settings = Settings(MapSettings())
            val startupCaptured = CountDownLatch(1)
            val resumeStartup = CountDownLatch(1)
            val manager =
                DeviceConnectManager(
                    settings = settings,
                    identityProvider = ::testIdentity,
                    localInfoProvider = {
                        val info =
                            DeviceInfoPayload(
                                "local-device",
                                settings.deviceConnectDeviceName.ifBlank { "Local" },
                                "Test",
                            )
                        if (startupCaptured.count > 0) {
                            startupCaptured.countDown()
                            check(resumeStartup.await(5, TimeUnit.SECONDS))
                        }
                        info
                    },
                )
            val startup = async(Dispatchers.Default) { manager.enable(Unit) }
            try {
                assertTrue(startupCaptured.await(5, TimeUnit.SECONDS))
                manager.updateDeviceName("Renamed")
                resumeStartup.countDown()
                startup.await()

                withTimeout(5.seconds) {
                    manager.state.first { state ->
                        state.localDeviceName == "Renamed" &&
                            state.log.count { it.message == "Service discovery started." } == 2
                    }
                }
                Unit
            } finally {
                resumeStartup.countDown()
                startup.cancel()
                manager.release()
            }
        }

    @Test
    fun networkAddressChangeRebindsDiscovery() =
        runBlocking {
            val previousNetwork = PlaybackNetworkStateStore.current
            val settings = Settings(MapSettings())
            val addresses = AtomicReference(setOf("192.168.1.20"))
            val manager =
                DeviceConnectManager(
                    settings = settings,
                    identityProvider = ::testIdentity,
                    localInfoProvider = { DeviceInfoPayload("local-device", "Local", "Test") },
                    localAddressProvider = addresses::get,
                )
            try {
                manager.enable(Unit)
                assertTrue(manager.state.value.localAddress in addresses.get())

                addresses.set(setOf("192.168.1.20", "192.168.2.20"))
                PlaybackNetworkStateStore.publish(PlaybackNetworkState("changed-addresses"))
                withTimeout(12.seconds) {
                    manager.state.first { state ->
                        state.localAddress in addresses.get() &&
                            state.log.count { it.message == "Service discovery started." } >= 2
                    }
                }
                Unit
            } finally {
                manager.release()
                PlaybackNetworkStateStore.publish(previousNetwork)
            }
        }

    @Test
    fun desiredSettingControlsPermissionRestoreAndReleasePreservesIt() =
        runBlocking {
            val settings = Settings(MapSettings())
            val manager = testManager(settings)
            var released = false
            try {
                manager.restoreIfEnabled(Unit, canUseLocalNetwork = false)
                assertFalse(manager.state.value.requiresPermission)

                settings.deviceConnectEnabled = true
                manager.restoreIfEnabled(Unit, canUseLocalNetwork = false)
                assertTrue(manager.state.value.requiresPermission)
                assertFalse(manager.state.value.enabled)

                manager.disable()
                assertFalse(settings.deviceConnectEnabled)

                settings.deviceConnectEnabled = true
                manager.release()
                released = true
                assertTrue(settings.deviceConnectEnabled)
            } finally {
                if (!released) manager.release()
            }
        }

    @Test
    fun restoreStartsOnlyWhenSaved() =
        runBlocking {
            val settings = Settings(MapSettings())
            val manager = testManager(settings)
            try {
                manager.restoreIfEnabled(Unit)
                assertFalse(manager.state.value.serverRunning)

                settings.deviceConnectEnabled = true
                manager.restoreIfEnabled(Unit)
                assertTrue(manager.state.value.enabled)
                assertTrue(manager.state.value.serverRunning)
            } finally {
                manager.release()
            }
        }

    @Test
    fun discoveryFailureIsVisibleAndClearedOnShutdown() =
        runBlocking {
            val manager = testManager(Settings(MapSettings()))
            try {
                manager.enable(null)
                assertTrue(manager.state.value.serverRunning)
                assertTrue(manager.state.value.discoveryFailed)
                manager.disable()
                withTimeout(5.seconds) { manager.state.first { !it.serverRunning } }
                assertFalse(manager.state.value.discoveryFailed)
            } finally {
                manager.release()
            }
        }

    @Test
    fun bindFailureDoesNotPublishRunningState() =
        runBlocking {
            val settings = Settings(MapSettings())
            ServerSocket(0).use { occupied ->
                val manager =
                    DeviceConnectManager(
                        settings = settings,
                        identityProvider = ::testIdentity,
                        localInfoProvider = { DeviceInfoPayload("local-device", "Local", "Test") },
                        requestedServerPort = occupied.localPort,
                    )
                try {
                    manager.enable(Unit)
                    assertTrue(settings.deviceConnectEnabled)
                    assertFalse(manager.state.value.enabled)
                    assertFalse(manager.state.value.serverRunning)
                    assertTrue(manager.state.value.serverFailed)
                    occupied.close()
                    manager.enable(Unit)
                    assertTrue(manager.state.value.serverRunning)
                    assertFalse(manager.state.value.serverFailed)
                } finally {
                    manager.release()
                }
            }
        }

    @Test
    fun startupFailureKeepsEnabledIntent() =
        runBlocking {
            val settings = Settings(MapSettings())
            val manager =
                DeviceConnectManager(
                    settings = settings,
                    identityProvider = ::testIdentity,
                    localInfoProvider = {
                        assertTrue(settings.deviceConnectEnabled)
                        error("forced startup failure")
                    },
                )
            try {
                assertFailsWith<IllegalStateException> { manager.enable(Unit) }
                assertTrue(settings.deviceConnectEnabled)
                assertFalse(manager.state.value.enabled)
                assertFalse(manager.state.value.serverRunning)
            } finally {
                manager.release()
            }
        }
}

private fun testManager(settings: Settings) =
    DeviceConnectManager(
        settings = settings,
        identityProvider = ::testIdentity,
        localInfoProvider = { DeviceInfoPayload("local-device", "Local", "Test") },
    )

private fun testIdentity() = AccountIdentitySnapshot("", "", anonymous = true, resolved = true)

private class ShutdownPlaybackPort : DeviceConnectPlaybackPort {
    var state = DcLocalPlaybackState(playing = true)

    override suspend fun snapshot(): DcLocalPlaybackState = state

    override suspend fun apply(
        snapshot: DcPlaybackSnapshot,
        positionMs: Long,
        shouldCommit: () -> Boolean,
    ) {
        if (shouldCommit()) state = snapshot.playback.copy(positionMs = positionMs)
    }

    private var controlling = false

    override suspend fun setControllerPlayback(playback: DcLocalPlaybackState?) {
        if (playback != null || controlling) state = playback ?: state.copy(playing = false)
        controlling = playback != null
    }

    override suspend fun setPlaying(playing: Boolean) {
        state = state.copy(playing = playing)
    }

    override suspend fun seekTo(positionMs: Long) {
        state = state.copy(positionMs = positionMs)
    }

    override suspend fun setSpeed(speed: Float) {
        state = state.copy(speed = speed)
    }

    override suspend fun setVolume(volume: Float) {
        state = state.copy(volume = volume)
    }

    override suspend fun setAudioOutputEnabled(enabled: Boolean) = Unit
}
