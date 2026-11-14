package com.metrolist.shared.ui.settings

import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OpenRouterAuthControllerTest {
    @Test
    fun browserResultArrivesAfterStartAndDisposalWithoutLosingTheVerifier() =
        runTest {
            val settings = Settings(MapSettings())
            var complete: ((Boolean) -> Unit)? = null
            val client = HttpClient(MockEngine { error("No exchange requested") })
            val controller =
                OpenRouterAuthController(
                    settings,
                    client,
                    CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])),
                ) { _, result -> complete = result }
            try {
                controller.start()
                val verifier = settings.lyricsOpenRouterOAuthSession
                assertTrue(verifier.isNotBlank())
                controller.dispose()
                checkNotNull(complete)(true)
                assertEquals(verifier, settings.lyricsOpenRouterOAuthSession)
            } finally {
                controller.dispose()
                client.close()
            }
        }

    @Test
    fun disposedControllerFinishesExchangeAndConsumesVerifierBeforeRequest() =
        runTest {
            val settings = Settings(MapSettings())
            val requestStarted = CompletableDeferred<Unit>()
            val finishRequest = CompletableDeferred<Unit>()
            val client =
                HttpClient(MockEngine) {
                    engine {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        addHandler {
                            assertEquals("", settings.lyricsOpenRouterOAuthSession)
                            requestStarted.complete(Unit)
                            finishRequest.await()
                            respond("""{"key":"persisted-key"}""")
                        }
                    }
                }
            val controller =
                OpenRouterAuthController(
                    settings,
                    client,
                    CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])),
                ) { _, result -> result(true) }
            try {
                controller.start()
                assertTrue(settings.lyricsOpenRouterOAuthSession.isNotBlank())
                controller.exchange("sample-code")
                runCurrent()
                assertTrue(requestStarted.isCompleted)
                controller.dispose()
                assertEquals("", settings.lyricsOpenRouterOAuthKey)
                finishRequest.complete(Unit)
                runCurrent()
                assertEquals("", settings.lyricsOpenRouterOAuthSession)
                assertEquals("persisted-key", settings.lyricsOpenRouterOAuthKey)
            } finally {
                controller.dispose()
                client.close()
            }
        }

    @Test
    fun pendingVerifierSurvivesIdleDisposalAndRestoresOnReturn() =
        runTest {
            val settings = Settings(MapSettings())

            fun controller() =
                OpenRouterAuthController(
                    settings,
                    HttpClient(MockEngine { error("No exchange requested") }),
                    CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])),
                ) { _, result -> result(true) }

            val first = controller()
            try {
                first.start()
                val pending = settings.lyricsOpenRouterOAuthSession
                assertTrue(pending.isNotBlank())
                first.dispose()
                assertEquals(pending, settings.lyricsOpenRouterOAuthSession)
                val restored = controller()
                try {
                    assertTrue(restored.awaitingCode)
                    restored.cancel()
                    assertEquals("", settings.lyricsOpenRouterOAuthSession)
                } finally {
                    restored.dispose()
                }
            } finally {
                first.dispose()
            }
        }

    @Test
    fun cancelAndFailedBrowserLaunchDiscardVerifier() =
        runTest {
            for (opened in listOf(true, false)) {
                val settings = Settings(MapSettings())
                val controller =
                    OpenRouterAuthController(
                        settings,
                        HttpClient(MockEngine { error("No exchange requested") }),
                        CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])),
                    ) { _, result -> result(opened) }
                try {
                    controller.start()
                    controller.cancel()
                    assertFalse(controller.awaitingCode)
                    assertEquals("", settings.lyricsOpenRouterOAuthSession)
                } finally {
                    controller.dispose()
                }
            }
        }
}
