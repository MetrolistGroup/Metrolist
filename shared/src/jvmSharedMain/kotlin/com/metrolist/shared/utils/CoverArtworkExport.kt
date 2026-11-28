package com.metrolist.shared.utils

import coil3.Image
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.Decoder
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.IdentityHashMap

enum class CoverArtworkFormat(
    val extension: String,
    val mimeType: String,
) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp"),
}

data class CoverArtwork(
    val bytes: ByteArray,
    val format: CoverArtworkFormat,
)

private val invalidFilenameCharacters = Regex("""[\p{Cntrl}<>:"/\\|?*]""")
private val reservedFilename = Regex("(?i)CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]")
private const val MAX_EXPORT_BYTES = 12L * 1024 * 1024

fun coverArtworkFilename(
    title: String,
    fallback: String,
): String {
    val name =
        title
            .replace(invalidFilenameCharacters, "_")
            .trim()
            .take(75)
            .trimEnd { it == ' ' || it == '.' || it.isHighSurrogate() }
    return when {
        name.isEmpty() -> fallback
        reservedFilename.matches(name.substringBefore('.').trimEnd()) -> "_$name"
        else -> name
    }
}

suspend fun loadCoverArtwork(
    context: PlatformContext,
    loader: ImageLoader,
    url: String,
): CoverArtwork? =
    withContext(Dispatchers.IO) {
        val sourceUrl = uncroppedHighResolutionArtworkUrl(url)
        val originals = IdentityHashMap<Image, CoverArtwork>()
        val request =
            ImageRequest
                .Builder(context)
                .data(sourceUrl)
                .size(MAX_ARTWORK_SIZE_PX)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .decoderFactory(
                    Decoder.Factory { source, options, imageLoader ->
                        val (decoder) = imageLoader.components.newDecoder(source, options, imageLoader) ?: return@Factory null
                        Decoder {
                            // Keep the encoded source while Coil validates it with the platform decoder.
                            val original =
                                source.source
                                    .source()
                                    .peek()
                                    .readCoverArtwork()
                            decoder.decode()?.also { result ->
                                if (original != null) originals[result.image] = original
                            }
                        }
                    },
                ).build()

        suspend fun load(request: ImageRequest): CoverArtwork? {
            originals.clear()
            val result = loader.execute(request) as? SuccessResult
            return result?.let { originals[it.image] }
        }

        load(request)?.let { return@withContext it }
        val cache = loader.diskCache ?: return@withContext null
        (artworkCacheCandidates(sourceUrl, 1).reversed() + sourceUrl).distinct().firstNotNullOfOrNull { key ->
            val cached =
                try {
                    cache.openSnapshot(key)?.use { true } == true
                } catch (_: IOException) {
                    false
                }
            if (!cached) return@firstNotNullOfOrNull null
            load(
                request
                    .newBuilder()
                    .diskCacheKey(key)
                    .networkCachePolicy(CachePolicy.DISABLED)
                    .build(),
            )
        }
    }

private fun BufferedSource.readCoverArtwork(): CoverArtwork? {
    if (request(MAX_EXPORT_BYTES + 1)) return null
    val format =
        when {
            sizeAtLeastJpegHeader() -> {
                CoverArtworkFormat.JPEG
            }

            rangeEquals(0, pngSignature) && !hasPngAnimation() -> {
                CoverArtworkFormat.PNG
            }

            rangeEquals(0, "RIFF".encodeUtf8()) &&
                rangeEquals(8, "WEBP".encodeUtf8()) &&
                (!rangeEquals(12, "VP8X".encodeUtf8()) || (buffer.size > 20 && (buffer[20].toInt() and 0x02) == 0)) -> {
                CoverArtworkFormat.WEBP
            }

            else -> {
                return null
            }
        }
    return CoverArtwork(readByteArray(), format)
}

private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).toByteString()

private fun BufferedSource.sizeAtLeastJpegHeader(): Boolean =
    buffer.size >= 3 && buffer[0].toInt() == -1 && buffer[1].toInt() == -40 && buffer[2].toInt() == -1

private fun BufferedSource.hasPngAnimation(): Boolean {
    val animationControl = "acTL".encodeUtf8()
    val imageData = "IDAT".encodeUtf8()
    var offset = 8L
    while (offset + 12 <= buffer.size) {
        if (rangeEquals(offset + 4, animationControl)) return true
        if (rangeEquals(offset + 4, imageData)) return false
        val length = (0..3).fold(0L) { value, index -> (value shl 8) + (buffer[offset + index].toInt() and 0xff) }
        offset += length + 12
    }
    return false
}
