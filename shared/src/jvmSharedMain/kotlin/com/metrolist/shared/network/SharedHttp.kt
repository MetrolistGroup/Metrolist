package com.metrolist.shared.network

import com.metrolist.shared.youtube.playback.ExtractionHttpCooldown
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

actual val sharedHttpTransportHandlesFailures = true

actual fun Throwable.isHttpTransportException(): Boolean = this is IOException

val sharedOkHttpClient: OkHttpClient by lazy {
    OkHttpClient
        .Builder()
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .eventListenerFactory {
            object : EventListener() {
                override fun callFailed(
                    call: Call,
                    ioe: IOException,
                ) {
                    if (!shouldLogHttpCallFailure(call.isCanceled(), ioe)) return
                    PlaybackEventLog.error(
                        PlaybackEventKind.NETWORK,
                        "HttpClient",
                        "HTTP call failed for ${call.request().url.host}",
                        ioe,
                    )
                }
            }
        }.build()
}

internal fun shouldLogHttpCallFailure(
    callWasCanceled: Boolean,
    error: IOException,
): Boolean =
    !callWasCanceled ||
        error is SocketTimeoutException ||
        (error is InterruptedIOException && error.message == "timeout")

internal expect fun apiOkHttpClient(): OkHttpClient

/** Share the Ktor engine; closing a client-owned engine would shut down the shared OkHttp dispatcher. */
val sharedHttpEngine: HttpClientEngine by lazy {
    OkHttp.create { preconfigured = apiOkHttpClient() }
}

actual fun createHttpClient(): HttpClient =
    HttpClient(sharedHttpEngine) {
        install(HttpFailureLog) { transportHandledByEngine = true }
        install(ExtractionHttpCooldown)
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                },
            )
        }
        install(WebSockets)
        install(ContentEncoding) {
            gzip(0.9F)
            deflate(0.8F)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
        expectSuccess = false
    }
