package com.metrolist.shared.viewmodels

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.responseDecodeDispatcher
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRefreshConcurrencyTest {
    @Test
    fun podcastAndEpisodeReadsOverlapAndKeepOptionalFailuresSeparate() =
        runTest {
            for (failedBrowseId in listOf(null, "VLSE", "FEmusic_library_non_music_audio_list")) {
                val start = testScheduler.currentTime
                withRepository(
                    beforeResponse = { id -> delay(if (id == "VLSE") 2_000 else 1_000) },
                    failedBrowseId = failedBrowseId,
                ) { repository, _ ->
                    val (podcasts, episodes) = loadPodcastLibrary(repository, canReconcile = true)
                    assertEquals(failedBrowseId != "FEmusic_library_non_music_audio_list", podcasts.isSuccess)
                    assertEquals(failedBrowseId != "VLSE", episodes.isSuccess)
                    assertEquals(2_000L, testScheduler.currentTime - start)
                }
            }
        }

    @Test
    fun cancellationStopsBothReads() =
        runTest {
            var active = 0
            var started = 0
            withRepository(beforeResponse = {
                started++
                active++
                try {
                    delay(10_000)
                } finally {
                    active--
                }
            }) { repository, _ ->
                val refresh = async { loadPodcastLibrary(repository, canReconcile = true) }
                runCurrent()
                assertEquals(2, active)
                refresh.cancelAndJoin()
                assertEquals(2, started)
                assertEquals(0, active)
                assertEquals(0L, testScheduler.currentTime)
            }
        }

    @Test
    fun sessionChangeCancelsInsteadOfReturningAnOptionalFailure() =
        runTest {
            withRepository(beforeResponse = { delay(1_000) }) { repository, tube ->
                val refresh = async { loadPodcastLibrary(repository, canReconcile = true) }
                runCurrent()
                tube.cookie = "SAPISID=replacement"
                assertFailsWith<CancellationException> { refresh.await() }
                assertTrue(refresh.isCancelled)
            }
        }

    private suspend fun TestScope.withRepository(
        beforeResponse: suspend (String) -> Unit,
        failedBrowseId: String? = null,
        block: suspend (YouTubeRepository, InnerTube) -> Unit,
    ) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val previousDispatcher = responseDecodeDispatcher
        responseDecodeDispatcher = testDispatcher
        Settings.replaceSharedForTest(Settings(MapSettings()))
        val client =
            HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = testDispatcher
                        addHandler { request ->
                            val id =
                                Json
                                    .parseToJsonElement(
                                        (request.body as TextContent).text,
                                    ).jsonObject["browseId"]!!
                                    .jsonPrimitive.content
                            beforeResponse(id)
                            respond(
                                """{"contents":{"sectionListRenderer":{"contents":[]}}}""",
                                if (id == failedBrowseId) HttpStatusCode.BadRequest else HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    },
                ),
            ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=session" }
            block(YouTubeRepository(tube), tube)
        } finally {
            client.close()
            responseDecodeDispatcher = previousDispatcher
            Settings.replaceSharedForTest(null)
        }
    }
}
