package com.metrolist.desktop.utils

import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSingleInstanceTest {
    @Test
    fun concurrentRequestsDeliverCompletePayloadsExactlyOnce() {
        val directory = Files.createTempDirectory("metrolist-single-instance-")
        val owner = SingleInstanceLock(directory, "test")
        val payloads = (1..20).map { "https://music.youtube.com/watch?v=$it&payload=" + "x".repeat(100_000) }
        val received = ConcurrentLinkedQueue<String?>()
        val delivered = CountDownLatch(payloads.size)
        val senders = Executors.newFixedThreadPool(4)
        try {
            assertTrue(
                owner.acquire {
                    received.add(it)
                    delivered.countDown()
                },
            )
            payloads
                .map { payload ->
                    senders.submit { assertFalse(SingleInstanceLock(directory, "test").acquire(payload) {}) }
                }.forEach { it.get(10, TimeUnit.SECONDS) }
            assertTrue(delivered.await(10, TimeUnit.SECONDS))
            assertEquals(payloads.toSet(), received.toSet())
            assertEquals(payloads.size, received.size)
        } finally {
            senders.shutdownNow()
            owner.release()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun secondInstanceSignalsOwnerAndCannotAcquireLock() {
        val directory = Files.createTempDirectory("metrolist-single-instance-")
        val restoreRequested = CountDownLatch(1)
        val owner = SingleInstanceLock(directory, "test")
        val contender = SingleInstanceLock(directory, "test")

        try {
            assertTrue(owner.acquire { restoreRequested.countDown() })
            assertFalse(contender.acquire {})
            assertTrue(restoreRequested.await(5, TimeUnit.SECONDS))
        } finally {
            contender.release()
            owner.release()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun secondInstanceSignalsOwnerWithPayload() {
        val directory = Files.createTempDirectory("metrolist-single-instance-")
        val payloadLatch = CountDownLatch(1)
        var receivedPayload: String? = null
        val owner = SingleInstanceLock(directory, "test")
        val contender = SingleInstanceLock(directory, "test")

        try {
            assertTrue(
                owner.acquire { payload ->
                    receivedPayload = payload
                    payloadLatch.countDown()
                },
            )
            val testUrl = "https://music.youtube.com/watch?v=dQw4w9WgXcQ"
            assertFalse(contender.acquire(testUrl) {})
            assertTrue(payloadLatch.await(5, TimeUnit.SECONDS))
            assertEquals(testUrl, receivedPayload)
        } finally {
            contender.release()
            owner.release()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun releasedLockCanBeAcquiredByAnotherInstance() {
        val directory = Files.createTempDirectory("metrolist-single-instance-")
        val first = SingleInstanceLock(directory, "test")
        val second = SingleInstanceLock(directory, "test")

        try {
            assertTrue(first.acquire {})
            first.release()
            assertTrue(second.acquire {})
        } finally {
            second.release()
            first.release()
            directory.toFile().deleteRecursively()
        }
    }
}
