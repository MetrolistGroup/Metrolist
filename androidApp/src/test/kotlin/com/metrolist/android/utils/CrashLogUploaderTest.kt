package com.metrolist.android.utils

import com.metrolist.android.ui.screens.crashLogWithPaste
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogUploaderTest {
    @Test
    fun sharedReportRedactsCredentialsWithAndWithoutPaste() {
        val log = "java.lang.IllegalStateException: Cookie: SID=private; other=secret\nhttps://example.com/media?signature=private"
        for (paste in listOf(null, CrashPaste("id", "https://paste.example/id"))) {
            val report = crashLogWithPaste(log, paste, "Paste ID: id", "Paste URL: https://paste.example/id")
            assertTrue(!report.contains("private"))
            assertTrue(!report.contains("secret"))
            assertTrue(report.contains("https://example.com/media?"))
        }
    }

    @Test
    fun uploadsCrashLogAndBuildsPasteLink() =
        runBlocking {
            var requestUrl: String? = null
            var requestBody: String? = null
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        requestUrl = chain.request().url.toString()
                        requestBody =
                            Buffer().use { buffer ->
                                chain.request().body?.writeTo(buffer)
                                buffer.readUtf8()
                            }
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(201)
                            .message("Created")
                            .body(
                                """{"id":"abc123","ignored":"value"}"""
                                    .toResponseBody("application/json".toMediaType()),
                            ).build()
                    }.build()

            try {
                val paste =
                    CrashLogUploader(
                        client = client,
                        baseUrl = "https://paste.example/".toHttpUrl(),
                    ).upload("Server: wss://user:private@host.example/ws?access_token=hidden")

                assertEquals("https://paste.example/api/v2/pastes", requestUrl)
                assertEquals(
                    "Server: wss://host.example/ws?<redacted>",
                    Json
                        .parseToJsonElement(checkNotNull(requestBody))
                        .jsonObject["content"]
                        ?.jsonPrimitive
                        ?.content,
                )
                assertEquals(CrashPaste("abc123", "https://paste.example/abc123"), paste)
                assertTrue(
                    crashLogWithPaste(
                        "crash contents",
                        paste,
                        "Paste ID: abc123",
                        "Paste URL: https://paste.example/abc123",
                    ).startsWith("Paste ID: abc123\n"),
                )
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
}
