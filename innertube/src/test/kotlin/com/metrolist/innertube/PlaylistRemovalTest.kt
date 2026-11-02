package com.metrolist.innertube

import com.metrolist.innertube.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistRemovalTest {
    private fun remove(response: String): Result<Unit> = runBlocking {
        val engine = MockEngine {
            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        HttpClient(engine) {
            install(ContentNegotiation) { json() }
        }.use { client ->
            val innerTube = InnerTube(client).apply { cookie = "SAPISID=session" }
            runCatching {
                innerTube.removeFromPlaylist(YouTubeClient.WEB_REMIX, "playlist", "song", "occurrence")
                Unit
            }
        }
    }

    @Test
    fun confirmedRemovalSucceeds() {
        assertTrue(remove("""{"status":"STATUS_SUCCEEDED"}""").isSuccess)
    }

    @Test
    fun rejectedRemovalFailsEvenWithHttpSuccess() {
        assertTrue(remove("""{"status":"STATUS_FAILED"}""").isFailure)
    }

    @Test
    fun missingConfirmationFails() {
        assertTrue(remove("{}").isFailure)
    }
}
