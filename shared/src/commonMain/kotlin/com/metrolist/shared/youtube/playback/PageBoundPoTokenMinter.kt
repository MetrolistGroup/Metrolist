package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.bodyAsTextLimited
import com.metrolist.innertubex.extraction.potoken.PoTokenException
import com.metrolist.innertubex.extraction.potoken.parseAttestationChallengeData
import com.metrolist.innertubex.extraction.potoken.parseAttestationInterpreterUrl
import com.metrolist.innertubex.extraction.potoken.parseIntegrityTokenData
import com.metrolist.innertubex.extraction.potoken.parseWebPageAttestationContext
import com.metrolist.innertubex.extraction.potoken.requireTrustedAttestationInterpreterUrl
import com.metrolist.innertubex.extraction.potoken.stringToU8
import com.metrolist.innertubex.extraction.potoken.u8ToBase64
import com.metrolist.shared.network.HttpFailureLog
import com.metrolist.shared.network.sharedHttpTransportHandlesFailures
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

internal class PageBoundPoTokenMinter private constructor(
    private val runtime: PageBoundPoTokenJavaScriptRuntime,
    val expiresAtMs: Long,
) {
    val isExpired: Boolean
        get() = Clock.System.now().toEpochMilliseconds() >= expiresAtMs

    suspend fun generate(identifier: String): String {
        runtime.evaluate(
            """
            (function() {
                const result = { state: "running" };
                globalThis.__metrolistPoToken = result;
                obtainPoToken(${stringToU8(identifier)}).then(function(token) {
                    result.value = Array.from(token).join(",");
                    result.state = "ready";
                }).catch(function(error) {
                    result.value = String(error);
                    result.state = "error";
                });
            })();
            "started";
            """.trimIndent(),
        )
        val result = awaitJavaScriptResult(runtime, "__metrolistPoToken")
        return u8ToBase64(result)
    }

    companion object {
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val CONSENT_COOKIE = "SOCS=CAI"
        private const val EXPIRY_MARGIN_SECONDS = 600L

        suspend fun create(
            client: HttpClient,
            runtime: PageBoundPoTokenJavaScriptRuntime,
            visitorData: String,
            cookie: String?,
            videoId: String,
            userAgent: String,
        ): PageBoundPoTokenMinter {
            check(runtime.available) { "Page-bound JavaScript runtime is unavailable" }
            val directClient =
                HttpClient(client.engine) {
                    install(HttpFailureLog) { transportHandledByEngine = sharedHttpTransportHandlesFailures }
                    install(ExtractionHttpCooldown)
                    expectSuccess = false
                    followRedirects = false
                }
            try {
                val page =
                    directClient
                        .get("https://www.youtube.com/watch") {
                            parameter("v", videoId)
                            parameter("app", "desktop")
                            header(HttpHeaders.UserAgent, userAgent)
                            header(HttpHeaders.Accept, "text/html")
                            header("X-Goog-Visitor-Id", visitorData)
                            header(HttpHeaders.Cookie, listOfNotNull(cookie, CONSENT_COOKIE).joinToString("; "))
                        }.limitedBody(WATCH_PAGE_BODY_LIMIT_BYTES)
                val attestation = parseWebPageAttestationContext(page)
                val interpreterUrl = requireTrustedAttestationInterpreterUrl(parseAttestationInterpreterUrl(attestation.challenge))
                val interpreterResponse =
                    directClient
                        .get(interpreterUrl) {
                            header(HttpHeaders.UserAgent, userAgent)
                        }
                if (!interpreterResponse.status.isSuccess()) {
                    interpreterResponse.discardBody()
                    error("BotGuard interpreter request failed with HTTP ${interpreterResponse.status.value}")
                }
                val interpreter = interpreterResponse.bodyAsTextLimited(INTERPRETER_BODY_LIMIT_BYTES)
                val challengeData = parseAttestationChallengeData(attestation.challenge, interpreter)
                val eventId = Json.encodeToString(attestation.eventId)
                runtime.evaluate(
                    """
                    globalThis.__metrolistPoSnapshot = { state: "running" };
                    globalThis.yt = { config_: { EVENT_ID: $eventId } };
                    globalThis.window.yt = globalThis.yt;
                    runBotGuard($challengeData).then(function(result) {
                        globalThis.__metrolistWebPoSignalOutput = result.webPoSignalOutput;
                        globalThis.__metrolistPoSnapshot = { state: "ready", value: result.botguardResponse };
                    }).catch(function(error) {
                        globalThis.__metrolistPoSnapshot = { state: "error", value: String(error) };
                    });
                    "started";
                    """.trimIndent(),
                )
                val botguardResponse = awaitJavaScriptResult(runtime, "__metrolistPoSnapshot")
                val integrityResponse =
                    directClient.post("https://www.youtube.com/api/jnn/v1/GenerateIT") {
                        contentType(ContentType.parse("application/json+protobuf"))
                        header(HttpHeaders.Accept, "application/json")
                        header(HttpHeaders.UserAgent, userAgent)
                        header("x-goog-api-key", GOOGLE_API_KEY)
                        header("x-user-agent", "grpc-web-javascript/0.1")
                        setBody("[\"$REQUEST_KEY\",${Json.encodeToString(botguardResponse)}]")
                    }
                if (!integrityResponse.status.isSuccess()) {
                    integrityResponse.discardBody()
                    error("BotGuard integrity request failed with HTTP ${integrityResponse.status.value}")
                }
                val integrity = parseIntegrityTokenData(integrityResponse.bodyAsTextLimited(INTEGRITY_BODY_LIMIT_BYTES))
                runtime.evaluate(
                    """
                    globalThis.__metrolistPoMinter = { state: "running" };
                    createPoTokenMinter(globalThis.__metrolistWebPoSignalOutput, ${integrity.tokenJavaScript}).then(function() {
                        globalThis.__metrolistPoMinter = { state: "ready", value: "ready" };
                    }).catch(function(error) {
                        globalThis.__metrolistPoMinter = { state: "error", value: String(error) };
                    });
                    "started";
                    """.trimIndent(),
                )
                awaitJavaScriptResult(runtime, "__metrolistPoMinter")
                val expiresAtMs =
                    Clock.System.now().toEpochMilliseconds() +
                        (integrity.lifetimeSeconds - EXPIRY_MARGIN_SECONDS).coerceAtLeast(60L) * 1_000L
                return PageBoundPoTokenMinter(runtime, expiresAtMs)
            } finally {
                directClient.close()
            }
        }
    }
}

