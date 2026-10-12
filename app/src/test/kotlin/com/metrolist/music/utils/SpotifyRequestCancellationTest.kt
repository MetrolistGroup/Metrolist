package com.metrolist.music.utils

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class SpotifyRequestCancellationTest {
    @Test fun cancelsAnInFlightRequestAndClosesItsSocket() = runBlocking {
        val client = OkHttpClient()
        val connected = CompletableDeferred<Unit>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val serverTask = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) { }
                    connected.complete(Unit)
                    reader.read() == -1
                }
            }
            try {
                val job = launch(Dispatchers.IO) {
                    client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/pending").build()).awaitSpotifyJson(0)
                }
                withTimeout(5000) { connected.await() }
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
                assertTrue(withTimeout(5000) { serverTask.await() })
            } finally {
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }
}
