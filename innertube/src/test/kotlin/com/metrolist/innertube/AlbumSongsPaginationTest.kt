package com.metrolist.innertube

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.SongItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumSongsPaginationTest {
    @Test
    fun `append actions preserve tracks and inline continuations`() = runBlocking {
        val (result, tokens) = runPages(
            initial(listOf(track("a"), marker("one"))),
            append(listOf(track("b"), marker("two"))),
            append(listOf(track("c"))),
        )
        assertEquals(listOf("a", "b", "c"), result.getOrThrow().map { it.id })
        assertEquals(listOf(null, "one", "two"), tokens)
    }

    @Test
    fun `shelf continuations preserve tracks and shelf tokens`() = runBlocking {
        val (result, tokens) = runPages(
            initial(listOf(track("a")), next = "one"),
            shelf(listOf(track("b")), "two"),
            shelf(listOf(track("c")), kind = "musicShelfContinuation"),
        )
        assertEquals(listOf("a", "b", "c"), result.getOrThrow().map { it.id })
        assertEquals(listOf(null, "one", "two"), tokens)
    }

    @Test
    fun `append tracks can use a token supplied by a shelf continuation`() = runBlocking {
        val mixed = """{"responseContext":{},"onResponseReceivedActions":[
            {"appendContinuationItemsAction":{"continuationItems":[${track("b")}]}}
        ],"continuationContents":{"musicPlaylistShelfContinuation":{
            "contents":[],"continuations":${continuations("two")}
        }}}"""
        val (result, tokens) = runPages(initial(listOf(track("a")), "one"), mixed, append(listOf(track("c"))))
        assertEquals(listOf("a", "b", "c"), result.getOrThrow().map { it.id })
        assertEquals(listOf(null, "one", "two"), tokens)
    }

    @Test
    fun `inline row continuation takes precedence over section continuation`() = runBlocking {
        val response = initial(listOf(track("a"), marker("row-page")), sectionNext = "section-page")
        val (result, tokens) = runPages(response, append(listOf(track("b"))))
        assertEquals(listOf("a", "b"), result.getOrThrow().map { it.id })
        assertEquals(listOf(null, "row-page"), tokens)
    }

    @Test
    fun `section-level continuation does not conflict with track continuation`() = runBlocking {
        val response = initial(listOf(track("a")), next = "track-page", sectionNext = "section-page")
        val (result, tokens) = runPages(
            response,
            append(listOf(track("b"))),
        )
        assertEquals(listOf("a", "b"), result.getOrThrow().map { it.id })
        assertEquals(listOf(null, "track-page"), tokens)
    }

    @Test
    fun `track shelf continuation takes precedence over section continuation`() = runBlocking {
        val mixed = """{"responseContext":{},"continuationContents":{
            "sectionListContinuation":{
                "contents":[{"musicShelfRenderer":{"contents":[${track("b")}]}}],
                "continuations":${continuations("section-page")}
            },
            "musicPlaylistShelfContinuation":{
                "contents":[],"continuations":${continuations("track-page")}
            }
        }}"""
        val (result, tokens) = runPages(
            initial(listOf(track("a")), "one"),
            mixed,
            append(listOf(track("c"))),
        )
        assertEquals(listOf("a", "b", "c"), result.getOrThrow().map { it.id })
        assertEquals(listOf(null, "one", "track-page"), tokens)
    }

    @Test
    fun `section list continuation shelves are traversed`() = runBlocking {
        val section = """{"responseContext":{},"continuationContents":{"sectionListContinuation":{
            "contents":[{"musicShelfRenderer":{"contents":[${track("b")}]}}],
            "continuations":${continuations("two")}
        }}}"""
        val (result, tokens) = runPages(initial(listOf(track("a")), "one"), section, append(listOf(track("c"))))
        assertEquals(listOf("a", "b", "c"), result.getOrThrow().map { it.id })
        assertEquals(listOf(null, "one", "two"), tokens)
    }

    @Test
    fun `missing initial shelf fails instead of returning an empty success`() = runBlocking {
        assertFailure(runPages("""{"responseContext":{}}""").first, "missing track shelf")
    }

    @Test
    fun `unparseable initial and continuation tracks fail without returning a prefix`() = runBlocking {
        assertFailure(runPages(initial(listOf(track("a"), track("bad", malformed = true)))).first, "unparseable track")
        assertFailure(
            runPages(initial(listOf(track("a")), "one"), append(listOf(track("bad", malformed = true)))).first,
            "unparseable track",
        )
    }

    @Test
    fun `unsupported source rows are not silently dropped by getItems`() = runBlocking {
        assertFailure(runPages(initial(listOf(track("a"), "{}"))).first, "unsupported row")
    }

    @Test
    fun `missing continuation payload fails instead of completing the prefix`() = runBlocking {
        assertFailure(runPages(initial(listOf(track("a")), "one"), """{"responseContext":{}}""").first, "missing track shelf")
    }

    @Test
    fun `repeated tokens fail before requesting the same page twice`() = runBlocking {
        val (result, tokens) = runPages(initial(listOf(track("a")), "one"), append(listOf(track("b"), marker("one"))))
        assertFailure(result, "repeated continuation")
        assertEquals(listOf(null, "one"), tokens)
    }

    @Test
    fun `unfinished pagination fails after fifty continuation requests`() = runBlocking {
        withResponses(responder = { index, _ ->
            if (index == 0) initial(listOf(track("t0")), "token1")
            else append(listOf(track("t$index"), marker("token${index + 1}")))
        }) { tokens ->
            assertFailure(YouTube.albumSongs("playlist", album), "continuation limit reached")
            assertEquals(51, tokens.size) // Initial request plus fifty continuation requests.
            assertEquals("token50", tokens.last())
        }
    }

    @Test
    fun `pagination may finish on the fiftieth continuation request`() = runBlocking {
        withResponses(responder = { index, _ ->
            if (index == 0) initial(listOf(track("t0")), "token1")
            else append(listOfNotNull(track("t$index"), if (index < 50) marker("token${index + 1}") else null))
        }) { tokens ->
            val songs = YouTube.albumSongs("playlist", album).getOrThrow()
            assertEquals((0..50).map { "t$it" }, songs.map { it.id })
            assertEquals(51, tokens.size)
        }
    }

    @Test
    fun `conflicting next tokens fail rather than choosing a branch`() = runBlocking {
        val mixed = """{"responseContext":{},"onResponseReceivedActions":[
            {"appendContinuationItemsAction":{"continuationItems":[${track("b")},${marker("two")}]}}
        ],"continuationContents":{"musicPlaylistShelfContinuation":{
            "contents":[],"continuations":${continuations("different")}
        }}}"""
        assertFailure(runPages(initial(listOf(track("a")), "one"), mixed).first, "conflicting continuations")
    }

    @Test
    fun `overlapping track payloads fail instead of inventing an order`() = runBlocking {
        assertFailure(runPages(initial(listOf(track("a")), "one"), append(listOf(track("a")))).first, "duplicate track")
    }

    @Test
    fun `a recognized empty shelf is distinguishable from a missing shelf`() = runBlocking {
        assertTrue(runPages(initial(emptyList())).first.getOrThrow().isEmpty())
    }

    private fun assertFailure(result: Result<List<SongItem>>, reason: String) {
        assertTrue("Expected failure: $reason", result.isFailure)
        assertTrue(result.exceptionOrNull().toString(), result.exceptionOrNull()?.message?.contains(reason) == true)
    }

    private suspend fun runPages(vararg pages: String): Pair<Result<List<SongItem>>, List<String?>> {
        var result: Result<List<SongItem>>? = null
        var tokens: List<String?> = emptyList()
        withResponses(responder = { index, _ -> pages.getOrElse(index) { error("Unexpected extra browse request") } }) {
            result = YouTube.albumSongs("playlist", album)
            tokens = it.toList()
        }
        return checkNotNull(result) to tokens
    }

    // Follow PodcastEnrichmentTest: restore both transports even when a test fails.
    private suspend fun withResponses(
        responder: (Int, String?) -> String,
        block: suspend (MutableList<String?>) -> Unit,
    ) {
        val tokens = mutableListOf<String?>()
        val engine = MockEngine { request ->
            check(request.url.encodedPath.endsWith("/browse"))
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val token = request.url.parameters["continuation"]
                ?: body["continuation"]?.jsonPrimitive?.contentOrNull
            val index = tokens.size
            tokens.add(token)
            respond(responder(index, token), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        }.use { client ->
            val innerField = YouTube::class.java.getDeclaredField("innerTube").apply { isAccessible = true }
            val inner = innerField.get(YouTube) as InnerTube
            val clientField = InnerTube::class.java.getDeclaredField("httpClient").apply { isAccessible = true }
            val transportField = InnerTube::class.java.getDeclaredField("innerTubeX").apply { isAccessible = true }
            val originalClient = clientField.get(inner)
            val originalTransport = transportField.get(inner)
            try {
                clientField.set(inner, client)
                transportField.set(inner, transportField.get(InnerTube(client)))
                block(tokens)
            } finally {
                clientField.set(inner, originalClient)
                transportField.set(inner, originalTransport)
            }
        }
    }

    private fun initial(
        rows: List<String>,
        next: String? = null,
        sectionNext: String? = null,
    ): String = """{
        "responseContext":{},"contents":{"twoColumnBrowseResultsRenderer":{
            "secondaryContents":{"sectionListRenderer":{
                "contents":[{
                    "musicPlaylistShelfRenderer":{"contents":[${rows.joinToString(",")}],"continuations":${continuations(next)}}
                }],
                "continuations":${continuations(sectionNext)}
            }}
        }}
    }"""

    private fun append(rows: List<String>): String = """{
        "responseContext":{},"onResponseReceivedActions":[{},
            {"appendContinuationItemsAction":{"continuationItems":[${rows.joinToString(",")}]}}
        ]
    }"""

    private fun shelf(rows: List<String>, next: String? = null, kind: String = "musicPlaylistShelfContinuation"): String = """{
        "responseContext":{},"continuationContents":{"$kind":{
            "contents":[${rows.joinToString(",")}],"continuations":${continuations(next)}
        }}
    }"""

    private fun continuations(token: String?): String =
        if (token == null) "[]" else """[{"nextContinuationData":{"continuation":"$token"}}]"""

    private fun marker(token: String): String = """{
        "continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$token"}}}
    }"""

    private fun track(id: String, malformed: Boolean = false): String {
        val duration = if (malformed) "[]" else """[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"1:40"}]}}}]"""
        return """{"musicResponsiveListItemRenderer":{
            "playlistItemData":{"videoId":"$id"},"fixedColumns":$duration,
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{
                "text":"$id","navigationEndpoint":{"watchEndpoint":{"videoId":"$id",
                    "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_ATV"}}
                }}
            }]}}}]
        }}"""
    }

    private val album = AlbumItem(browseId = "album", playlistId = "playlist", title = "Album", artists = emptyList(), thumbnail = "cover")
}
