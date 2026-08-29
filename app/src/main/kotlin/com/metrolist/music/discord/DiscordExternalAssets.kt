package com.metrolist.music.discord

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

object DiscordExternalAssets {

    private const val TAG = "DiscordSvc"
    private const val EXTERNAL_ASSETS_API =
        "https://discord.com/api/v9/applications/%s/external-assets"

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, String>()
    private const val CACHE_MAX_SIZE = 128

    private val client: HttpClient by lazy {
        HttpClient(io.ktor.client.engine.cio.CIO) {
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 10_000L
                connectTimeoutMillis = 5_000L
                socketTimeoutMillis = 10_000L
            }
            expectSuccess = false
        }
    }

    suspend fun resolve(
        imageUrl: String,
        appId: String,
        token: String,
    ): String? {
        if (imageUrl.isBlank()) return null
        if (imageUrl.startsWith("mp:")) return imageUrl

        cache[imageUrl]?.let {
            Timber.tag(TAG).d("resolve: cache hit for %s -> %s", imageUrl.take(60), it)
            return it
        }
        Timber.tag(TAG).d("resolve: cache miss for %s, calling API", imageUrl.take(60))

        return try {
            val response = client.post(EXTERNAL_ASSETS_API.format(appId)) {
                header("Authorization", token)
                header("User-Agent", DiscordSuperProperties.USER_AGENT)
                header("X-Super-Properties", DiscordSuperProperties.base64)
                header("Content-Type", "application/json")
                setBody(json.encodeToString(ExternalAssetRequest(listOf(imageUrl))))
            }

            val body = response.bodyAsText()
            val statusCode = response.status.value

            if (statusCode in 200..299 && body.isNotBlank()) {
                val parsed = json.decodeFromString<List<ExternalAssetResponse>>(body)
                val assetPath = parsed.firstOrNull()?.externalAssetPath
                if (assetPath != null) {
                    val result = "mp:$assetPath"
                    cache[imageUrl] = result
                    trimCache()
                    Timber.tag(TAG).i("external-assets: resolved %s -> %s", imageUrl.take(60), result)
                    return result
                } else {
                    Timber.tag(TAG).w("external-assets: no path in response for %s: %s", imageUrl.take(60), body.take(200))
                }
            } else {
                Timber.tag(TAG).w("external-assets: HTTP %d for %s: %s", statusCode, imageUrl.take(60), body.take(200))
            }
            null
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "external-assets: failed for %s", imageUrl.take(60))
            null
        }
    }

    private fun trimCache() {
        if (cache.size > CACHE_MAX_SIZE) {
            val toRemove = cache.size - CACHE_MAX_SIZE
            cache.keys.take(toRemove).forEach { cache.remove(it) }
        }
    }

    fun clearCache() {
        Timber.tag(TAG).d("clearCache: clearing %d entries", cache.size)
        cache.clear()
    }

    fun close() {
        runCatching { client.close() }
    }

    @kotlinx.serialization.Serializable
    private data class ExternalAssetRequest(
        val urls: List<String>,
    )

    @kotlinx.serialization.Serializable
    private data class ExternalAssetResponse(
        val url: String? = null,
        @kotlinx.serialization.SerialName("external_asset_path")
        val externalAssetPath: String? = null,
    )
}
