/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Desktop LAN discovery via JmDNS.
 */

package com.metrolist.shared.deviceconnect

import com.metrolist.shared.utils.Logger
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlin.concurrent.thread

private const val LOG_TAG = "DeviceConnect"
private const val FULL_RECORD_TIMEOUT_MS = 3_000L

actual class DeviceDiscoveryController actual constructor(
    context: Any,
    onError: (message: String, throwable: Throwable?) -> Unit,
    onClosed: () -> Unit,
    onFailure: () -> Unit,
) {
    private val reportError = onError
    private val reportClosed = onClosed
    private val reportFailure = onFailure
    private val serviceInstanceId = UUID.randomUUID().toString().take(8)
    private var localDeviceId = ""
    private val instances = CopyOnWriteArrayList<JmDNS>()

    @Volatile
    private var closeFailure: Throwable? = null
    private val listeners = ConcurrentHashMap<JmDNS, ServiceListener>()
    private val activeServiceKeys = mutableSetOf<String>()
    private val devicesByService = ConcurrentHashMap<String, DiscoveredDevice>()
    private val devicesById = ConcurrentHashMap<String, DiscoveredDevice>()
    private val discoveryLock = Any()
    private var discovering = false
    private val fetchingServices = mutableSetOf<String>()

    init {
        val addresses = deviceConnectLanAddresses()
        Logger.i(LOG_TAG, "mDNS LAN addresses: ${addresses.map { it.hostAddress }}")
        addresses.forEach { address ->
            runCatching { JmDNS.create(address, "Metrolist") }
                .onSuccess {
                    instances += it
                    Logger.i(LOG_TAG, "mDNS listening on ${address.hostAddress}")
                }.onFailure { reportError("mDNS unavailable on ${address.hostAddress}: ${it.message}", it) }
        }
        if (instances.isEmpty()) {
            reportError("mDNS unavailable: no active LAN interface.", null)
            reportFailure()
        }
    }

    actual fun registerService(
        deviceId: String,
        deviceName: String,
        port: Int,
        deviceType: DeviceConnectDeviceType,
    ) {
        localDeviceId = deviceId
        val properties = deviceConnectDiscoveryTxtRecords(deviceId, deviceName, deviceType)
        Logger.i(LOG_TAG, "mDNS register metrolist-$deviceId-$serviceInstanceId port=$port on ${instances.size} interface(s).")
        instances.forEach { jmDns ->
            runCatching {
                jmDns.registerService(
                    ServiceInfo.create(serviceType(), "metrolist-$deviceId-$serviceInstanceId", port, 0, 0, properties),
                )
            }.onFailure {
                reportError("mDNS register failed: ${it.message}", it)
                reportFailure()
            }
        }
    }

    actual suspend fun unregisterService() {
        try {
            runDesktopDiscoveryWorkers(instances.map { jmDns -> { jmDns.unregisterAllServices() } })
        } catch (error: Throwable) {
            reportError("mDNS unregister failed: ${error.message}", error)
            error.suppressed.forEach { reportError("mDNS unregister failed: ${it.message}", it) }
            throw error
        }
    }

    actual fun startDiscovery(
        onFound: (DiscoveredDevice) -> Unit,
        onLost: (DiscoveredDevice) -> Unit,
    ) {
        synchronized(discoveryLock) { discovering = true }
        Logger.i(LOG_TAG, "mDNS discover ${serviceType()} on ${instances.size} interface(s).")
        instances.forEach { jmDns ->
            val listener =
                object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) {
                        Logger.d(LOG_TAG, "mDNS found ${event.name} via ${jmDns.inetAddress.hostAddress}")
                        synchronized(discoveryLock) {
                            if (!discovering || listeners[jmDns] !== this) return
                            activeServiceKeys += serviceKey(jmDns, event.name)
                        }
                        event.dns.requestServiceInfo(event.type, event.name)
                    }

                    override fun serviceRemoved(event: ServiceEvent) {
                        Logger.d(LOG_TAG, "mDNS lost ${event.name} via ${jmDns.inetAddress.hostAddress}")
                        val (replacement, lost) =
                            synchronized(discoveryLock) {
                                if (!discovering || listeners[jmDns] !== this) return
                                val key = serviceKey(jmDns, event.name)
                                activeServiceKeys.remove(key)
                                val device = devicesByService.remove(key) ?: return
                                if (!devicesById.remove(device.deviceId, device)) return
                                val replacement = desktopDiscoveryReplacement(device, devicesByService.values)
                                if (replacement == null) {
                                    null to device
                                } else {
                                    devicesById[device.deviceId] = replacement
                                    replacement to null
                                }
                            }
                        replacement?.let(onFound)
                        lost?.let(onLost)
                    }

                    override fun serviceResolved(event: ServiceEvent) {
                        resolved(event, event.info, allowFetch = true)
                    }

                    // JmDNS can resolve before the TXT record arrives; fetch the complete record once in that case.
                    private fun resolved(
                        event: ServiceEvent,
                        info: ServiceInfo,
                        allowFetch: Boolean,
                    ) {
                        val host =
                            selectDeviceConnectHost(
                                info.inet4Addresses.map { it.hostAddress },
                                jmDns.inetAddress.hostAddress,
                            ) ?: selectDeviceConnectHost(
                                info.inetAddresses.mapNotNull { address ->
                                    if (address is Inet6Address && address.isLinkLocalAddress) {
                                        val local = jmDns.inetAddress
                                        val scopeId =
                                            (local as? Inet6Address)?.scopeId?.takeIf { it > 0 }
                                                ?: NetworkInterface.getByInetAddress(local)?.index?.takeIf { it > 0 }
                                                ?: return@mapNotNull null
                                        address.hostAddress?.substringBefore('%')?.let { "$it%$scopeId" }
                                    } else {
                                        address.hostAddress
                                    }
                                },
                            )
                                ?: run {
                                    Logger.w(
                                        LOG_TAG,
                                        "mDNS ${event.name} has no LAN address (ipv4=${info.inet4Addresses.map { it.hostAddress }}, " +
                                            "ipv6=${info.inetAddresses.map { it.hostAddress }}).",
                                    )
                                    return
                                }
                        val deviceId = info.getPropertyString(DEVICE_CONNECT_DEVICE_ID_TXT_KEY).orEmpty()
                        if (deviceId == localDeviceId && deviceId.isNotBlank()) return
                        if (deviceId.isBlank() && allowFetch) {
                            // One fetch per service at a time; events that arrive meanwhile are covered by it.
                            if (!synchronized(discoveryLock) { fetchingServices.add(event.name) }) return
                            Logger.d(LOG_TAG, "mDNS ${event.name} resolved without TXT; fetching the full record.")
                            thread(name = "DeviceConnect-mDNS-fetch", isDaemon = true) {
                                val full =
                                    runCatching { jmDns.getServiceInfo(serviceType(), event.name, true, FULL_RECORD_TIMEOUT_MS) }
                                        .onFailure {
                                            Logger.w(LOG_TAG, "mDNS full record fetch for ${event.name} failed: ${it.message}", it)
                                        }.getOrNull()
                                synchronized(discoveryLock) { fetchingServices.remove(event.name) }
                                if (full != null) resolved(event, full, allowFetch = false)
                            }
                            return
                        }
                        if (deviceId.isBlank() || info.port !in 1..65535) {
                            Logger.w(
                                LOG_TAG,
                                "mDNS ${event.name} has an invalid record (deviceId blank=${deviceId.isBlank()}, port=${info.port}, " +
                                    "txtKeys=${Collections.list(info.propertyNames)}).",
                            )
                            return
                        }
                        val device =
                            DiscoveredDevice(
                                serviceName = event.name,
                                deviceId = deviceId,
                                deviceName = deviceConnectNameFromTxtRecord(info::getPropertyString),
                                host = host,
                                port = info.port,
                                deviceType = deviceConnectTypeFromTxtRecord(info::getPropertyString),
                            )
                        Logger.d(LOG_TAG, "mDNS resolved ${event.name} -> $host:${info.port} (deviceId=$deviceId)")
                        val changed =
                            synchronized(discoveryLock) {
                                if (!discovering || listeners[jmDns] !== this) return
                                val key = serviceKey(jmDns, event.name)
                                if (key !in activeServiceKeys) return
                                devicesByService[key] = device
                                devicesById.put(deviceId, device) != device
                            }
                        if (changed) onFound(device)
                    }
                }
            synchronized(discoveryLock) {
                if (!discovering) return@forEach
                listeners[jmDns] = listener
            }
            runCatching { jmDns.addServiceListener(serviceType(), listener) }
                .onFailure {
                    synchronized(discoveryLock) { listeners.remove(jmDns, listener) }
                    reportError("mDNS discovery failed: ${it.message}", it)
                    reportFailure()
                }
        }
    }

    actual suspend fun stopDiscovery() {
        Logger.i(LOG_TAG, "mDNS discovery stopping.")
        val activeListeners =
            synchronized(discoveryLock) {
                discovering = false
                listeners.entries.toList().also {
                    listeners.clear()
                    activeServiceKeys.clear()
                    devicesByService.clear()
                    devicesById.clear()
                }
            }
        activeListeners.forEach { (jmDns, listener) ->
            runCatching { jmDns.removeServiceListener(serviceType(), listener) }
                .onFailure { reportError("mDNS listener removal failed: ${it.message}", it) }
        }
    }

    actual suspend fun close() {
        closeFailure?.let { throw it }
        stopDiscovery()
        val unregisterFailure = runCatching { unregisterService() }.exceptionOrNull()
        closeFailure = unregisterFailure
        val closingInstances = instances.toList()
        instances.clear()
        // Native close can still confirm withdrawal after unregisterAllServices fails.
        thread(name = "DeviceConnect-mDNS-close", isDaemon = true) {
            runCatching {
                runDesktopDiscoveryWorkers(closingInstances.map { jmDns -> { jmDns.close() } })
            }.onSuccess {
                closeFailure = null
                if (unregisterFailure != null) reportClosed()
            }.onFailure { reportError("mDNS close failed: ${it.message}", it) }
        }
        unregisterFailure?.let { throw it }
        reportClosed()
    }

    private fun serviceType() = "${DeviceConnectManager.SERVICE_TYPE}local."

    private fun serviceKey(
        jmDns: JmDNS,
        serviceName: String,
    ) = "${System.identityHashCode(jmDns)}:$serviceName"
}

internal fun desktopDiscoveryReplacement(
    removed: DiscoveredDevice,
    remaining: Collection<DiscoveredDevice>,
): DiscoveredDevice? = remaining.firstOrNull { it.deviceId == removed.deviceId }

internal fun runDesktopDiscoveryWorkers(
    actions: Collection<() -> Unit>,
    timeoutMillis: Long = 5_000,
) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    val failures = ConcurrentLinkedQueue<Throwable>()
    actions
        .map { action ->
            thread(name = "DeviceConnect-mDNS-unregister", isDaemon = true) {
                runCatching(action).exceptionOrNull()?.let(failures::add)
            }
        }.forEach {
            if (!it.joinUntil(deadline)) {
                failures.add(TimeoutException("mDNS operation did not finish within ${timeoutMillis}ms"))
            }
        }
    val first = failures.poll() ?: return
    failures.forEach(first::addSuppressed)
    throw first
}

private fun Thread.joinUntil(deadlineNanos: Long): Boolean {
    var interrupted = false
    try {
        while (isAlive) {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) return false
            try {
                TimeUnit.NANOSECONDS.timedJoin(this, remaining)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        return true
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}
