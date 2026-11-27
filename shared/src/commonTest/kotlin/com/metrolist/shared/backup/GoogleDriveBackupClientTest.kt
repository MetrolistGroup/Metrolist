package com.metrolist.shared.backup

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleDriveBackupClientTest {
    @Test
    fun `uploads backup to app folder and prunes beyond retention limit`() =
        runTest {
            val deletedFiles = mutableListOf<String>()
            val backupList = (1..11).joinToString(",") { """{"id":"backup-$it"}""" }
            val client =
                HttpClient(
                    MockEngine { request ->
                        when {
                            request.url.encodedPath == "/v1/auth/access-token" -> {
                                jsonResponse("""{"accessToken":"drive-token","expiresIn":3600}""")
                            }

                            request.url.encodedPath == "/drive/v3/files" &&
                                request.url.parameters["q"]?.contains("metrolistBackupFolder") == true -> {
                                jsonResponse("""{"files":[]}""")
                            }

                            request.url.encodedPath == "/drive/v3/files" && request.url.parameters["q"]?.contains("in parents") == true -> {
                                if (request.url.parameters["pageToken"] == null) {
                                    jsonResponse("""{"nextPageToken":"older-files","files":[$backupList]}""")
                                } else {
                                    assertEquals("older-files", request.url.parameters["pageToken"])
                                    jsonResponse("""{"files":[{"id":"older-backup"}]}""")
                                }
                            }

                            request.url.encodedPath == "/drive/v3/files" && request.method.value == "POST" -> {
                                jsonResponse("""{"id":"folder-id"}""")
                            }

                            request.url.encodedPath == "/upload/drive/v3/files" && request.method.value == "POST" -> {
                                assertEquals("resumable", request.url.parameters["uploadType"])
                                respond(
                                    "{}",
                                    HttpStatusCode.OK,
                                    headersOf(
                                        HttpHeaders.Location,
                                        "https://www.googleapis.com/upload/drive/v3/files?upload_id=upload-session",
                                    ),
                                )
                            }

                            request.url.encodedPath == "/upload/drive/v3/files" && request.method.value == "PUT" -> {
                                assertEquals("bytes 0-2/3", request.headers[HttpHeaders.ContentRange])
                                jsonResponse("""{"id":"new-backup","name":"backup.zip"}""")
                            }

                            request.url.encodedPath.startsWith("/drive/v3/files/") && request.method.value == "DELETE" -> {
                                deletedFiles += request.url.encodedPath.substringAfterLast('/')
                                respond("", HttpStatusCode.NoContent)
                            }

                            else -> {
                                error("Unexpected Drive request: ${request.method.value} ${request.url.encodedPath}")
                            }
                        }
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }

            val backup = byteArrayOf(1, 2, 3)
            val result =
                GoogleDriveBackupClient("https://broker.example", client)
                    .uploadBackup("opaque-session", "backup.zip", backup.size.toLong()) {
                        Buffer().apply { write(backup, 0, backup.size) }
                    }

            assertEquals("new-backup", result.id)
            assertEquals(listOf("backup-11", "older-backup"), deletedFiles)
            client.close()
        }

    @Test
    fun `resumes a resumable upload after a retryable server error`() =
        runTest {
            val contentRanges = mutableListOf<String?>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        when {
                            request.url.encodedPath == "/v1/auth/access-token" -> {
                                jsonResponse("""{"accessToken":"drive-token","expiresIn":3600}""")
                            }

                            request.url.encodedPath == "/drive/v3/files" &&
                                request.url.parameters["q"]?.contains("metrolistBackupFolder") == true -> {
                                jsonResponse("""{"files":[]}""")
                            }

                            request.url.encodedPath == "/drive/v3/files" && request.url.parameters["q"]?.contains("in parents") == true -> {
                                jsonResponse("""{"files":[]}""")
                            }

                            request.url.encodedPath == "/drive/v3/files" && request.method.value == "POST" -> {
                                jsonResponse("""{"id":"folder-id"}""")
                            }

                            request.url.encodedPath == "/upload/drive/v3/files" && request.method.value == "POST" -> {
                                respond(
                                    "{}",
                                    HttpStatusCode.OK,
                                    headersOf(
                                        HttpHeaders.Location,
                                        "https://www.googleapis.com/upload/drive/v3/files?upload_id=upload-session",
                                    ),
                                )
                            }

                            request.url.encodedPath == "/upload/drive/v3/files" && request.method.value == "PUT" -> {
                                val range = request.headers[HttpHeaders.ContentRange]
                                contentRanges += range
                                when (range) {
                                    "bytes 0-2/3" -> {
                                        respond("", HttpStatusCode.ServiceUnavailable)
                                    }

                                    "bytes */3" -> {
                                        respond(
                                            "",
                                            HttpStatusCode.PermanentRedirect,
                                            headersOf(HttpHeaders.Range, "bytes=0-0"),
                                        )
                                    }

                                    "bytes 1-2/3" -> {
                                        jsonResponse("""{"id":"resumed-backup","name":"backup.zip"}""")
                                    }

                                    else -> {
                                        error("Unexpected upload range: $range")
                                    }
                                }
                            }

                            else -> {
                                error("Unexpected Drive request: ${request.method.value} ${request.url.encodedPath}")
                            }
                        }
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }

            val backup = byteArrayOf(1, 2, 3)
            val result =
                GoogleDriveBackupClient("https://broker.example", client)
                    .uploadBackup("opaque-session", "backup.zip", backup.size.toLong()) {
                        Buffer().apply { write(backup, 0, backup.size) }
                    }

            assertEquals("resumed-backup", result.id)
            assertEquals(listOf<String?>("bytes 0-2/3", "bytes */3", "bytes 1-2/3"), contentRanges)
            client.close()
        }

    @Test
    fun `resumes a resumable upload after Drive throttling`() =
        runTest {
            val contentRanges = mutableListOf<String?>()
            var statusProbes = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        when {
                            request.url.encodedPath == "/v1/auth/access-token" -> {
                                jsonResponse("""{"accessToken":"drive-token","expiresIn":3600}""")
                            }

                            request.url.encodedPath == "/drive/v3/files" &&
                                request.url.parameters["q"]?.contains("metrolistBackupFolder") == true -> {
                                jsonResponse("""{"files":[]}""")
                            }

                            request.url.encodedPath == "/drive/v3/files" && request.url.parameters["q"]?.contains("in parents") == true -> {
                                jsonResponse("""{"files":[]}""")
                            }

                            request.url.encodedPath == "/drive/v3/files" && request.method.value == "POST" -> {
                                jsonResponse("""{"id":"folder-id"}""")
                            }

                            request.url.encodedPath == "/upload/drive/v3/files" && request.method.value == "POST" -> {
                                respond(
                                    "{}",
                                    HttpStatusCode.OK,
                                    headersOf(
                                        HttpHeaders.Location,
                                        "https://www.googleapis.com/upload/drive/v3/files?upload_id=upload-session",
                                    ),
                                )
                            }

                            request.url.encodedPath == "/upload/drive/v3/files" && request.method.value == "PUT" -> {
                                val range = request.headers[HttpHeaders.ContentRange]
                                contentRanges += range
                                when (range) {
                                    "bytes 0-2/3" -> {
                                        respond(
                                            """{"error":{"errors":[{"reason":"rateLimitExceeded"}]}}""",
                                            HttpStatusCode.Forbidden,
                                            headersOf(HttpHeaders.RetryAfter, "1"),
                                        )
                                    }

                                    "bytes */3" -> {
                                        statusProbes += 1
                                        if (statusProbes == 1) {
                                            respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "1"))
                                        } else {
                                            respond(
                                                "",
                                                HttpStatusCode.PermanentRedirect,
                                                headersOf(HttpHeaders.Range, "bytes=0-0"),
                                            )
                                        }
                                    }

                                    "bytes 1-2/3" -> {
                                        jsonResponse("""{"id":"throttled-backup","name":"backup.zip"}""")
                                    }

                                    else -> {
                                        error("Unexpected upload range: $range")
                                    }
                                }
                            }

                            else -> {
                                error("Unexpected Drive request: ${request.method.value} ${request.url.encodedPath}")
                            }
                        }
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }

            val backup = byteArrayOf(1, 2, 3)
            val result =
                GoogleDriveBackupClient("https://broker.example", client)
                    .uploadBackup("opaque-session", "backup.zip", backup.size.toLong()) {
                        Buffer().apply { write(backup, 0, backup.size) }
                    }

            assertEquals("throttled-backup", result.id)
            assertEquals(
                listOf<String?>("bytes 0-2/3", "bytes */3", "bytes */3", "bytes 1-2/3"),
                contentRanges,
            )
            client.close()
        }

    private fun MockRequestHandleScope.jsonResponse(body: String) =
        respond(
            body,
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
}
