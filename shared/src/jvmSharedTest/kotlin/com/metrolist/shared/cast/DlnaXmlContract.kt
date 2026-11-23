package com.metrolist.shared.cast

import com.metrolist.shared.youtube.playback.PlaybackResource
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

abstract class DlnaXmlContract {
    @Test
    fun utf8NamespacedDescriptorRetainsEscapedNonAsciiName() = verifyDescriptor(Charsets.UTF_8)

    @Test
    fun utf16NamespacedDescriptorRetainsEscapedNonAsciiName() = verifyDescriptor(Charsets.UTF_16)

    @Test
    fun utf16LittleEndianDescriptorWithoutBomRetainsName() = verifyDescriptor(Charsets.UTF_16LE)

    @Test
    fun utf16BigEndianDescriptorWithoutBomRetainsName() = verifyDescriptor(Charsets.UTF_16BE)

    @Test
    fun unqualifiedDescriptorRemainsSupported() = verifyDescriptor(Charsets.UTF_8, namespaced = false)

    @Test
    fun sonosDescriptorUsesNonAsciiRoomName() = verifyDescriptor(Charsets.UTF_8, sonos = true)

    @Test
    fun utf8SoapResponsesAllowSeekAndAutoplay() = verifyLoad(Charsets.UTF_8)

    @Test
    fun utf16SoapResponsesAllowSeekAndAutoplay() = verifyLoad(Charsets.UTF_16)

    @Test
    fun knownDurationIsSentInDidlLite() = verifyLoad(Charsets.UTF_8, durationMs = 180_000L)

    @Test
    fun unknownDurationOmitsDidlLiteDurationAttribute() = verifyLoad(Charsets.UTF_8, durationMs = 0L)

    @Test
    fun utf8InternalDoctypeIsRejected() = verifyDoctypeRejected(Charsets.UTF_8, external = false)

    @Test
    fun utf16InternalDoctypeIsRejected() = verifyDoctypeRejected(Charsets.UTF_16, external = false)

    @Test
    fun utf8ExternalDoctypeIsRejectedWithoutFetchingIt() = verifyDoctypeRejected(Charsets.UTF_8, external = true)

    @Test
    fun utf16ExternalDoctypeIsRejectedWithoutFetchingIt() = verifyDoctypeRejected(Charsets.UTF_16, external = true)

    @Test
    fun utf16ExternalGeneralEntityInNameIsRejectedWithoutFetchingIt() = verifyExternalEntityRejected(parameterEntity = false)

    @Test
    fun utf16ExternalParameterEntityIsRejectedWithoutFetchingIt() = verifyExternalEntityRejected(parameterEntity = true)

    @Test
    fun malformedDescriptorIsRejected() =
        runBlocking {
            Receiver().use { receiver ->
                receiver.descriptorBytes = "<root><device></root>".toByteArray()
                assertFails { fetchDlnaDescriptor(receiver.location) }
                assertEquals(0, receiver.externalRequests.get())
            }
        }

    private fun verifyDescriptor(
        charset: Charset,
        namespaced: Boolean = true,
        sonos: Boolean = false,
    ) = runBlocking {
        Receiver(charset, namespaced, sonos).use { receiver ->
            // The selected real platform parser accepts this fixture without the production configuration.
            val raw =
                DocumentBuilderFactory
                    .newInstance()
                    .apply { isNamespaceAware = true }
                    .newDocumentBuilder()
                    .parse(ByteArrayInputStream(receiver.descriptorBytes))
            assertEquals("Sala & música", raw.getElementsByTagName("friendlyName").item(0).textContent)

            val descriptor = fetchDlnaDescriptor(receiver.location)
            assertEquals("uuid:550e8400-e29b-41d4-a716-446655440000", descriptor.id)
            assertEquals(if (sonos) "Quarto São Paulo" else "Sala & música", descriptor.name)
            assertEquals(sonos, descriptor.isSonos)
            assertEquals(AV_TRANSPORT, descriptor.avTransportType)
            assertEquals("${receiver.baseUrl}/transport", descriptor.avTransportUrl)
            assertEquals(RENDERING_CONTROL, descriptor.renderingControlType)
            assertEquals("${receiver.baseUrl}/volume", descriptor.renderingControlUrl)
        }
    }

    private fun verifyLoad(
        charset: Charset,
        durationMs: Long = 180_000L,
    ) = runBlocking {
        Receiver(charset, trackDuration = if (durationMs > 0L) "00:03:00" else "00:00:00").use { receiver ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = DlnaSession(scope, receiver.location)
            val media =
                CastMedia(
                    "Canção & música",
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
                    durationMs,
                )
            try {
                session.load("${receiver.baseUrl}/audio?a=1&b=2", media, 42_000L, true, 0.9f, 7L)
                assertEquals(listOf("SetAVTransportURI", "Seek", "Play", "GetVolume"), receiver.actions.take(4))
                assertTrue(
                    receiver.requests
                        .single { it.first == "Seek" }
                        .second
                        .contains("<Target>00:00:42</Target>"),
                )
                val state = session.state.value
                assertTrue(state.connected)
                assertTrue(state.isPlaying)
                assertEquals(42_000L, state.positionMs)
                assertEquals(durationMs, state.durationMs)
                val didl = receiver.requests.single { it.first == "SetAVTransportURI" }.second
                if (durationMs > 0L) {
                    assertTrue(didl.contains("duration=&quot;00:03:00&quot;"))
                } else {
                    assertFalse(didl.contains("duration"))
                }
                assertEquals(0.25f, state.volume)
                assertEquals(7L, state.mediaGeneration)
            } finally {
                session.close()
                scope.cancel()
            }
        }
    }

