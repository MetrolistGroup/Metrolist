package com.metrolist.android.cast

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.os.ext.SdkExtensions
import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.cast.CastError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNsdManager
import org.robolectric.util.ReflectionHelpers
import java.net.InetAddress
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AndroidMdnsCastDiscoveryTest {
    @Test
    fun lostServicesDiscardQueuedAndActiveResultsEvenWhenRediscovered() {
        val context = RuntimeEnvironment.getApplication()
        val nsd = shadowOf(context.getSystemService(NsdManager::class.java))
        val main = shadowOf(Looper.getMainLooper())
        val discovery = AndroidMdnsCastDiscovery(context)
        val found = mutableListOf<CastDevice>()
        val lost = mutableListOf<String>()
        discovery.start(found::add, lost::add)
        main.idle()
        val listener = nsd.getDiscoveryListeners("_fcast._tcp.").orEmpty().single()

        fun service(name: String) =
            NsdServiceInfo().apply {
                serviceName = name
                serviceType = "_fcast._tcp."
                hostAddresses = listOf(InetAddress.getLoopbackAddress())
                port = 46899
            }
        val active = service("Active")
        val queued = service("Queued")
        listener.onServiceFound(active)
        listener.onServiceFound(queued)
        main.idle()
        val resolution = nsd.getResolveListeners(active).orEmpty().single()
        listener.onServiceLost(active)
        listener.onServiceLost(queued)
        val rediscovered = active
        listener.onServiceFound(rediscovered)
        main.idle()
        resolution.onServiceResolved(active)
        main.idle()
        assertTrue(found.isEmpty())
        assertEquals(listOf("fcast:Active", "fcast:Queued"), lost)
        assertTrue(nsd.getResolveListeners(queued).orEmpty().isEmpty())
        nsd
            .getResolveListeners(rediscovered)
            .orEmpty()
            .last()
            .onServiceResolved(rediscovered)
        main.idle()
        assertEquals(listOf("Active"), found.map { it.name })
        discovery.stop()
        main.idle()
    }

    @Test
    @Config(sdk = [32])
    fun discoveryFailuresRetryTwiceThenReportAndStoppedSessionsDoNotRetry() {
        val context = RuntimeEnvironment.getApplication()
        val nsd = shadowOf(context.getSystemService(NsdManager::class.java))
        val wifi = shadowOf(context.getSystemService(WifiManager::class.java))
        val main = shadowOf(Looper.getMainLooper())
        val discovery = AndroidMdnsCastDiscovery(context)
        val errors = mutableListOf<CastError>()
        discovery.start({}, {}, errors::add)
        main.idle()
        repeat(3) { attempt ->
            nsd
                .getDiscoveryListeners("_fcast._tcp.")
                .orEmpty()
                .single()
                .onStartDiscoveryFailed("_fcast._tcp.", NsdManager.FAILURE_INTERNAL_ERROR)
            main.idle()
            if (attempt < 2) {
                assertTrue(errors.isEmpty())
                main.idleFor(Duration.ofSeconds(1))
            }
        }
        assertEquals(listOf(CastError.DISCOVERY_FAILED), errors)
        assertEquals(1, wifi.activeLockCount)
        assertEquals(1, nsd.getDiscoveryListeners("_googlecast._tcp.").orEmpty().size)
        discovery.stop()
        main.idle()
        assertEquals(0, wifi.activeLockCount)
        discovery.start({}, {}, errors::add)
        main.idle()
        nsd
            .getDiscoveryListeners("_fcast._tcp.")
            .orEmpty()
            .single()
            .onStartDiscoveryFailed("_fcast._tcp.", NsdManager.FAILURE_INTERNAL_ERROR)
        main.idle()
        discovery.stop()
        main.idleFor(Duration.ofSeconds(2))
        assertTrue(nsd.getDiscoveryListeners("_fcast._tcp.").orEmpty().isEmpty())
        assertEquals(1, errors.size)
        assertEquals(0, wifi.activeLockCount)
    }

    @Test
    @Config(sdk = [24, 32])
    fun totalDiscoveryFailureReleasesLockAndAllowsRestart() {
        val context = RuntimeEnvironment.getApplication()
        val nsd = shadowOf(context.getSystemService(NsdManager::class.java))
        val wifi = shadowOf(context.getSystemService(WifiManager::class.java))
        val main = shadowOf(Looper.getMainLooper())
        val discovery = AndroidMdnsCastDiscovery(context)
        val errors = mutableListOf<CastError>()
        val types = listOf("_fcast._tcp.", "_googlecast._tcp.")
        discovery.start({}, {}, errors::add)
        main.idle()
        assertEquals(1, wifi.activeLockCount)
        val old = nsd.getDiscoveryListeners(types.first()).orEmpty().single()
        repeat(3) { attempt ->
            types.forEach { type ->
                nsd
                    .getDiscoveryListeners(type)
                    .orEmpty()
                    .single()
                    .onStartDiscoveryFailed(type, NsdManager.FAILURE_INTERNAL_ERROR)
            }
            main.idle()
            if (attempt < 2) {
                assertTrue(errors.isEmpty())
                assertEquals(1, wifi.activeLockCount)
                main.idleFor(Duration.ofSeconds(1))
            }
        }
        assertEquals(listOf(CastError.DISCOVERY_FAILED, CastError.DISCOVERY_FAILED), errors)
        assertEquals(0, wifi.activeLockCount)
        types.forEach { assertTrue(nsd.getDiscoveryListeners(it).orEmpty().isEmpty()) }

        discovery.start({}, {}, errors::add)
        main.idle()
        old.onDiscoveryStopped(types.first())
        old.onStartDiscoveryFailed(types.first(), NsdManager.FAILURE_INTERNAL_ERROR)
        main.idleFor(Duration.ofSeconds(2))
        assertEquals(1, wifi.activeLockCount)
        types.forEach { assertEquals(1, nsd.getDiscoveryListeners(it).orEmpty().size) }
        assertEquals(2, errors.size)
        discovery.stop()
        main.idle()
        assertEquals(0, wifi.activeLockCount)
    }

    @Test
    fun unexpectedDiscoveryStopsKeepLockUntilQueuedResolutionsFinish() {
        val context = RuntimeEnvironment.getApplication()
        val nsdManager = context.getSystemService(NsdManager::class.java)
        val nsd = shadowOf(nsdManager)
        val wifi = shadowOf(context.getSystemService(WifiManager::class.java))
        val main = shadowOf(Looper.getMainLooper())
        val discovery = AndroidMdnsCastDiscovery(context)
        val found = mutableListOf<CastDevice>()
        discovery.start(found::add, {})
        main.idle()
        val fcast = nsd.getDiscoveryListeners("_fcast._tcp.").orEmpty().single()
        val googleCast = nsd.getDiscoveryListeners("_googlecast._tcp.").orEmpty().single()
        val services =
            listOf("Living room", "Kitchen").map { name ->
                NsdServiceInfo().apply {
                    serviceName = name
                    serviceType = "_fcast._tcp."
                    hostAddresses = listOf(InetAddress.getLoopbackAddress())
                    port = 46899
                }
            }
        services.forEach(fcast::onServiceFound)
        main.idle()
        nsdManager.stopServiceDiscovery(fcast)
        fcast.onDiscoveryStopped("_fcast._tcp.")
        nsdManager.stopServiceDiscovery(googleCast)
        googleCast.onDiscoveryStopped("_googlecast._tcp.")
        main.idle()
        assertEquals(1, wifi.activeLockCount)
        assertTrue(nsd.getResolveListeners(services.last()).orEmpty().isEmpty())

        nsd
            .getResolveListeners(services.first())
            .orEmpty()
            .single()
            .onResolveFailed(services.first(), NsdManager.FAILURE_INTERNAL_ERROR)
        main.idle()
        assertEquals(1, wifi.activeLockCount)
        nsd
            .getResolveListeners(services.last())
            .orEmpty()
            .single()
            .onServiceResolved(services.last())
        main.idle()
        assertEquals(listOf("Kitchen"), found.map { it.name })
        assertEquals(0, wifi.activeLockCount)
        discovery.stop()
        main.idle()
        assertEquals(0, wifi.activeLockCount)
    }

    @Test
    @Config(sdk = [33])
    fun platformManagedMulticastDoesNotTakeAnAppLock() {
        val context = RuntimeEnvironment.getApplication()
        val nsd = shadowOf(context.getSystemService(NsdManager::class.java))
        val wifi = shadowOf(context.getSystemService(WifiManager::class.java))
        val main = shadowOf(Looper.getMainLooper())
        val originalExtension = SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU)
        try {
            listOf(6, 7).forEach { extension ->
                ReflectionHelpers.setStaticField(SdkExtensions::class.java, "T_EXTENSION_INT", extension)
                val discovery = AndroidMdnsCastDiscovery(context)
                discovery.start({}, {})
                main.idle()
                assertEquals(if (extension < 7) 1 else 0, wifi.activeLockCount)
                assertEquals(1, nsd.getDiscoveryListeners("_fcast._tcp.").orEmpty().size)
                assertEquals(1, nsd.getDiscoveryListeners("_googlecast._tcp.").orEmpty().size)
                discovery.stop()
                main.idle()
                assertEquals(0, wifi.activeLockCount)
            }
        } finally {
            ReflectionHelpers.setStaticField(SdkExtensions::class.java, "T_EXTENSION_INT", originalExtension)
        }
    }

    @Test
    @Config(sdk = [24, 32])
    @Suppress("DEPRECATION")
    fun backgroundCallbacksAreSerializedAndOldSessionsCannotAffectRestart() {
        val context = RuntimeEnvironment.getApplication()
        val nsd = shadowOf(context.getSystemService(NsdManager::class.java))
        val wifi = shadowOf(context.getSystemService(WifiManager::class.java))
        val main = shadowOf(Looper.getMainLooper())
        val discovery = AndroidMdnsCastDiscovery(context)
        val found = mutableListOf<CastDevice>()
        val lost = mutableListOf<String>()

        fun background(action: () -> Unit) {
            Thread(action).apply {
                start()
                join()
            }
        }
        background { discovery.start({ found += it }, { lost += it }) }
        main.idle()
        val old = nsd.getDiscoveryListeners("_fcast._tcp.").orEmpty().single()
        val service =
            NsdServiceInfo().apply {
                serviceName = "Living room"
                serviceType = "_fcast._tcp."
                host = InetAddress.getLoopbackAddress()
                port = 46899
            }
        background { old.onServiceFound(service) }
        assertTrue(nsd.getResolveListeners(service).orEmpty().isEmpty())
        main.idle()
        val staleResolution = nsd.getResolveListeners(service).orEmpty().single()
        background {
            discovery.stop()
            discovery.start({ found += it }, { lost += it })
        }
        main.idle()
        assertEquals(1, wifi.activeLockCount)
        val current = nsd.getDiscoveryListeners("_fcast._tcp.").orEmpty().single()
        background {
            old.onServiceFound(service)
            old.onServiceLost(service)
            current.onServiceFound(service)
        }
        main.idle()
        assertEquals(1, nsd.getResolveListeners(service).orEmpty().size)
        background { staleResolution.onServiceResolved(service) }
        main.idle()
        assertTrue(found.isEmpty())
        assertTrue(lost.isEmpty())
        assertEquals(2, nsd.getResolveListeners(service).orEmpty().size)
        val newResolution = nsd.getResolveListeners(service).orEmpty().last()
        background {
            staleResolution.onResolveFailed(service, 0)
            current.onServiceFound(service)
        }
        main.idle()
        assertEquals(2, nsd.getResolveListeners(service).orEmpty().size)
        background { newResolution.onServiceResolved(service) }
        main.idle()
        assertEquals(1, found.size)
        assertEquals(3, nsd.getResolveListeners(service).orEmpty().size)
        background { discovery.stop() }
        main.idle()
        assertEquals(0, wifi.activeLockCount)
    }

    @Test
    @Config(sdk = [24, 32])
    fun failedLegacyResolutionReleasesSlotBeforeOrAfterRestart() {
        val context = RuntimeEnvironment.getApplication()
        val nsd = shadowOf(context.getSystemService(NsdManager::class.java))
        val wifi = shadowOf(context.getSystemService(WifiManager::class.java))
        val main = shadowOf(Looper.getMainLooper())
        listOf(true, false).forEach { completeBeforeRestart ->
            val discovery = AndroidMdnsCastDiscovery(context)
            val errors = mutableListOf<CastError>()
            val service =
                NsdServiceInfo().apply {
                    serviceName = "Receiver-$completeBeforeRestart"
                    serviceType = "_fcast._tcp."
                }
            discovery.start({}, {}, errors::add)
            main.idle()
            nsd
                .getDiscoveryListeners(service.serviceType)
                .orEmpty()
                .single()
                .onServiceFound(service)
            main.idle()
            val oldResolution = nsd.getResolveListeners(service).orEmpty().single()
            discovery.stop()
            main.idle()
            assertEquals(0, wifi.activeLockCount)
            if (completeBeforeRestart) {
                oldResolution.onResolveFailed(service, NsdManager.FAILURE_INTERNAL_ERROR)
                main.idle()
            }
            discovery.start({}, {}, errors::add)
            main.idle()
            nsd
                .getDiscoveryListeners(service.serviceType)
                .orEmpty()
                .single()
                .onServiceFound(service)
            main.idle()
            if (!completeBeforeRestart) {
                assertEquals(1, nsd.getResolveListeners(service).orEmpty().size)
                oldResolution.onResolveFailed(service, NsdManager.FAILURE_INTERNAL_ERROR)
                main.idle()
            }
            assertEquals(2, nsd.getResolveListeners(service).orEmpty().size)
            assertTrue(errors.isEmpty())
            discovery.stop()
            main.idle()
            nsd
                .getResolveListeners(service)
                .orEmpty()
                .last()
                .onResolveFailed(service, NsdManager.FAILURE_INTERNAL_ERROR)
            main.idle()
            assertEquals(0, wifi.activeLockCount)
        }
    }

    @Test
    @Config(sdk = [34], shadows = [CancellableNsdManager::class])
    fun restartWaitsForCancellationOrResolutionCompletion() {
        val context = RuntimeEnvironment.getApplication()
        val nsd = Shadow.extract<CancellableNsdManager>(context.getSystemService(NsdManager::class.java))
        val wifi = shadowOf(context.getSystemService(WifiManager::class.java))
        val main = shadowOf(Looper.getMainLooper())
        listOf(true, false).forEach { cancellationSucceeds ->
            val discovery = AndroidMdnsCastDiscovery(context)
            val found = mutableListOf<CastDevice>()
            val service =
                NsdServiceInfo().apply {
                    serviceName = "Receiver-$cancellationSucceeds"
                    serviceType = "_fcast._tcp."
                    hostAddresses = listOf(InetAddress.getLoopbackAddress())
                    port = 46899
                }
            discovery.start(found::add, {})
            main.idle()
            nsd
                .getDiscoveryListeners(service.serviceType)
                .orEmpty()
                .single()
                .onServiceFound(service)
            main.idle()
            val oldResolution = nsd.getResolveListeners(service).orEmpty().single()
            discovery.stop()
            main.idle()
            assertEquals(oldResolution, nsd.stopRequests.last())
            assertEquals(0, wifi.activeLockCount)
            discovery.start(found::add, {})
            main.idle()
            val current = nsd.getDiscoveryListeners(service.serviceType).orEmpty().single()
            current.onServiceFound(service)
            main.idle()
            assertEquals(1, nsd.getResolveListeners(service).orEmpty().size)
            if (cancellationSucceeds) {
                oldResolution.onResolutionStopped(service)
            } else {
                oldResolution.onStopResolutionFailed(service, NsdManager.FAILURE_OPERATION_NOT_RUNNING)
                main.idle()
                assertEquals(1, nsd.getResolveListeners(service).orEmpty().size)
                oldResolution.onServiceResolved(service)
            }
            main.idle()
            assertTrue(found.isEmpty())
            assertEquals(2, nsd.getResolveListeners(service).orEmpty().size)
            val newResolution = nsd.getResolveListeners(service).orEmpty().last()
            current.onServiceFound(service)
            oldResolution.onResolutionStopped(service)
            main.idle()
            assertEquals(2, nsd.getResolveListeners(service).orEmpty().size)
            newResolution.onServiceResolved(service)
            main.idle()
            assertEquals(listOf(service.serviceName), found.map { it.name })
            assertEquals(3, nsd.getResolveListeners(service).orEmpty().size)
            discovery.stop()
            main.idle()
            assertEquals(nsd.getResolveListeners(service).orEmpty().last(), nsd.stopRequests.last())
            nsd.stopRequests.last().onResolutionStopped(service)
            main.idle()
            assertEquals(0, wifi.activeLockCount)
        }
    }
}

@Implements(NsdManager::class)
class CancellableNsdManager : ShadowNsdManager() {
    val stopRequests = mutableListOf<NsdManager.ResolveListener>()

    @Implementation(minSdk = 34)
    fun stopServiceResolution(listener: NsdManager.ResolveListener) {
        stopRequests += listener
    }
}
