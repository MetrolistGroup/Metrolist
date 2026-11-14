package com.metrolist.shared.state

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.SongAlbumMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getDesktopDatabaseBuilder
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AlbumRecoveryTest {
    @Test
    fun recoverySavesCanonicalPlaylistAndRepairsArtistsWithoutChangingBookmarkOrTracks() =
        withDatabase { database ->
            val engine = engine()
            val repository = YouTubeRepository(innerTube(engine))
            database.saveBookmarkedAlbum(ALBUM_ID, repository)
            val stored = database.dao().album(ALBUM_ID).first()!!
            assertEquals(BOOKMARKED_AT, stored.album.bookmarkedAt)
            assertEquals("OLAKcanonical", stored.album.playlistId)
            assertEquals("Canonical album", stored.album.title)
            assertEquals(listOf("Artist"), stored.artists.map { it.name })
            assertEquals(listOf("UCartist"), stored.artists.map { it.id })
            assertEquals(1, stored.album.songCount)
            assertEquals(180, stored.album.duration)
            assertEquals(
                listOf("track"),
                database
                    .dao()
                    .storedAlbumSongs(ALBUM_ID)
                    .first()
                    .map { it.id },
            )
            val save = engine.requestHistory.last()
            assertTrue(save.url.encodedPath.endsWith("/like/like"))
            val target = Json.parseToJsonElement((save.body as TextContent).text).jsonObject["target"]!!.jsonObject
            assertEquals("OLAKcanonical", target["playlistId"]!!.jsonPrimitive.content)
            database.saveBookmarkedAlbum(ALBUM_ID, repository)
            assertEquals(stored, database.dao().album(ALBUM_ID).first())
            assertEquals(2, engine.requestHistory.count { it.url.encodedPath.endsWith("/like/like") })
        }

    @Test
    fun failedSavePreservesBookmarkAndMetadataAndCanBeRetried() =
        withDatabase { database ->
            val before = database.dao().album(ALBUM_ID).first()
            val repository = YouTubeRepository(innerTube(engine(saveStatus = HttpStatusCode.Forbidden)))
            assertTrue(runCatching { database.saveBookmarkedAlbum(ALBUM_ID, repository) }.isFailure)
            assertEquals(before, database.dao().album(ALBUM_ID).first())
            database.saveBookmarkedAlbum(ALBUM_ID, YouTubeRepository(innerTube(engine())))
            assertEquals(
                listOf("Artist"),
                database
                    .dao()
                    .album(ALBUM_ID)
                    .first()!!
                    .artists
                    .map { it.name },
            )
        }

    @Test
    fun failedOrMissingMetadataNeverSavesTheStalePlaylistId() =
        withDatabase { database ->
            val before = database.dao().album(ALBUM_ID).first()
            for ((body, status) in listOf("{}" to HttpStatusCode.OK, HEADER to HttpStatusCode.Forbidden)) {
                val engine = engine(header = body, browseStatus = status)
                assertTrue(runCatching { database.saveBookmarkedAlbum(ALBUM_ID, YouTubeRepository(innerTube(engine))) }.isFailure)
                assertEquals(before, database.dao().album(ALBUM_ID).first())
                assertTrue(engine.requestHistory.none { it.url.encodedPath.endsWith("/like/like") })
            }
        }

    @Test
    fun accountChangeDuringMetadataFetchCancelsBeforeSaving() =
        withDatabase { database ->
            val before = database.dao().album(ALBUM_ID).first()
            lateinit var tube: InnerTube
            var browsed = false
            val engine =
                engine(onBrowse = {
                    browsed = true
                    tube.authUser = "1"
                })
            tube = innerTube(engine)
            val failure = runCatching { database.saveBookmarkedAlbum(ALBUM_ID, YouTubeRepository(tube)) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(before, database.dao().album(ALBUM_ID).first())
            assertTrue(browsed)
            assertTrue(engine.requestHistory.none { it.url.encodedPath.endsWith("/like/like") })
        }

    @Test
    fun signedOutOrRemovedBookmarksCannotBeRecovered() =
        withDatabase { database ->
            val engine = engine()
            val tube = innerTube(engine).apply { cookie = null }
            assertTrue(runCatching { database.saveBookmarkedAlbum(ALBUM_ID, YouTubeRepository(tube)) }.isFailure)
            val album =
                database
                    .dao()
                    .album(ALBUM_ID)
                    .first()!!
                    .album
            database.dao().upsert(album.copy(bookmarkedAt = null))
            assertTrue(runCatching { database.saveBookmarkedAlbum(ALBUM_ID, YouTubeRepository(innerTube(engine))) }.isFailure)
            assertTrue(engine.requestHistory.isEmpty())
        }

    private fun engine(
        header: String = HEADER,
        browseStatus: HttpStatusCode = HttpStatusCode.OK,
        saveStatus: HttpStatusCode = HttpStatusCode.OK,
        onBrowse: () -> Unit = {},
    ) = MockEngine { request ->
        val browse = request.url.encodedPath.endsWith("/browse")
        if (browse) onBrowse()
        respond(
            if (browse) header else "{}",
            if (browse) browseStatus else saveStatus,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private fun innerTube(engine: MockEngine) =
        InnerTube(
            HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } },
            retryDelay = {},
        ).apply {
            cookie = "SAPISID=session"
            visitorData = "visitor"
            dataSyncId = "channel"
            authUser = "0"
            useLoginForBrowse = true
        }

    private fun withDatabase(block: suspend (MusicDatabase) -> Unit) =
        runBlocking {
            val path = Files.createTempFile("metrolist-album-recovery", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                database.dao().upsert(
                    AlbumEntity(
                        id = ALBUM_ID,
                        title = "Stranded album",
                        playlistId = "stale-playlist",
                        bookmarkedAt = BOOKMARKED_AT,
                        songCount = 1,
                        duration = 180,
                    ),
                )
                database.dao().insert(SongEntity(id = "track", title = "Track"))
                database.dao().upsert(SongAlbumMap("track", ALBUM_ID, 0))
                block(database)
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    companion object {
        private const val ALBUM_ID = "MPREalbum"
        private val BOOKMARKED_AT = LocalDateTime(2026, 10, 8, 12, 0)
        private const val HEADER = """{
            "microformat":{"microformatDataRenderer":{"urlCanonical":"https://music.youtube.com/playlist?list=OLAKcanonical"}},
            "header":{"musicDetailHeaderRenderer":{
                "title":{"simpleText":"Canonical album"},
                "straplineTextOne":{"runs":[{"text":"Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist"}}}]}
            }}
        }"""
    }
}
