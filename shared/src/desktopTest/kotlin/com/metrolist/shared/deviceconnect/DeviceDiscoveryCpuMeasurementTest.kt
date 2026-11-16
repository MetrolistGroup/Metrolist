/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.deviceconnect

import com.metrolist.shared.utils.LogBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.lang.management.ManagementFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceDiscoveryCpuMeasurementTest {
    @Test
    fun measureIdleDiscovery() {
        // Opt in locally. This opens real multicast sockets and takes about a minute.
        assumeTrue(System.getenv("METROLIST_MEASURE_DISCOVERY") == "1")
        println("mDNS addresses: ${deviceConnectLanAddresses().map { it.hostAddress }}")
        println("mDNS selected addresses: ${desktopDeviceConnectDiscoveryAddresses().map { it.hostAddress }}")
        measure("off")
        val lookups = AtomicInteger()
        val examples = ConcurrentLinkedQueue<String>()
        val previousSink = LogBridge.sink
        LogBridge.sink = { _, _, message, _ ->
            if ("fetching the full record" in message && lookups.incrementAndGet() <= 3) examples += message
        }
        val failures = ConcurrentLinkedQueue<String>()
        val foundPeer = CompletableFuture<DiscoveredDevice>()
        val discovery =
            DeviceDiscoveryController(
                Unit,
                onError = { message, _ -> failures += message },
                onClosed = {},
                onFailure = {},
            )
        try {
            discovery.registerService("cpu-measurement", "CPU measurement", 40_000, DeviceConnectDeviceType.DESKTOP)
            discovery.startDiscovery({ if (it.deviceId == "cpu-peer") foundPeer.complete(it) }, {})
            Thread.sleep(5_000)
            measure("on")
            println("mDNS TXT lookups: ${lookups.get()}, examples=$examples")
            val foundLocal = CompletableFuture<DiscoveredDevice>()
            val peer =
                DeviceDiscoveryController(
                    Unit,
                    onError = { message, _ -> failures += message },
                    onClosed = {},
                    onFailure = {},
                )
            try {
                peer.registerService("cpu-peer", "CPU peer", 40_001, DeviceConnectDeviceType.DESKTOP)
                peer.startDiscovery({ if (it.deviceId == "cpu-measurement") foundLocal.complete(it) }, {})
                assertEquals("CPU peer", foundPeer.get(20, TimeUnit.SECONDS).deviceName)
                assertEquals(40_000, foundLocal.get(20, TimeUnit.SECONDS).port)
                println("mDNS peer discovery: both controllers resolved each other's TXT records")
            } finally {
                runBlocking { peer.close() }
            }
            assertTrue(failures.isEmpty(), failures.joinToString())
        } finally {
            try {
                runBlocking { discovery.close() }
            } finally {
                LogBridge.sink = previousSink
            }
        }
    }

    private fun measure(label: String) {
        val threads = ManagementFactory.getThreadMXBean()
        check(threads.isThreadCpuTimeSupported)
        threads.isThreadCpuTimeEnabled = true
        val previous = mutableMapOf<Long, Long>()
        val cpuByName = mutableMapOf<String, Long>()
        var peakThreads = threads.threadCount

        fun sample(record: Boolean) {
            val ids = threads.allThreadIds
            peakThreads = maxOf(peakThreads, ids.size)
            ids.forEach { id ->
                val info = threads.getThreadInfo(id) ?: return@forEach
                val cpu = threads.getThreadCpuTime(id).takeIf { it >= 0 } ?: return@forEach
                val before = previous.put(id, cpu) ?: 0
                if (record) cpuByName.merge(info.threadName, cpu - before, Long::plus)
            }
        }
        sample(false)
        val start = System.nanoTime()
        repeat(25) {
            Thread.sleep(1_000)
            sample(true)
        }
        val elapsed = System.nanoTime() - start
        val cpu = cpuByName.values.sum()
        println(
            "mDNS $label: threads=${threads.threadCount}, peak=$peakThreads, cpuMs=${cpu / 1_000_000}, elapsedMs=${elapsed / 1_000_000}, corePercent=${100.0 * cpu / elapsed}",
        )
        cpuByName.entries.sortedByDescending { it.value }.take(12).forEach { (name, nanos) ->
            println("mDNS $label thread: $name cpuMs=${nanos / 1_000_000}")
        }
    }
}
