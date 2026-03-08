package com.metrolist.shared.network

import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

expect val sharedHttpTransportHandlesFailures: Boolean

data class HttpFailureLogConfig(
    var transportHandledByEngine: Boolean = false,
    var expectedStatusCodes: Set<Int> = emptySet(),
)

expect fun Throwable.isHttpTransportException(): Boolean

val ExpectedHttpFailureStatusCodes = AttributeKey<Set<Int>>("ExpectedHttpFailureStatusCodes")

/** Requests made in this context fail too often for reasons outside the app (lyrics lookups) to be worth logging. */
object QuietHttpFailures : AbstractCoroutineContextElement(QuietHttpFailuresKey)

private object QuietHttpFailuresKey : CoroutineContext.Key<QuietHttpFailures>

private suspend fun isQuietHttpCall(): Boolean = currentCoroutineContext()[QuietHttpFailuresKey] != null

object HttpFailureLog : HttpClientPlugin<HttpFailureLogConfig, HttpFailureLogConfig> {
    override val key = AttributeKey<HttpFailureLogConfig>("HttpFailureLog")

    override fun prepare(block: HttpFailureLogConfig.() -> Unit) = HttpFailureLogConfig().apply(block)

    override fun install(
        plugin: HttpFailureLogConfig,
        scope: HttpClient,
    ) {
        scope.plugin(HttpSend).intercept { request ->
            if (isQuietHttpCall()) return@intercept execute(request)
            try {
                val call = execute(request)
                val status = call.response.status
                val expectedStatusCodes =
                    plugin.expectedStatusCodes +
                        request.attributes.getOrNull(ExpectedHttpFailureStatusCodes).orEmpty()
                if (status.value >= 400 && status.value !in expectedStatusCodes) {
                    PlaybackEventLog.error(
                        PlaybackEventKind.NETWORK,
                        "HttpClient",
                        "HTTP request failed for ${request.url.host} (HTTP ${status.value})",
                        IllegalStateException("HTTP ${status.value}"),
                    )
                }
                call
            } catch (error: CancellationException) {
                if (error.hasHttpRequestTimeoutInCauseChain()) {
                    PlaybackEventLog.error(
                        PlaybackEventKind.NETWORK,
                        "HttpClient",
                        "HTTP request timed out for ${request.url.host}",
                        error,
                    )
                }
                throw error
            } catch (error: Exception) {
                if (!plugin.transportHandledByEngine || !error.hasHttpTransportFailureInCauseChain()) {
                    PlaybackEventLog.error(
                        PlaybackEventKind.NETWORK,
                        "HttpClient",
                        "HTTP call failed for ${request.url.host}",
                        error,
                    )
                }
                throw error
            }
        }

        scope.responsePipeline.intercept(HttpResponsePipeline.Receive) {
            if (isQuietHttpCall()) {
                proceed()
                return@intercept
            }
            try {
                proceed()
            } catch (error: CancellationException) {
                if (error.hasHttpRequestTimeoutInCauseChain()) {
                    PlaybackEventLog.error(
                        PlaybackEventKind.NETWORK,
                        "HttpClient",
                        "HTTP response timed out for ${context.request.url.host}",
                        error,
                    )
                }
                throw error
            } catch (error: Throwable) {
                if (!plugin.transportHandledByEngine || !error.hasHttpTransportFailureInCauseChain()) {
                    PlaybackEventLog.error(
                        PlaybackEventKind.NETWORK,
                        "HttpClient",
                        "HTTP response handling failed for ${context.request.url.host}",
                        error,
                    )
                }
                throw error
            }
        }
    }
}

fun Throwable.hasHttpTransportFailureInCauseChain(): Boolean =
    generateSequence(this) { it.cause?.takeUnless { cause -> cause === it } }
        .any(Throwable::isHttpTransportException)

fun Throwable.hasHttpRequestTimeoutInCauseChain(): Boolean =
    generateSequence(this) { it.cause?.takeUnless { cause -> cause === it } }
        .any { it is HttpRequestTimeoutException }
