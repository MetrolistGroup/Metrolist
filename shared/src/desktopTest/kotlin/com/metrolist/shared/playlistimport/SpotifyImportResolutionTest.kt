package com.metrolist.shared.playlistimport

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.getDatabaseBuilder
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class SpotifyImportResolutionTest {
    @Test
    fun retriesSpotifySearchWithoutAlbumWhenAlbumScopedSearchMisses() =
        runBlocking {
            val databasePath = Files.createTempFile("spotify-import-resolution", ".db")
            Files.deleteIfExists(databasePath)
            val database = getDatabaseBuilder(databasePath.toFile()).build()
            val queries = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        val query =
                            Json
                                .parseToJsonElement((request.body as TextContent).text)
                                .jsonObject["query"]!!
                                .jsonPrimitive.content
                        queries += query
                        val items = if (query == "Song Artist Spotify Album") "" else SONG_RESULT
                        respond(
                            """
                            {"contents":{"sectionListRenderer":{"contents":[
                              {"musicShelfRenderer":{"contents":[$items]}}
                            ]}}}
                            """.trimIndent(),
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json() } }
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                val manager = PlaylistImportManager(database, YouTubeRepository(InnerTube(client)))
                val track = PlaylistImportManager.CsvTrack("Song", "Artist", null, album = "Spotify Album")

                val matches =
                    manager.resolveImportTracks(
                        listOf(track),
                        PlaylistImportManager.Destination.NewLocal,
                        ImportSourceType.SPOTIFY,
                    )

                assertEquals(listOf("Song Artist Spotify Album", "Song Artist"), queries)
                assertEquals(listOf("abcdefghijk"), matches.map { it.second.id })
            } finally {
                Settings.replaceSharedForTest(null)
                client.close()
                database.close()
                Files.deleteIfExists(databasePath)
            }
        }

    private companion object {
        const val SONG_RESULT =
            """
            {"musicResponsiveListItemRenderer":{
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                  {"text":"Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"artist-id"}}}
                ]}}}
              ],
              "playlistItemData":{"videoId":"abcdefghijk"}
            }}
            """
    }
}