private suspend fun io.ktor.client.statement.HttpResponse.limitedBody(maxBytes: Int): String {
    if (!status.isSuccess()) {
        discardBody()
        error("BotGuard request failed with HTTP $status")
    }
    return bodyAsTextLimited(maxBytes)
}

private suspend fun io.ktor.client.statement.HttpResponse.discardBody() {
    bodyAsChannel().cancel(null)
}

@Serializable
private data class JavaScriptResult(
    val state: String,
    val value: String? = null,
)

private suspend fun awaitJavaScriptResult(
    runtime: PageBoundPoTokenJavaScriptRuntime,
    globalName: String,
): String =
    withTimeoutOrNull(JAVASCRIPT_TIMEOUT_MS.milliseconds) {
        while (true) {
            val result =
                runCatching {
                    Json.decodeFromString<JavaScriptResult>(runtime.evaluate("JSON.stringify(globalThis.$globalName || null)"))
                }.getOrNull()
            when (result?.state) {
                "ready" -> return@withTimeoutOrNull result.value.orEmpty()
                "error" -> throw PoTokenException(result.value ?: "BotGuard JavaScript failed")
            }
            delay(JAVASCRIPT_POLL_INTERVAL_MS.milliseconds)
        }
        error("unreachable")
    } ?: throw PoTokenException("BotGuard token generation timed out")

private const val JAVASCRIPT_TIMEOUT_MS = 17_000L
private const val JAVASCRIPT_POLL_INTERVAL_MS = 25L
private const val INTEGRITY_BODY_LIMIT_BYTES = 1 * 1024 * 1024
private const val WATCH_PAGE_BODY_LIMIT_BYTES = 4 * 1024 * 1024
private const val INTERPRETER_BODY_LIMIT_BYTES = 8 * 1024 * 1024
