package com.metrolist.shared.viewmodels

import com.metrolist.shared.network.bodyAsBytesLimited
import com.metrolist.shared.state.GlobalInnerTube
import io.ktor.client.request.prepareGet
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess

private const val MAX_PHOTO_BYTES = 32 * 1_024 * 1_024

class ArtistPhotoDownload(
    val bytes: ByteArray,
    val mimeType: String,
    val baseName: String,
    val extension: String,
)

suspend fun downloadArtistPhoto(
    photo: ArtistPhoto,
    artistName: String,
): ArtistPhotoDownload =
    GlobalInnerTube.client.prepareGet(photo.url).execute { response ->
        check(response.status.isSuccess()) { "Photo download failed with HTTP ${response.status.value}" }
        val responseMimeType = response.contentType()?.withoutParameters()?.toString()
        val mimeType = checkNotNull(responseMimeType?.takeIf { it.startsWith("image/") }) { "Photo response is not an image" }
        val extension = artistPhotoExtension(mimeType)
        val bytes = checkNotNull(response.bodyAsBytesLimited(MAX_PHOTO_BYTES)) { "Photo exceeds $MAX_PHOTO_BYTES bytes" }
        ArtistPhotoDownload(bytes, mimeType, artistPhotoBaseName(artistName, photo.url), extension)
    }

internal fun artistPhotoBaseName(
    artistName: String,
    url: String,
): String =
    "$artistName ${Url(url).encodedPath.substringAfterLast('/').substringBefore('.')}"
        .replace(Regex("""[\\/:*?"<>|\x00-\x1f\x7f]"""), "_")

internal fun artistPhotoExtension(mimeType: String): String =
    mimeType
        .substringAfter('/')
        .substringBefore('+')
        .let { if (it == "jpeg") "jpg" else it }
        .also { require(it.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9.-]*"))) { "Invalid photo extension" } }
