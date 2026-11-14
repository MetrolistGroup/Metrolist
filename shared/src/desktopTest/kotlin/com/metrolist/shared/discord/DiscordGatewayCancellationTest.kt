package com.metrolist.shared.discord

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestPipeline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiscordGatewayCancellationTest {
    @Test
    fun connectionTimeoutCancelsThePendingHandshake() =
        runTest {
            val connectionScope = CoroutineScope(coroutineContext + SupervisorJob())
            val handshakeStarted = CompletableDeferred<Unit>()
            val handshakeCancelled = CompletableDeferred<Unit>()
            val client = delayedHandshakeClient(handshakeStarted, handshakeCancelled)
            val gateway = DiscordGateway("test-app", { "test-token" }, connectionScope, client)
            try {
                val connect = async { gateway.connect() }
                handshakeStarted.await()
                advanceTimeBy(15_000)
                runCurrent()
                assertFalse(connect.await())
                assertTrue(handshakeCancelled.isCompleted, "A timed-out handshake must not open a late connection")
            } finally {
                connectionScope.cancel()
                client.close()
            }
        }

    @Test
    fun cancellingConnectCancelsThePendingHandshake() =
        runTest {
            val connectionScope = CoroutineScope(coroutineContext + SupervisorJob())
            val handshakeStarted = CompletableDeferred<Unit>()
            val handshakeCancelled = CompletableDeferred<Unit>()
            val client = delayedHandshakeClient(handshakeStarted, handshakeCancelled)
            val gateway = DiscordGateway("test-app", { "test-token" }, connectionScope, client)
            try {
                val connect = launch { gateway.connect() }
                handshakeStarted.await()
                connect.cancelAndJoin()
                runCurrent()
                assertTrue(handshakeCancelled.isCompleted, "The handshake must not outlive the cancelled connect call")
            } finally {
                connectionScope.cancel()
                client.close()
            }
        }

    @Test
    fun suspendingTheGatewayCancelsAnUnfinishedHandshake() =
        runTest {
            val connectionScope = CoroutineScope(coroutineContext + SupervisorJob())
            val handshakeStarted = CompletableDeferred<Unit>()
            val handshakeCancelled = CompletableDeferred<Unit>()
            val client = delayedHandshakeClient(handshakeStarted, handshakeCancelled)
            val gateway = DiscordGateway("test-app", { "test-token" }, connectionScope, client)
            try {
                val connect = launch { gateway.connect() }
                handshakeStarted.await()
                gateway.suspendConnection()
                runCurrent()
                assertTrue(handshakeCancelled.isCompleted)
                assertTrue(connect.isCancelled)
            } finally {
                connectionScope.cancel()
                client.close()
            }
        }

    @Test
    fun cancellingTheConnectionScopeReleasesTheConnectWaiter() =
        runTest {
            val connectionScope = CoroutineScope(coroutineContext + SupervisorJob())
            val handshakeStarted = CompletableDeferred<Unit>()
            val handshakeCancelled = CompletableDeferred<Unit>()
            val client = delayedHandshakeClient(handshakeStarted, handshakeCancelled)
            val gateway = DiscordGateway("test-app", { "test-token" }, connectionScope, client)
            var connect: Job? = null
            try {
                connect = launch { gateway.connect() }
                handshakeStarted.await()
                connectionScope.cancel()
                runCurrent()
                assertTrue(handshakeCancelled.isCompleted)
                assertTrue(connect.isCompleted, "Scope cancellation must release the waiter without waiting for its timeout")
            } finally {
                connect?.cancelAndJoin()
                connectionScope.cancel()
                client.close()
            }
        }

    private fun delayedHandshakeClient(
        started: CompletableDeferred<Unit>,
        cancelled: CompletableDeferred<Unit>,
    ): HttpClient =
        HttpClient(MockEngine { error("Handshake must remain suspended") }) {
            install(WebSockets)
        }.apply {
            requestPipeline.intercept(HttpRequestPipeline.Before) {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
}
