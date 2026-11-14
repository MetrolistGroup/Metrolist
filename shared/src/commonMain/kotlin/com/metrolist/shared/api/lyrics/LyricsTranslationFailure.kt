package com.metrolist.shared.api.lyrics

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.http.Headers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal class LyricsTranslationHttpException(
    val statusCode: Int,
    responseBody: String,
    val headers: Headers = Headers.Empty,
) : IllegalStateException("AI request failed (HTTP $statusCode)") {
    val failure = classifyLyricsTranslationHttpFailure(statusCode, responseBody, headers)

    fun retryAtMillis(now: Long): Long? {
        val reset = headers["X-RateLimit-Reset"]?.toLongOrNull()?.let { if (it < 1_000_000_000_000) it * 1_000 else it }
        return reset?.takeIf { it > now }
            ?: headers["Retry-After"]?.toLongOrNull()?.takeIf { it in 1..86_400 }?.let { now + it * 1_000 }
    }
}

internal fun classifyLyricsTranslationHttpFailure(
    statusCode: Int,
    responseBody: String,
    headers: Headers = Headers.Empty,
): LyricsTranslationFailure {
    val root = runCatching { Json.parseToJsonElement(responseBody) as? JsonObject }.getOrNull()
    val choice = (root?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
    val error = (root?.get("error") ?: choice?.get("error")) as? JsonObject
    val metadata = error?.get("metadata") as? JsonObject
    val type = ((metadata?.get("error_type") ?: error?.get("error_type")) as? JsonPrimitive)?.contentOrNull
    val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
    val code = (error?.get("code") as? JsonPrimitive)?.intOrNull ?: statusCode
    return when {
        "data policy" in message || "privacy settings" in message -> {
            LyricsTranslationFailure.DATA_POLICY
        }

        type == "authentication" || code == 401 -> {
            LyricsTranslationFailure.AUTHENTICATION
        }

        type == "payment_required" || code == 402 -> {
            LyricsTranslationFailure.CREDIT_LIMIT
        }

        type == "rate_limit_exceeded" || code == 429 -> {
            when {
                "free-models-per-day" in message || "daily" in message || "per day" in message -> {
                    LyricsTranslationFailure.FREE_DAILY_LIMIT
                }

                "free-models-per-min" in message || "per minute" in message -> {
                    LyricsTranslationFailure.FREE_MINUTE_LIMIT
                }

                headers.names().any { it.startsWith("X-RateLimit-", ignoreCase = true) } -> {
                    LyricsTranslationFailure.RATE_LIMIT
                }

                "upstream" in message || metadata?.get("provider_name") != null || metadata?.get("provider_code") != null -> {
                    LyricsTranslationFailure.MODEL_BUSY
                }

                else -> {
                    LyricsTranslationFailure.RATE_LIMIT
                }
            }
        }

        type in setOf("not_found", "provider_unavailable", "provider_overloaded") || code in setOf(404, 502, 503) -> {
            LyricsTranslationFailure.MODEL_UNAVAILABLE
        }

        type == "timeout" || code == 408 || code == 504 -> {
            LyricsTranslationFailure.TIMEOUT
        }

        else -> {
            LyricsTranslationFailure.REQUEST_FAILED
        }
    }
}

internal suspend fun <T> retryLyricsTranslation(request: suspend () -> Result<T>): Result<T> {
    suspend fun attempt(): Result<T> =
        try {
            currentCoroutineContext().ensureActive()
            request()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }

    val first = attempt()
    val error = first.exceptionOrNull() ?: return first
    if (error is CancellationException) throw error
    val statusCode =
        when (error) {
            is LyricsTranslationHttpException -> error.statusCode
            is ResponseException -> error.response.status.value
            else -> null
        }
    if (error is IllegalArgumentException || statusCode in setOf(401, 402, 403, 404, 429) ||
        error.lyricsTranslationFailure() in
        setOf(
            LyricsTranslationFailure.DATA_POLICY,
            LyricsTranslationFailure.AUTHENTICATION,
            LyricsTranslationFailure.CREDIT_LIMIT,
            LyricsTranslationFailure.RATE_LIMIT,
            LyricsTranslationFailure.FREE_DAILY_LIMIT,
            LyricsTranslationFailure.FREE_MINUTE_LIMIT,
            LyricsTranslationFailure.MODEL_BUSY,
        )
    ) {
        return first
    }
    delay(1_000)
    val retry = attempt()
    retry.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    return retry
}

enum class LyricsTranslationFailure {
    DATA_POLICY,
    AUTHENTICATION,
    CREDIT_LIMIT,
    RATE_LIMIT,
    FREE_DAILY_LIMIT,
    FREE_MINUTE_LIMIT,
    MODEL_BUSY,
    MODEL_UNAVAILABLE,
    NETWORK,
    EMPTY_RESPONSE,
    INVALID_RESPONSE,
    INCOMPLETE_RESPONSE,
    TIMEOUT,
    REQUEST_FAILED,
}

internal class LyricsTranslationException(
    val failure: LyricsTranslationFailure,
    message: String,
) : IllegalStateException(message)

internal fun Throwable.lyricsTranslationFailure(): LyricsTranslationFailure =
    when (this) {
        is LyricsTranslationHttpException -> failure
        is LyricsTranslationException -> failure
        is ResponseException -> classifyLyricsTranslationHttpFailure(response.status.value, "")
        is HttpRequestTimeoutException, is SocketTimeoutException, is ConnectTimeoutException -> LyricsTranslationFailure.TIMEOUT
        is IOException -> LyricsTranslationFailure.NETWORK
        else -> LyricsTranslationFailure.REQUEST_FAILED
    }
