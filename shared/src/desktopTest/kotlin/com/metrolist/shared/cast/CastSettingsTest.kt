package com.metrolist.shared.cast

import androidx.compose.runtime.snapshots.Snapshot
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class CastSettingsTest {
    @Test
    fun disabledCastNeverCreatesDiscoveryOrConnectsAnyProtocol() =
        runBlocking {
            val settings = Settings(MapSettings()).apply { castEnabled = false }
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            var discoveryCreations = 0
            val manager =
                JvmCastManager(
                    scope = scope,
                    discoveryFactory = {
                        discoveryCreations++
                        error("Disabled discovery was initialized")
                    },
                    localAddresses = { error("Disabled discovery queried network interfaces") },
                    appVersion = "test",
                    settings = settings,
                )
            try {
                manager.startDiscovery()
                val media =
                    CastMedia(
                        "test",
                        null,
                        PlaybackResource(
                            "id",
                            "https://example.invalid/audio",
                            emptyMap(),
                            null,
                            Instant.DISTANT_FUTURE,
                        ),
                        1000L,
                    )
                CastProtocol.entries.forEach { protocol ->
                    assertFalse(manager.cast(CastDevice("id", "test", protocol, "invalid", 0), media, 0L, true, 1f))
                }
                assertEquals(0, discoveryCreations)
                assertEquals(CastState(), manager.state.value)
                assertTrue(manager.devices.value.isEmpty())
            } finally {
                manager.release()
                scope.cancel()
            }
        }

    @Test
    fun restartingDiscoveryDropsOldDevicesAndRetainsPendingErrors() =
        runBlocking {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            var found: ((CastDevice) -> Unit)? = null
            val discovery =
                object : MdnsCastDiscovery {
                    override fun start(
                        onDeviceFound: (CastDevice) -> Unit,
                        onDeviceLost: (String) -> Unit,
                        onError: (CastError) -> Unit,
                    ) {
                        found = onDeviceFound
                        onDeviceFound(CastDevice("new", "New", CastProtocol.FCAST, "invalid", 0))
                    }

                    override fun stop() = Unit
                }
            val manager = JvmCastManager(scope, { discovery }, { emptyList() }, "test", Settings(MapSettings()))
            try {
                manager.showError(CastError.UNSUPPORTED_STREAM)
                manager.startDiscovery()
                withTimeout(5_000L) { manager.devices.first { it.isNotEmpty() } }
                found!!(CastDevice("old", "Old", CastProtocol.GOOGLE_CAST, "invalid", 0))
                assertEquals(2, manager.devices.value.size)
                val oldCallback = checkNotNull(found)
                manager.stopDiscovery()
                found(CastDevice("late", "Late", CastProtocol.FCAST, "invalid", 0))
                assertTrue(manager.devices.value.isEmpty())
                manager.startDiscovery()
                withTimeout(5_000L) { manager.devices.first { it.isNotEmpty() } }
                oldCallback(CastDevice("stale", "Stale", CastProtocol.FCAST, "invalid", 0))
                assertEquals(listOf("new"), manager.devices.value.map { it.id })
                assertEquals(CastError.UNSUPPORTED_STREAM, manager.state.value.error)
            } finally {
                manager.release()
                scope.cancel()
            }
        }

    @Test
    fun dlnaRoundsRemoveMissingReceiversWithoutRemovingMdnsDevices() {
        val google = CastDevice("cast", "Cast", CastProtocol.GOOGLE_CAST, "host", 8009)
        val old = CastDevice("old", "Old", CastProtocol.SONOS, "host", 1400)
        val replacement = old.copy(id = "new", name = "New", protocol = CastProtocol.DLNA)
        val devices = linkedMapOf(google.id to google, old.id to old)
        devices.replaceDlnaDevices(listOf(replacement))
        assertEquals(listOf(google, replacement), devices.values.toList())
        devices.replaceDlnaDevices(emptyList())
        assertEquals(listOf(google), devices.values.toList())
    }

    @Test
    fun disablingStopsDiscoveryClearsDevicesAndAllowsLaterReenable() =
        runBlocking {
            val settings = Settings(MapSettings())
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            var started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val discovery =
                object : MdnsCastDiscovery {
                    override fun start(
                        onDeviceFound: (CastDevice) -> Unit,
                        onDeviceLost: (String) -> Unit,
                        onError: (CastError) -> Unit,
                    ) {
                        onDeviceFound(CastDevice("id", "test", CastProtocol.FCAST, "invalid", 0))
                        started.complete(Unit)
                    }

                    override fun stop() {
                        stopped.complete(Unit)
                    }
                }
            val manager = JvmCastManager(scope, { discovery }, { emptyList() }, "test", settings)
            try {
                manager.startDiscovery()
                withTimeout(5000) { started.await() }
                assertEquals(1, manager.devices.value.size)
                settings.castEnabled = false
                Snapshot.sendApplyNotifications()
                withTimeout(5000) { stopped.await() }
                withTimeout(5000) {
                    manager.devices.first { it.isEmpty() }
                }
                manager.startDiscovery()
                assertTrue(manager.devices.value.isEmpty())
                settings.castEnabled = true
                Snapshot.sendApplyNotifications()
                started = CompletableDeferred()
                manager.startDiscovery()
                withTimeout(5000) { started.await() }
                assertEquals(1, manager.devices.value.size)
            } finally {
                manager.release()
                scope.cancel()
            }
        }
}
