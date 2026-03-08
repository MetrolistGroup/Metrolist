package meow.nyx.spotlib

import com.metrolist.shared.network.HttpFailureLog
import com.metrolist.shared.network.sharedHttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import io.ktor.client.plugins.logging.LogLevel as KtorLogLevel

internal object SpotifyHttp {
    private val prettyJson = Json { prettyPrint = true }
    private val sensitiveHeaders =
        setOf(
            HttpHeaders.Authorization.lowercase(),
            HttpHeaders.Cookie.lowercase(),
            HttpHeaders.SetCookie.lowercase(),
            "client-token",
            "x-csrf-token",
        )

    fun create(config: SpotifyConfig): HttpClient {
        Log.configure(config.logger, config.logLevel)
        return HttpClient(config.engine) {
            install(HttpFailureLog) { transportHandledByEngine = config.engine === sharedHttpEngine }
            defaultRequest {
                header(HttpHeaders.UserAgent, config.userAgent)
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    },
                )
            }
            install(io.ktor.client.plugins.compression.ContentEncoding)
            install(HttpTimeout) {
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 60_000
            }
            if (config.logLevel == LogLevel.BODIES) {
                install(Logging) {
                    logger =
                        object : Logger {
                            override fun log(message: String) {
                                Log.debug(prettyJsonLines(message))
                            }
                        }
                    level = KtorLogLevel.ALL
                    sanitizeHeader { it.lowercase() in sensitiveHeaders }
                }
            }
            expectSuccess = false
            followRedirects = false
        }
    }

    private fun prettyJsonLines(message: String): String {
        val trimmed = message.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return message
        if (trimmed.length > 2_000_000) return message
        return try {
            val parsed = Json.parseToJsonElement(trimmed)
            if (parsed is JsonObject || parsed is JsonArray) {
                message.substringBefore(trimmed) + prettyJson.encodeToString<JsonElement>(parsed)
            } else {
                message
            }
        } catch (_: Exception) {
            message
        }
    }
}
