package com.metrolist.shared.cast

import androidx.compose.runtime.snapshots.Snapshot
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueEntry
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DlnaSessionTest {
    @Test
    fun managerCarriesQueueIdentityAcrossReplacementAndCheckpointsExplicitSeeks() =
        runBlocking {
            Receiver().use { receiver ->
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val manager = manager(scope, Settings(MapSettings()))
                val song = SongItem("song", "Song", null, emptyList(), null)
                val first = MusicQueueEntry(1L, song)
                val second = MusicQueueEntry(2L, song)
                try {
                    assertTrue(manager.cast(receiver.device, media.copy(queueEntry = first), 1_000L, true, 1f))
                    assertEquals(first, manager.state.value.queueEntry)
                    assertEquals(media.resource.musicVideoId ?: media.resource.videoId, manager.state.value.mediaVideoId)
                    assertTrue(manager.replaceMedia(media.copy(queueEntry = second), 2_000L, false, 1f))
                    assertEquals(second, manager.state.value.queueEntry)
                    val generation = manager.state.value.seekGeneration
                    manager.seekTo(60_000L)
                    assertEquals(60_000L, manager.state.value.positionMs)
                    assertEquals(generation + 1L, manager.state.value.seekGeneration)
                    assertEquals(second, manager.state.value.queueEntry)
                } finally {
                    manager.release()
                    scope.cancel()
                }
            }
        }

    @Test
    fun loadingPreservesDlnaReceiverVolumeUntilExplicitAdjustment() = verifyReceiverVolume(CastProtocol.DLNA)

    @Test
    fun loadingPreservesSonosReceiverVolumeUntilExplicitAdjustment() = verifyReceiverVolume(CastProtocol.SONOS)

    @Test
    fun successfulMetadataFallbackDoesNotLogTheRejectedAttempt() =
        runBlocking {
            PlaybackEventLog.clear()
            Receiver().use { receiver ->
                receiver.rejectMetadataOnce = true
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val manager = manager(scope, Settings(MapSettings()))
                try {
                    assertTrue(manager.cast(receiver.device, media, 0L, false, 1f))
                    assertEquals(2, receiver.actions.count { it == "SetAVTransportURI" })
                    assertTrue(PlaybackEventLog.events.value.isEmpty())
                } finally {
                    manager.release()
                    scope.cancel()
                    PlaybackEventLog.clear()
                }
            }
        }

    @Test
    fun terminalDlnaLoadFailureIsLoggedOnlyByTheCastManager() =
        runBlocking {
            PlaybackEventLog.clear()
            Receiver().use { receiver ->
                receiver.failSetUri = true
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val manager = manager(scope, Settings(MapSettings()))
                try {
                    assertFalse(manager.cast(receiver.device, media, 0L, false, 1f))
                    assertEquals(1, PlaybackEventLog.events.value.size)
                } finally {
                    manager.release()
                    scope.cancel()
                    PlaybackEventLog.clear()
                }
            }
        }

    private fun verifyReceiverVolume(protocol: CastProtocol) =
        runBlocking {
            Receiver(protocol).use { receiver ->
                receiver.volume = 20
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val manager = manager(scope, Settings(MapSettings()))
                try {
                    assertTrue(manager.cast(receiver.device, media, 0L, true, 1f))
                    assertEquals(20, receiver.volume)
                    assertEquals(0.2f, manager.state.value.volume)
                    assertTrue(manager.replaceMedia(media, 0L, true, 0.8f))
                    assertEquals(20, receiver.volume)
                    assertEquals(0.2f, manager.state.value.volume)
                    assertFalse("SetVolume" in receiver.actions)
                    manager.setVolume(0.5f)
                    assertEquals(50, receiver.volume)
                    assertEquals(1, receiver.actions.count { it == "SetVolume" })
                } finally {
                    manager.release()
                    scope.cancel()
                }
            }
        }

    @Test
    fun loadPreservesVolumeAndOptionalVolumeFailuresDoNotEndPlayback() =
        runBlocking {
            PlaybackEventLog.clear()
            Receiver().use { receiver ->
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val manager = manager(scope, Settings(MapSettings()))
                try {
                    assertTrue(manager.cast(receiver.device, media, 42_000L, true, 0.376f))
                    assertEquals(80, receiver.volume)
                    assertFalse("SetVolume" in receiver.actions)
                    assertEquals(0.8f, manager.state.value.volume)
                    manager.setVolume(0.376f)
                    assertEquals(37, receiver.volume)
                    withTimeout(3_000L) { manager.state.first { it.volume == 0.37f } }
                    receiver.failVolume = true
                    manager.setVolume(0.9f)
                    assertTrue(manager.state.value.isConnected)
                    val eventsAfterRequestedVolumeFailure = PlaybackEventLog.events.value.size
                    withTimeout(12_000L) {
                        receiver.failedVolumePolls.first { it >= 6 }
                    }
                    assertEquals(eventsAfterRequestedVolumeFailure, PlaybackEventLog.events.value.size)
                    assertTrue(manager.state.value.isConnected)
                    assertTrue(manager.state.value.isPlaying)
                    assertEquals(0.37f, manager.state.value.volume)
                    manager.showError(CastError.UNSUPPORTED_STREAM)
                    val polls = receiver.failedVolumePolls.value
                    withTimeout(3_000L) {
                        receiver.failedVolumePolls.first { it > polls }
                    }
                    assertEquals(CastError.UNSUPPORTED_STREAM, manager.state.value.error)
                    manager.disconnect()
                    assertEquals(CastError.UNSUPPORTED_STREAM, manager.state.value.error)
                } finally {
                    manager.release()
                    scope.cancel()
                    PlaybackEventLog.clear()
                }
            }
        }

    @Test
    fun disablingCastKeepsTheLastReceiverStateForLocalHandoff() =
        runBlocking {
            Receiver().use { receiver ->
                val settings = Settings(MapSettings())
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val manager = manager(scope, settings)
                try {
                    assertTrue(manager.cast(receiver.device, media, 42_000L, true, 0.4f))
                    val generation = manager.state.value.mediaGeneration
                    settings.castEnabled = false
                    Snapshot.sendApplyNotifications()
                    val disconnected = withTimeout(5_000L) { manager.state.first { !it.isConnected } }
                    assertEquals(42_000L, disconnected.positionMs)
                    assertTrue(disconnected.isPlaying)
                    assertEquals(generation, disconnected.mediaGeneration)
                    assertFalse(receiver.playing)
                    assertEquals(CastConnectionStatus.DISCONNECTED, disconnected.status)
                } finally {
                    manager.release()
                    scope.cancel()
                }
            }
        }

    @Test
    fun supersededHandoffStopsTheReceiverWithoutPublishingAConnection() =
        runBlocking {
            Receiver().use { receiver ->
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val manager = manager(scope, Settings(MapSettings()))
                val current = AtomicBoolean(true)
                receiver.onPlay = { current.set(false) }
                try {
                    assertFalse(manager.cast(receiver.device, media, 42_000L, true, 0.4f, current::get))
                    assertFalse(manager.state.value.isConnected)
                    assertFalse(receiver.playing)
                    assertEquals(listOf("Play", "Stop"), receiver.actions.filter { it == "Play" || it == "Stop" })
                } finally {
                    manager.release()
                    scope.cancel()
                }
            }
        }

    @Test
    fun cancelledReplacementClosesItsProxyAndAllowsAnotherReplacement() =
        runBlocking {
            Receiver().use { receiver ->
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val manager = manager(scope, Settings(MapSettings()))
                try {
                    assertTrue(manager.cast(receiver.device, media, 0L, true, 1f))
                    val replacing = CompletableDeferred<Unit>()
                    val replacement =
                        launch {
                            replacing.await()
                            manager.replaceMedia(media, 0L, true, 1f)
                        }
                    receiver.onPlay = { replacement.cancel() }
                    replacing.complete(Unit)
                    replacement.join()
                    assertTrue(replacement.isCancelled)
                    val url = URI(receiver.mediaUrl)
                    assertFails { Socket(url.host, url.port).use { } }
                    receiver.onPlay = {}
                    assertTrue(manager.replaceMedia(media, 0L, true, 1f))
                    val nextUrl = URI(receiver.mediaUrl)
                    Socket(nextUrl.host, nextUrl.port).use { }
                } finally {
                    manager.release()
                    scope.cancel()
                }
            }
        }

    private fun manager(
        scope: CoroutineScope,
        settings: Settings,
    ) = JvmCastManager(
        scope,
        { error("Discovery is not needed for a known receiver") },
        { listOf(InetAddress.getLoopbackAddress()) },
        "test",
        settings,
    )

    private class Receiver(
        protocol: CastProtocol = CastProtocol.DLNA,
    ) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val actions = CopyOnWriteArrayList<String>()
        val failedVolumePolls = MutableStateFlow(0)

        @Volatile var onPlay: () -> Unit = {}

        @Volatile var mediaUrl = ""

        @Volatile var failVolume = false

        @Volatile var rejectMetadataOnce = false

        @Volatile var failSetUri = false

        private val metadataRejected = AtomicBoolean()

        @Volatile var volume = 80

        @Volatile var playing = false
        val device =
            CastDevice(
                "dlna:test",
                "Receiver",
                protocol,
                "127.0.0.1",
                server.address.port,
                "http://127.0.0.1:${server.address.port}/descriptor",
            )

        init {
            server.createContext("/") { exchange ->
                val action =
                    exchange.requestHeaders
                        .getFirst("SOAPACTION")
                        ?.substringAfter('#')
                        ?.trim('"')
                val request = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                if (action != null) actions += action
                val failed =
                    (failVolume && action in listOf("SetVolume", "GetVolume")) ||
                        (failSetUri && action == "SetAVTransportURI")
                val rejectedMetadata =
                    rejectMetadataOnce &&
                        action == "SetAVTransportURI" &&
                        request.contains("<CurrentURIMetaData>&lt;DIDL-Lite") &&
                        metadataRejected.compareAndSet(false, true)
                val response =
                    if (action == null) {
                        """<root><device><friendlyName>Receiver</friendlyName><UDN>test</UDN><manufacturer>${if (protocol == CastProtocol.SONOS) "Sonos" else "DLNA"}</manufacturer><serviceList>
                    <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>/transport</controlURL></service>
                    <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                    <controlURL>/volume</controlURL></service>
                    </serviceList></device></root>"""
                    } else {
                        when (action) {
                            "SetAVTransportURI" -> {
                                mediaUrl = Regex("<CurrentURI>(.*?)</CurrentURI>").find(request)!!.groupValues[1]
                            }

                            "SetVolume" -> {
                                if (!failed) {
                                    volume =
                                        Regex("<DesiredVolume>([0-9]+)</DesiredVolume>").find(request)!!.groupValues[1].toInt()
                                }
                            }

                            "Play" -> {
                                playing = true
                                onPlay()
                            }

                            "Stop", "Pause" -> {
                                playing = false
                            }

                            "GetVolume" -> {
                                if (failed) failedVolumePolls.value++
                            }
                        }
                        when (action) {
                            "GetPositionInfo" -> {
                                "<response><RelTime>00:00:42</RelTime><TrackDuration>00:03:00</TrackDuration></response>"
                            }

                            "GetTransportInfo" -> {
                                "<response><CurrentTransportState>${if (playing) "PLAYING" else "STOPPED"}" +
                                    "</CurrentTransportState></response>"
                            }

                            "GetVolume" -> {
                                "<response><CurrentVolume>$volume</CurrentVolume></response>"
                            }

                            else -> {
                                "<response/>"
                            }
                        }
                    }.toByteArray()
                exchange.sendResponseHeaders(if (failed || rejectedMetadata) 500 else 200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
                exchange.close()
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    private val media =
        CastMedia(
            "Song",
            null,
            PlaybackResource(
                "song",
                "https://r1.googlevideo.com/videoplayback",
                emptyMap(),
                null,
                null,
                contentLengthBytes = 1024L,
                mimeType = "audio/mp4",
            ),
            180_000L,
        )
}
