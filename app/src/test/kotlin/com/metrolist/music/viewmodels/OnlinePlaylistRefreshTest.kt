package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.innertube.InnerTube
import com.metrolist.innertube.YouTube
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OnlinePlaylistRefreshTest {
    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun `retry cancels an older request without displaying its failure`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        val client = HttpClient(MockEngine {
            if (requests.incrementAndGet() == 1) {
                started.complete(Unit)
                release.await()
            }
            respond("""{"responseContext":{}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                })
            }
        }
        val facade = YouTube::class.java.getDeclaredField("innerTube").apply { isAccessible = true }.get(null) as InnerTube
        val clientField = InnerTube::class.java.getDeclaredField("httpClient").apply { isAccessible = true }
        val transportField = InnerTube::class.java.getDeclaredField("innerTubeX").apply { isAccessible = true }
        val originalClient = clientField.get(facade)
        val originalTransport = transportField.get(facade)
        val mockFacade = InnerTube(client).apply { cookie = "SAPISID=test-session" }
        var viewModel: OnlinePlaylistViewModel? = null
        try {
            clientField.set(facade, clientField.get(mockFacade))
            transportField.set(facade, transportField.get(mockFacade))
            val model = OnlinePlaylistViewModel(context, SavedStateHandle(mapOf("playlistId" to "playlist")), MusicDatabase(room))
            viewModel = model
            withTimeout(5_000) { started.await() }
            val oldLoad = OnlinePlaylistViewModel::class.java.getDeclaredField("initialLoadJob")
                .apply { isAccessible = true }.get(model) as Job

            model.retry()
            withTimeout(5_000) { model.isLoading.first { !it } }

            assertTrue(oldLoad.isCancelled)
            assertEquals(2, requests.get())
            assertEquals("playlist", model.playlist.value?.id)
            assertNull(model.error.value)
        } finally {
            release.complete(Unit)
            viewModel?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            clientField.set(facade, originalClient)
            transportField.set(facade, originalTransport)
            client.close()
            room.close()
        }
    }
}
