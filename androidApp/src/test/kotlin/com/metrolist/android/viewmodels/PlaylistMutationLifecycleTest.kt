package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModelStore
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PlaylistEntity
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.setResourceReaderAndroidContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalResourceApi::class)
class PlaylistMutationLifecycleTest {
    @Test
    fun renameFinishesRemoteAndLocalWritesAfterClearingWithoutCallingUi() = checkRename(clearViewModel = true)

    @Test
    fun renameReportsItsResultWhileTheViewModelIsAlive() = checkRename(clearViewModel = false)

    private fun checkRename(clearViewModel: Boolean) =
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
            val requestStarted = CompletableDeferred<Unit>()
            val releaseRequest = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath == "/youtubei/v1/browse") {
                            return@MockEngine respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                        assertEquals("/youtubei/v1/browse/edit_playlist", request.url.encodedPath)
                        requestStarted.complete(Unit)
                        releaseRequest.await()
                        respond(
                            """{"status":"STATUS_SUCCEEDED"}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json() } }
            try {
                val playlist = PlaylistEntity(id = "LPtest", name = "Original", isLocal = true)
                database.dao().insert(playlist)
                val repository = YouTubeRepository(InnerTube(client).apply { cookie = "SAPISID=session" })
                val model =
                    PlaylistViewModel(
                        playlistId = playlist.id,
                        database = database,
                        applicationScope = backgroundScope,
                        repositoryProvider = { repository },
                    )
                store.put("playlist", model)
                awaitPlaylistState { model.playlistInfo.value != null }
                database.dao().update(playlist.copy(browseId = "PLremote", isLocal = false))
                var callbackCalled = false
                model.renamePlaylist("Renamed") { callbackCalled = true }
                requestStarted.await()
                if (clearViewModel) store.clear()
                releaseRequest.complete(Unit)

                val saved = database.dao().playlist(playlist.id).first { it?.title == "Renamed" }
                awaitPlaylistState { model.playlistInfo.value?.title == "Renamed" }
                runCurrent()
                assertEquals("Renamed", saved?.title)
                assertEquals(!clearViewModel, callbackCalled)
            } finally {
                releaseRequest.complete(Unit)
                store.clear()
                client.close()
                database.close()
                Dispatchers.resetMain()
                Settings.replaceSharedForTest(null)
            }
        }
}
