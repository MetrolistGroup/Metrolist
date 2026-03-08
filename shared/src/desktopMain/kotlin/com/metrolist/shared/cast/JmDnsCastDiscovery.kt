package com.metrolist.shared.cast

import java.net.Inet4Address
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

class JmDnsCastDiscovery(
    private val localAddresses: () -> List<InetAddress>,
) : MdnsCastDiscovery {
    private val instances = mutableListOf<JmDNS>()
    private val lock = Any()

    @Volatile private var running = false

    override fun start(
        onDeviceFound: (CastDevice) -> Unit,
        onDeviceLost: (String) -> Unit,
        onError: (CastError) -> Unit,
    ) {
        synchronized(lock) {
            if (running) return
            running = true
        }
        val addresses = runCatching { localAddresses().distinct() }.getOrDefault(emptyList())
        addresses.forEach { address ->
            if (!running) return
            runCatching {
                val jmDns = JmDNS.create(address, "Metrolist")
                synchronized(lock) {
                    if (!running) {
                        runCatching { jmDns.close() }
                        return
                    }
                    instances += jmDns
                }
                register(jmDns, FCAST_TYPE, CastProtocol.FCAST, onDeviceFound, onDeviceLost)
                register(jmDns, GOOGLE_CAST_TYPE, CastProtocol.GOOGLE_CAST, onDeviceFound, onDeviceLost)
            }
        }
    }

    private fun register(
        jmDns: JmDNS,
        serviceType: String,
        protocol: CastProtocol,
        onDeviceFound: (CastDevice) -> Unit,
        onDeviceLost: (String) -> Unit,
    ) {
        jmDns.addServiceListener(
            serviceType,
            object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    event.dns.requestServiceInfo(event.type, event.name)
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    onDeviceLost(deviceId(protocol, event.name))
                }

                override fun serviceResolved(event: ServiceEvent) {
                    val address =
                        event.info.inet4Addresses.firstOrNull()
                            ?: event.info.inetAddresses.firstOrNull { it is Inet4Address }
                            ?: event.info.inet6Addresses.firstOrNull()
                            ?: event.info.inetAddresses.firstOrNull()
                            ?: return
                    val name = event.info.getPropertyString("fn")?.takeIf(String::isNotBlank) ?: event.name
                    onDeviceFound(
                        CastDevice(
                            id = deviceId(protocol, event.name),
                            name = name,
                            protocol = protocol,
                            host = address.hostAddress,
                            port = event.info.port,
                        ),
                    )
                }
            },
        )
    }

    override fun stop() {
        val toClose =
            synchronized(lock) {
                if (!running) return
                running = false
                val list = instances.toList()
                instances.clear()
                list
            }
        toClose.forEach { runCatching { it.close() } }
    }

    private fun deviceId(
        protocol: CastProtocol,
        serviceName: String,
    ): String = "${protocol.name.lowercase()}:$serviceName"

    private companion object {
        const val FCAST_TYPE = "_fcast._tcp.local."
        const val GOOGLE_CAST_TYPE = "_googlecast._tcp.local."
    }
}
