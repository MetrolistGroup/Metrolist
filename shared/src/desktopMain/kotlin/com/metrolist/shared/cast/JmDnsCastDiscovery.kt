package com.metrolist.shared.cast

import com.metrolist.shared.utils.MdnsDiscoverySession
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

class JmDnsCastDiscovery(
    private val localAddresses: () -> List<InetAddress>,
) : MdnsCastDiscovery {
    private val lock = Any()
    private var reportError: (CastError) -> Unit = {}
    private var session: MdnsDiscoverySession<InetAddress, JmDNS>? = null
    private val foundDevices = ConcurrentHashMap.newKeySet<String>()
    private val listeners = ConcurrentHashMap<JmDNS, MutableMap<String, ServiceListener>>()

    @Volatile private var generation: Any? = null

    override fun start(
        onDeviceFound: (CastDevice) -> Unit,
        onDeviceLost: (String) -> Unit,
        onError: (CastError) -> Unit,
    ) {
        synchronized(lock) {
            if (generation != null) return
            if (runCatching { session?.close() }.isFailure) {
                onError(CastError.DISCOVERY_FAILED)
                return
            }
            reportError = onError
            val current = Any().also { generation = it }
            session =
                MdnsDiscoverySession(
                    addresses = localAddresses,
                    create = { JmDNS.create(it, "Metrolist") },
                    configure = { jmDns ->
                        register(jmDns, FCAST_TYPE, CastProtocol.FCAST, onDeviceFound, onDeviceLost, current)
                        register(jmDns, GOOGLE_CAST_TYPE, CastProtocol.GOOGLE_CAST, onDeviceFound, onDeviceLost, current)
                    },
                    beforeClose = { jmDns ->
                        listeners.remove(jmDns)?.forEach { (type, listener) -> jmDns.removeServiceListener(type, listener) }
                    },
                    onReset = {
                        listeners.clear()
                        val lost = foundDevices.toList()
                        foundDevices.clear()
                        if (generation === current) lost.forEach(onDeviceLost)
                    },
                    onError = { if (generation === current) onError(CastError.DISCOVERY_FAILED) },
                ).also { it.start() }
        }
    }

    private fun register(
        jmDns: JmDNS,
        serviceType: String,
        protocol: CastProtocol,
        onDeviceFound: (CastDevice) -> Unit,
        onDeviceLost: (String) -> Unit,
        current: Any,
    ) {
        val listener =
            object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    // JmDNS already resolves services through its built-in collector.
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    if (generation === current && listeners[jmDns]?.get(serviceType) === this) {
                        val id = deviceId(protocol, event.name)
                        foundDevices.remove(id)
                        onDeviceLost(id)
                    }
                }

                override fun serviceResolved(event: ServiceEvent) {
                    if (generation !== current || listeners[jmDns]?.get(serviceType) !== this) return
                    val address =
                        event.info.inet4Addresses.firstOrNull()
                            ?: event.info.inetAddresses.firstOrNull { it is Inet4Address }
                            ?: event.info.inet6Addresses.firstOrNull()
                            ?: event.info.inetAddresses.firstOrNull()
                            ?: return
                    val name = event.info.getPropertyString("fn")?.takeIf(String::isNotBlank) ?: event.name
                    val id = deviceId(protocol, event.name)
                    foundDevices.add(id)
                    onDeviceFound(
                        CastDevice(
                            id = id,
                            name = name,
                            protocol = protocol,
                            host = address.hostAddress,
                            port = event.info.port,
                        ),
                    )
                }
            }
        listeners.getOrPut(jmDns) { ConcurrentHashMap() }[serviceType] = listener
        jmDns.addServiceListener(serviceType, listener)
    }

    override fun stop() {
        synchronized(lock) {
            generation = null
            runCatching { session?.close() }
                .onSuccess { session = null }
                .onFailure { reportError(CastError.DISCOVERY_FAILED) }
        }
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
