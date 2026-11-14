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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
                scope.cancel()
                client.close()
                YouTubeSyncState.clear()
                Settings.replaceSharedForTest(null)
            }
        }
}
