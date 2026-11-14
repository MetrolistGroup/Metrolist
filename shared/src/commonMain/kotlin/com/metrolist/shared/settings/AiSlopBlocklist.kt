package com.metrolist.shared.settings

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.state.GlobalYouTube
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlin.time.Clock

object AiSlopBlocklist {
    const val CONTRIBUTION_URL = "https://github.com/Override92/AiSList"
    private const val SOURCE_URL = "https://raw.githubusercontent.com/Override92/AiSList/main/AiSList/aislist_blocklist.txt"
    private const val MAX_RESPONSE_CHARACTERS = 524_288
    private const val REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1_000L

    private val channelIdPattern = Regex("UC[A-Za-z0-9_-]{20,24}")

    private var entries: Set<String> = parse(Settings.shared.aiSlopBlocklistCache)

    suspend fun refresh(force: Boolean = false) {
        if (!Settings.shared.blockAiSlopArtists) return
        val now = Clock.System.now().toEpochMilliseconds()
        if (!force && entries.isNotEmpty() && now - Settings.shared.aiSlopBlocklistUpdatedAt < REFRESH_INTERVAL_MS) return
        runCatching {
            val response = GlobalYouTube.client.get(SOURCE_URL)
            check(response.status.isSuccess()) { "AI blocklist request failed (${response.status.value})" }
            val declaredSize = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            check(declaredSize == null || declaredSize <= MAX_RESPONSE_CHARACTERS)
            val body = response.bodyAsText()
            check(body.length <= MAX_RESPONSE_CHARACTERS)
            val parsed = parse(body)
            check(parsed.isNotEmpty())
            Settings.shared.aiSlopBlocklistCache = body
            Settings.shared.aiSlopBlocklistUpdatedAt = now
            entries = parsed
            BlockedContent.notifyChanged()
        }
    }

    fun matches(item: YTItem): Boolean {
        if (!Settings.shared.blockAiSlopArtists || entries.isEmpty()) return false
        return when (item) {
            is SongItem -> item.artists.any { matches(it.id, it.name) }
            is AlbumItem -> item.artists.any { matches(it.id, it.name) }
            is ArtistItem -> matches(item.id, item.title)
            is EpisodeItem -> matches(item.author?.id, item.author?.name)
            is PlaylistItem -> matches(null, item.author)
            else -> false
        }
    }

    internal fun snapshot(): AiSlopBlockSnapshot =
        AiSlopBlockSnapshot(
            enabled = Settings.shared.blockAiSlopArtists,
            entries = entries,
        )

    private fun matches(
        channelId: String?,
        name: String?,
    ): Boolean = channelId?.lowercase() in entries || name?.trim()?.lowercase()?.let { "@$it" } in entries

    internal fun parse(text: String): Set<String> =
        text
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith("@") || it.matches(channelIdPattern) }
            .map(String::lowercase)
            .toSet()

    internal fun replaceForTest(values: Set<String>) {
        entries = values.map(String::lowercase).toSet()
    }
}

internal data class AiSlopBlockSnapshot(
    private val enabled: Boolean,
    private val entries: Set<String>,
) {
    fun matches(song: SongItem): Boolean =
        enabled &&
            entries.isNotEmpty() &&
            song.artists.any { artist ->
                artist.id?.lowercase() in entries ||
                    artist.name
                        .trim()
                        .lowercase()
                        .let { "@$it" } in entries
            }
}
