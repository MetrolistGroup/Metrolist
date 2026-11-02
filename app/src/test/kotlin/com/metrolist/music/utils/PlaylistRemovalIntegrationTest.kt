package com.metrolist.music.utils

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.innertube.InnerTube
import com.metrolist.innertube.YouTube
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.ui.menu.findPlaylistRemovalCandidates
import com.metrolist.music.ui.menu.remotePlaylistRemovalOccurrences
import com.metrolist.innertube.utils.completed
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalSerializationApi::class)
class PlaylistRemovalIntegrationTest {
    private lateinit var room: InternalDatabase
    private lateinit var database: MusicDatabase
    private lateinit var sync: SyncUtils
    private lateinit var client: HttpClient
    private lateinit var facade: InnerTube
    private lateinit var originalClient: Any
    private lateinit var originalTransport: Any
    private var mutationStatus = HttpStatusCode.OK
    private var mutationBody = """{"status":"STATUS_SUCCEEDED"}"""
    private var remoteOccurrences: List<String>? = null
    private var remoteEditable = true
    private var remoteCount: Int? = null
    private var addStarted: CompletableDeferred<Unit>? = null
    private var releaseAdd: CompletableDeferred<Unit>? = null
    private val mutationBodies = mutableListOf<JsonObject>()
    private val removedNowResults = mutableListOf<Boolean>()

