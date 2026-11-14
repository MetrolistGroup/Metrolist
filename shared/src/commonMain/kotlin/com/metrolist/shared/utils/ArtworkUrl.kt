package com.metrolist.shared.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

private const val DEFAULT_ARTWORK_SIZE_PX = 544
private const val CANONICAL_ARTWORK_SIZE_PX = 1200
private const val MAX_ARTWORK_SIZE_PX = 2400

private val GoogleArtworkSizeSuffix = Regex("=(?:s(\\d+)|w(\\d+)(?:-[^-?#=]+)*-h\\d+)([^?#]*)")
private val YouTubeVideoArtworkPath =
    Regex("(/vi(?:_webp)?/[^/?#]+/)(mqdefault|hqdefault|sddefault|hq720|maxresdefault)(\\.(?:jpg|webp))(?=[?#]|$)")

internal class ArtworkUrlCache(
    private val capacity: Int = 512,
) {
    private val lock = SynchronizedObject()
    private val values = LinkedHashMap<Pair<String, Int?>, String>()

    fun get(
        url: String,
        size: Int?,
        compute: () -> String,
    ): String {
        val key = url to size
        synchronized(lock) {
            values.remove(key)?.let {
                values[key] = it
                return it
            }
        }
        val result = compute()
        synchronized(lock) {
            values[key] = result
            if (values.size > capacity) values.remove(values.keys.first())
        }
        return result
    }
}

private val artworkUrls = ArtworkUrlCache()
private val canonicalUrls = ArtworkUrlCache()

fun canonicalArtworkUrl(url: String): String = canonicalUrls.get(url, null) { canonicalArtworkUrlUncached(url) }

fun artworkColorSourceUrl(url: String): String {
    val source = sizedArtworkUrl(url, 256)
    val host =
        source
            .substringAfter("://", "")
            .substringBefore('/')
            .substringBefore(':')
            .lowercase()
    if (host != "i.ytimg.com" && !host.endsWith(".ytimg.com")) return source
    val videoPath = YouTubeVideoArtworkPath.find(source.substringBefore('#').substringBefore('?')) ?: return source
    return source.replaceRange(videoPath.range, "${videoPath.groupValues[1]}mqdefault${videoPath.groupValues[3]}")
}

fun persistentArtworkUrl(url: String): String = canonicalArtworkUrlUncached(url, promoteVideoArtwork = false)

private fun canonicalArtworkUrlUncached(
    url: String,
    promoteVideoArtwork: Boolean = true,
): String {
    val requestedSize =
        GoogleArtworkSizeSuffix
            .find(url)
            ?.let { it.groupValues[1].ifEmpty { it.groupValues[2] }.toIntOrNull() }
    val canonicalSize = if ((requestedSize ?: 0) > CANONICAL_ARTWORK_SIZE_PX) MAX_ARTWORK_SIZE_PX else CANONICAL_ARTWORK_SIZE_PX
    return sizedArtworkUrlUncached(url, canonicalSize, promoteVideoArtwork)
}

fun bucketedArtworkUrl(
    url: String,
    resolvedSizePx: Int,
): String =
    sizedArtworkUrl(
        url,
        when {
            resolvedSizePx <= 256 -> 256
            resolvedSizePx <= DEFAULT_ARTWORK_SIZE_PX -> DEFAULT_ARTWORK_SIZE_PX
            resolvedSizePx <= CANONICAL_ARTWORK_SIZE_PX -> CANONICAL_ARTWORK_SIZE_PX
            else -> MAX_ARTWORK_SIZE_PX
        },
    )

fun artworkCacheCandidates(
    url: String,
    resolvedSizePx: Int,
): List<String> {
    val requested = bucketedArtworkUrl(url, resolvedSizePx)
    return (
        listOf(requested) +
            listOf(256, DEFAULT_ARTWORK_SIZE_PX, CANONICAL_ARTWORK_SIZE_PX, MAX_ARTWORK_SIZE_PX)
                .filter { it >= resolvedSizePx }
                .map { sizedArtworkUrl(url, it) }
    ).distinct()
}

fun ImageLoader.cachedArtworkPreview(url: String?): Pair<MemoryCache.Key, MemoryCache.Value>? {
    if (url.isNullOrBlank()) return null
    val cache = memoryCache ?: return null
    return (listOf(url) + artworkCacheCandidates(url, 1).reversed())
        .firstNotNullOfOrNull { candidate ->
            val key = MemoryCache.Key(candidate)
            cache[key]?.let { key to it }
        }
}

