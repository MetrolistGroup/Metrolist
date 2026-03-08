/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * LAN discovery via Android NsdManager. The local service advertises its opaque id,
 * display name, and coarse device type; authenticated metadata is exchanged after pairing.
 */

package com.metrolist.shared.deviceconnect

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.UUID

actual class DeviceDiscoveryController actual constructor(
    context: Any,
    onError: (message: String, throwable: Throwable?) -> Unit,
    onClosed: () -> Unit,
    onFailure: () -> Unit,
) {
    private val ctx = context as Context
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var localDeviceId = Settings_shared.deviceConnectDeviceId
    private val serviceInstanceId = UUID.randomUUID().toString().take(8)
    private val registrationLock = Any()
    private val discoveryLock = Any()
    private val byName = mutableMapOf<String, DiscoveredDevice>()
    private val foundServiceNames = mutableSetOf<String>()

    // Discovery and service-info callbacks can use different executors.
    private val reportError = onError
    private val reportClosed = onClosed
    private val reportFailure = onFailure
    private val closeLock = Any()
    private var closeRequested = false
    private var closeReported = false
    private var registrationClosed = true
    private var discoveryClosed = true

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var registrationShutdown: AndroidNsdShutdownBarrier? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discoveryShutdown: AndroidNsdShutdownBarrier? = null
    private val serviceInfoCallbacks = mutableMapOf<String, Any>()
    private var discovering = false

    actual fun registerService(
        deviceId: String,
        deviceName: String,
        port: Int,
        deviceType: DeviceConnectDeviceType,
    ) {
        localDeviceId = deviceId
        val info =
            NsdServiceInfo().apply {
                serviceName = "metrolist-$deviceId-$serviceInstanceId"
                serviceType = DeviceConnectManager.SERVICE_TYPE
                setPort(port)
                deviceConnectDiscoveryTxtRecords(deviceId, deviceName, deviceType).forEach(::setAttribute)
            }
        lateinit var listener: NsdManager.RegistrationListener
        lateinit var shutdown: AndroidNsdShutdownBarrier
        listener =
            object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(
                    serviceInfo: NsdServiceInfo?,
                    errorCode: Int,
                ) {
                    finishRegistration(listener, shutdown)
                    reportError("mDNS register failed: ${nsdErrorName(errorCode)}", null)
                    reportFailure()
                }

                override fun onUnregistrationFailed(
                    serviceInfo: NsdServiceInfo?,
                    errorCode: Int,
                ) {
                    // NsdManager removes this listener on either terminal callback (API 20+).
                    finishRegistration(listener, shutdown)
                    reportError("mDNS unregister failed: ${nsdErrorName(errorCode)}", null)
                }

                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    if (!isCurrentRegistration(listener)) return
                    Logger.i(TAG, "mDNS service registered: ${serviceInfo.serviceName}")
                    shutdown.started()
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                    finishRegistration(listener, shutdown)
                    Logger.i(TAG, "mDNS service unregistered.")
                }
            }
        shutdown =
            AndroidNsdShutdownBarrier(
                stopRequest = { nsd.unregisterService(listener) },
                onRequestFailure = { reportError("mDNS unregister failed: ${it.message}", it) },
                onCompleted = ::registrationFinished,
            )
        synchronized(closeLock) { registrationClosed = false }
        synchronized(registrationLock) {
            registrationListener = listener
            registrationShutdown = shutdown
        }
        Logger.i(TAG, "mDNS register ${info.serviceName} port=$port type=${info.serviceType}")
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Throwable) {
            finishRegistration(listener, shutdown)
            throw error
        }
    }

    actual suspend fun unregisterService() {
        val shutdown = synchronized(registrationLock) { registrationShutdown } ?: return
        check(shutdown.close(NSD_SHUTDOWN_TIMEOUT_MS)) { "mDNS unregister did not complete." }
    }

    actual fun startDiscovery(
        onFound: (DiscoveredDevice) -> Unit,
        onLost: (DiscoveredDevice) -> Unit,
    ) {
        lateinit var listener: NsdManager.DiscoveryListener
        lateinit var shutdown: AndroidNsdShutdownBarrier
        listener =
            object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(
                    serviceType: String?,
                    errorCode: Int,
                ) {
                    finishDiscovery(listener, shutdown)
                    reportError("mDNS discovery start failed: ${nsdErrorName(errorCode)}", null)
                    reportFailure()
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String?,
                    errorCode: Int,
                ) {
                    // The framework retires failed discovery listeners too; retry needs a new one.
                    finishDiscovery(listener, shutdown)
                    reportError("mDNS discovery stop failed: ${nsdErrorName(errorCode)}", null)
                }

                override fun onDiscoveryStarted(serviceType: String?) {
                    if (!isCurrentDiscovery(listener)) return
                    Logger.d(TAG, "mDNS discovery started.")
                    shutdown.started()
                }

                override fun onDiscoveryStopped(serviceType: String?) {
                    finishDiscovery(listener, shutdown)
                    Logger.d(TAG, "mDNS discovery stopped.")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    Logger.d(TAG, "mDNS found ${service.serviceName}")
                    val active =
                        synchronized(discoveryLock) {
                            if (!discovering || discoveryListener !== listener) {
                                false
                            } else {
                                foundServiceNames += service.serviceName
                                true
                            }
                        }
                    if (!active) return
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        registerServiceInfoCallback(service, listener, onFound, onLost)
                    } else {
                        resolveServiceLegacy(service, listener, onFound)
                    }
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    Logger.d(TAG, "mDNS lost ${service.serviceName}")
                    val (device, callback) =
                        synchronized(discoveryLock) {
                            if (!discovering || discoveryListener !== listener) return
                            foundServiceNames.remove(service.serviceName)
                            byName.remove(service.serviceName) to serviceInfoCallbacks.remove(service.serviceName)
                        }
                    device?.let(onLost)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        (callback as? NsdManager.ServiceInfoCallback)?.let(::unregisterServiceInfoCallbackInstance)
                    }
                }
            }
        shutdown =
            AndroidNsdShutdownBarrier(
                stopRequest = { nsd.stopServiceDiscovery(listener) },
                onRequestFailure = { reportError("mDNS discovery stop failed: ${it.message}", it) },
                onCompleted = ::discoveryFinished,
            )
        synchronized(closeLock) { discoveryClosed = false }
        synchronized(discoveryLock) {
            discovering = true
            discoveryListener = listener
            discoveryShutdown = shutdown
        }
        Logger.i(TAG, "mDNS discover ${DeviceConnectManager.SERVICE_TYPE} sdk=${Build.VERSION.SDK_INT}")
        try {
            nsd.discoverServices(DeviceConnectManager.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Throwable) {
            finishDiscovery(listener, shutdown)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveServiceLegacy(
        service: NsdServiceInfo,
        expectedDiscovery: NsdManager.DiscoveryListener,
        onFound: (DiscoveredDevice) -> Unit,
    ) {
        nsd.resolveService(
            service,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                    serviceInfo: NsdServiceInfo?,
                    errorCode: Int,
                ) {
                    Logger.w(TAG, "mDNS resolve failed for ${serviceInfo?.serviceName}: ${nsdErrorName(errorCode)}")
                    reportError("mDNS resolve failed: ${nsdErrorName(errorCode)}", null)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    handleResolvedService(serviceInfo, onFound, expectedDiscovery = expectedDiscovery)
                }
            },
        )
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun registerServiceInfoCallback(
        service: NsdServiceInfo,
        expectedDiscovery: NsdManager.DiscoveryListener,
        onFound: (DiscoveredDevice) -> Unit,
        onLost: (DiscoveredDevice) -> Unit,
    ) {
        val serviceName = service.serviceName
        val callback =
            object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    val active =
                        synchronized(discoveryLock) {
                            serviceInfoCallbacks.remove(serviceName, this)
                            discovering
                        }
                    if (active) {
                        reportError("mDNS service info callback registration failed: ${nsdErrorName(errorCode)}", null)
                    }
                }

                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    handleResolvedService(serviceInfo, onFound, serviceName, this)
                }

                override fun onServiceLost() {
                    val device =
                        synchronized(discoveryLock) {
                            if (!discovering || serviceInfoCallbacks[serviceName] !== this) return
                            byName.remove(serviceName)
                        }
                    device?.let(onLost)
                }

                override fun onServiceInfoCallbackUnregistered() {
                    synchronized(discoveryLock) {
                        serviceInfoCallbacks.remove(serviceName, this)
                    }
                }
            }
        var failure: Throwable? = null
        synchronized(discoveryLock) {
            if (
                !discovering ||
                discoveryListener !== expectedDiscovery ||
                serviceName !in foundServiceNames ||
                serviceInfoCallbacks.containsKey(serviceName)
            ) {
                return
            }
            serviceInfoCallbacks[serviceName] = callback
            failure =
                runCatching { nsd.registerServiceInfoCallback(service, ctx.mainExecutor, callback) }
                    .exceptionOrNull()
            if (failure != null) serviceInfoCallbacks.remove(serviceName, callback)
        }
        failure?.let { reportError("mDNS service info callback failed: ${it.message}", it) }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun unregisterServiceInfoCallbackInstance(callback: NsdManager.ServiceInfoCallback) {
        runCatching { nsd.unregisterServiceInfoCallback(callback) }
            .onFailure { reportError("mDNS service info callback unregister failed: ${it.message}", it) }
    }

    private fun handleResolvedService(
        service: NsdServiceInfo,
        onFound: (DiscoveredDevice) -> Unit,
        expectedServiceName: String = service.serviceName,
        expectedCallback: Any? = null,
        expectedDiscovery: NsdManager.DiscoveryListener? = null,
    ) {
        val host =
            hostAddress(service) ?: run {
                Logger.w(TAG, "mDNS ${service.serviceName} has no LAN IPv4 address yet.")
                return
            }
        val deviceId = attr(service, DEVICE_CONNECT_DEVICE_ID_TXT_KEY)
        if (service.serviceName != expectedServiceName) {
            Logger.w(TAG, "mDNS resolved ${service.serviceName} for a different service ($expectedServiceName); ignored.")
            return
        }
        if (deviceId == localDeviceId && deviceId.isNotBlank()) return
        if (deviceId.isBlank() || service.port !in 1..65535) {
            Logger.w(TAG, "mDNS ${service.serviceName} has an invalid record (deviceId blank=${deviceId.isBlank()}, port=${service.port}).")
            return
        }
        Logger.d(TAG, "mDNS resolved ${service.serviceName} -> $host:${service.port}")
        val deviceName = deviceConnectNameFromTxtRecord { attr(service, it) }
        val deviceType = deviceConnectTypeFromTxtRecord { attr(service, it) }
        val device = DiscoveredDevice(service.serviceName, deviceId, deviceName, host, service.port, deviceType)
        synchronized(discoveryLock) {
            if (
                !discovering ||
                (expectedDiscovery != null && discoveryListener !== expectedDiscovery) ||
                expectedServiceName !in foundServiceNames ||
                (expectedCallback != null && serviceInfoCallbacks[expectedServiceName] !== expectedCallback)
            ) {
                return
            }
            byName[expectedServiceName] = device
            onFound(device)
        }
    }

    private fun hostAddress(service: NsdServiceInfo): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            selectDeviceConnectHost(
                service.hostAddresses
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { it.hostAddress },
            ) ?: selectDeviceConnectHost(service.hostAddresses.mapNotNull { it.hostAddress })
        } else {
            legacyHostAddress(service)
        }

    @Suppress("DEPRECATION")
    private fun legacyHostAddress(service: NsdServiceInfo): String? = service.host?.hostAddress?.takeIf(::isDeviceConnectLanHost)

    private fun attr(
        service: NsdServiceInfo,
        key: String,
    ): String {
        val bytes = service.attributes[key] ?: return ""
        return bytes.toString(Charsets.UTF_8)
    }

    actual suspend fun stopDiscovery() {
        Logger.i(TAG, "mDNS discovery stopping.")
        val (shutdown, callbacks) =
            synchronized(discoveryLock) {
                discovering = false
                foundServiceNames.clear()
                byName.clear()
                discoveryShutdown to serviceInfoCallbacks.values.toList().also { serviceInfoCallbacks.clear() }
            }
        unregisterServiceInfoCallbacks(callbacks)
        if (shutdown != null) {
            check(shutdown.close(NSD_SHUTDOWN_TIMEOUT_MS)) { "mDNS discovery stop did not complete." }
        }
    }

    actual suspend fun close() {
        closeRequested()
        withContext(NonCancellable) {
            val registration = async { runCatching { unregisterService() } }
            val discovery = async { runCatching { stopDiscovery() } }
            awaitAll(registration, discovery).forEach { it.getOrThrow() }
        }
    }

    private fun closeRequested() = updateCloseState { closeRequested = true }

    private fun registrationFinished() = updateCloseState { registrationClosed = true }

    private fun discoveryFinished() = updateCloseState { discoveryClosed = true }

    private inline fun updateCloseState(update: () -> Unit) {
        val notify =
            synchronized(closeLock) {
                update()
                if (closeRequested && registrationClosed && discoveryClosed && !closeReported) {
                    closeReported = true
                    true
                } else {
                    false
                }
            }
        if (notify) reportClosed()
    }

    private fun isCurrentRegistration(listener: NsdManager.RegistrationListener): Boolean =
        synchronized(registrationLock) { registrationListener === listener }

    private fun finishRegistration(
        listener: NsdManager.RegistrationListener,
        shutdown: AndroidNsdShutdownBarrier,
    ) {
        synchronized(registrationLock) {
            if (registrationListener === listener) {
                registrationListener = null
                registrationShutdown = null
            }
        }
        shutdown.finished()
    }

    private fun isCurrentDiscovery(listener: NsdManager.DiscoveryListener): Boolean =
        synchronized(discoveryLock) { discoveryListener === listener }

    private fun finishDiscovery(
        listener: NsdManager.DiscoveryListener,
        shutdown: AndroidNsdShutdownBarrier,
    ) {
        val callbacks =
            synchronized(discoveryLock) {
                if (discoveryListener !== listener) return
                discovering = false
                discoveryListener = null
                discoveryShutdown = null
                foundServiceNames.clear()
                byName.clear()
                serviceInfoCallbacks.values.toList().also { serviceInfoCallbacks.clear() }
            }
        shutdown.finished()
        unregisterServiceInfoCallbacks(callbacks)
    }

    private fun unregisterServiceInfoCallbacks(callbacks: List<Any>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            callbacks
                .filterIsInstance<NsdManager.ServiceInfoCallback>()
                .forEach(::unregisterServiceInfoCallbackInstance)
        }
    }

    companion object {
        private const val TAG = "DeviceConnect"
        private const val NSD_SHUTDOWN_TIMEOUT_MS = 5_000L

        private fun nsdErrorName(errorCode: Int): String =
            when (errorCode) {
                NsdManager.FAILURE_INTERNAL_ERROR -> "INTERNAL_ERROR"
                NsdManager.FAILURE_ALREADY_ACTIVE -> "ALREADY_ACTIVE"
                NsdManager.FAILURE_MAX_LIMIT -> "MAX_LIMIT"
                NsdManager.FAILURE_OPERATION_NOT_RUNNING -> "OPERATION_NOT_RUNNING"
                NsdManager.FAILURE_BAD_PARAMETERS -> "BAD_PARAMETERS"
                else -> "UNKNOWN($errorCode)"
            }
    }
}

