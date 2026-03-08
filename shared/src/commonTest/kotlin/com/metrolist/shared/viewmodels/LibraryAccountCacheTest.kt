package com.metrolist.shared.viewmodels

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryAccountCacheTest {
    @Test
    fun accountSwitchCannotRepublishCachedItemsWhileOldRefreshRuns() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { dataSyncId = "first" })
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var blocked = false
            val client =
                HttpClient(
                    MockEngine { request ->
                        if (blocked) {
                            started.complete(Unit)
                            release.await()
                            error("offline")
                        }
                        val id =
                            Json
                                .parseToJsonElement((request.body as TextContent).text)
                                .jsonObject["browseId"]
                                ?.jsonPrimitive
                                ?.content
                        respond(
                            if (id == "FEmusic_liked_playlists" || id == "VLPLfirst") playlistResponse else emptyResponse,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json() } }
            try {
                val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=first" }
                val model = LibraryViewModel(backgroundScope, repository = YouTubeRepository(tube))
                model.setActive(true)
                model.uiState.first { state -> state.items.any { it.id.removePrefix("VL") == "PLfirst" } }
                blocked = true
                model.refresh()
                started.await()
                Settings.shared.dataSyncId = "second"
                tube.cookie = "SAPISID=second"
                model.refresh()
                model.setActive(false)
                runCurrent()
                model.setActive(true)
                advanceTimeBy(500)
                runCurrent()
                assertTrue(
                    model.uiState.value.items
                        .isEmpty(),
                )
                release.complete(Unit)
                model.uiState.first { it.error }
                model.setActive(false)
                runCurrent()
                model.setActive(true)
                advanceTimeBy(500)
                runCurrent()
                assertTrue(
                    model.uiState.value.items
                        .isEmpty(),
                )
            } finally {
                release.complete(Unit)
                client.close()
                Settings.replaceSharedForTest(null)
            }
        }

    private val emptyResponse = """{"contents":{"sectionListRenderer":{"contents":[]}}}"""
    private val playlistResponse = """{
      "header": {"musicEditablePlaylistDetailHeaderRenderer": {}},
      "contents": {"sectionListRenderer": {"contents": [{"gridRenderer": {"items": [
        {"musicTwoRowItemRenderer": {
          "title": {"runs": [{"text": "First account"}]},
          "navigationEndpoint": {"browseEndpoint": {"browseId": "VLPLfirst"}}
        }}
      ]}}]}}
    }"""
}
