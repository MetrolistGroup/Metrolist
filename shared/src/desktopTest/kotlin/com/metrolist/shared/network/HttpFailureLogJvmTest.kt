package com.metrolist.shared.network

import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpFailureLogJvmTest {
    @AfterTest
    fun tearDown() {
        PlaybackEventLog.clear()
    }

    @Test
    fun doesNotRelogIoExceptionSubtypesAlreadyHandledByTransport() =
        runBlocking {
            PlaybackEventLog.clear()
            val failure = EOFException("transport closed")
            val client =
                HttpClient(
                    MockEngine {
                        PlaybackEventLog.error(
                            PlaybackEventKind.NETWORK,
                            "HttpClient",
                            "HTTP call failed for example.test",
                            failure,
                        )
                        throw failure
                    },
                ) {
                    install(HttpFailureLog) { transportHandledByEngine = true }
                }
            try {
                runCatching { client.get("https://example.test/resource") }

                assertEquals(1, PlaybackEventLog.events.value.size)
            } finally {
                client.close()
            }
        }

    @Test
    fun canceledCallsAreLoggedOnlyWhenTheyFailedWithATimeout() {
        assertEquals(true, shouldLogHttpCallFailure(callWasCanceled = true, error = InterruptedIOException("timeout")))
        assertEquals(false, shouldLogHttpCallFailure(callWasCanceled = true, error = InterruptedIOException("interrupted")))
        assertEquals(false, shouldLogHttpCallFailure(callWasCanceled = true, error = IOException("Canceled")))
        assertEquals(true, shouldLogHttpCallFailure(callWasCanceled = false, error = IOException("connection reset")))
    }
}
