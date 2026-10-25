/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.constants.EnableZemerKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.util.Locale

/**
 * Zemer: verified lyrics for Jewish music, matched by YouTube videoId (exact identity, no title search).
 * The Zemer server returns pointers to the public sources carrying this track's lyrics (plus inline text
 * for content it hosts itself); the client fetches each pointer directly. 404 = Zemer has nothing.
 */
object ZemerLyricsProvider : LyricsProvider {
    override val name = "Zemer"
    private const val BASE_URL = "https://search.zemer.io"

    @Serializable
    private data class Source(
        val type: String,
        val url: String? = null,
        val songId: Long? = null,
        val feedUrl: String? = null,
        val trackId: Long? = null,
        val plain: String? = null,
        val syncedLrc: String? = null,
        val synced: Boolean = false,
    )

    @Serializable
    private data class Resolved(
        val videoId: String,
        val lang: String? = null,
        val verified: Boolean = false,
        val hasSynced: Boolean = false,
        val sources: List<Source> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 15000
            }
            expectSuccess = false
        }
    }

    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableZemerKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = runCatching {
        val resolved = resolve(id) ?: throw IllegalStateException("Zemer has no lyrics for $id")
        for (source in resolved.sources.sortedWith(compareBy({ it.synced != true }, { rank(it) }))) {
            val body = runCatching { fetchBody(source) }
                .onFailure { Timber.tag("Zemer").d(it, "source ${source.type} failed") }
                .getOrNull()
            if (body != null && hasBody(body)) return@runCatching body
        }
        throw IllegalStateException("No Zemer source yielded a body")
    }

    private fun rank(s: Source) = when (s.type) {
        "jkaraoke" -> 0
        "lrclib", "jyrics", "shironet", "zingmusic", "tab4u", "zemirotdb" -> 1
        "booklet", "manual" -> 2
        "canonical", "community" -> 3
        else -> 9
    }

    private suspend fun fetchBody(s: Source): String? = when (s.type) {
        "jkaraoke" -> s.feedUrl?.let { fetchText(it) }?.let { page -> s.songId?.let { jkaraokeLrc(page, it) } }
        "lrclib" -> s.trackId?.let { fetchText("https://lrclib.net/api/get/$it") }?.let { lrclibBody(it) }
        "jyrics" -> s.url?.let { fetchText(it) }?.let { parseJyrics(it) }
        "shironet" -> s.url?.let { fetchText(it) }?.let { parseShironet(it) }
        "zingmusic" -> s.trackId?.let { zingLyricsHtml(it) }?.let { zingToPlain(it) }
        "tab4u" -> s.url?.let { fetchText(it) }?.let { parseTab4u(it) }
        "zemirotdb" -> s.url?.let { fetchText(it) }?.let { parseZemirotDb(it) }
        "booklet", "manual", "canonical", "community" ->
            s.syncedLrc?.takeIf { it.isNotBlank() } ?: s.plain?.takeIf { it.isNotBlank() }
        else -> null
    }

    private suspend fun resolve(videoId: String): Resolved? {
        val r = client.get("$BASE_URL/lyrics/resolve") {
            parameter("videoId", videoId)
            header(HttpHeaders.Accept, "application/json")
        }
        return if (r.status == HttpStatusCode.OK) r.body<Resolved>() else null
    }

    private suspend fun fetchText(url: String): String? {
        val r = client.get(url) { header(HttpHeaders.Accept, "text/html,application/json") }
        return if (r.status == HttpStatusCode.OK) r.bodyAsText() else null
    }

    private fun hasBody(text: String) = text.lineSequence().count { it.isNotBlank() } >= 4

    @Serializable
    private data class FeedLine(val start: Double? = null, val text: String? = null)

    @Serializable
    private data class FeedSong(val id: Long, val duration: Int? = null, val lyrics: List<FeedLine> = emptyList())

    @Serializable
    private data class FeedPage(val data: List<FeedSong> = emptyList())

    private fun jkaraokeLrc(pageJson: String, songId: Long): String? {
        val song = runCatching { json.decodeFromString(FeedPage.serializer(), pageJson) }.getOrNull()
            ?.data?.firstOrNull { it.id == songId } ?: return null
        val ls = song.lyrics.filter { it.start != null && !it.text.isNullOrBlank() }
        if (ls.size < 4) return null
        for (k in 1 until ls.size) if (ls[k].start!! + 0.01 < ls[k - 1].start!!) return null
        val dur = song.duration
        if (ls.first().start!! < 0 || (dur != null && dur > 0 && ls.last().start!! > dur + 2)) return null
        return ls.joinToString("\n") { "[${lrcTime(it.start!!)}]${it.text!!.trim()}" }
    }

    private fun lrcTime(s: Double): String {
        val m = (s / 60).toInt()
        return String.format(Locale.US, "%02d:%05.2f", m, s - m * 60)
    }

    @Serializable
    private data class LrcLibTrack(val syncedLyrics: String? = null, val plainLyrics: String? = null, val instrumental: Boolean = false)

    private fun lrclibBody(body: String): String? =
        runCatching { json.decodeFromString(LrcLibTrack.serializer(), body) }.getOrNull()
            ?.takeUnless { it.instrumental }
            ?.let { t -> t.syncedLyrics?.takeIf { it.isNotBlank() } ?: t.plainLyrics?.takeIf { it.isNotBlank() } }

    private suspend fun zingLyricsHtml(trackId: Long): String? {
        val r = client.post("https://jewishmusic.fm:8443/graphql") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"query":"{ track(where:{id:$trackId}){ heLyrics enLyrics } }"}""")
        }
        if (r.status != HttpStatusCode.OK) return null
        val track = runCatching {
            json.parseToJsonElement(r.bodyAsText()).jsonObject["data"]?.jsonObject?.get("track")?.jsonObject
        }.getOrNull() ?: return null
        return track["heLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: track["enLyrics"]?.jsonPrimitive?.contentOrNull
    }

    private val NUMERIC_ENTITY = Regex("""&#(\d+);""")
    private val TAG = Regex("""<[^>]+>""")
    private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""[ \t ]+""")

    private fun unescape(s: String): String = s
        .replace("&#8217;", "'").replace("&rsquo;", "'")
        .replace("&#8211;", "–").replace("&ndash;", "–")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&nbsp;", " ")
        .replace(NUMERIC_ENTITY) { m -> m.groupValues[1].toInt().toChar().toString() }

    private fun tidy(lines: List<String>): String {
        val out = ArrayList<String>()
        for (raw in lines) {
            val l = raw.replace(SPACES, " ").trim()
            if (l.isNotEmpty() || (out.isNotEmpty() && out.last().isNotEmpty())) out.add(l)
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        while (out.isNotEmpty() && out.first().isEmpty()) out.removeAt(0)
        return out.joinToString("\n")
    }

    private val JYRICS_CUT = Regex("""<ul[^>]*class="[^"]*related-list|<h\d[^>]*>\s*Other Songs from""", RegexOption.IGNORE_CASE)
    private val JYRICS_SCRIPT = Regex("""<script[\s\S]*?</script>|<style[\s\S]*?</style>|<!--[\s\S]*?-->""")
    private val JYRICS_NAV = Regex("""^(Print|SHARE|Added by|admin)$""", RegexOption.IGNORE_CASE)
    private val LABEL = Regex("""^\(?\s*(verse|chorus|bridge|intro|outro|pre-?chorus|hook|refrain|interlude|פזמון|בית|גשר|מעבר|סיום|פתיחה)\s*[\d\w]*\s*:?\s*\)?$""", RegexOption.IGNORE_CASE)
    private val CREDIT = Regex("""^\(?\s*(composed|arranged|written|lyrics|words|music|produced|recorded|later recorded|originally|from the album|album)\b[^\n]*\bby\b|^\(?\s*(composed|arranged|recorded)\b|^(מילים|לחן|עיבוד|הפקה)\s*:""", RegexOption.IGNORE_CASE)

    private fun parseJyrics(html: String): String? {
        var art = Regex("""<article[\s\S]*?</article>""").find(html)?.value ?: html
        JYRICS_CUT.find(art)?.let { art = art.substring(0, it.range.first) }
        val text = unescape(
            art.replace(JYRICS_SCRIPT, "").replace(BR, "\n")
                .replace(Regex("""</p>\s*""", RegexOption.IGNORE_CASE), "\n\n")
                .replace(Regex("""</(div|h\d|li)>\s*""", RegexOption.IGNORE_CASE), "\n")
                .replace(TAG, ""),
        )
        val lines = text.split("\n").map { it.replace(SPACES, " ").trim() }
        val i = lines.indexOfFirst { it.equals("LYRIC", ignoreCase = true) }
        val body = lines.subList(if (i >= 0) i + 1 else 0, lines.size)
            .filterNot { JYRICS_NAV.matches(it) }.toMutableList()
        while (body.isNotEmpty() && body.first().isEmpty()) body.removeAt(0)
        if (body.isNotEmpty()) body.removeAt(0)
        return tidy(body.filterNot { LABEL.matches(it) || CREDIT.containsMatchIn(it) }).ifBlank { null }
    }

    private fun parseShironet(html: String): String? {
        val span = Regex("""<span[^>]*class="artist_lyrics_text"[^>]*>([\s\S]*?)</span>""").find(html)
            ?.groupValues?.get(1) ?: return null
        val text = unescape(span.replace(BR, "\n").replace(TAG, ""))
        return tidy(text.split("\n").filterNot { LABEL.matches(it.trim()) }).ifBlank { null }
    }

    private fun zingToPlain(html: String): String? {
        val text = unescape(
            html.replace(BR, "\n")
                .replace(Regex("""</(p|div|pre|li|h\d)>""", RegexOption.IGNORE_CASE), "\n")
                .replace(TAG, "").replace("&lt;", "<").replace("&gt;", ">"),
        )
        return tidy(
            text.split("\n").filterNot {
                val l = it.trim()
                Regex("""^\[[^\]]*\]$""").matches(l) ||
                    Regex("""^\((?:verse|chorus|bridge|intro|outro|פזמון|בית|מעבר|גשר)\b[^)]*\)$""", RegexOption.IGNORE_CASE).matches(l)
            },
        ).ifBlank { null }
    }

    private fun parseTab4u(html: String): String? {
        val lines = ArrayList<String>()
        for (m in Regex("""<td class="song[^"]*"[^>]*>([\s\S]*?)</td>""").findAll(html)) {
            val t = unescape(m.groupValues[1].replace(TAG, "")).replace(SPACES, " ").trim()
            if (t.isEmpty() || !t.contains(Regex("""[א-ת]"""))) continue
            lines.add(t)
        }
        return tidy(lines).ifBlank { null }
    }

    private fun parseZemirotDb(html: String): String? {
        val div = Regex("""<div id=['"]hebrew['"][^>]*>([\s\S]*?)</div>""").find(html)
            ?.groupValues?.get(1) ?: return null
        val text = unescape(
            div.replace(BR, "\n").replace(Regex("""</p>\s*""", RegexOption.IGNORE_CASE), "\n\n").replace(TAG, ""),
        )
        return tidy(text.split("\n")).ifBlank { null }
    }
}