@Composable
fun rememberCachedArtworkPreview(
    url: String?,
    previewUrl: String?,
): Pair<MemoryCache.Key, MemoryCache.Value>? {
    val context = LocalPlatformContext.current
    return remember(url, previewUrl, context) { SingletonImageLoader.get(context).cachedArtworkPreview(previewUrl) }
}

fun highResolutionArtworkUrl(url: String): String = sizedArtworkUrl(url, MAX_ARTWORK_SIZE_PX)

fun String?.withArtworkCacheRevision(revision: Long): String? = this?.substringBefore('#')?.let { "$it#metrolist-refresh=$revision" }

fun ImageLoader.evictArtworkCache(url: String?) {
    if (url.isNullOrBlank()) return
    val cacheKeys =
        setOf(url, canonicalArtworkUrl(url)) +
            listOf(256, DEFAULT_ARTWORK_SIZE_PX, CANONICAL_ARTWORK_SIZE_PX, MAX_ARTWORK_SIZE_PX).map { sizedArtworkUrl(url, it) }
    memoryCache?.let { cache ->
        cache.keys.filter { it.key in cacheKeys }.forEach(cache::remove)
    }
    diskCache?.let { cache -> cacheKeys.forEach(cache::remove) }
}

fun sizedArtworkUrl(
    url: String,
    requestedSizePx: Int?,
): String = artworkUrls.get(url, requestedSizePx) { sizedArtworkUrlUncached(url, requestedSizePx) }

private fun sizedArtworkUrlUncached(
    url: String,
    requestedSizePx: Int?,
    promoteVideoArtwork: Boolean = true,
): String {
    val host =
        url
            .substringAfter("://", missingDelimiterValue = "")
            .substringBefore('/')
            .substringBefore(':')
            .lowercase()
    val isGoogleArtworkHost = host.endsWith(".googleusercontent.com") || host.endsWith(".ggpht.com")
    val isYouTubeArtworkHost = host == "i.ytimg.com" || host.endsWith(".ytimg.com")
    if (!isGoogleArtworkHost && !isYouTubeArtworkHost) return url

    val sourcePath = url.substringBefore('#').substringBefore('?')
    val videoPath = YouTubeVideoArtworkPath.find(sourcePath).takeIf { isYouTubeArtworkHost }
    if (videoPath != null) {
        val fragment = url.substringAfter('#', missingDelimiterValue = "")
        val source = sourcePath + fragment.takeIf { it.isNotEmpty() }?.let { "#$it" }.orEmpty()
        return if (promoteVideoArtwork && videoPath.groupValues[2] == "mqdefault" && (requestedSizePx ?: DEFAULT_ARTWORK_SIZE_PX) > 320) {
            source.replaceRange(videoPath.range, "${videoPath.groupValues[1]}maxresdefault${videoPath.groupValues[3]}")
        } else {
            source
        }
    }

    val sizePx = (requestedSizePx ?: DEFAULT_ARTWORK_SIZE_PX).coerceIn(1, MAX_ARTWORK_SIZE_PX)
    if (GoogleArtworkSizeSuffix.containsMatchIn(url)) {
        return url.replace(GoogleArtworkSizeSuffix) { match ->
            val suffix = match.groupValues[3]
            // Google's standalone square-crop flag returns HTTP 400 beside an sNNN size.
            val size = if ("s" in suffix.split('-')) "w$sizePx-h$sizePx" else "s$sizePx"
            "=$size$suffix"
        }
    }

    if (!isGoogleArtworkHost) return url
    val suffixIndex = url.indexOfAny(charArrayOf('?', '#')).takeIf { it >= 0 } ?: url.length
    return url.substring(0, suffixIndex) + "=s$sizePx" + url.substring(suffixIndex)
}

internal fun videoArtworkFallbackUrl(url: String): String? {
    val host =
        url
            .substringAfter("://", missingDelimiterValue = "")
            .substringBefore('/')
            .substringBefore(':')
            .lowercase()
    if (host != "i.ytimg.com" && !host.endsWith(".ytimg.com")) return null
    val path = YouTubeVideoArtworkPath.find(url.substringBefore('#').substringBefore('?')) ?: return null
    if (path.groupValues[2] != "maxresdefault") return null
    // hqdefault and sddefault bake letterboxing into the image; mqdefault keeps the bar-free frame.
    return url.replaceRange(path.range, "${path.groupValues[1]}mqdefault${path.groupValues[3]}")
}
