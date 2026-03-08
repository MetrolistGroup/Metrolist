package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UploadValidationTest {
    @Test
    fun uploadStreamsContentToTheSelectedAccountBeforeReportingSuccess() =
        runBlocking {
            for (fileName in listOf(
                "song.mp3",
                "song.m4a",
                "song.wma",
                "song.flac",
                "song.ogg",
                "song.opus",
                "SONG.OPUS",
                "mix.song.OpUs",
            )) {
                val content = byteArrayOf(1, 2, 3, 4)
                var requests = 0
                var contentOpened = false
                val client =
                    HttpClient(
                        MockEngine { request ->
                            requests++
                            assertEquals("2", request.headers["X-Goog-AuthUser"])
                            assertTrue(request.headers[HttpHeaders.Authorization].orEmpty().startsWith("SAPISIDHASH "))
                            if (requests == 1) {
                                assertEquals("2", request.url.parameters["authuser"])
                                assertEquals("start", request.headers["X-Goog-Upload-Command"])
                                assertEquals(fileName, parseQueryString(request.body.toByteArray().decodeToString())["filename"])
                                assertEquals(content.size.toString(), request.headers["X-Goog-Upload-Header-Content-Length"])
                                assertFalse(contentOpened)
                                respond(
                                    "",
                                    headers = headersOf("X-Goog-Upload-URL", "https://upload.youtube.com/?upload_id=session"),
                                )
                            } else {
                                assertEquals("session", request.url.parameters["upload_id"])
                                assertEquals("upload, finalize", request.headers["X-Goog-Upload-Command"])
                                val body = request.body as OutgoingContent.ReadChannelContent
                                assertEquals(content.size.toLong(), body.contentLength)
                                assertContentEquals(content, body.readFrom().toByteArray())
                                respond("", headers = headersOf("X-Goog-Upload-Status", "final"))
                            }
                        },
                    )
                val tube =
                    InnerTube(client, retryDelay = {}).apply {
                        cookie = "SAPISID=session"
                        authUser = "2"
                    }
                try {
                    val result =
                        YouTubeRepository(tube).uploadSong(fileName, content.size.toLong()) {
                            contentOpened = true
                            ByteReadChannel(content)
                        }
                    assertTrue(result.success)
                    assertTrue(contentOpened)
                    assertEquals(2, requests)
                } finally {
                    tube.close()
                    client.close()
                }
            }
        }

    @Test
    fun rejectedUploadsNeverReportSuccessOrRetryTheTransfer() =
        runBlocking {
            for (rejectedRequest in 1..2) {
                var requests = 0
                val client =
                    HttpClient(
                        MockEngine {
                            requests++
                            if (requests == rejectedRequest) {
                                respond("", HttpStatusCode.Forbidden)
                            } else {
                                respond(
                                    "",
                                    headers = headersOf("X-Goog-Upload-URL", "https://upload.youtube.com/?upload_id=session"),
                                )
                            }
                        },
                    )
                val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=session" }
                try {
                    val result = YouTubeRepository(tube).uploadSong("song.mp3", 1) { ByteReadChannel(byteArrayOf(1)) }
                    assertFalse(result.success)
                    assertEquals(rejectedRequest, requests)
                    if (rejectedRequest == 2) {
                        assertEquals(UploadFailure.HTTP, result.failure)
                        assertEquals(HttpStatusCode.Forbidden.value, result.httpStatus)
                    }
                } finally {
                    tube.close()
                    client.close()
                }
            }
        }

    @Test
    fun duplicateUploadConflictIsReportedAsAlreadyUploaded() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        if (requests == 1) {
                            respond("", headers = headersOf("X-Goog-Upload-URL", "https://upload.youtube.com/?upload_id=session"))
                        } else {
                            respond("", HttpStatusCode.Conflict, headersOf("X-Goog-Upload-Status", "final"))
                        }
                    },
                )
            val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=session" }
            try {
                val result = YouTubeRepository(tube).uploadSong("song.mp3", 1) { ByteReadChannel(byteArrayOf(1)) }
                assertFalse(result.success)
                assertEquals(UploadFailure.ALREADY_UPLOADED, result.failure)
                assertEquals(2, requests)
            } finally {
                tube.close()
                client.close()
            }
        }

    @Test
    fun deletingAnUploadSendsItsEntityIdAndRequiresSignIn() =
        runBlocking {
            val bodies = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertEquals("/youtubei/v1/music/delete_privately_owned_entity", request.url.encodedPath)
                        bodies += request.body.toByteArray().decodeToString()
                        respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json() } }
            val tube = InnerTube(client, retryDelay = {})
            val repository = YouTubeRepository(tube)
            try {
                assertFalse(repository.deleteUploadedSong("t_po_entity"))
                assertTrue(bodies.isEmpty())
                tube.cookie = "SAPISID=session"
                assertTrue(repository.deleteUploadedSong("t_po_entity"))
                assertTrue(bodies.single().contains("\"entityId\":\"t_po_entity\""))
            } finally {
                tube.close()
                client.close()
            }
        }

    @Test
    fun uploadValidationReturnsStructuredReasonsWithoutSendingContent() =
        runBlocking {
            val client = HttpClient(MockEngine { error("Invalid uploads must not use network") })
            val tube = InnerTube(client, retryDelay = {})
            val repository = YouTubeRepository(tube)
            try {
                suspend fun failure(
                    name: String,
                    size: Long,
                ) = repository.uploadSong(name, size) { error("Invalid uploads must not open content") }.failure
                assertEquals(UploadFailure.SIGN_IN_REQUIRED, failure("song.mp3", 10))
                tube.cookie = "SAPISID=session"
                for (fileName in listOf("song.wav", "SONG.WAV", "song.opus.wav", "song.txt", "song")) {
                    assertEquals(UploadFailure.UNSUPPORTED_TYPE, failure(fileName, 10), fileName)
                }
                assertEquals(UploadFailure.EMPTY_FILE, failure("song.mp3", 0))
                assertEquals(UploadFailure.TOO_LARGE, failure("song.mp3", 300L * 1024 * 1024))
                assertEquals(UploadFailure.EMPTY_FILE, failure("song.opus", 0))
                assertEquals(UploadFailure.TOO_LARGE, failure("song.opus", 300L * 1024 * 1024))
            } finally {
                tube.close()
                client.close()
            }
        }
}
