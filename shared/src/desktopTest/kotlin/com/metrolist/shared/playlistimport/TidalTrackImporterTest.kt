package com.metrolist.shared.playlistimport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TidalTrackImporterTest {
    @Test
    fun currentEmbedImportsActualTrackInsteadOfLocalizedFallback() =
        runBlocking {
            val html = checkNotNull(javaClass.getResource("/tidal/track-112126943.html")).readText()
            withHtml(html) {
                val result = TidalPlaylistImporter.loadPlaylist("https://tidal.com/track/112126943").getOrThrow()
                assertEquals("Московскому мальчику", result.tracks.single().title)
                assertEquals("Шура Кузнецова", result.tracks.single().artist)
                assertEquals("Московскому мальчику", result.title)
            }
        }

    @Test
    fun unknownPageDoesNotInventGenericTrack() =
        runBlocking {
            withHtml("<html><title>TIDAL Embed Player</title></html>") {
                assertTrue(TidalPlaylistImporter.loadPlaylist("https://tidal.com/track/112126943").isFailure)
            }
        }

    @Test
    fun legacyTrackMetadataStillImports() =
        runBlocking {
            withHtml("""<meta property="og:title" content="Original"><meta property="og:description" content="Artist">""") {
                val result = TidalPlaylistImporter.loadPlaylist("https://tidal.com/track/112126943").getOrThrow()
                assertEquals("Original", result.tracks.single().title)
                assertEquals("Artist", result.tracks.single().artist)
            }
        }

    @Test
    fun requestedTrackIsSelectedAndQuotedBracesAreNotExecuted() =
        runBlocking {
            withHtml(
                """<script>window.tidalMediaSessionMetadata = {
            "1":{"title":"Not this one","artist":"Other"},
            "112126943":{"title":"A \"quote\" }; { \\ end","artist":"A & B"}
        };window.doNotExecute();</script>""",
            ) {
                val result = TidalPlaylistImporter.loadPlaylist("https://tidal.com/track/112126943").getOrThrow()
                assertEquals("A \"quote\" }; { \\ end", result.tracks.single().title)
                assertEquals("A & B", result.tracks.single().artist)
            }
        }

    @Test
    fun malformedOrMismatchedMetadataDoesNotFallBackToPageTitle() =
        runBlocking {
            for (metadata in listOf(
                "{}",
                """{"1":{"title":"Other"}}""",
                """{"112126943":{"title":null}}""",
                """{"112126943":{"title":false}}""",
                """{"112126943":{"title":"  "}}""",
                """{"112126943":{"title":[]}}""",
                """{"112126943":{"title":"unclosed"}""",
                "(() => dangerous())()",
            )) {
                withHtml(
                    """<meta property="og:title" content="Wrong fallback"><script>window.tidalMediaSessionMetadata=$metadata;</script>""",
                ) {
                    var progressed = false
                    val result =
                        TidalPlaylistImporter.loadPlaylist("https://tidal.com/track/112126943", onProgress = { _, _ ->
                            progressed =
                                true
                        })
                    assertTrue(result.isFailure, metadata)
                    assertTrue(!progressed)
                }
            }
        }

    @Test
    fun failedHttpResponseCannotImportItsMetadata() =
        runBlocking {
            withEngine(MockEngine { respond("""<meta property="og:title" content="Wrong">""", HttpStatusCode.ServiceUnavailable) }) {
                assertTrue(TidalPlaylistImporter.loadPlaylist("https://tidal.com/track/112126943").isFailure)
            }
        }

    @Test
    fun cancellationPropagates() =
        runBlocking {
            withEngine(MockEngine { throw CancellationException("cancelled") }) {
                assertFailsWith<CancellationException> { TidalPlaylistImporter.loadPlaylist("https://tidal.com/track/112126943") }
            }
        }

    private suspend fun withHtml(
        html: String,
        block: suspend () -> Unit,
    ) {
        withEngine(MockEngine { respond(html) }, block)
    }

    private suspend fun withEngine(
        engine: MockEngine,
        block: suspend () -> Unit,
    ) {
        val previous = TidalPlaylistImporter.clientProvider
        val client = HttpClient(engine)
        TidalPlaylistImporter.clientProvider = { client }
        try {
            block()
        } finally {
            TidalPlaylistImporter.clientProvider = previous
            client.close()
        }
    }
}
