package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModelStore
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.settings.Settings
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
                model.loadPlaylist()
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
