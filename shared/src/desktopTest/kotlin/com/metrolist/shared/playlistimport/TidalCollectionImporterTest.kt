package com.metrolist.shared.playlistimport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TidalCollectionImporterTest {
    @Test
    fun currentAlbumImportsAllFourteenTracksInSourceOrder() =
        runBlocking {
            val html = checkNotNull(javaClass.getResource("/tidal/album-1550545.html")).readText()
            withHtml(html) {
                val result = TidalPlaylistImporter.loadPlaylist("https://tidal.com/album/1550545").getOrThrow()
                assertEquals("Discovery", result.title)
                assertEquals(14, result.tracks.size)
                assertEquals("One More Time", result.tracks.first().title)
                assertEquals("Too Long", result.tracks.last().title)
                assertEquals("Daft Punk", result.tracks.first().artist)
            }
        }

    @Test
    fun currentPublicPlaylistImportsAllTwentyFiveTracks() =
        runBlocking {
            val html = checkNotNull(javaClass.getResource("/tidal/playlist-avicii.html")).readText()
            withHtml(html) {
                val result =
                    TidalPlaylistImporter.loadPlaylist("https://tidal.com/playlist/72d9ce98-87ae-4eea-b470-b72b2f543db0").getOrThrow()
                assertEquals("Avicii Essentials", result.title)
                assertEquals(25, result.tracks.size)
                assertEquals("Levels", result.tracks.first().title)
                assertEquals("You Make Me", result.tracks.last().title)
            }
        }

    @Test
    fun playlistUsesVisibleOrderAndKeepsDuplicateOccurrences() =
        runBlocking {
            withHtml(
                """<script>window.tidalMediaSessionMetadata={"1":{"title":"First","artist":"A"},"2":{"title":"Second","artist":"B"}};</script>
<h1 class="media-album"><a href="https://tidal.com/playlist/abcd">A &amp; B</a></h1>
<media-item-list><list-item product-id="2" product-type="track"></list-item><list-item product-id="1" product-type="track"></list-item><list-item product-id="2" product-type="track"></list-item></media-item-list>""",
            ) {
                val result = TidalPlaylistImporter.loadPlaylist("https://tidal.com/playlist/abcd").getOrThrow()
                assertEquals("A & B", result.title)
                assertEquals(listOf("Second", "First", "Second"), result.tracks.map { it.title })
            }
        }

    @Test
    fun rejectsAmbiguousFiftyTrackCapRatherThanImportingPrefix() =
        runBlocking {
            for (count in listOf(50, 51)) {
                withHtml(collection((1..count).map { it.toString() })) {
                    var progressed = false
                    val result =
                        TidalPlaylistImporter.loadPlaylist("https://tidal.com/playlist/abcd", onProgress = {
                            _,
                            _,
                            ->
                            progressed = true
                        })
                    assertEquals(ImportErrorKey.IncompleteSource, assertIs<ImportException>(result.exceptionOrNull()).errorKey)
                    assertTrue(!progressed)
                }
            }
            withHtml(collection((1..49).map { it.toString() })) {
                assertEquals(
                    49,
                    TidalPlaylistImporter
                        .loadPlaylist("https://tidal.com/playlist/abcd")
                        .getOrThrow()
                        .tracks.size,
                )
            }
        }

    @Test
    fun duplicateOccurrencesCountTowardEmbedLimit() =
        runBlocking {
            withHtml(collection(List(50) { "1" })) {
                val error = TidalPlaylistImporter.loadPlaylist("https://tidal.com/album/12345").exceptionOrNull()
                assertEquals(ImportErrorKey.IncompleteSource, assertIs<ImportException>(error).errorKey)
            }
        }

    @Test
    fun missingOrExtraMetadataDoesNotSilentlyDropEntries() =
        runBlocking {
            for (html in listOf(
                collection(listOf("1", "2"), listOf("1")),
                collection(listOf("1"), listOf("1", "2")),
                collection(listOf("1")).replace("product-id=\"1\"", "product-id=\"\""),
                collection(listOf("1")).replace("</list-item>", ""),
                collection(listOf("1")).replace("\"title\":\"Song 1\"", "\"title\":null"),
            )) {
                withHtml(html) { assertTrue(TidalPlaylistImporter.loadPlaylist("https://tidal.com/playlist/abcd").isFailure) }
            }
        }

    @Test
    fun unsupportedVideoDoesNotReturnOnlyAudioPrefix() =
        runBlocking {
            withHtml(
                collection(listOf("1", "2")).replace("product-id=\"2\" product-type=\"track\"", "product-id=\"2\" product-type=\"video\""),
            ) {
                val error = TidalPlaylistImporter.loadPlaylist("https://tidal.com/playlist/abcd").exceptionOrNull()
                assertEquals(ImportErrorKey.UnsupportedPlaylistEntries, assertIs<ImportException>(error).errorKey)
            }
        }

    @Test
    fun legacyNextDataKeepsItsExistingCollectionBehavior() =
        runBlocking {
            for (count in listOf(1, 50)) {
                val tracks = List(count) { """{"title":"Song","artists":[{"name":"Artist"}]}""" }.joinToString(",")
                val html = """<meta property="og:title" content="Legacy">
<script id="__NEXT_DATA__">{"props":{"pageProps":{"tracks":[$tracks]}}}</script>"""
                withHtml(html) {
                    for (url in listOf("https://tidal.com/album/12345", "https://tidal.com/playlist/abcd")) {
                        val result = TidalPlaylistImporter.loadPlaylist(url).getOrThrow()
                        assertEquals("Legacy", result.title)
                        assertEquals(count, result.tracks.size)
                        assertEquals("Song", result.tracks.first().title)
                        assertEquals("Artist", result.tracks.first().artist)
                    }
                }
            }
        }

    private fun collection(
        ids: List<String>,
        metadataIds: List<String> = ids.distinct(),
    ): String {
        val metadata = metadataIds.joinToString(",") { "\"$it\":{\"title\":\"Song $it\",\"artist\":\"Artist\"}" }
        val rows = ids.joinToString("") { """<list-item product-id="$it" product-type="track"></list-item>""" }
        return """<script>window.tidalMediaSessionMetadata={$metadata};</script>
<h1 class="media-album">Collection</h1><media-item-list>$rows</media-item-list>"""
    }

    private suspend fun withHtml(
        html: String,
        block: suspend () -> Unit,
    ) {
        val previous = TidalPlaylistImporter.clientProvider
        val client = HttpClient(MockEngine { respond(html) })
        TidalPlaylistImporter.clientProvider = { client }
        try {
            block()
        } finally {
            TidalPlaylistImporter.clientProvider = previous
            client.close()
        }
    }
}
