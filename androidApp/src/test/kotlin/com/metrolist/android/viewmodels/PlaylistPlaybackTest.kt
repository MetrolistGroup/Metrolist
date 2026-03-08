package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModelStore
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.PlaylistPresentationOptions
import com.metrolist.shared.model.indexOfPlaylistOccurrence
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.setResourceReaderAndroidContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalResourceApi::class)
class PlaylistPlaybackTest {
    @Test
    fun originalOrderPlaybackSnapshotsTheWholeLoadedQueueWithoutWaitingForSearchPagination() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            setResourceReaderAndroidContext(RuntimeEnvironment.getApplication())
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val database =
                Room
                    .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MusicDatabase::class.java)
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            val store = ViewModelStore()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        if (requests > 1) {
                            started.complete(Unit)
                            release.await()
                        }
                        val rows =
                            (0..1).joinToString(",") { index ->
                                """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song $index"}]}}}],"playlistItemData":{"videoId":"duplicate","playlistSetVideoId":"$index"}}}"""
                            }
                        respond(
                            if (requests == 1) {
                                """{"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[$rows],"continuations":[{"nextContinuationData":{"continuation":"next"}}]}}]}}}"""
                            } else {
                                "{}"
                            },
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json() } }
            try {
                val repository = YouTubeRepository(InnerTube(client))
                val model = PlaylistViewModel("PLremote", database, backgroundScope, repositoryProvider = { repository })
                store.put("playlist", model)
                awaitPlaylistState { model.songs.value.size == 2 }
                val selected = model.songs.value[1]
                model.loadForSearch()
                started.await()
                var played = false
                model.withPlaybackSongs(PlaylistPresentationOptions()) { songs, page ->
                    assertEquals(2, songs.size)
                    assertEquals(1, songs.indexOfPlaylistOccurrence(selected))
                    assertTrue(page?.hasMore == true)
                    assertEquals(songs, page?.songs)
                    played = true
                }
                runCurrent()
                assertTrue(played)
                assertEquals(2, requests)
            } finally {
                release.complete(Unit)
                store.clear()
                client.close()
                database.close()
                Dispatchers.resetMain()
                Settings.replaceSharedForTest(null)
            }
        }
}
