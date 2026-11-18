package com.metrolist.desktop.storage

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CompleteDownloadResponseTest {
    @Test
    fun downloadsDoNotPromotePartialBodiesAsCompleteFiles() =
        verifyCompleteResponses(DownloadManager.getDefaultDownloadDir()) { url, name ->
            DownloadManager.downloadUrlSingle(url, name, false, emptyMap(), null)?.let(::File)
        }

    @Test
    fun cacheDoesNotPromotePartialBodiesAsCompleteFiles() =
        verifyCompleteResponses(SongCacheManager.cacheDir) { url, name ->
            SongCacheManager.fetch(url, name, false)
        }

    private fun verifyCompleteResponses(
        directory: File,
        download: suspend (String, String) -> File?,
    ) = runBlocking {
        val prefix = "complete-response-${UUID.randomUUID()}"
        val body = ByteArray(2048) { 7 }
        val cases =
            listOf(
                Triple("prefix", "bytes 0-2047/8192", false),
                Triple("suffix", "bytes 6144-8191/8192", false),
                Triple("unknown", "bytes 0-2047/*", false),
                Triple("missing", null, false),
                Triple("short", "bytes 0-8191/8192", false),
                Triple("complete", "bytes 0-2047/2048", true),
                Triple("chunked", "bytes 0-2047/2048", true),
                Triple("ordinary", null, true),
            )
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        for ((name, range, _) in cases) {
            server.createContext("/$name") { exchange ->
                exchange.use {
                    it.responseHeaders.add("Content-Type", "audio/mp4")
                    range?.let { range -> it.responseHeaders.add("Content-Range", range) }
                    it.sendResponseHeaders(if (name == "ordinary") 200 else 206, if (name == "chunked") 0 else body.size.toLong())
                    it.responseBody.write(body)
                }
            }
        }
        server.start()
        try {
            for ((name, _, complete) in cases) {
                val existing = File(directory, "$prefix-$name.m4a")
                val original = ByteArray(1024) { 9 }
                if (!complete) existing.writeBytes(original)
                val file = download("http://127.0.0.1:${server.address.port}/$name", "$prefix-$name")
                if (complete) {
                    assertEquals(body.size.toLong(), assertNotNull(file, name).length())
                } else {
                    assertNull(file, "Incomplete range $name must not be promoted")
                    assertContentEquals(original, existing.readBytes(), "Incomplete range $name must preserve the existing file")
                }
            }
        } finally {
            server.stop(0)
            directory
                .listFiles()
                .orEmpty()
                .filter { it.name.startsWith(prefix) }
                .forEach { it.delete() }
        }
    }
}
