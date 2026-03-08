package com.metrolist.android.recognition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ShazamRecognitionClientTest {
    @Test
    fun parsesVideoLinksWithExtraParametersAndFragments() {
        for (url in listOf(
            "https://www.youtube.com/watch?v=ABCDEFGHIJK&feature=share#fragment",
            "https://youtu.be/ABCDEFGHIJK?feature=share",
        )) {
            val response = """{"track":{"key":"track","title":"Song","hub":{"options":[{"type":"video","actions":[{"uri":"$url"}]}]}}}"""
            assertEquals("ABCDEFGHIJK", ShazamRecognitionClient.parseResult(response)?.youtubeVideoId)
        }
    }

    @Test
    fun cancellationInterruptsARequestWaitingForTheServer() =
        runBlocking {
            ServerSocket(0).use { server ->
                val accepted = async(Dispatchers.IO) { server.accept() }
                val call = OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
                val request = launch(Dispatchers.IO) { call.readCancellable() }
                withTimeout(5.seconds) {
                    accepted.await().use {
                        request.cancelAndJoin()
                    }
                }
                assertTrue(call.isCanceled())
            }
        }
}