    private val clientField = InnerTube::class.java.getDeclaredField("httpClient").apply { isAccessible = true }
    private val transportField = InnerTube::class.java.getDeclaredField("innerTubeX").apply { isAccessible = true }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        room = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build()
        database = MusicDatabase(room)
        database.insert(SongEntity(id = "song", title = "Song"))
        listOf("A", "B").forEach { id ->
            database.insert(PlaylistEntity(id = id, name = id, browseId = "VLplaylist-$id", bookmarkedAt = LocalDateTime.now()))
        }
        database.insert(PlaylistSongMap(id = 1, playlistId = "A", songId = "song", position = 0, setVideoId = "first"))
        database.insert(PlaylistSongMap(id = 2, playlistId = "A", songId = "song", position = 1, setVideoId = "second"))
        database.insert(PlaylistSongMap(id = 3, playlistId = "B", songId = "song", position = 0, setVideoId = "other-playlist"))
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("browse/edit_playlist")) {
                addStarted?.complete(Unit)
                releaseAdd?.await()
                mutationBodies.add(Json.parseToJsonElement((request.body as TextContent).text).jsonObject)
                respond(mutationBody, mutationStatus, headersOf(HttpHeaders.ContentType, "application/json"))
            } else if (request.url.encodedPath.endsWith("browse") && remoteOccurrences != null) {
                val songs = remoteOccurrences!!.joinToString(",") { occurrence ->
                    """{"musicResponsiveListItemRenderer":{
                        "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}}],
                        "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.com/song.jpg","width":120,"height":120}]}}},
                        "playlistItemData":{"videoId":"song","playlistSetVideoId":"$occurrence"}
                    }}"""
                }
                respond(
                    """{"responseContext":{},"contents":{"twoColumnBrowseResultsRenderer":{
                        "tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{
                            ${if (remoteEditable) "\"musicEditablePlaylistDetailHeaderRenderer\":{\"header\":{" else ""}
                            "musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"Playlist"}]},
                                "secondSubtitle":{"runs":[{"text":"${remoteCount ?: remoteOccurrences!!.size} songs"}]}}
                            ${if (remoteEditable) "},\"editHeader\":{}}" else ""}
                        }]}}}}],
                        "secondaryContents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[$songs]}}]}}
                    }}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond("{}", HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) } }
        facade = YouTube::class.java.getDeclaredField("innerTube").apply { isAccessible = true }.get(null) as InnerTube
        originalClient = checkNotNull(clientField.get(facade))
        originalTransport = checkNotNull(transportField.get(facade))
        val mockFacade = InnerTube(client).apply { cookie = "SAPISID=test-session" }
        // Replace only the app's mutable transport fields; no Java serialization internals are accessed.
        clientField.set(facade, clientField.get(mockFacade))
        transportField.set(facade, transportField.get(mockFacade))
        sync = SyncUtils(context, database)
    }

    @After
    fun tearDown() = runBlocking {
        val job = SyncUtils::class.java.getDeclaredField("syncJob").apply { isAccessible = true }.get(sync) as Job
        job.cancelAndJoin()
        clientField.set(facade, originalClient)
        transportField.set(facade, originalTransport)
        client.close()
        room.close()
    }

    private suspend fun removeSecond(): PlaylistRemovalUpdate {
        var successes = 0
        var failures = 0
        return kotlinx.coroutines.coroutineScope {
            val update = async(start = CoroutineStart.UNDISPATCHED) { sync.playlistRemovalUpdates.first() }
            sync.scheduleRemoveFromPlaylist(
                "VLplaylist-A", "song", "A",
                onSuccess = { removedNowResults.add(it); successes++ }, onFailure = { failures++ },
            ) { "second" }
            withTimeout(15_000) { update.await() }.also {
                assertEquals(if (it.removed) 1 else 0, successes)
                assertEquals(if (it.removed) 0 else 1, failures)
                assertEquals("playlist-A", mutationBodies.last()["playlistId"]?.jsonPrimitive?.content)
                val action = mutationBodies.last()["actions"]!!.jsonArray.single().jsonObject
                assertEquals("second", action["setVideoId"]?.jsonPrimitive?.content)
            }
        }
    }

    private suspend fun removePending(rowId: Int = 2): PlaylistRemovalUpdate = kotlinx.coroutines.coroutineScope {
        val update = async(start = CoroutineStart.UNDISPATCHED) { sync.playlistRemovalUpdates.first() }
        sync.scheduleRemoveFromPlaylist("VLplaylist-A", "song", "A", localRowId = rowId) { null }
        withTimeout(15_000) { update.await() }
    }

    @Test
    fun `a stale null snapshot removes only the row identified after backfill`() = runBlocking {
        database.update(database.playlistSongMaps("A", 0).last().copy(setVideoId = null))
        remoteOccurrences = listOf("first", "second")
        assertTrue(removePending().removed)
        assertEquals(listOf("first"), database.playlistSongMaps("A", 0).map { it.setVideoId })
        val action = mutationBodies.single()["actions"]!!.jsonArray.single().jsonObject
        assertEquals("second", action["setVideoId"]?.jsonPrimitive?.content)
        assertEquals(listOf("other-playlist"), database.playlistSongMaps("B", 0).map { it.setVideoId })
        assertTrue(removePending().removed)
        assertEquals(1, mutationBodies.size)
        assertEquals(listOf("first"), database.playlistSongMaps("A", 0).map { it.setVideoId })
    }

    @Test
    fun `a known occurrence never changes to a readded copy with the same local row id`() = runBlocking {
        database.update(database.playlistSongMaps("A", 0).last().copy(setVideoId = "readded"))
        remoteOccurrences = listOf("first", "readded")
        mutationStatus = HttpStatusCode.Forbidden
        val update = async(start = CoroutineStart.UNDISPATCHED) { sync.playlistRemovalUpdates.first() }
        sync.scheduleRemoveFromPlaylist("VLplaylist-A", "song", "A", localRowId = 2) { "second" }
        assertTrue(withTimeout(15_000) { update.await() }.removed)
        val action = mutationBodies.single()["actions"]!!.jsonArray.single().jsonObject
        assertEquals("second", action["setVideoId"]?.jsonPrimitive?.content)
        assertEquals(listOf("first", "readded"), database.playlistSongMaps("A", 0).map { it.setVideoId })
    }

    @Test
    fun `chooser snapshot removes its exact pending row when new additions prevent backfill`() = runBlocking {
        database.update(database.playlistSongMaps("A", 0).last().copy(setVideoId = null))
        database.insert(PlaylistSongMap(id = 4, playlistId = "A", songId = "song", position = 2))
        remoteOccurrences = listOf("first", "second")
        val update = async(start = CoroutineStart.UNDISPATCHED) { sync.playlistRemovalUpdates.first() }
        sync.scheduleRemoveFromPlaylist("VLplaylist-A", "song", "A", localRowId = 2) { "second" }
        assertTrue(withTimeout(15_000) { update.await() }.removed)
        val action = mutationBodies.single()["actions"]!!.jsonArray.single().jsonObject
        assertEquals("second", action["setVideoId"]?.jsonPrimitive?.content)
        assertEquals(listOf(1, 4), database.playlistSongMaps("A", 0).map { it.id })
        assertEquals(listOf("first", null), database.playlistSongMaps("A", 0).map { it.setVideoId })
    }

    @Test
    fun `a duplicate browse id still removes only the requested local playlist row`() = runBlocking {
        database.insert(PlaylistEntity(id = "C", name = "C", browseId = "VLplaylist-A", bookmarkedAt = LocalDateTime.now()))
        database.insert(PlaylistSongMap(id = 4, playlistId = "C", songId = "song"))
        remoteOccurrences = emptyList()
        val update = async(start = CoroutineStart.UNDISPATCHED) { sync.playlistRemovalUpdates.first() }
        sync.scheduleRemoveFromPlaylist("VLplaylist-A", "song", "C", localRowId = 4) { null }
        assertTrue(withTimeout(15_000) { update.await() }.removed)
        assertTrue(database.playlistSongMaps("C", 0).isEmpty())
        assertEquals(listOf("first", "second"), database.playlistSongMaps("A", 0).map { it.setVideoId })
        assertTrue(mutationBodies.isEmpty())
    }

    @Test
    fun `removing an unadded duplicate preserves the confirmed remote copy`() = runBlocking {
        database.update(database.playlistSongMaps("A", 0).last().copy(setVideoId = null))
        remoteOccurrences = listOf("first")
        assertTrue(removePending().removed)
        assertTrue(mutationBodies.isEmpty())
        assertEquals(listOf("first"), database.playlistSongMaps("A", 0).map { it.setVideoId })
        assertEquals(listOf(0), database.playlistSongMaps("A", 0).map { it.position })
        assertEquals(listOf("other-playlist"), database.playlistSongMaps("B", 0).map { it.setVideoId })
    }

    @Test
    fun `failed addition stays discoverable and can be removed without remote deletion`() = runBlocking {
        database.clearPlaylist("A")
        database.clearPlaylist("B")
        database.addSongsToPlaylist(database.playlistBlocking("A")!!, listOf("song" to null))
        mutationStatus = HttpStatusCode.Forbidden
        sync.scheduleAddToPlaylist("VLplaylist-A", "A", listOf("song"))
        withTimeout(10_000) { sync.awaitPlaylistAdditions("A") }
        assertTrue(database.songInEditablePlaylist("song").first())
        assertEquals(1, mutationBodies.size)
        remoteOccurrences = emptyList()
        mutationStatus = HttpStatusCode.OK
        val rows = database.playlistSongMaps("song")
        val candidates = findPlaylistRemovalCandidates(database.playlistsByNameAsc().first(), "song", rows) {
            YouTube.playlist(it).completed().getOrThrow()
        }
        assertEquals(listOf("A"), candidates.playlists.map { it.id })
        assertFalse(candidates.hasFailures)
        val matches = remotePlaylistRemovalOccurrences(rows, "A", "song", emptyList())
        assertEquals(rows, matches)
        assertTrue(removePending(matches.single().id).removed)
        assertFalse(database.songInEditablePlaylist("song").first())
        assertEquals(1, mutationBodies.size)
    }

    @Test
    fun `offline lookup keeps pending and confirmed rows intact`() = runBlocking {
        database.update(database.playlistSongMaps("A", 0).last().copy(setVideoId = null))
        assertFalse(removePending().removed)
        assertTrue(mutationBodies.isEmpty())
        assertEquals(listOf("first", null), database.playlistSongMaps("A", 0).map { it.setVideoId })
    }

    @Test
    fun `ambiguous pending copies cannot delete a remote occurrence`() = runBlocking {
        database.playlistSongMaps("A", 0).forEach { database.update(it.copy(setVideoId = null)) }
        remoteOccurrences = listOf("unclaimed")
        assertFalse(removePending().removed)
        assertTrue(mutationBodies.isEmpty())
        assertEquals(listOf(null, null), database.playlistSongMaps("A", 0).map { it.setVideoId })
    }

    @Test
    fun `incomplete empty page keeps pending rows intact`() = runBlocking {
        database.update(database.playlistSongMaps("A", 0).last().copy(setVideoId = null))
        remoteOccurrences = emptyList()
        remoteCount = 2
        assertFalse(removePending().removed)
        assertTrue(mutationBodies.isEmpty())
        assertEquals(listOf("first", null), database.playlistSongMaps("A", 0).map { it.setVideoId })
    }

    @Test
    fun `read only playlist keeps pending rows intact`() = runBlocking {
        database.update(database.playlistSongMaps("A", 0).last().copy(setVideoId = null))
        remoteOccurrences = listOf("first")
        remoteEditable = false
        assertFalse(removePending().removed)
        assertTrue(mutationBodies.isEmpty())
        assertEquals(listOf("first", null), database.playlistSongMaps("A", 0).map { it.setVideoId })
    }

    @Test
    fun `direct removal waits for in flight addition before checking pending row`() = runBlocking {
        database.update(database.playlistSongMaps("A", 0).last().copy(setVideoId = null))
        addStarted = CompletableDeferred()
        releaseAdd = CompletableDeferred()
        remoteOccurrences = listOf("first", "second")
        sync.scheduleAddToPlaylist("VLplaylist-A", "A", listOf("song"))
        withTimeout(5_000) { addStarted!!.await() }
        val removal = async(start = CoroutineStart.UNDISPATCHED) { removePending() }
        try {
            assertFalse(removal.isCompleted)
            assertEquals(listOf("first", null), database.playlistSongMaps("A", 0).map { it.setVideoId })
        } finally {
            releaseAdd!!.complete(Unit)
        }
        assertTrue(withTimeout(15_000) { removal.await() }.removed)
        assertEquals(2, mutationBodies.size)
        val action = mutationBodies.last()["actions"]!!.jsonArray.single().jsonObject
        assertEquals("second", action["setVideoId"]?.jsonPrimitive?.content)
        assertEquals(listOf("first"), database.playlistSongMaps("A", 0).map { it.setVideoId })
    }

    @Test
    fun `removal lookup waits for both queued additions without blocking other playlists`() = runBlocking {
        addStarted = CompletableDeferred()
        releaseAdd = CompletableDeferred()
        sync.scheduleAddToPlaylist("VLplaylist-A", "A", listOf("song"))
        sync.scheduleAddToPlaylist("VLplaylist-A", "A", listOf("song"))
        withTimeout(5_000) { addStarted!!.await() }
        val lookup = async(start = CoroutineStart.UNDISPATCHED) { sync.awaitPlaylistAdditions("A") }
        try {
            assertFalse(lookup.isCompleted)
            withTimeout(1_000) { sync.awaitPlaylistAdditions("B") }
        } finally {
            releaseAdd!!.complete(Unit)
        }
        withTimeout(10_000) { lookup.await() }
        assertEquals(2, mutationBodies.size)
    }

    @Test
    fun `remote rejection preserves duplicate rows and the displayed playlist`() = runBlocking {
        mutationStatus = HttpStatusCode.Forbidden
        assertFalse(removeSecond().removed)
        assertEquals(listOf("first", "second"), database.playlistSongs("A").first().map { it.map.setVideoId })
        assertEquals(listOf(0, 1), database.playlistSongMaps("A", 0).map { it.position })
        assertEquals(listOf("other-playlist"), database.playlistSongMaps("B", 0).map { it.setVideoId })
    }

    @Test
    fun `removal menu membership clears only after the last copy and returns after adding`() = runBlocking {
        val membership = database.songInEditablePlaylist("song")
        assertTrue(membership.first())
        database.delete(database.playlistSongMaps("A", 0).first())
        assertTrue(membership.first())
        database.delete(database.playlistSongMaps("A", 0).single())
        assertTrue(membership.first())
        val noMembership = async(start = CoroutineStart.UNDISPATCHED) { membership.first { !it } }
        database.delete(database.playlistSongMaps("B", 0).single())
        assertFalse(withTimeout(5_000) { noMembership.await() })
        database.insert(PlaylistSongMap(playlistId = "A", songId = "song", position = 0))
        assertTrue(membership.first())
    }

    @Test
    fun `read only and unbookmarked playlists do not offer a removal menu`() = runBlocking {
        database.update(database.playlistBlocking("A")!!.playlist.copy(isEditable = false))
        database.update(database.playlistBlocking("B")!!.playlist.copy(bookmarkedAt = null))
        assertFalse(database.songInEditablePlaylist("song").first())
    }

    @Test
    fun `successful removal updates Room before notifying the UI and preserves other playlists`() = runBlocking {
        assertTrue(removeSecond().removed)
        assertEquals(listOf("first"), database.playlistSongs("A").first().map { it.map.setVideoId })
        assertEquals(listOf("other-playlist"), database.playlistSongMaps("B", 0).map { it.setVideoId })
    }

    @Test
    fun `removal backfills a pending local occurrence before deleting the exact duplicate`() = runBlocking {
        database.update(database.playlistSongMaps("A", 0).last().copy(setVideoId = null))
        remoteOccurrences = listOf("first", "second")

        assertTrue(removeSecond().removed)

        assertEquals(listOf("first"), database.playlistSongMaps("A", 0).map { it.setVideoId })
        assertEquals(listOf(0), database.playlistSongMaps("A", 0).map { it.position })
        assertEquals(listOf("other-playlist"), database.playlistSongMaps("B", 0).map { it.setVideoId })
    }

    @Test
    fun `rejected removal keeps the backfilled row so retry deletes the same occurrence`() = runBlocking {
        database.update(database.playlistSongMaps("A", 0).last().copy(setVideoId = null))
        remoteOccurrences = listOf("first", "second")
        mutationStatus = HttpStatusCode.Forbidden

        assertFalse(removeSecond().removed)
        assertEquals(listOf("first", "second"), database.playlistSongMaps("A", 0).map { it.setVideoId })

        mutationStatus = HttpStatusCode.OK
        assertTrue(removeSecond().removed)
        assertEquals(listOf("first"), database.playlistSongMaps("A", 0).map { it.setVideoId })
    }

    @Test
    fun `http success with a rejected edit preserves local rows`() = runBlocking {
        mutationBody = """{"status":"STATUS_FAILED"}"""
        assertFalse(removeSecond().removed)
        assertEquals(listOf("first", "second"), database.playlistSongs("A").first().map { it.map.setVideoId })
    }

    @Test
    fun `failed removal can be retried without losing occurrence identity`() = runBlocking {
        mutationStatus = HttpStatusCode.Forbidden
        assertFalse(removeSecond().removed)
        mutationStatus = HttpStatusCode.OK
        assertTrue(removeSecond().removed)
        assertEquals(2, mutationBodies.size)
        assertEquals(listOf("first"), database.playlistSongs("A").first().map { it.map.setVideoId })
    }

    @Test
    fun `repeating a completed removal does not send another edit or report a new deletion`() = runBlocking {
        assertTrue(removeSecond().removed)
        assertTrue(removeSecond().removed)
        assertEquals(1, mutationBodies.size)
        assertEquals(listOf(true, false), removedNowResults)
        assertEquals(listOf("first"), database.playlistSongs("A").first().map { it.map.setVideoId })
    }

    @Test
    fun `confirmed server absence releases the repeat guard so a restored occurrence can be removed`() = runBlocking {
        assertTrue(removeSecond().removed)
        sync.clearConfirmedPlaylistRemovals("VLplaylist-A", emptyList())
        database.insert(PlaylistSongMap(id = 2, playlistId = "A", songId = "song", position = 1, setVideoId = "second"))
        assertTrue(removeSecond().removed)
        assertEquals(2, mutationBodies.size)
        assertEquals(listOf(true, true), removedNowResults)
    }
}