    private fun verifyDoctypeRejected(
        charset: Charset,
        external: Boolean,
    ) = runBlocking {
        Receiver(charset).use { receiver ->
            val declaration =
                if (external) {
                    "<!DOCTYPE root SYSTEM \"${receiver.baseUrl}/external.dtd\">"
                } else {
                    "<!DOCTYPE root [<!ENTITY room \"Sala\">]>"
                }
            receiver.descriptorBytes =
                (xmlDeclaration(charset) + declaration + descriptorBody()).toByteArray(charset)
            assertFails { fetchDlnaDescriptor(receiver.location) }
            assertEquals(0, receiver.externalRequests.get())
        }
    }

    private fun verifyExternalEntityRejected(parameterEntity: Boolean) =
        runBlocking {
            Receiver(Charsets.UTF_16).use { receiver ->
                val declaration =
                    if (parameterEntity) {
                        "<!DOCTYPE root [<!ENTITY % definitions SYSTEM \"${receiver.baseUrl}/external.dtd\">%definitions;]>"
                    } else {
                        receiver.externalBody = "Injected room"
                        "<!DOCTYPE root [<!ENTITY room SYSTEM \"${receiver.baseUrl}/external.dtd\">]>"
                    }
                val body =
                    if (parameterEntity) {
                        descriptorBody()
                    } else {
                        descriptorBody().replace("Sala &amp; música", "&room;")
                    }
                receiver.descriptorBytes = (xmlDeclaration(Charsets.UTF_16) + declaration + body).toByteArray(Charsets.UTF_16)
                assertFails { fetchDlnaDescriptor(receiver.location) }
                assertEquals(0, receiver.externalRequests.get())
            }
        }

    private class Receiver(
        private val charset: Charset = Charsets.UTF_8,
        namespaced: Boolean = true,
        sonos: Boolean = false,
        private val trackDuration: String = "00:03:00",
    ) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        val location = "$baseUrl/descriptor"
        val actions = CopyOnWriteArrayList<String>()
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val externalRequests = AtomicInteger()

        @Volatile
        var externalBody = "<!ELEMENT root ANY>"

        @Volatile
        var descriptorBytes = (xmlDeclaration(charset) + descriptorBody(namespaced, sonos)).toByteArray(charset)

        init {
            server.createContext("/") { exchange ->
                val action =
                    exchange.requestHeaders
                        .getFirst("SOAPACTION")
                        ?.substringAfter('#')
                        ?.trim('"')
                val request = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                val response =
                    when {
                        exchange.requestURI.path == "/external.dtd" -> {
                            externalRequests.incrementAndGet()
                            externalBody.toByteArray()
                        }

                        action == null -> {
                            descriptorBytes
                        }

                        else -> {
                            actions += action
                            requests += action to request
                            val fields =
                                when (action) {
                                    "GetVolume" -> "<CurrentVolume>25</CurrentVolume>"
                                    "GetPositionInfo" -> "<RelTime>00:00:42</RelTime><TrackDuration>$trackDuration</TrackDuration>"
                                    "GetTransportInfo" -> "<CurrentTransportState>PLAYING</CurrentTransportState>"
                                    else -> ""
                                }
                            val service = if (action == "GetVolume") RENDERING_CONTROL else AV_TRANSPORT
                            (
                                xmlDeclaration(charset) +
                                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                                    "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
                                    "<u:${action}Response xmlns:u=\"$service\">$fields</u:${action}Response>" +
                                    "</s:Body></s:Envelope>"
                            ).toByteArray(charset)
                        }
                    }
                exchange.responseHeaders.add("Content-Type", "text/xml; charset=${charset.name()}")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
                exchange.close()
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    private companion object {
        const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"

        fun xmlDeclaration(charset: Charset) = "<?xml version=\"1.0\" encoding=\"${charset.name()}\"?>"

        fun descriptorBody(
            namespaced: Boolean = true,
            sonos: Boolean = false,
        ): String =
            """<root${if (namespaced) " xmlns=\"urn:schemas-upnp-org:device-1-0\"" else ""}>
                <specVersion><major>1</major><minor>0</minor></specVersion><device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>Sala &amp; música</friendlyName>
                <manufacturer>${if (sonos) "Sonos, Inc." else "DLNA Receiver"}</manufacturer>
                <modelName>XML regression receiver</modelName><UDN>uuid:550e8400-e29b-41d4-a716-446655440000</UDN>
                <roomName>Quarto São Paulo</roomName>
                <serviceList><service><serviceType>$AV_TRANSPORT</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><SCPDURL>/avtransport.xml</SCPDURL>
                <controlURL>/transport</controlURL><eventSubURL>/transport/events</eventSubURL></service>
                <service><serviceType>$RENDERING_CONTROL</serviceType>
                <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId><SCPDURL>/renderingcontrol.xml</SCPDURL>
                <controlURL>/volume</controlURL><eventSubURL>/volume/events</eventSubURL></service>
                <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId><SCPDURL>/connectionmanager.xml</SCPDURL>
                <controlURL>/connection</controlURL><eventSubURL>/connection/events</eventSubURL></service></serviceList>
                </device></root>"""
    }
}
