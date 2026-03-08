package com.metrolist.shared.viewmodels

import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalInnerTube
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

private const val MAX_CACHED_ARTISTS = 50
private val REFRESH_INTERVAL = 24.hours

@Serializable
internal data class CachedLastFmArtist(
    val data: LastFmArtistData,
    val fetchedAtMillis: Long = 0,
    val language: String = "",
)

class LastFmArtistCache internal constructor(
    private val read: () -> String,
    private val write: (String) -> Unit,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val contentLanguage: () -> String = LastFM::contentLanguage,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val entries =
        MutableStateFlow(
            runCatching { json.decodeFromString<Map<String, CachedLastFmArtist>>(read()) }.getOrDefault(emptyMap()),
        )

    private val refreshLock = Mutex()

    internal fun currentLanguage(): String = contentLanguage()

    fun get(artistId: String): LastFmArtistData? = entries.value[artistId]?.current()

    fun artist(artistId: String): Flow<LastFmArtistData?> = entries.map { it[artistId]?.current() }.distinctUntilChanged()

    private fun CachedLastFmArtist.current() = if (language == contentLanguage()) data else data.copy(summary = null)

    suspend fun refresh(
        artistId: String,
        artistName: String,
        catalogTitles: List<String>,
        client: HttpClient = GlobalInnerTube.client,
    ) = withContext(dispatcher) {
        refreshLock.withLock {
            val cached = entries.value[artistId]
            val language = contentLanguage()
            val isRecent =
                cached != null && cached.data.photos.isNotEmpty() && cached.fetchedAtMillis > 0 &&
                    now() - cached.fetchedAtMillis < REFRESH_INTERVAL.inWholeMilliseconds
            if (isRecent && cached.language == language) return@withLock
            val matched = LastFM.getMatchedArtist(client, artistName, catalogTitles, language) ?: return@withLock
            val audience = LastFM.parseArtistAudience(matched.info, matched.page)
            val fallbackPhotos = listOfNotNull(LastFM.parseArtistImage(matched.info), matched.page?.avatarUrl)

            fun toPhotos(vararg sources: List<String>) =
                mergeLastFmArtistPhotos(*sources).map { ArtistPhoto(it, LastFM.photoPageUrl(artistName, it)) }
            val metadata =
                LastFmArtistData(
                    photos =
                        cached
                            ?.data
                            ?.photos
                            .orEmpty()
                            .ifEmpty { toPhotos(fallbackPhotos) },
                    tags = audience.genres,
                    summary = LastFM.parseArtistBio(matched.info, matched.page, language),
                    listeners = audience.listeners,
                    scrobbles = audience.scrobbles,
                    similarArtists = audience.similarArtists,
                )
            store(artistId, metadata, 0, language)
            val photos = LastFM.getArtistPhotos(client, artistName, language)
            if (photos.isNotEmpty()) {
                store(artistId, metadata.copy(photos = toPhotos(photos, fallbackPhotos)), now(), language)
            }
        }
    }

    private fun store(
        artistId: String,
        data: LastFmArtistData,
        fetchedAtMillis: Long,
        language: String,
    ) {
        val entry = CachedLastFmArtist(data, fetchedAtMillis, language)
        if (entries.value[artistId] == entry) return
        entries.update { current ->
            (current - artistId + (artistId to entry)).toList().takeLast(MAX_CACHED_ARTISTS).toMap()
        }
        write(json.encodeToString(entries.value))
    }

    companion object {
        val shared by lazy {
            LastFmArtistCache(
                read = { Settings.shared.lastFmArtistCacheJson },
                write = { Settings.shared.lastFmArtistCacheJson = it },
            )
        }
    }
}
