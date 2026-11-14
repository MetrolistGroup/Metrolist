package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioQueueRequestTest {
    @Test
    fun initialAndContinuedRadioPagesReadPanelContinuations() {
        for (continuationKind in listOf("nextRadioContinuationData", "nextContinuationData")) {
            val panel =
                """
                {
                  "contents": [{"playlistPanelVideoRenderer": {
                    "videoId": "seed", "title": {"runs": [{"text": "Seed"}]}
                  }}],
                  "continuations": [{"$continuationKind": {"continuation": "next"}}]
                }
                """.trimIndent()
            val initial =
                """
                {"contents": {"singleColumnMusicWatchNextResultsRenderer": {
                  "tabbedRenderer": {"watchNextTabbedResultsRenderer": {"tabs": [
                    {"tabRenderer": {"content": {"musicQueueRenderer": {
                      "content": {"playlistPanelRenderer": $panel}
                    }}}},
                    {"tabRenderer": {"content": {"continuationCommand": {"token": "unrelated"}}}}
                  ]}}
                }}}
                """.trimIndent()
            val continued = """{"continuationContents":{"playlistPanelContinuation":$panel}}"""
            for (response in listOf(initial, continued)) {
                val page = YouTubeRadioParser.parseRadioQueueResponse(Json.parseToJsonElement(response).jsonObject)
                assertEquals(listOf("seed"), page.songs.map { it.id })
                assertEquals("next", page.continuation)
            }
        }
    }

    @Test
    fun radioStillAcceptsInlineContinuationCommandsAndIgnoresReloadTokens() {
        val response =
            """
            {"continuationContents":{"playlistPanelContinuation":{
              "contents":[{"continuationItemRenderer":{
                "continuationEndpoint":{"continuationCommand":{"token":"next"}}
              }}],
              "continuations":[{"reloadContinuationData":{"continuation":"reload"}}]
            }}}
            """.trimIndent()
        val page = YouTubeRadioParser.parseRadioQueueResponse(Json.parseToJsonElement(response).jsonObject)
        assertTrue(page.songs.isEmpty())
        assertEquals("next", page.continuation)
    }

    @Test
    fun failedRadioRequestsAreNotReportedAsAnExhaustedQueue() =
        runBlocking {
            var fail = true
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            """{"continuationContents":{"playlistPanelContinuation":{"contents":[]}}}""",
                            if (fail) HttpStatusCode.BadRequest else HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json() }
                }
            try {
                Settings.replaceSharedForTest(Settings(MapSettings()))
                val repository = YouTubeRepository(InnerTube(client))
                assertFailsWith<IllegalStateException> { repository.startRadioQueue("seed") }
                assertFailsWith<IllegalStateException> { repository.startRadio("seed") }
                assertFailsWith<IllegalStateException> { repository.continueRadioQueue("page") }
                fail = false
                for (page in listOf(repository.startRadioQueue("seed"), repository.continueRadioQueue("page"))) {
                    assertTrue(page.songs.isEmpty())
                    assertNull(page.continuation)
                }
            } finally {
                Settings.replaceSharedForTest(null)
                client.close()
            }
        }
}
