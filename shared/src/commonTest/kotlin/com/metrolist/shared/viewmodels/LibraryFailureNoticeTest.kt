package com.metrolist.shared.viewmodels

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.AccountLibraryFailureTest
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.auth.YouTubeSyncState
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class LibraryFailureNoticeTest {
    @Test
    fun partialRefreshReportsFailureAndRetryClearsItWithoutCachingSuccess() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            YouTubeSyncState.clear()
            val fail = atomic(true)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val client =
                HttpClient(
                    MockEngine { request ->
                        val body = (request.body as TextContent).text
                        respond(
                            AccountLibraryFailureTest.EMPTY_LIBRARY,
                            if (fail.value && "FEmusic_liked_albums" in body) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val model =
                    LibraryViewModel(
                        scope,
                        repository =
                            YouTubeRepository(
                                InnerTube(client, retryDelay = {}).apply {
                                    cookie =
                                        "SAPISID=test"
                                },
                            ),
                        syncRetryDelays = emptyList(),
                    )
                model.setActive(true)
                withTimeout(5_000) { model.uiState.first { it.error } }
                assertTrue(model.uiState.value.error)
                assertEquals(0L, Settings.shared.lastLibraryRefreshEpoch)
                val failures = withTimeout(5_000) { YouTubeSyncState.failures.first { "library" in it } }
                val notice = assertNotNull(failures["library"])
                YouTubeSyncState.failed("library") { error("Duplicate replaced the retry") }
                assertEquals(notice, YouTubeSyncState.failures.value["library"])
                fail.value = false
                notice.retry()
                withTimeout(5_000) { YouTubeSyncState.failures.first { "library" !in it } }
                assertTrue(Settings.shared.lastLibraryRefreshEpoch > 0)
                assertFalse(model.uiState.value.isRefreshing)
                assertFalse(model.uiState.value.error)
            } finally {
                // Refresh's finally block reads Settings.shared, so it must finish before the test settings are removed.
                scope.coroutineContext.job.cancelAndJoin()
                client.close()
                YouTubeSyncState.clear()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun transientRefreshFailureRetriesQuietly() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            YouTubeSyncState.clear()
            val failingRequests = atomic(0)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val client =
                HttpClient(
                    MockEngine { request ->
                        val body = (request.body as TextContent).text
                        // InnerTube retries each request itself; fail every attempt of the first refresh only.
                        val fail = "FEmusic_liked_albums" in body && failingRequests.incrementAndGet() <= INNERTUBE_ATTEMPTS
                        respond(
                            AccountLibraryFailureTest.EMPTY_LIBRARY,
                            if (fail) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val model =
                    LibraryViewModel(
                        scope,
                        repository = YouTubeRepository(InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }),
                        syncRetryDelays = listOf(10.milliseconds),
                    )
                val errors = scope.async { model.uiState.first { it.error } }
                model.setActive(true)
                withTimeout(5_000) { while (Settings.shared.lastLibraryRefreshEpoch == 0L) delay(10) }
                assertTrue(failingRequests.value > INNERTUBE_ATTEMPTS)
                assertFalse(errors.isCompleted)
                assertFalse(model.uiState.value.error)
                assertFalse("library" in YouTubeSyncState.failures.value)
                errors.cancel()
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
                client.close()
                YouTubeSyncState.clear()
                Settings.replaceSharedForTest(null)
            }
        }

    private companion object {
        const val INNERTUBE_ATTEMPTS = 3
    }
}
