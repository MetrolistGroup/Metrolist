package com.metrolist.shared.api.lastfm

import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.serialization.Serializable

@Serializable
data class LastFmPhotoDetails(
    val title: String? = null,
    val description: String? = null,
    val authorName: String? = null,
    val authorProfileUrl: String? = null,
    val authorAvatarUrl: String? = null,
    val uploadDate: String? = null,
)

private val PHOTO_UPLOAD_DATE_FORMAT =
    LocalDate.Format {
        day(Padding.NONE)
        char(' ')
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        char(' ')
        year()
    }

internal fun parsePhotoUploadDate(text: String): LocalDate? = PHOTO_UPLOAD_DATE_FORMAT.parseOrNull(text.trim())

internal fun parsePhotoDetails(html: String): LastFmPhotoDetails? {
    fun section(className: String): String? =
        Regex("""<(h3|p|div)\b[^>]*class=["'][^"']*\b$className\b[^"']*["'][^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)
            ?.groupValues
            ?.get(2)

    val title = section("gallery-image-title")?.photoHtmlText()
    val description = section("gallery-image-description")?.photoHtmlText()
    val uploadedBy = section("gallery-image-uploaded-by")
    val author =
        uploadedBy?.let {
            Regex("""<a\b[^>]*href=["']/user/([^/"'?#]+)["'][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).find(it)
        }
    val username = author?.groupValues?.get(1)?.decodeURLQueryComponent()
    val date =
        author?.let { uploadedBy.substring(it.range.last + 1).replace(Regex("""^\s*on\s+"""), "").photoHtmlText() }
    return LastFmPhotoDetails(
        title = title,
        description = description,
        authorName = author?.groupValues?.get(2)?.photoHtmlText(),
        authorProfileUrl = username?.let { "https://www.last.fm/user/${it.encodeURLParameter()}" },
        uploadDate = date,
    ).takeIf { title != null || description != null || author != null }
}

// Named references Last.fm text is likely to contain; any other named reference is left as written.
private val PHOTO_HTML_ENTITIES =
    mapOf(
        "amp" to "&",
        "quot" to "\"",
        "apos" to "'",
        "lt" to "<",
        "gt" to ">",
        "nbsp" to " ",
        "copy" to "©",
        "reg" to "®",
        "trade" to "™",
        "mdash" to "—",
        "ndash" to "–",
        "hellip" to "…",
        "lsquo" to "‘",
        "rsquo" to "’",
        "ldquo" to "“",
        "rdquo" to "”",
        "laquo" to "«",
        "raquo" to "»",
    )

private fun String.photoHtmlText(): String? =
    replace(Regex("""<(?:br\b[^>]*|/?(?:p|div)\b[^>]*)>"""), " ")
        .replace(Regex("<[^>]*>"), "")
        .replace(Regex("""&(#[xX][0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")) { match ->
            val entity = match.groupValues[1]
            if (entity[0] != '#') return@replace PHOTO_HTML_ENTITIES[entity] ?: match.value
            val code = if (entity[1] in "xX") entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
            when {
                code == null || code !in 1..0x10ffff || code in 0xd800..0xdfff -> match.value
                code <= 0xffff -> code.toChar().toString()
                else -> ((code - 0x10000) / 0x400 + 0xd800).toChar().toString() + ((code - 0x10000) % 0x400 + 0xdc00).toChar()
            }
        }.replace(Regex("""\s+"""), " ")
        .trim()
        .ifBlank { null }