internal class AndroidNsdShutdownBarrier(
    private val stopRequest: () -> Unit,
    private val onRequestFailure: (Throwable) -> Unit,
    private val onCompleted: () -> Unit = {},
) {
    private val completion = CompletableDeferred<Unit>()
    private val lock = Any()
    private var started = false
    private var closing = false
    private var stopRequested = false

    fun started() {
        val shouldStop =
            synchronized(lock) {
                started = true
                if (closing && !stopRequested) {
                    stopRequested = true
                    true
                } else {
                    false
                }
            }
        if (shouldStop) requestStop()
    }

    fun finished() {
        if (completion.complete(Unit)) onCompleted()
    }

    suspend fun close(timeoutMs: Long): Boolean {
        val shouldStop =
            synchronized(lock) {
                closing = true
                if (started && !stopRequested) {
                    stopRequested = true
                    true
                } else {
                    false
                }
            }
        if (shouldStop && !requestStop()) return false
        return withTimeoutOrNull(timeoutMs) {
            completion.await()
            true
        } ?: false
    }

    private fun requestStop(): Boolean =
        runCatching(stopRequest)
            .onFailure {
                synchronized(lock) { stopRequested = false }
                onRequestFailure(it)
            }.isSuccess
}

private val Settings_shared
    get() = Settings.shared
