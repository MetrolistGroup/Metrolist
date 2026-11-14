package com.metrolist.shared.db

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistOccurrencesTest {
    @Test
    fun repeatedVideosWithoutOccurrenceIdsSurvivePaginationCachingAndOfflineReload() =
        runBlocking {
            val path = Files.createTempFile("playlist-occurrences", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val previousNetwork = PlaybackNetworkStateStore.current
            PlaybackNetworkStateStore.publish(previousNetwork.copy(connected = true))
            val ids = (0 until 487).map { "video-${it % 469}" }
            var requests = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests++
                        val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val offset = payload["continuation"]?.jsonPrimitive?.content?.toInt() ?: 0
                        val rows =
                            ids.drop(offset).take(50).joinToString(",") { id ->
                                """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}}],"playlistItemData":{"videoId":"$id"}}}"""
                            }
                        val continuation =
                            if (offset + 50 < ids.size) {
                                """, "continuations":[{"nextContinuationData":{"continuation":"${offset + 50}"}}]"""
                            } else {
                                ""
                            }
                        val shelf = """{"contents":[$rows]$continuation}"""
                        val body =
                            if (offset == 0) {
                                """{"header":{"musicDetailHeaderRenderer":{"title":{"simpleText":"Playlist"},"secondSubtitle":{"simpleText":"487 songs"}}},"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":$shelf}]}}}"""
                            } else {
                                """{"continuationContents":{"musicPlaylistShelfContinuation":$shelf}}"""
                            }
                        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val repository =
                    YouTubeRepository(
                        InnerTube(client),
                        databaseProvider = { database },
                        offlineSongAvailabilityProvider = { OfflineSongAvailability { ids.toSet() } },
                    )
                database.dao().insert(PlaylistEntity(id = "LPsaved", name = "Playlist", browseId = "PLsaved"))
                val page = database.loadAndCachePlaylistPage(repository, "PLsaved")
                assertFalse(page.hasMore)
                assertEquals(ids, page.songs.map { it.id })
                assertEquals(10, requests)
                assertEquals(487, database.dao().findPlaylistEntity("PLsaved")?.remoteSongCount)
                assertEquals(
                    ids,
                    database
                        .dao()
                        .playlistSongs("LPsaved")
                        .first()
                        .map { it.song.id },
                )
                PlaybackNetworkStateStore.publish(previousNetwork.copy(connected = false))
                val offline = database.loadAndCachePlaylistPage(repository, "PLsaved")
                assertTrue(offline.isOffline)
                assertEquals(ids, offline.songs.map { it.id })
                assertEquals(10, requests)
            } finally {
                PlaybackNetworkStateStore.publish(previousNetwork)
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }
}
