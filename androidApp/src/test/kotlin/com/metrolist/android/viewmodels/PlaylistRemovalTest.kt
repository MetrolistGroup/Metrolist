package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModelStore
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.playlistTotalDuration
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.setResourceReaderAndroidContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalResourceApi::class)
class PlaylistRemovalTest {
    @Before
    fun initializeResources() {
        setResourceReaderAndroidContext(RuntimeEnvironment.getApplication())
    }

    @Test
    fun removedOccurrenceCannotBeResubmittedWhileRefreshIsPending() = checkRemoval()

    @Test
    fun failedRefreshDoesNotRestoreRemovedOccurrence() = checkRemoval(refreshFails = true)

    @Test
    fun rejectedRemovalPreservesBothOccurrencesAndAllowsRetry() = checkRemoval(removalFails = true)

    @Test
    fun headerRuntimeIncludesTracksHiddenByContentSettings() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { hideVideoSongs = true })
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val database =
                Room
                    .inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(),
                        MusicDatabase::class.java,
                    ).setDriver(AndroidSQLiteDriver())
                    .build()
            val store = ViewModelStore()
            val rows =
                listOf("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV")
                    .mapIndexed { index, type ->
                        """{"musicResponsiveListItemRenderer":{
                "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"Song"}}}],
                "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"simpleText":"2:00"}}}],
                "navigationEndpoint":{"watchEndpoint":{"videoId":"song-$index","watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"$type"}}}},
                "playlistItemData":{"videoId":"song-$index","playlistSetVideoId":"occurrence-$index"}
            }}"""
                    }.joinToString(",")
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            """{"header":{"musicResponsiveHeaderRenderer":{"title":{"simpleText":"Playlist"}}},"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[$rows]}}]}}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json() } }
            try {
                val model =
                    PlaylistViewModel(
                        playlistId = "PLremote",
                        database = database,
                        applicationScope = backgroundScope,
                        repositoryProvider = { YouTubeRepository(InnerTube(client)) },
                    )
                store.put("playlist", model)
                awaitPlaylistState { model.playlistTrackCount.value == 2 && !model.isLoading.value }
                assertEquals(1, model.songs.value.size)
                assertEquals(2, model.durationSongs.value.size)
                assertEquals(240L, playlistTotalDuration(model.durationSongs.value, hasMoreSongs = model.page.value!!.hasMore))
            } finally {
                store.clear()
                client.close()
                database.close()
                Dispatchers.resetMain()
                Settings.replaceSharedForTest(null)
            }
        }

    private fun checkRemoval(
        refreshFails: Boolean = false,
        removalFails: Boolean = false,
    ) = runTest {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val database =
            Room
                .inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    MusicDatabase::class.java,
                ).setDriver(AndroidSQLiteDriver())
                .build()
        val store = ViewModelStore()
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val browseCalls = AtomicInteger()
        val edits = CopyOnWriteArrayList<String>()
        val client =
            HttpClient(
                MockEngine { request ->
                    val body =
                        when (request.url.encodedPath) {
                            "/youtubei/v1/browse" -> {
                                if (browseCalls.incrementAndGet() == 1) {
                                    page(listOf("first", "second"))
                                } else {
                                    refreshStarted.complete(Unit)
                                    releaseRefresh.await()
                                    if (refreshFails) {
                                        return@MockEngine respond("{}", HttpStatusCode.ServiceUnavailable)
                                    }
                                    page(listOf("second"))
                                }
                            }

                            "/youtubei/v1/browse/edit_playlist" -> {
                                edits += (request.body as TextContent).text
                                if (removalFails) {
                                    """{"status":"STATUS_FAILED"}"""
                                } else {
                                    """{"status":"STATUS_SUCCEEDED"}"""
                                }
                            }

                            else -> {
                                error("Unexpected request: ${request.url.encodedPath}")
                            }
                        }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ) {
                install(ContentNegotiation) { json() }
            }
        try {
            val repository =
                YouTubeRepository(
                    InnerTube(client, retryDelay = {}).apply {
                        cookie = "SAPISID=session"
                        visitorData = "visitor-data"
                        dataSyncId = "channel-sync-id"
                        useLoginForBrowse = true
                    },
                )
            val model =
                PlaylistViewModel(
                    playlistId = "PLremote",
                    database = database,
                    applicationScope = backgroundScope,
                    repositoryProvider = { repository },
                )
            store.put("playlist", model)
            awaitPlaylistState { model.songs.value.size == 2 }
            assertTrue(model.page.value?.hasMore == true)
            assertNull(database.dao().findPlaylistEntity("PLremote"))
            val removed = model.songs.value.first()
            model.removeSong(removed)
            awaitPlaylistState { model.operationState.value.message != null }
            assertTrue(
                model.operationState.value.inProgressSongIds
                    .isEmpty(),
            )
            assertEquals(1, edits.size)
            assertTrue(edits.single().contains("first"))
            assertFalse(edits.single().contains("second"))

            if (removalFails) {
                assertEquals(listOf("first", "second"), model.songs.value.map { it.playlistSetVideoId })
                assertEquals(1, browseCalls.get())
                model.removeSong(removed)
                awaitPlaylistState {
                    model.operationState.value.inProgressSongIds
                        .isEmpty() && edits.size == 2
                }
            } else {
                refreshStarted.await()
                assertEquals(listOf("second"), model.songs.value.map { it.playlistSetVideoId })
                assertEquals(model.songs.value, model.presentation.value.songs)
                assertEquals(0, model.sourceIndices[model.songs.value.single()])
                assertEquals(1, model.playlistTrackCount.value)

                model.removeSong(removed)
                assertTrue(
                    model.operationState.value.inProgressSongIds
                        .isNotEmpty(),
                )
                releaseRefresh.complete(Unit)
                awaitPlaylistState {
                    model.operationState.value.inProgressSongIds
                        .isEmpty()
                }
                assertEquals(1, edits.size)
                assertEquals(listOf("second"), model.songs.value.map { it.playlistSetVideoId })
                assertEquals(model.songs.value, model.presentation.value.songs)
            }
        } finally {
            releaseRefresh.complete(Unit)
            store.clear()
            client.close()
            database.close()
            Dispatchers.resetMain()
            Settings.replaceSharedForTest(null)
        }
    }

    private fun page(occurrences: List<String>): String {
        val rows =
            occurrences.joinToString(",") { occurrence ->
                """{"musicResponsiveListItemRenderer":{
                    "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}}],
                    "playlistItemData":{"videoId":"song","playlistSetVideoId":"$occurrence"}
                }}"""
            }
        return """{"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{
            "contents":[$rows],"continuations":[{"nextContinuationData":{"continuation":"next-page"}}]
        }}]}}}"""
    }
}
