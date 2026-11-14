package com.metrolist.shared.db

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.shared.api.LibraryItemsResult
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.playlistSongs
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playlist.LibraryDeletionTracker
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.UnknownHostException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistSyncTest {
    @Test
    fun remoteRefreshUpdatesEveryStoredCopyWithoutMergingItsSongs() =
        runBlocking {
            val path = Files.createTempFile("playlist-metadata", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val copies =
                    listOf(
                        PlaylistEntity(id = "legacy", name = "Old", browseId = "VLPLremote", thumbnailUrl = "old-cover"),
                        PlaylistEntity(id = "PLremote", name = "Older", browseId = "PLremote", thumbnailUrl = "older-cover"),
                    )
                copies.forEachIndexed { index, playlist ->
                    database.dao().upsert(playlist)
                    val song = SongItem("song-$index", "Song", null, emptyList(), null)
                    database.dao().upsertHistorySong(song)
                    database.dao().insert(PlaylistSongMap(playlistId = playlist.id, songId = song.id, position = 0))
                }
                val local = PlaylistEntity(id = "local", name = "Local", browseId = "PLremote", isLocal = true)
                database.dao().upsert(local)
                val before =
                    database
                        .dao()
                        .allPlaylists()
                        .first()
                        .associateBy { it.id }
                database.syncRemotePlaylists(listOf(PlaylistItem("PLremote", "Renamed", "new-cover", isEditable = true)))
                val stored =
                    database
                        .dao()
                        .allPlaylists()
                        .first()
                        .associateBy { it.id }
                copies.forEachIndexed { index, copy ->
                    val refreshed = stored.getValue(copy.id)
                    assertEquals("Renamed", refreshed.name)
                    assertEquals("new-cover", refreshed.thumbnailUrl)
                    assertEquals(before.getValue(copy.id).createdAt, refreshed.createdAt)
                    assertEquals(before.getValue(copy.id).bookmarkedAt, refreshed.bookmarkedAt)
                    assertEquals(
                        listOf("song-$index"),
                        database
                            .dao()
                            .playlistSongs(copy.id)
                            .first()
                            .map { it.song.id },
                    )
                }
                assertEquals(before.getValue(local.id), stored.getValue(local.id))
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun unavailableRowsRetainCachedMembershipCountsAndRemovalIds() =
        runBlocking {
            val path = Files.createTempFile("unavailable-playlist", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val songs =
                    listOf(
                        SongItem(
                            "unavailable",
                            "Private video",
                            null,
                            emptyList(),
                            null,
                            playlistSetVideoId = "private-row",
                            isUnavailable = true,
                        ),
                        SongItem("playable", "Playable", null, emptyList(), null, playlistSetVideoId = "playable-row"),
                    )
                val playlist = PlaylistItem("PLtest", "Playlist", null, songCount = "2", isEditable = true)
                database.cacheRemotePlaylist("PLtest", playlist, songs)
                val cached = database.cachedRemotePlaylistSections("PLtest")!!
                assertEquals(songs.map { it.playlistSetVideoId }, cached.playlistSongs().map { it.playlistSetVideoId })
                assertEquals(listOf(true, false), cached.playlistSongs().map { it.isUnavailable })
                assertEquals("2", (cached.getValue("header").single() as PlaylistItem).songCount)
                assertEquals(2, database.dao().findPlaylistEntity("PLtest")!!.remoteSongCount)
                assertFalse(database.dao().isPlaylistDownloaded("PLtest").first())
                database.dao().upsert(
                    CachedAudioEntity(
                        songId = "playable",
                        title = "Playable",
                        artist = "",
                        filePath = "/playable",
                        fileSize = 10,
                        cachedAt = 1,
                        lastAccessedAt = 1,
                        cacheLocation = "internal",
                        kind = KIND_DOWNLOAD,
                    ),
                )
                assertTrue(database.dao().isPlaylistDownloaded("PLtest").first())
                assertEquals(listOf("playlist:PLtest"), database.dao().downloadedCollectionKeys(listOf("playable")).first())
                database.cacheRemotePlaylist("PLtest", playlist, songs.map { it.copy(isUnavailable = false) })
                assertTrue(database.cachedRemotePlaylistSections("PLtest")!!.playlistSongs().none { it.isUnavailable })
                assertFalse(database.dao().isPlaylistDownloaded("PLtest").first())
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun syncedPlaylistCompletesEveryPageBeforeReplacingStoredSongs() =
        runBlocking {
            val path = Files.createTempFile("synced-playlist-pages", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            var failContinuation = true
            var firstPagePublished = false
            val requests = mutableListOf<String?>()

            fun row(occurrence: String) =
                """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}}],"playlistItemData":{"videoId":"song","playlistSetVideoId":"$occurrence"}}}"""
            val client =
                HttpClient(
                    MockEngine { request ->
                        val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val continuation = payload["continuation"]?.jsonPrimitive?.content
                        requests += continuation
                        if (continuation != null) {
                            assertTrue(firstPagePublished)
                            assertEquals(
                                listOf("cached"),
                                database
                                    .dao()
                                    .playlistSongs("LPsaved")
                                    .first()
                                    .map { it.song.id },
                            )
                            respond(
                                """{"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[${row("second")}]}}}""",
                                if (failContinuation) HttpStatusCode.BadRequest else HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        } else {
                            assertEquals("VLPLsaved", payload["browseId"]?.jsonPrimitive?.content)
                            respond(
                                """{"header":{"musicDetailHeaderRenderer":{"title":{"simpleText":"Synced"}}},"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[${row(
                                    "first",
                                )}],"continuations":[{"nextContinuationData":{"continuation":"next"}}]}}]}}}""",
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                database.dao().insert(PlaylistEntity(id = "LPsaved", name = "Saved", browseId = "PLsaved", isEditable = true))
                assertNull(database.cachedRemotePlaylistSections("VLPLsaved"))
                database.cacheRemotePlaylist(
                    "LPsaved",
                    PlaylistItem("PLsaved", "Saved", null),
                    listOf(SongItem("cached", "Cached", null, emptyList(), null)),
                )
                val cached = database.cachedRemotePlaylistSections("VLPLsaved")!!
                assertEquals("PLsaved", (cached["header"]!!.single() as PlaylistItem).id)
                assertEquals(listOf("cached"), cached.playlistSongs().map { it.id })
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
                assertFailsWith<InnerTubeHttpException> {
                    database.loadAndCachePlaylistPage(repository, "VLPLsaved") { first ->
                        assertTrue(first.hasMore)
                        assertEquals(listOf("first"), first.songs.map { it.playlistSetVideoId })
                        firstPagePublished = true
                    }
                }
                assertEquals(cached, database.cachedRemotePlaylistSections("LPsaved"))
                assertEquals(
                    listOf("cached"),
                    database
                        .dao()
                        .playlistSongs("LPsaved")
                        .first()
                        .map { it.song.id },
                )
                assertEquals(emptyList(), database.dao().downloadedCollectionKeys(listOf("song")).first())
                failContinuation = false
                val page =
                    database.loadAndCachePlaylistPage(repository, "VLPLsaved") { first ->
                        assertTrue(first.hasMore)
                        assertEquals(listOf("first"), first.songs.map { it.playlistSetVideoId })
                        firstPagePublished = true
                    }
                assertFalse(page.hasMore)
                assertFalse(page.isOffline)
                assertFalse((page.sections["header"]!!.single() as PlaylistItem).isEditable)
                assertEquals(listOf("first", "second"), page.songs.map { it.playlistSetVideoId })
                assertEquals(
                    listOf("first", "second"),
                    database
                        .dao()
                        .playlistSongs("LPsaved")
                        .first()
                        .map { it.map.setVideoId },
                )
                assertEquals(2, database.dao().findPlaylistEntity("PLsaved")?.remoteSongCount)
                assertEquals(listOf("playlist:PLsaved"), database.dao().downloadedCollectionKeys(listOf("song")).first())
                assertFalse(database.dao().isPlaylistDownloaded("PLsaved").first())
                database.dao().upsert(
                    CachedAudioEntity(
                        songId = "song",
                        title = "Song",
                        artist = "Artist",
                        filePath = "/song",
                        fileSize = 10,
                        cachedAt = 1,
                        lastAccessedAt = 1,
                        cacheLocation = "internal",
                        kind = KIND_DOWNLOAD,
                    ),
                )
                assertTrue(database.dao().isPlaylistDownloaded("PLsaved").first())
                assertEquals(listOf("playlist:PLsaved"), database.dao().downloadedCollectionKeys().first())
                assertEquals(listOf("song"), database.dao().playlistDownloadIds("PLsaved"))
                assertEquals(listOf(null, "next", null, "next"), requests)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun offlinePlaylistResultsPreserveTheCompleteStoredPlaylist() =
        runBlocking {
            val path = Files.createTempFile("offline-playlist-sync", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val previousNetwork = PlaybackNetworkStateStore.current
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        throw UnknownHostException("music.youtube.com")
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                database.dao().insert(PlaylistEntity(id = "LPsaved", name = "Saved", browseId = "PLsaved", isEditable = true))
                database.cacheRemotePlaylist(
                    "LPsaved",
                    PlaylistItem("PLsaved", "Saved", null),
                    listOf(
                        SongItem("downloaded", "Downloaded", null, emptyList(), null, playlistSetVideoId = "first"),
                        SongItem("online", "Online", null, emptyList(), null, playlistSetVideoId = "second"),
                    ),
                )
                database.dao().updateDownloadedInfo("downloaded", true, null)
                val stored = database.dao().findPlaylistEntity("PLsaved")
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}), databaseProvider = { database })
                for (connected in listOf(false, true)) {
                    PlaybackNetworkStateStore.publish(previousNetwork.copy(connected = connected))
                    for (id in listOf("LPsaved", "PLsaved", "VLPLsaved")) {
                        val page = database.loadAndCachePlaylistPage(repository, id)
                        assertTrue(page.isOffline)
                        assertFalse(page.hasMore)
                        assertEquals(listOf("downloaded"), page.songs.map { it.id })
                        assertEquals(stored, database.dao().findPlaylistEntity(id))
                        assertEquals(
                            listOf("first", "second"),
                            database
                                .dao()
                                .playlistSongs("LPsaved")
                                .first()
                                .map { it.map.setVideoId },
                        )
                    }
                    if (!connected) assertEquals(0, requests) else assertTrue(requests > 0)
                }
            } finally {
                PlaybackNetworkStateStore.publish(previousNetwork)
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun curatedPlaylistKeepsItsBrowseEndpointWhenReopenedFromCache() =
        runBlocking {
            val path = Files.createTempFile("curated-playlist", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val browseIds = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        val browseId =
                            Json
                                .parseToJsonElement((request.body as TextContent).text)
                                .jsonObject["browseId"]!!
                                .jsonPrimitive.content
                        browseIds += browseId
                        respond(
                            """{"header":{"musicDetailHeaderRenderer":{"title":{"simpleText":"Curated"}}}}""",
                            if (browseId == "VLRDCLAK5uy_test") HttpStatusCode.OK else HttpStatusCode.BadRequest,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
                val first = database.loadAndCachePlaylist(repository, "VLRDCLAK5uy_test")
                val stored = database.dao().findPlaylistEntity("VLRDCLAK5uy_test")!!
                val second = database.loadAndCachePlaylist(repository, stored.browseId!!)
                assertEquals("Curated", (first["header"]!!.single() as PlaylistItem).title)
                assertEquals("Curated", (second["header"]!!.single() as PlaylistItem).title)
                assertEquals(listOf("VLRDCLAK5uy_test", "VLRDCLAK5uy_test"), browseIds)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun detailsRevokeStaleOwnershipAndDeletedPlaylistsCannotBeRecached() =
        runBlocking {
            val path = Files.createTempFile("created-playlist", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            """{"header":{"musicDetailHeaderRenderer":{"title":{"simpleText":"Created"}}}}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            LibraryDeletionTracker.clear()
            try {
                val created = PlaylistEntity(id = "LPcreated", name = "Created", browseId = "PLcreated", isEditable = true)
                database.dao().insert(created)
                val result = database.loadAndCachePlaylist(YouTubeRepository(InnerTube(client)), "PLcreated")
                val header = result["header"]!!.single() as PlaylistItem
                assertFalse(header.isEditable)
                assertFalse(database.dao().findPlaylistEntity(created.id)!!.isEditable)
                LibraryDeletionTracker.trackDeletedPlaylist("PLcreated")
                database.dao().delete(created)
                database.cacheRemotePlaylist("PLcreated", header, emptyList())
                database.syncRemotePlaylists(listOf(header))
                assertTrue(
                    database
                        .dao()
                        .allPlaylists()
                        .first()
                        .isEmpty(),
                )
            } finally {
                LibraryDeletionTracker.clear()
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun formattedCountsPersistAndAbbreviatedCountsDoNotReplaceKnownTotals() =
        runBlocking {
            val path = Files.createTempFile("playlist-count", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val item = PlaylistItem("PLcount", "Playlist", null, songCount = "1,234 songs")
                database.syncRemotePlaylists(listOf(item))
                assertEquals(1234, database.dao().findPlaylistEntity(item.id)?.remoteSongCount)
                database.syncRemotePlaylists(listOf(item.copy(songCount = "1.2K songs")))
                assertEquals(1234, database.dao().findPlaylistEntity(item.id)?.remoteSongCount)
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun playlistFetchPropagatesStatusAndPreservesCacheUntilSuccessfulRetry() =
        runBlocking {
            val path = Files.createTempFile("metrolist-playlist-fetch", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            var status = HttpStatusCode.Forbidden
            var body = "{}"
            val client =
                HttpClient(
                    MockEngine { request ->
                        val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        assertEquals("VLPLtest", payload["browseId"]?.jsonPrimitive?.content)
                        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
            try {
                database.cacheRemotePlaylist(
                    "PLtest",
                    PlaylistItem("PLtest", "Cached", null),
                    listOf(SongItem("cached", "Cached song", null, emptyList(), null)),
                )
                val failure = assertFailsWith<InnerTubeHttpException> { database.loadAndCachePlaylist(repository, "PLtest") }
                assertEquals(403, failure.status.value)
                assertEquals(
                    listOf("cached"),
                    database
                        .dao()
                        .playlistSongs("PLtest")
                        .first()
                        .map { it.song.id },
                )
                status = HttpStatusCode.OK
                assertFailsWith<IllegalStateException> { database.loadAndCachePlaylist(repository, "PLtest") }
                assertEquals(
                    listOf("cached"),
                    database
                        .dao()
                        .playlistSongs("PLtest")
                        .first()
                        .map { it.song.id },
                )
                body = """{"header":{"musicDetailHeaderRenderer":{"title":{"simpleText":"Empty playlist"}}}}"""
                val cached = requireNotNull(database.dao().findPlaylistEntity("PLtest"))
                database.dao().delete(cached)
                database.dao().upsert(cached.copy(id = "LPlocal"))
                val result = database.loadAndCachePlaylist(repository, "LPlocal")
                assertEquals("Empty playlist", (result["header"]!!.single() as PlaylistItem).title)
                assertTrue(
                    database
                        .dao()
                        .playlistSongs("LPlocal")
                        .first()
                        .isEmpty(),
                )
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun syncMakesExistingAccountPlaylistsAvailableWithoutDuplicates() =
        runBlocking {
            val path = Files.createTempFile("metrolist-playlist-sync", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val remote =
                    PlaylistItem(
                        id = "VLremote",
                        title = "Remote playlist",
                        thumbnail = "https://example.com/cover.jpg",
                        songCount = "12",
                        isEditable = true,
                    )
                (1..12)
                    .map { index ->
                        async {
                            if (index % 2 == 0) {
                                database.syncRemotePlaylists(listOf(remote))
                            } else {
                                database.cacheRemotePlaylist(remote.id, remote, emptyList())
                            }
                        }
                    }.awaitAll()
                database.syncRemotePlaylists(listOf(remote.copy(title = "Renamed")))

                val playlists = database.dao().allPlaylists().first()
                assertEquals(1, playlists.size)
                assertEquals("remote", playlists.single().browseId)
                assertEquals("Renamed", playlists.single().name)
                assertTrue(playlists.single().isEditable)
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun partialLibraryRowsPreserveHydratedPlaylistMetadata() =
        runBlocking {
            val path = Files.createTempFile("metrolist-playlist-metadata", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val updatedAt = LocalDateTime(2026, 1, 2, 3, 4)
                database.dao().upsert(
                    PlaylistEntity(
                        id = "cached",
                        name = "Cached",
                        browseId = "PLremote",
                        thumbnailUrl = "https://example.com/hydrated.jpg",
                        remoteSongCount = 12,
                        lastUpdateTime = updatedAt,
                    ),
                )

                database.syncRemotePlaylists(
                    listOf(
                        PlaylistItem(id = "VLPLremote", title = "Renamed", thumbnail = null),
                        PlaylistItem(id = "VLPLfresh", title = "Fresh", thumbnail = null, songCount = "12 songs"),
                    ),
                )

                val stored = database.dao().findPlaylistEntity("PLremote")!!
                assertEquals("Renamed", stored.name)
                assertEquals("https://example.com/hydrated.jpg", stored.thumbnailUrl)
                assertEquals(12, stored.remoteSongCount)
                assertEquals(updatedAt, stored.lastUpdateTime)
                assertEquals(12, database.dao().findPlaylistEntity("PLfresh")?.remoteSongCount)
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun completeLibraryRefreshRepairsStalePlaylistEditability() =
        runBlocking {
            val path = Files.createTempFile("metrolist-playlist-sync", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = PLAYLIST_LIBRARY_RESPONSE,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            try {
                val stale = PlaylistEntity(id = "cached", name = "Cached", browseId = "PLremote", isEditable = false)
                database.dao().insert(stale)
                assertTrue(
                    database
                        .dao()
                        .editablePlaylistsByCreateDateAsc()
                        .first()
                        .isEmpty(),
                )

                val innerTube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
                val result = YouTubeRepository(innerTube).getCompleteLibraryItemsResult()
                val remotePlaylists = result.items.filterIsInstance<PlaylistItem>()
                database.syncRemotePlaylists(remotePlaylists)

                assertTrue(result.complete)
                assertTrue(remotePlaylists.single().isEditable)
                assertEquals(
                    stale.id,
                    database
                        .dao()
                        .editablePlaylistsByCreateDateAsc()
                        .first()
                        .single()
                        .id,
                )
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun failedOwnershipLookupPreservesKnownEditablePlaylistUntilConfirmedNonOwned() =
        runBlocking {
            for (completeLibrary in listOf(false, true)) {
                val path = Files.createTempFile("metrolist-playlist-ownership", ".db")
                Files.deleteIfExists(path)
                val database = getDesktopDatabaseBuilder(path.toFile()).build()
                var failDetailLookup = true
                val client =
                    HttpClient(
                        MockEngine { request ->
                            val browseId =
                                Json
                                    .parseToJsonElement((request.body as TextContent).text)
                                    .jsonObject["browseId"]
                                    ?.jsonPrimitive
                                    ?.content
                            val detailLookup = browseId == "VLPLremote"
                            respond(
                                content = if (detailLookup) "{}" else PLAYLIST_LIBRARY_ROW_RESPONSE,
                                status =
                                    if (detailLookup && failDetailLookup) {
                                        HttpStatusCode.ServiceUnavailable
                                    } else {
                                        HttpStatusCode.OK
                                    },
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    ) {
                        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                    }
                try {
                    val stored = PlaylistEntity(id = "known-owned", name = "Known owned", browseId = "PLremote", isEditable = true)
                    database.dao().insert(stored)
                    val innerTube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
                    val authenticatedRepository = YouTubeRepository(innerTube)

                    suspend fun load(): LibraryItemsResult =
                        if (completeLibrary) {
                            authenticatedRepository.getCompleteLibraryItemsResult()
                        } else {
                            authenticatedRepository.getUserPlaylistsResult()
                        }

                    val unresolved = load()
                    val preserved =
                        database
                            .preserveKnownPlaylistEditability(
                                unresolved.items,
                                unresolved.unresolvedPlaylistEditabilityIds,
                            ).filterIsInstance<PlaylistItem>()
                    database.syncRemotePlaylists(preserved)

                    assertFalse(unresolved.complete)
                    assertEquals(setOf("VLPLremote"), unresolved.unresolvedPlaylistEditabilityIds)
                    assertTrue(preserved.single().isEditable)
                    assertEquals(
                        stored.id,
                        database
                            .dao()
                            .editablePlaylistsByCreateDateAsc()
                            .first()
                            .single()
                            .id,
                    )

                    failDetailLookup = false
                    val confirmedNonOwned = load()
                    val confirmed =
                        database
                            .preserveKnownPlaylistEditability(
                                confirmedNonOwned.items,
                                confirmedNonOwned.unresolvedPlaylistEditabilityIds,
                            ).filterIsInstance<PlaylistItem>()
                    database.syncRemotePlaylists(confirmed)

                    assertTrue(confirmedNonOwned.complete)
                    assertTrue(confirmedNonOwned.unresolvedPlaylistEditabilityIds.isEmpty())
                    assertFalse(confirmed.single().isEditable)
                    assertTrue(
                        database
                            .dao()
                            .editablePlaylistsByCreateDateAsc()
                            .first()
                            .isEmpty(),
                    )
                } finally {
                    client.close()
                    database.close()
                    Files.deleteIfExists(path)
                }
            }
        }

    private companion object {
        const val PLAYLIST_LIBRARY_RESPONSE =
            """
            {
              "header": {"musicEditablePlaylistDetailHeaderRenderer": {}},
              "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {
                "sectionListRenderer": {"contents": [{"gridRenderer": {"items": [
                  {"musicTwoRowItemRenderer": {
                    "title": {"runs": [{"text": "Remote playlist"}]},
                    "navigationEndpoint": {"browseEndpoint": {"browseId": "VLPLremote"}}
                  }}
                ]}}]}
              }}}]}}
            }
            """
        const val PLAYLIST_LIBRARY_ROW_RESPONSE =
            """
            {
              "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {
                "sectionListRenderer": {"contents": [{"gridRenderer": {"items": [
                  {"musicTwoRowItemRenderer": {
                    "title": {"runs": [{"text": "Remote playlist"}]},
                    "navigationEndpoint": {"browseEndpoint": {"browseId": "VLPLremote"}}
                  }}
                ]}}]}
              }}}]}}
            }
            """
    }
}
