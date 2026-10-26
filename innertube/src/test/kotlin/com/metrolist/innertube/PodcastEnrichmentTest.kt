package com.metrolist.innertube

import com.metrolist.innertube.models.Artist
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastEnrichmentTest {
    @Test
    fun `new episodes enrich missing artists and preserve linked or unresolved episodes`() = runBlocking {
        val lookedUp = mutableListOf<String>()
        val engine = MockEngine { request ->
            val response = when {
                request.url.encodedPath.endsWith("/browse") -> browseResponse
                request.url.encodedPath.endsWith("/next") -> {
                    val id = Json.parseToJsonElement((request.body as TextContent).text).jsonObject["videoId"]!!.jsonPrimitive.content
                    lookedUp.add(id)
                    when (id) {
                        "missing" -> """{"contents":{"twoColumnWatchNextResults":{"results":{"results":{"contents":[
                            {"videoSecondaryInfoRenderer":{"owner":{"videoOwnerRenderer":{
                                "title":{"runs":[{"text":"Resolved Host"}]},
                                "navigationEndpoint":{"browseEndpoint":{"browseId":"UChost"}}
                            }}}}
                        ]}}}}}"""
                        "failed" -> return@MockEngine respond("{}", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json"))
                        else -> """{"contents":{}}"""
                    }
                }
                request.url.host == "returnyoutubedislikeapi.com" -> "{}"
                else -> error("Unexpected request: ${request.url.encodedPath}")
            }
            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        }.use { client ->
            val field = YouTube::class.java.getDeclaredField("innerTube").apply { isAccessible = true }
            val innerTube = field.get(YouTube) as InnerTube
            val clientField = InnerTube::class.java.getDeclaredField("httpClient").apply { isAccessible = true }
            val transportField = InnerTube::class.java.getDeclaredField("innerTubeX").apply { isAccessible = true }
            val originalClient = clientField.get(innerTube)
            val originalTransport = transportField.get(innerTube)
            try {
                clientField.set(innerTube, client)
                transportField.set(innerTube, transportField.get(InnerTube(client)))
                val episodes = YouTube.newEpisodes().getOrThrow()
                assertEquals(listOf("missing", "linked", "unknown", "failed"), episodes.map { it.id })
                assertEquals(listOf(Artist("Resolved Host", "UChost")), episodes[0].artists)
                assertEquals(listOf(Artist("Linked Host", "UClinked")), episodes[1].artists)
                assertTrue(episodes[2].artists.isEmpty())
                assertTrue(episodes[3].artists.isEmpty())
                assertEquals(setOf("missing", "unknown", "failed"), lookedUp.toSet())
                assertEquals(1, lookedUp.count { it == "missing" })
                assertEquals(1, lookedUp.count { it == "unknown" })
            } finally {
                clientField.set(innerTube, originalClient)
                transportField.set(innerTube, originalTransport)
            }
        }
    }

    private val browseResponse = """{
        "responseContext":{},
        "contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{"contents":[
            {"musicShelfRenderer":{"contents":[
                {"musicMultiRowListItemRenderer":{"title":{"runs":[{"text":"Missing credit"}]},"onTap":{"watchEndpoint":{"videoId":"missing"}}}},
                {"musicMultiRowListItemRenderer":{"title":{"runs":[{"text":"Linked credit"}]},"onTap":{"watchEndpoint":{"videoId":"linked"}},"secondSubtitle":{"runs":[{"text":"Linked Host","navigationEndpoint":{"browseEndpoint":{"browseId":"UClinked"}}}]}}},
                {"musicMultiRowListItemRenderer":{"title":{"runs":[{"text":"Unknown credit"}]},"onTap":{"watchEndpoint":{"videoId":"unknown"}}}},
                {"musicMultiRowListItemRenderer":{"title":{"runs":[{"text":"Failed lookup"}]},"onTap":{"watchEndpoint":{"videoId":"failed"}}}}
            ]}}
        ]}}}}
    }"""
}
