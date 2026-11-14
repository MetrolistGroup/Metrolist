package com.metrolist.shared.discord

import com.metrolist.shared.utils.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

object DiscordExternalAssets {
    private val resolver = DiscordExternalAssetResolver()

    fun peekCache(imageUrl: String): String? = resolver.peekCache(imageUrl)

    suspend fun resolve(
        imageUrl: String,
        appId: String,
        token: String,
        httpClient: HttpClient,
    ): String? = resolveAll(listOf(imageUrl), appId, token, httpClient)[imageUrl]

    suspend fun resolveAll(
        imageUrls: List<String>,
        appId: String,
        token: String,
        httpClient: HttpClient,
    ): Map<String, String> = resolver.resolveAll(imageUrls, appId, token, httpClient)

    fun clearCache() = resolver.clearCache()
}

internal class DiscordExternalAssetResolver(
    private val nowMs: () -> Long =
        run {
            val origin = TimeSource.Monotonic.markNow()
            val clock: () -> Long = { origin.elapsedNow().inWholeMilliseconds }
            clock
        },
) {
    private val tag = "DiscordSvc"
    private val externalAssetsApi = "https://discord.com/api/v9/applications/%s/external-assets"

    private val json = Json { ignoreUnknownKeys = true }

    private data class Entry(
        val asset: String?,
        val attempts: Int = 0,
        val retryAtMs: Long = 0L,
    )

    @Volatile private var cache: Map<String, Entry> = emptyMap()
    private val requests = Mutex()
    private val cacheMaxSize = 128

    fun peekCache(imageUrl: String): String? {
        if (imageUrl.isBlank() || imageUrl.startsWith("mp:")) return imageUrl
        return cache[imageUrl]?.asset
    }

    suspend fun resolveAll(
        imageUrls: List<String>,
        appId: String,
        token: String,
        httpClient: HttpClient,
    ): Map<String, String> =
        requests.withLock {
            resolveLocked(imageUrls, appId, token, httpClient)
        }

    private suspend fun resolveLocked(
        imageUrls: List<String>,
        appId: String,
        token: String,
        httpClient: HttpClient,
    ): Map<String, String> {
        val resolved = mutableMapOf<String, String>()
        val missing = mutableListOf<String>()

        imageUrls
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { imageUrl ->
                val cached = cache[imageUrl]
                when {
                    imageUrl.startsWith("mp:") -> resolved[imageUrl] = imageUrl
                    cached?.asset != null -> resolved[imageUrl] = cached.asset
                    cached != null && nowMs() < cached.retryAtMs -> Unit
                    else -> missing.add(imageUrl)
                }
            }

        if (missing.isEmpty()) return resolved

        Logger.d(tag, "resolve: ${missing.size} cache misses, calling API")

        var retryAfterMs = 0L
        try {
            val url = externalAssetsApi.replace("%s", appId)
            val response =
                httpClient.post(url) {
                    header("Authorization", token)
                    header("User-Agent", DiscordSuperProperties.USER_AGENT)
                    header("X-Super-Properties", DiscordSuperProperties.base64)
                    header("Content-Type", "application/json")
                    setBody(json.encodeToString(ExternalAssetRequest.serializer(), ExternalAssetRequest(missing)))
                }

            val responseBody = response.bodyAsText()
            val statusCode = response.status.value

            if (statusCode in 200..299 && responseBody.isNotBlank()) {
                val parsed = json.decodeFromString<List<ExternalAssetResponse>>(responseBody)
                parsed.forEachIndexed { index, item ->
                    val assetPath = item.externalAssetPath ?: return@forEachIndexed
                    val originalUrl = item.url?.takeIf { it.isNotBlank() } ?: missing.getOrNull(index)
                    if (originalUrl != null && originalUrl in missing && assetPath.isNotBlank()) {
                        val result = "mp:$assetPath"
                        cache = cache + (originalUrl to Entry(result))
                        resolved[originalUrl] = result
                    }
                }
            } else {
                retryAfterMs =
                    when (statusCode) {
                        429 -> {
                            val seconds =
                                response.headers["Retry-After"]?.toDoubleOrNull()
                                    ?: runCatching {
                                        json
                                            .parseToJsonElement(
                                                responseBody,
                                            ).jsonObject["retry_after"]
                                            ?.jsonPrimitive
                                            ?.doubleOrNull
                                    }.getOrNull()
                            seconds?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1_000).toLong() } ?: 0L
                        }

                        in 400..499 -> {
                            30 * 60_000L
                        }

                        else -> {
                            0L
                        }
                    }
                Logger.w(tag, "external-assets: HTTP $statusCode for ${missing.size} urls")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w(tag, "external-assets: failed for ${missing.size} urls", e)
        }
        missing.filter { it !in resolved }.forEach { url ->
            val attempts = ((cache[url]?.attempts ?: 0) + 1).coerceAtMost(7)
            val backoffMs = (30_000L * (1L shl (attempts - 1))).coerceAtMost(30 * 60_000L)
            cache = cache + (url to Entry(null, attempts, nowMs() + maxOf(backoffMs, retryAfterMs)))
        }
        trimCache()
        return resolved
    }

    private fun trimCache() {
        if (cache.size > cacheMaxSize) {
            val toRemove = cache.size - cacheMaxSize
            cache = cache - cache.keys.take(toRemove).toSet()
        }
    }

    fun clearCache() {
        cache = emptyMap()
    }

    @Serializable
    private data class ExternalAssetRequest(
        val urls: List<String>,
    )

    @Serializable
    private data class ExternalAssetResponse(
        val url: String? = null,
        @SerialName("external_asset_path")
        val externalAssetPath: String? = null,
    )
}
