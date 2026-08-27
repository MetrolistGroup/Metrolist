package com.metrolist.music.features.lyrics

import android.content.Context
import com.metrolist.music.lyrics.LyricsProvider
import com.metrolist.music.constants.EnableMusixmatchKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import timber.log.Timber

private const val MUSIXMATCH_BASE_URL = "https://apic.musixmatch.com/ws/1.1/"
private const val MUSIXMATCH_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"

@Serializable
private data class MxHeader(@SerialName("status_code") val statusCode: Int)

@Serializable
private data class MxTokenBody(@SerialName("user_token") val userToken: String? = null)

@Serializable
private data class MxEnvelope(val header: MxHeader, val body: JsonElement? = null)

@Serializable
private data class MxTokenResponse(val message: MxEnvelope)

@Serializable
private data class MxTrack(
    @SerialName("track_id") val trackId: Long,
    @SerialName("track_name") val trackName: String,
    @SerialName("artist_name") val artistName: String,
    @SerialName("track_length") val trackLength: Int? = null,
)

@Serializable
private data class MxTrackContainer(val track: MxTrack)

@Serializable
private data class MxSearchBody(@SerialName("track_list") val trackList: List<MxTrackContainer> = emptyList())

@Serializable
private data class MxSearchResponse(val message: MxEnvelope)

@Serializable
private data class MxLyrics(@SerialName("lyrics_body") val lyricsBody: String? = null)

@Serializable
private data class MxLyricsBody(val lyrics: MxLyrics? = null)

@Serializable
private data class MxLyricsResponse(val message: MxEnvelope)

@Serializable
private data class MxRichSync(@SerialName("richsync_body") val body: String? = null)

@Serializable
private data class MxRichSyncBody(val richsync: MxRichSync? = null)

@Serializable
private data class MxRichSyncResponse(val message: MxEnvelope)

@Serializable
private data class MxRichLine(val ts: Double, val l: List<MxWord> = emptyList(), val x: String)

@Serializable
private data class MxWord(val c: String, val o: Double)

@Serializable
private data class MxSubtitle(@SerialName("subtitle_body") val body: String? = null)

@Serializable
private data class MxSubtitleBody(val subtitle: MxSubtitle? = null)

@Serializable
private data class MxSubtitleResponse(val message: MxEnvelope)

@Serializable
private data class MxSubtitleLine(val text: String, val time: MxSubtitleTime)

@Serializable
private data class MxSubtitleTime(val total: Double)

object MusixmatchLyricsProvider : LyricsProvider {
    override val name = "Musixmatch"

