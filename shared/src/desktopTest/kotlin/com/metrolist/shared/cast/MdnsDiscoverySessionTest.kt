package com.metrolist.shared.cast

import com.metrolist.shared.utils.MdnsDiscoverySession
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MdnsDiscoverySessionTest {
    @Test
    fun lookupsFinishBeforeCloseWithoutHoldingTheLibraryMonitor() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val finished = AtomicBoolean()
        val closed = AtomicBoolean()
        val resource =
            object : Closeable {
                override fun close() {
                    assertFalse(Thread.holdsLock(this))
                    assertTrue(finished.get())
                    closed.set(true)
                }
            }
        val session =
            MdnsDiscoverySession(
                addresses = { listOf(Unit) },
                create = { resource },
                configure = {},
                onReset = {},
                onError = { throw AssertionError(it) },
            )
        session.start(waitUntilStarted = true)
        val lookup =
            CompletableFuture.runAsync {
                session.withResource(resource) {
                    assertFalse(Thread.holdsLock(resource))
                    entered.countDown()
                    check(resume.await(2, TimeUnit.SECONDS))
                    finished.set(true)
                }
            }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val stopping = CompletableFuture.runAsync { session.close() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!session.isStopping && System.nanoTime() < deadline) Thread.yield()
            assertTrue(session.isStopping)
        } finally {
            resume.countDown()
        }
        lookup.get(2, TimeUnit.SECONDS)
        stopping.get(2, TimeUnit.SECONDS)
        assertTrue(closed.get())
        assertNull(session.withResource(resource) { error("lookup after close") })
    }

    @Test
    fun closeWithdrawsAllInterfacesInParallelBeforeAcknowledgingShutdown() {
        val configured = CountDownLatch(2)
        val closing = CountDownLatch(2)
        val closed = AtomicInteger()
        val session =
            MdnsDiscoverySession(
                addresses = { listOf("first", "second") },
                create = {
                    Closeable {
                        closing.countDown()
                        check(closing.await(2, TimeUnit.SECONDS))
                        closed.incrementAndGet()
                    }
                },
                configure = { configured.countDown() },
                onReset = {},
                onError = { throw AssertionError(it) },
            )
        try {
            session.start()
            assertTrue(configured.await(2, TimeUnit.SECONDS))
        } finally {
            session.close()
        }
        assertEquals(2, closed.get())
    }

    @Test
    fun duplicateStartsCreateOncePerAddressAndCloseIsIdempotent() {
        val created = AtomicInteger()
        val closed = AtomicInteger()
        val configured = CountDownLatch(2)
        val session =
            MdnsDiscoverySession(
                addresses = { listOf("first", "first", "second") },
                create = {
                    created.incrementAndGet()
                    Closeable { closed.incrementAndGet() }
                },
                configure = { configured.countDown() },
                onReset = {},
                onError = { throw AssertionError(it) },
            )
        try {
            session.start()
            session.start()
            assertTrue(configured.await(2, TimeUnit.SECONDS))
        } finally {
            session.close()
        }
        session.close()
        session.start()
        assertEquals(2, created.get())
        assertEquals(2, closed.get())
    }

    @Test
    fun stopDuringCreationClosesTheResourceWithoutRegisteringListeners() {
        val creating = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val closed = AtomicInteger()
        val configured = AtomicInteger()
        val session =
            MdnsDiscoverySession(
                addresses = { listOf(Unit) },
                create = {
                    creating.countDown()
                    check(resume.await(2, TimeUnit.SECONDS))
                    Closeable { closed.incrementAndGet() }
                },
                configure = { configured.incrementAndGet() },
                onReset = {},
                onError = { throw AssertionError(it) },
            )
        session.start()
        assertTrue(creating.await(2, TimeUnit.SECONDS))
        val stopping = CompletableFuture.runAsync { session.close() }
        try {
            // close marks the session stopped before waiting for the factory.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!session.isStopping && System.nanoTime() < deadline) Thread.yield()
            assertTrue(session.isStopping)
        } finally {
            resume.countDown()
        }
        stopping.get(2, TimeUnit.SECONDS)
        assertEquals(0, configured.get())
        assertEquals(1, closed.get())
    }

    @Test
    fun renewalClosesEveryOldResourceBeforeCreatingReplacements() {
        val events = ConcurrentLinkedQueue<String>()
        val sequence = AtomicInteger()
        val renewed = CountDownLatch(1)
        val session =
            MdnsDiscoverySession(
                addresses = { listOf(Unit) },
                create = {
                    val id = sequence.incrementAndGet()
                    events.add("open $id")
                    Closeable { events.add("close $id") }
                },
                configure = { if (sequence.get() == 2) renewed.countDown() },
                beforeClose = { events.add("detach") },
                onReset = { events.add("reset") },
                onError = { throw AssertionError(it) },
                lifetimeMillis = 10,
            )
        try {
            session.start()
            assertTrue(renewed.await(2, TimeUnit.SECONDS))
        } finally {
            session.close()
        }
        assertEquals(listOf("open 1", "detach", "close 1", "reset", "open 2"), events.toList().take(5))
        assertEquals(sequence.get(), events.count { it.startsWith("close ") })
    }

    @Test
    fun setupFailureClosesPartiallyConfiguredResourcesAndReportsTheError() {
        val closed = AtomicInteger()
        val reported = CompletableFuture<Throwable>()
        val session =
            MdnsDiscoverySession(
                addresses = { listOf(Unit) },
                create = { Closeable { closed.incrementAndGet() } },
                configure = { error("listener registration failed") },
                onReset = {},
                onError = { reported.complete(it) },
            )
        try {
            session.start()
            assertEquals("listener registration failed", reported.get(2, TimeUnit.SECONDS).message)
        } finally {
            session.close()
        }
        assertEquals(1, closed.get())
    }

    @Test
    fun failedClosePreventsRenewalAndCanBeRetried() {
        val created = AtomicInteger()
        val allowClose = CountDownLatch(1)
        val otherClosed = AtomicInteger()
        val reported = CompletableFuture<Throwable>()
        val session =
            MdnsDiscoverySession(
                addresses = { listOf("failing", "other") },
                create = { address ->
                    created.incrementAndGet()
                    Closeable {
                        if (address == "failing") {
                            check(allowClose.count == 0L) { "close failed" }
                        } else {
                            otherClosed.incrementAndGet()
                        }
                    }
                },
                configure = {},
                onReset = {},
                onError = { reported.complete(it) },
                lifetimeMillis = 10,
            )
        try {
            session.start()
            assertEquals("close failed", reported.get(2, TimeUnit.SECONDS).message)
            assertFailsWith<IllegalStateException> { session.close() }
            assertEquals(2, created.get())
            assertEquals(1, otherClosed.get())
        } finally {
            allowClose.countDown()
            session.close()
        }
    }

    @Test
    fun unavailableInterfaceDoesNotPreventSynchronousStartupOnOtherInterfaces() {
        val errors = ConcurrentLinkedQueue<Throwable>()
        val configured = CountDownLatch(1)
        val closed = AtomicInteger()
        val session =
            MdnsDiscoverySession(
                addresses = { listOf("unavailable", "available") },
                create = { address ->
                    if (address == "unavailable") error("interface is down")
                    Closeable { closed.incrementAndGet() }
                },
                configure = { configured.countDown() },
                onReset = {},
                onError = { errors.add(it) },
            )
        try {
            session.start(waitUntilStarted = true)
            assertEquals(0L, configured.count)
            assertEquals(listOf("interface is down"), errors.map { it.message })
        } finally {
            session.close()
        }
        assertEquals(1, closed.get())
    }

    @Test
    fun libraryThreadFailureClosesDiscoveryAndReportsInsteadOfReachingTheAppHandler() {
        val closed = AtomicInteger()
        val reported = CompletableFuture<Throwable>()
        val session =
            MdnsDiscoverySession(
                addresses = { listOf(Unit) },
                create = { Closeable { closed.incrementAndGet() } },
                configure = {
                    thread(name = "SocketListener-test", isDaemon = true) { throw OutOfMemoryError("test failure") }
                },
                onReset = {},
                onError = { reported.complete(it) },
            )
        try {
            session.start()
            assertTrue(reported.get(2, TimeUnit.SECONDS) is OutOfMemoryError)
        } finally {
            session.close()
        }
        assertEquals(1, closed.get())
    }
}
