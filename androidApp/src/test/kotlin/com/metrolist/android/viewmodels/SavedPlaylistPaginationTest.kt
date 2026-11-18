package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModelStore
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.cacheRemotePlaylist
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playlist.addSongToStoredPlaylist
import com.metrolist.shared.settings.Settings
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.setResourceReaderAndroidContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalResourceApi::class)
class SavedPlaylistPaginationTest {
    @Test
    fun migratedCompleteReadOnlyPlaylistRefreshesOnceWithoutScrolling() = checkMigratedCompletePlaylist(isEditable = false)

    @Test
    fun migratedCompleteEditablePlaylistRecoversOccurrenceIdsWithoutScrolling() = checkMigratedCompletePlaylist(isEditable = true)

    private fun checkMigratedCompletePlaylist(isEditable: Boolean) =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val previousNetwork = PlaybackNetworkStateStore.current
            PlaybackNetworkStateStore.publish(previousNetwork.copy(connected = true))
            val context = RuntimeEnvironment.getApplication()
            setResourceReaderAndroidContext(context)
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val requests = CopyOnWriteArrayList<String?>()
            val header =
                if (isEditable) {
                    """{"musicEditablePlaylistDetailHeaderRenderer":{"header":{"musicDetailHeaderRenderer":{"title":{"simpleText":"Updated"}}}}}"""
                } else {
                    """{"musicDetailHeaderRenderer":{"title":{"simpleText":"Updated"}}}"""
                }