    private val secretCache = AtomicReference<String?>(null)
    private val tokenCache = AtomicReference<String?>(null)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }

    private val client by lazy {
        HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(json)
                json(json, ContentType.Text.Html)
                json(json, ContentType.Text.Plain)
            }
            install(ContentEncoding) { gzip(); deflate() }
        }
    }

    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableMusixmatchKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = runCatching {
        withTokenRetry {
            val secret = getSecret()
            val token = getUserToken(secret)
            val track = searchTrack(title, artist, duration, token, secret)
                ?: error("Musixmatch track not found")
            getRichSync(track.trackId, token, secret)
                ?: getSubtitle(track.trackId, token, secret)
                ?: getPlainLyrics(track.trackId, token, secret)
                ?: error("Musixmatch lyrics unavailable")
        }
    }

    override suspend fun getAllLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        callback: (String) -> Unit,
    ) {
        runCatching {
            withTokenRetry {
                val secret = getSecret()
                val token = getUserToken(secret)
                val track = searchTrack(title, artist, duration, token, secret) ?: return@withTokenRetry
                coroutineScope {
                    val rich = async { getRichSync(track.trackId, token, secret) }
                    val subtitle = async { getSubtitle(track.trackId, token, secret) }
                    val plain = async { getPlainLyrics(track.trackId, token, secret) }
                    rich.await()?.let(callback)
                    subtitle.await()?.let(callback)
                    plain.await()?.let(callback)
                }
            }
        }.onFailure { Timber.tag("Musixmatch").d(it, "Lyrics request failed") }
    }

    private suspend fun searchTrack(title: String, artist: String, duration: Int, token: String, secret: String): MxTrack? {
        if (title.isBlank() || artist.isBlank()) return null
        val url = MUSIXMATCH_BASE_URL + "track.search?app_id=web-desktop-app-v1.0&format=json" +
            "&q_track=${encode(title)}&q_artist=${encode(artist)}&f_has_lyrics=true&page_size=10&usertoken=$token"
        val response = client.get(sign(url, secret)) { commonHeaders() }.body<MxSearchResponse>()
        checkStatus(response.message.header.statusCode)
        val body = response.message.body as? JsonObject ?: return null
        val tracks = json.decodeFromJsonElement<MxSearchBody>(body).trackList.map { it.track }
        val normalizedTitle = clean(title)
        return tracks.minByOrNull { track ->
            val candidate = clean(track.trackName)
            val titleScore = when {
                candidate == normalizedTitle -> 0
                candidate.contains(normalizedTitle) || normalizedTitle.contains(candidate) -> 1
                else -> 2
            }
            val length = track.trackLength ?: 0
            val difference = if (duration > 0 && length > 0) abs(length - duration) else Int.MAX_VALUE
            (titleScore.toLong() shl 32) + difference
        }
    }

    private suspend fun getRichSync(trackId: Long, token: String, secret: String): String? {
        val url = MUSIXMATCH_BASE_URL + "track.richsync.get?app_id=web-desktop-app-v1.0&format=json&track_id=$trackId&usertoken=$token"
        val response = client.get(sign(url, secret)) { commonHeaders() }.body<MxRichSyncResponse>()
        checkStatus(response.message.header.statusCode)
        val body = response.message.body as? JsonObject ?: return null
        val richBody = json.decodeFromJsonElement<MxRichSyncBody>(body).richsync?.body ?: return null
        val lines = runCatching { json.decodeFromString<List<MxRichLine>>(richBody) }.getOrNull() ?: return null
        return lines.asSequence().filter { it.x.isNotBlank() }.joinToString(separator = "") { line ->
            val lineTime = formatTime(line.ts * 1000, false)
            val words = line.l.joinToString("") { word ->
                if (word.c.isBlank()) word.c else formatTime((line.ts + word.o) * 1000, true) + word.c
            }
            "$lineTime$words\n"
        }.takeIf { it.isNotBlank() }
    }

    private suspend fun getSubtitle(trackId: Long, token: String, secret: String): String? {
        val url = MUSIXMATCH_BASE_URL + "track.subtitle.get?app_id=web-desktop-app-v1.0&format=json&track_id=$trackId&usertoken=$token"
        val response = client.get(sign(url, secret)) { commonHeaders() }.body<MxSubtitleResponse>()
        checkStatus(response.message.header.statusCode)
        val body = response.message.body as? JsonObject ?: return null
        val subtitleBody = json.decodeFromJsonElement<MxSubtitleBody>(body).subtitle?.body ?: return null
        val lines = runCatching { json.decodeFromString<List<MxSubtitleLine>>(subtitleBody) }.getOrNull()
        return lines?.joinToString("") { "${formatTime(it.time.total * 1000, false)}${it.text}\n" }
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun getPlainLyrics(trackId: Long, token: String, secret: String): String? {
        val url = MUSIXMATCH_BASE_URL + "track.lyrics.get?app_id=web-desktop-app-v1.0&format=json&track_id=$trackId&usertoken=$token"
        val response = client.get(sign(url, secret)) { commonHeaders() }.body<MxLyricsResponse>()
        checkStatus(response.message.header.statusCode)
        val body = response.message.body as? JsonObject ?: return null
        return json.decodeFromJsonElement<MxLyricsBody>(body).lyrics?.lyricsBody?.takeIf { it.isNotBlank() }
    }

    private suspend fun getSecret(): String {
        secretCache.get()?.let { return it }
        val secret = runCatching {
            val html = client.get("https://www.musixmatch.com/search") {
                header("User-Agent", MUSIXMATCH_USER_AGENT)
                header("Cookie", "mxm_bab=AB")
                header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            }.body<String>()
            val path = Regex("src=\"([^\"]*/_next/static/chunks/pages/_app-[^\"]+\\.js)\"").find(html)?.groupValues?.get(1)
                ?: error("Musixmatch app bundle not found")
            val js = client.get(if (path.startsWith("http")) path else "https://www.musixmatch.com$path") {
                header("User-Agent", MUSIXMATCH_USER_AGENT)
            }.body<String>()
            val encoded = Regex("from\\(\\s*\"(.*?)\"\\s*\\.split").find(js)?.groupValues?.get(1)
                ?: error("Musixmatch secret not found")
            String(Base64.getDecoder().decode(encoded.reversed()), StandardCharsets.UTF_8)
        }.getOrElse {
            Timber.tag("Musixmatch").w(it, "Dynamic secret failed; using fallback")
            "b3dc8788299f5806a70a6a20a0cb0ffc"
        }
        secretCache.set(secret)
        return secret
    }

    private suspend fun getUserToken(secret: String): String {
        tokenCache.get()?.let { return it }
        val url = sign(MUSIXMATCH_BASE_URL + "token.get?app_id=web-desktop-app-v1.0&format=json", secret)
        val response = client.get(url) { commonHeaders() }.body<MxTokenResponse>()
        val body = response.message.body as? JsonObject ?: error("Musixmatch token body missing")
        val token = json.decodeFromJsonElement<MxTokenBody>(body).userToken
            ?: error("Musixmatch user token missing")
        tokenCache.set(token)
        return token
    }

    private suspend fun <T> withTokenRetry(action: suspend () -> T): T = try {
        action()
    } catch (e: TokenExpiredException) {
        tokenCache.set(null)
        secretCache.set(null)
        action()
    }

    private fun checkStatus(status: Int) {
        if (status == 401 || status == 402) throw TokenExpiredException("Musixmatch unauthorized: $status")
    }

    private fun sign(url: String, secret: String): String {
        val normalized = url.replace("%20", "+").replace(" ", "+")
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        }
        val signature = Base64.getEncoder().encodeToString(mac.doFinal((normalized + date).toByteArray(StandardCharsets.UTF_8)))
        return "$normalized&signature=${encode(signature)}&signature_protocol=sha256"
    }

    private fun io.ktor.client.request.HttpRequestBuilder.commonHeaders() {
        header("User-Agent", MUSIXMATCH_USER_AGENT)
        header("Accept", "application/json, text/plain, */*")
        header("Accept-Language", "en-US,en;q=0.9")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun clean(value: String): String = value
        .replace(',', ' ')
        .replace('&', ' ')
        .replace(Regex("\\(.*?\\)|\\[.*?\\]"), "")
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9 ]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun formatTime(milliseconds: Double, syllable: Boolean): String {
        val total = milliseconds.toLong()
        val minutes = total / 60_000
        val seconds = (total / 1_000) % 60
        val millis = total % 1_000
        return String.format(Locale.US, "%s%02d:%02d.%03d%s", if (syllable) "<" else "[", minutes, seconds, millis, if (syllable) ">" else "]")
    }

    private class TokenExpiredException(message: String) : Exception(message)
}
