package com.metrolist.shared.db

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playlist.LibraryDeletionTracker
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.russhwolf.settings.MapSettings
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistMembershipRepairTest {
    private lateinit var path: Path
    private lateinit var database: MusicDatabase
    private lateinit var client: HttpClient
    private lateinit var repository: YouTubeRepository
    private lateinit var previousNetwork: PlaybackNetworkState
    private val requests = mutableListOf<String>()
    private var failContinuation = false
    private var beforeResponse: suspend (String) -> Unit = {}

    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        LibraryDeletionTracker.clear()
        previousNetwork = PlaybackNetworkStateStore.current
        PlaybackNetworkStateStore.publish(previousNetwork.copy(connected = true))
        path = Files.createTempFile("playlist-membership-repair", ".db")
        Files.delete(path)
        database = getDesktopDatabaseBuilder(path.toFile()).build()
        client =
            HttpClient(
                MockEngine { request ->
                    val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    val continuation = payload["continuation"]?.jsonPrimitive?.content
                    val key = continuation ?: payload["browseId"]!!.jsonPrimitive.content
                    requests += key
                    beforeResponse(key)
                    val body =
                        if (continuation == null) {
                            """{"header":{"musicDetailHeaderRenderer":{"title":{"simpleText":"Playlist"}}},"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[${row(
                                "first",
                            )}],"continuations":[{"nextContinuationData":{"continuation":"next"}}]}}]}}}"""
                        } else {
                            """{"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[${row("second")}]}}}"""
                        }
                    respond(
                        body,
                        if (continuation != null && failContinuation) HttpStatusCode.BadRequest else HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        repository = YouTubeRepository(InnerTube(client, retryDelay = {}), databaseProvider = { database })
    }

    @AfterTest
    fun tearDown() {
        client.close()
        database.close()
        Files.deleteIfExists(path)
        LibraryDeletionTracker.clear()
        PlaybackNetworkStateStore.publish(previousNetwork)
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun repairsMissingAndPartialMembershipOnceWithoutOpeningPlaylists() =
        runBlocking {
            val missing = PlaylistItem("VLPLmissing", "Missing", null, songCount = "2")
            val partial = PlaylistItem("PLpartial", "Partial", null, songCount = "2")
            val unknown = PlaylistItem("PLunknown", "Unknown count", null)
            val playlists = listOf(missing, partial, unknown)
            database.cacheRemotePlaylist(partial.id, partial, listOf(song()))
            database.syncRemotePlaylists(playlists)
            assertEquals(emptyList(), database.dao().downloadedCollectionKeys(listOf("song")).first())

            database.repairRemotePlaylistMembership(repository, playlists + missing.copy(id = "PLmissing")) { true }

            assertEquals(listOf("VLPLmissing", "next", "VLPLpartial", "next", "VLPLunknown", "next"), requests)
            for (playlist in playlists) {
                val entity = database.dao().findPlaylistEntity(playlist.id)!!
                assertEquals(2, entity.remoteSongCount)
                assertEquals(
                    listOf("first", "second"),
                    database
                        .dao()
                        .playlistSongs(entity.id)
                        .first()
                        .map { it.map.setVideoId },
                )
            }
            assertEquals(
                setOf("playlist:PLmissing", "playlist:PLpartial", "playlist:PLunknown"),
                database
                    .dao()
                    .downloadedCollectionKeys(listOf("song"))
                    .first()
                    .toSet(),
            )
            database.repairRemotePlaylistMembership(repository, playlists) { true }
            assertEquals(6, requests.size)
        }

    @Test
    fun skipsLocalDeletedAndCompletelyCachedPlaylists() =
        runBlocking {
            val items = listOf("PLlocal", "PLdeleted", "PLcomplete", "PLempty").map { PlaylistItem(it, it, null) }
            database.dao().insert(PlaylistEntity(id = "PLlocal", name = "Local", browseId = "PLlocal", isLocal = true))
            database.syncRemotePlaylists(items.drop(1))
            LibraryDeletionTracker.trackDeletedPlaylist("PLdeleted")
            database.cacheRemotePlaylist("PLcomplete", items[2], listOf(song(), song().copy(playlistSetVideoId = "second")))
            database.syncRemotePlaylists(listOf(items[2].copy(songCount = "1")))
            database.cacheRemotePlaylist("PLempty", items[3], emptyList())

            database.repairRemotePlaylistMembership(repository, items) { true }

            assertTrue(requests.isEmpty())
            assertEquals(0, database.dao().localPlaylistSongCount("PLlocal"))
        }

    @Test
    fun failedContinuationPreservesMembershipAndDoesNotStopOtherRepairs() =
        runBlocking {
            val failed = PlaylistItem("PLfailed", "Failed", null, songCount = "2")
            val next = PlaylistItem("PLnext", "Next", null, songCount = "2")
            database.cacheRemotePlaylist(failed.id, failed, listOf(song()))
            database.syncRemotePlaylists(listOf(failed, next))
            val original = database.dao().findPlaylistEntity(failed.id)
            beforeResponse = { key -> if (key != "next") failContinuation = key == "VLPLfailed" }

            database.repairRemotePlaylistMembership(repository, listOf(failed, next)) { true }

            assertEquals(original, database.dao().findPlaylistEntity(failed.id))
            assertEquals(1, database.dao().localPlaylistSongCount(failed.id))
            assertEquals(listOf("playlist:PLnext"), database.dao().downloadedCollectionKeys(listOf("song")).first())
            beforeResponse = {}
            failContinuation = false
            database.repairRemotePlaylistMembership(repository, listOf(failed, next)) { true }
            assertEquals(2, database.dao().localPlaylistSongCount(failed.id))
            assertEquals(listOf("VLPLfailed", "next", "VLPLnext", "next", "VLPLfailed", "next"), requests)
        }

    @Test
    fun deletionDuringFetchDoesNotRestoreThePlaylist() =
        runBlocking {
            val item = PlaylistItem("PLremoved", "Removed", null, songCount = "2")
            database.syncRemotePlaylists(listOf(item))
            beforeResponse = { key ->
                if (key == "next") database.dao().deletePlaylistById(item.id)
            }

            database.repairRemotePlaylistMembership(repository, listOf(item)) { true }

            assertEquals(listOf("VLPLremoved", "next"), requests)
            assertNull(database.dao().findPlaylistEntity(item.id))
        }

    @Test
    fun offlineAccountChangeAndCancellationNeverReplaceMembership() =
        runBlocking {
            val item = PlaylistItem("PLsaved", "Saved", null, songCount = "2")
            database.cacheRemotePlaylist(item.id, item, listOf(song()))
            database.syncRemotePlaylists(listOf(item))
            database.dao().updateDownloadedInfo("song", true, null)
            val original = database.dao().findPlaylistEntity(item.id)
            PlaybackNetworkStateStore.publish(previousNetwork.copy(connected = false))
            database.repairRemotePlaylistMembership(repository, listOf(item)) { true }
            assertTrue(requests.isEmpty())
            assertEquals(original, database.dao().findPlaylistEntity(item.id))

            PlaybackNetworkStateStore.publish(previousNetwork.copy(connected = true))
            var current = true
            beforeResponse = { current = false }
            database.repairRemotePlaylistMembership(repository, listOf(item)) { current }
            assertFalse(current)
            assertEquals(original, database.dao().findPlaylistEntity(item.id))

            beforeResponse = { throw CancellationException("Cancelled repair") }
            assertFailsWith<CancellationException> {
                database.repairRemotePlaylistMembership(repository, listOf(item)) { true }
            }
            assertEquals(original, database.dao().findPlaylistEntity(item.id))
            assertEquals(1, database.dao().localPlaylistSongCount(item.id))
        }

    private fun song() = SongItem("song", "Song", null, emptyList(), null, playlistSetVideoId = "first")

    private fun row(occurrence: String) =
        """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}}],"playlistItemData":{"videoId":"song","playlistSetVideoId":"$occurrence"}}}"""
}