            fun row(occurrence: String) =
                """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"duplicate","playlistSetVideoId":"$occurrence"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"Song"}}}]}}"""
            val client =
                HttpClient(
                    MockEngine { request ->
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val continuation = body["continuation"]?.jsonPrimitive?.content
                        requests += continuation
                        val response =
                            if (continuation == null) {
                                assertEquals("VLPLsaved", body["browseId"]?.jsonPrimitive?.content)
                                """{"header":$header,"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[${row(
                                    "first",
                                )}],"continuations":[{"nextContinuationData":{"continuation":"next"}}]}}]}}}"""
                            } else {
                                assertEquals("next", continuation)
                                """{"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[${row("second")}]}}}"""
                            }
                        respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val tube = InnerTube(client, retryDelay = {}).apply { replaceSession("SAPISID=test", "visitor", "account", "0", true) }
            val store = ViewModelStore()
            try {
                database.dao().insert(
                    PlaylistEntity(
                        id = "LPsaved",
                        name = "Saved",
                        browseId = "PLsaved",
                        remoteSongCount = 2,
                        isEditable = isEditable,
                        isAutoSync = true,
                    ),
                )
                database.dao().insert(SongEntity(id = "duplicate", title = "Cached"))
                repeat(2) { database.dao().insert(PlaylistSongMap(playlistId = "LPsaved", songId = "duplicate", position = it)) }
                val repository = YouTubeRepository(tube)
                val model = PlaylistViewModel("LPsaved", database, backgroundScope, repositoryProvider = { repository })
                store.put("playlist", model)
                awaitPlaylistState { !model.isLoading.value && model.page.value != null }
                model.loadPlaylist()
                awaitPlaylistState { !model.isLoading.value && (model.hasCompleteStoredSnapshot.value || model.page.value != null) }
                assertEquals(1, requests.count { it == null })
                assertEquals(listOf(null, "next"), requests.toList())
                assertTrue(model.hasCompleteStoredSnapshot.value)
                assertNull(model.page.value)
                assertEquals(listOf("first", "second"), model.durationSongs.value.map { it.playlistSetVideoId })
                val stored = checkNotNull(database.dao().findPlaylistEntity("LPsaved"))
                assertNotNull(stored.remoteHeader)
                assertEquals(isEditable, stored.isEditable)
                assertEquals(2, stored.remoteSongCount)
                assertEquals(
                    listOf("first", "second"),
                    database
                        .dao()
                        .playlistSongs("LPsaved")
                        .first()
                        .map { it.map.setVideoId },
                )
            } finally {
                store.clear()
                tube.close()
                client.close()
                database.close()
                PlaybackNetworkStateStore.publish(previousNetwork)
                Dispatchers.resetMain()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun acknowledgedAddReloadsMissingOccurrenceIdsAndThenReusesTheSnapshot() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val context = RuntimeEnvironment.getApplication()
            setResourceReaderAndroidContext(context)
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            var browseRequests = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        val response =
                            if (request.url.encodedPath.endsWith("/browse/edit_playlist")) {
                                """{"status":"STATUS_SUCCEEDED"}"""
                            } else {
                                browseRequests++
                                val rows =
                                    listOf("original", "added").joinToString(",") { id ->
                                        """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"same","playlistSetVideoId":"$id"},
                        "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"Song"}}}]}}"""
                                    }
                                """{"header":{"musicEditablePlaylistDetailHeaderRenderer":{"header":{"musicDetailHeaderRenderer":{"title":{"simpleText":"Playlist"}}}}},
                    "contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[$rows]}}]}}}"""
                            }
                        respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val tube = InnerTube(client, retryDelay = {}).apply { replaceSession("SAPISID=test", "visitor", "account", "0", true) }
            val repository = YouTubeRepository(tube)
            val store = ViewModelStore()
            try {
                val song = SongItem("same", "Song", null, emptyList(), null, playlistSetVideoId = "original")
                database.cacheRemotePlaylist("PLadd", PlaylistItem("PLadd", "Playlist", null, isEditable = true), listOf(song))
                val model = PlaylistViewModel("PLadd", database, backgroundScope, repositoryProvider = { repository })
                store.put("playlist", model)
                awaitPlaylistState { !model.isLoading.value && model.songs.value.size == 1 }
                assertEquals(0, browseRequests)
                assertTrue(model.hasCompleteStoredSnapshot.value)

                addSongToStoredPlaylist(database, repository, "PLadd", song, allowDuplicate = true).getOrThrow()
                awaitPlaylistState { model.songs.value.map { it.playlistSetVideoId } == listOf("original", "added") }
                assertEquals(1, browseRequests)
                assertFalse(model.hasCompleteStoredSnapshot.value)
                model.loadPlaylist()
                awaitPlaylistState { model.hasCompleteStoredSnapshot.value && !model.isLoading.value && model.songs.value.size == 2 }
                assertEquals(1, browseRequests)
                assertTrue(model.hasCompleteStoredSnapshot.value)
            } finally {
                store.clear()
                tube.close()
                client.close()
                database.close()
                Dispatchers.resetMain()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun tappedMixCoverIsCachedOnTheFirstPageAndCustomCoverSurvivesRefresh() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val context = RuntimeEnvironment.getApplication()
            setResourceReaderAndroidContext(context)
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            """{"header":{"musicResponsiveHeaderRenderer":{"title":{"simpleText":"Energy Mix 1"},
                    "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"browse-cover"}]}}}}}}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val store = ViewModelStore()
            try {
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
                val model =
                    PlaylistViewModel("RDmix", database, backgroundScope, repositoryProvider = { repository }, thumbnailHint = "home-cover")
                store.put("playlist", model)
                awaitPlaylistState { !model.isLoading.value && model.page.value != null }
                assertEquals("home-cover", model.playlistInfo.value?.thumbnail)
                val cached = checkNotNull(database.dao().findPlaylistEntity("RDmix"))
                assertEquals("home-cover", cached.thumbnailUrl)
                database.dao().upsert(cached.copy(customThumbnailUrl = "custom-cover"))
                model.loadPlaylist()
                awaitPlaylistState {
                    !model.isLoading.value && model.page.value != null && model.playlistInfo.value?.customThumbnail == "custom-cover"
                }
                assertEquals("home-cover", model.playlistInfo.value?.thumbnail)
                val refreshed = checkNotNull(database.dao().findPlaylistEntity("RDmix"))
                assertEquals("home-cover", refreshed.thumbnailUrl)
                assertEquals("custom-cover", refreshed.customThumbnailUrl)
                model.removePlaylistCover { it.getOrThrow() }
                awaitPlaylistState {
                    !model.isLoading.value && model.page.value != null && model.playlistInfo.value?.thumbnail == "browse-cover"
                }
                assertEquals("browse-cover", database.dao().findPlaylistEntity("RDmix")?.thumbnailUrl)
            } finally {
                store.clear()
                client.close()
                database.close()
                Dispatchers.resetMain()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun savedPlaylistPublishesFirstPageAndReplacesMembershipOnlyAfterTheLastPage() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val context = RuntimeEnvironment.getApplication()
            setResourceReaderAndroidContext(context)
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val requests = CopyOnWriteArrayList<String?>()
            var failLast = true

            fun row(occurrence: String) =
                """{"musicResponsiveListItemRenderer":{
            "playlistItemData":{"videoId":"duplicate","playlistSetVideoId":"$occurrence"},
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"Track"}}}]}}"""
            val client =
                HttpClient(
                    MockEngine { request ->
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val token = body["continuation"]?.jsonPrimitive?.content
                        requests += token
                        val response =
                            if (token == null) {
                                assertEquals("VLPLremote", body["browseId"]?.jsonPrimitive?.content)
                                """{"header":{"musicResponsiveHeaderRenderer":{"title":{"simpleText":"Remote"},
                    "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"browse-cover"}]}}}}},
                    "contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{
                    "contents":[${row("first")}],
                    "continuations":[{"nextContinuationData":{"continuation":"page-2"}}]}}]}}}"""
                            } else {
                                val next =
                                    if (token ==
                                        "page-2"
                                    ) {
                                        ",\"continuations\":[{\"nextContinuationData\":{\"continuation\":\"page-3\"}}]"
                                    } else {
                                        ""
                                    }
                                """{"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[${row(token)}]$next}}}"""
                            }
                        respond(
                            response,
                            if (token == "page-3" && failLast) HttpStatusCode.BadRequest else HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val store = ViewModelStore()
            try {
                database.dao().insert(PlaylistEntity(id = "LPsaved", name = "Saved", browseId = "PLremote", thumbnailUrl = "cached-cover"))
                database.dao().insert(SongEntity(id = "cached", title = "Cached"))
                database.dao().insert(PlaylistSongMap(playlistId = "LPsaved", songId = "cached", position = 0))
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
                val model =
                    PlaylistViewModel(
                        "LPsaved",
                        database,
                        backgroundScope,
                        repositoryProvider = { repository },
                        thumbnailHint = "home-cover",
                    )
                store.put("playlist", model)
                awaitPlaylistState { !model.isLoading.value && model.page.value != null }
                assertEquals(listOf<String?>(null), requests.toList())
                assertEquals(listOf("first"), model.songs.value.map { it.playlistSetVideoId })
                assertEquals("home-cover", model.playlistInfo.value?.thumbnail)
                assertEquals("cached-cover", database.dao().findPlaylistEntity("LPsaved")?.thumbnailUrl)

                suspend fun stored() =
                    database
                        .dao()
                        .playlistSongs("LPsaved")
                        .first()
                        .map { it.song.id }
                assertEquals(listOf("cached"), stored())
                checkNotNull(model.loadMore(Int.MAX_VALUE)).join()
                assertEquals(2, model.songs.value.size)
                assertEquals(listOf("cached"), stored())
                checkNotNull(model.loadMore(Int.MAX_VALUE)).join()
                assertTrue(model.page.value!!.hasMore)
                assertEquals(listOf("cached"), stored())
                failLast = false
                checkNotNull(model.loadMore(Int.MAX_VALUE, retry = true)).join()
                assertEquals(listOf("duplicate", "duplicate", "duplicate"), stored())
                assertEquals("home-cover", database.dao().findPlaylistEntity("LPsaved")?.thumbnailUrl)
                model.loadPlaylist(forceRefresh = true)
                awaitPlaylistState { !model.isLoading.value && model.page.value?.hasMore == true }
                assertEquals("home-cover", model.playlistInfo.value?.thumbnail)
                assertEquals(listOf(null, "page-2", "page-3", "page-3", null), requests.toList())
            } finally {
                store.clear()
                client.close()
                database.close()
                Dispatchers.resetMain()
                Settings.replaceSharedForTest(null)
            }
        }
}
