package com.metrolist.shared.settings

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.isNonMusicalVideo
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.isPodcastBrowseId
import com.metrolist.shared.model.isVideoSong
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object BlockedContent {
    private val json = Json { ignoreUnknownKeys = true }
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    internal fun notifyChanged() {
        _version.value++
    }

    private fun csvSet(value: String): Set<String> =
        value
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private data class BlockSources(
        val songs: String,
        val artists: String,
        val songIds: String,
        val dislikedIds: String,
        val artistIds: String,
    )

    private class BlockLists(
        val sources: BlockSources,
    ) {
        val storedSongs =
            try {
                json.decodeFromString<List<BlockedSong>>(sources.songs)
            } catch (_: Exception) {
                emptyList()
            }
        val storedArtists =
            try {
                json.decodeFromString<List<BlockedArtist>>(sources.artists)
            } catch (_: Exception) {
                emptyList()
            }
        val songIds = csvSet(sources.songIds) + csvSet(sources.dislikedIds) + storedSongs.map { it.id }
        val artistIds = csvSet(sources.artistIds) + storedArtists.map { it.id }
        val songs = storedSongs + (songIds - storedSongs.mapTo(mutableSetOf()) { it.id }).map { BlockedSong(it, it) }
        val artists = storedArtists + (artistIds - storedArtists.mapTo(mutableSetOf()) { it.id }).map { BlockedArtist(it, it) }
        val artistNames = artists.map { it.name.normalizedArtistName() }.filter(String::isNotBlank).toSet()
    }

    private val cacheLock = SynchronizedObject()
    private var cachedLists: BlockLists? = null

    private fun lists(): BlockLists {
        val settings = Settings.shared
        val sources =
            BlockSources(
                settings.blockedSongsJson,
                settings.blockedArtistsJson,
                settings.blockedSongIds,
                settings.dislikedSongIds,
                settings.blockedArtistIds,
            )
        return synchronized(cacheLock) {
            cachedLists?.takeIf { it.sources == sources } ?: BlockLists(sources).also { cachedLists = it }
        }
    }

    private fun storedSongs(): List<BlockedSong> = lists().storedSongs

    fun songs(): List<BlockedSong> = lists().songs

    fun artists(): List<BlockedArtist> = lists().artists

    fun songIds(): Set<String> = lists().songIds

    fun artistIds(): Set<String> = lists().artistIds

    fun isSongBlocked(songId: String?): Boolean = !songId.isNullOrBlank() && songId in songIds()

    fun isArtistBlocked(artistId: String?): Boolean = !artistId.isNullOrBlank() && artistId in artistIds()

    fun isBlocked(song: SongItem): Boolean = snapshot().isBlocked(song)

    fun isBlocked(item: YTItem): Boolean = isBlocked(item, snapshot())

    fun isBlocked(
        item: YTItem,
        snapshot: SongBlockSnapshot,
    ): Boolean =
        when (item) {
            is SongItem -> {
                snapshot.isBlocked(item)
            }

            is ArtistItem -> {
                snapshot.isArtistBlocked(ArtistTiny(item.id, item.title)) || AiSlopBlocklist.matches(item)
            }

            is AlbumItem -> {
                item.artists.any(snapshot::isArtistBlocked) ||
                    (!Settings.shared.explicitOnly && Settings.shared.hideExplicit && item.isExplicit) ||
                    (Settings.shared.explicitOnly && !item.isExplicit) ||
                    AiSlopBlocklist.matches(item)
            }

            else -> {
                (Settings.shared.hidePodcasts && item.isPodcast) || AiSlopBlocklist.matches(item)
            }
        }

    fun filterSongs(
        songs: List<SongItem>,
        snapshot: SongBlockSnapshot = snapshot(),
    ): List<SongItem> = songs.filterNot(snapshot::isBlocked)

    fun filterPlayableSongs(
        songs: List<SongItem>,
        snapshot: SongBlockSnapshot = snapshot(),
    ): List<SongItem> = songs.filterNot { it.isUnavailable || snapshot.isBlocked(it) }

    fun filterItems(
        items: List<YTItem>,
        snapshot: SongBlockSnapshot = snapshot(),
    ): List<YTItem> = items.filterNot { isBlocked(it, snapshot) }.let { if (it.size == items.size) items else it }

    suspend fun refreshAiSlopBlocklist(force: Boolean = false) {
        AiSlopBlocklist.refresh(force)
    }

    fun filterItemSections(
        sections: Map<String, List<YTItem>>,
        snapshot: SongBlockSnapshot = snapshot(),
    ): Map<String, List<YTItem>> =
        sections
            .mapNotNull { (title, items) ->
                if (title == "header") {
                    title to items
                } else {
                    val filteredItems = filterItems(items, snapshot)
                    if (filteredItems.isEmpty()) null else title to filteredItems
                }
            }.toMap()

    fun filterSections(
        sections: List<HomeSection>,
        snapshot: SongBlockSnapshot = snapshot(),
    ): List<HomeSection> =
        sections
            .mapNotNull { section ->
                if (Settings.shared.hidePodcasts && section.browseId.isPodcastBrowseId()) return@mapNotNull null
                val items = filterItems(section.items, snapshot)
                if (items.isEmpty() && section.continuation == null) null else section.copy(items = items)
            }

    fun filterAlbumPage(page: AlbumPage): AlbumPage =
        page.copy(
            songs = filterSongs(page.songs),
            otherVersions = filterItems(page.otherVersions).filterIsInstance<AlbumItem>(),
            artistAlbums = filterItems(page.artistAlbums).filterIsInstance<AlbumItem>(),
        )

    fun filterArtistPage(page: ArtistPageResult): ArtistPageResult = page.copy(sections = filterItemSections(page.sections))

    fun contentFiltersChanged() {
        _version.value += 1
    }

    fun rememberDislikedSong(song: SongItem) {
        if (song.id.isBlank()) return
        val blockedSong =
            BlockedSong(
                id = song.id,
                title = song.title.ifBlank { song.id },
                artistNames = song.artists.map { it.name }.filter { it.isNotBlank() },
                thumbnail = song.thumbnail,
            )
        saveSongs(listOf(blockedSong) + songs().filterNot { it.id == song.id })
    }

    fun unblockSong(songId: String) {
        if (songId.isBlank()) return
        saveSongs(storedSongs().filterNot { it.id == songId })
        Settings.shared.setSongBlocked(songId, false)
    }

    fun blockArtist(artist: ArtistTiny) {
        val id = artist.id ?: return
        blockArtist(id = id, name = artist.name, thumbnail = artist.thumbnail)
    }

    fun blockArtist(
        id: String,
        name: String,
        thumbnail: String? = null,
    ) {
        if (id.isBlank()) return
        val blockedArtist =
            BlockedArtist(
                id = id,
                name = name.ifBlank { id },
                thumbnail = thumbnail,
            )
        saveArtists(listOf(blockedArtist) + artists().filterNot { it.id == id })
    }

    fun unblockArtist(artistId: String) {
        if (artistId.isBlank()) return
        saveArtists(artists().filterNot { it.id == artistId })
    }

    private fun saveSongs(songs: List<BlockedSong>) {
        val distinct = songs.distinctBy { it.id }
        Settings.shared.blockedSongsJson = json.encodeToString(distinct)
        Settings.shared.blockedSongIds = distinct.joinToString(",") { it.id }
        _version.value += 1
    }

    private fun saveArtists(artists: List<BlockedArtist>) {
        val distinct = artists.distinctBy { it.id }
        Settings.shared.blockedArtistsJson = json.encodeToString(distinct)
        Settings.shared.blockedArtistIds = distinct.joinToString(",") { it.id }
        _version.value += 1
    }

    fun snapshot(): SongBlockSnapshot {
        val lists = lists()
        return SongBlockSnapshot(
            songIds = lists.songIds,
            artistIds = lists.artistIds,
            artistNames = lists.artistNames,
            explicitOnly = Settings.shared.explicitOnly,
            hideExplicit = Settings.shared.hideExplicit,
            hideVideoSongs = Settings.shared.hideVideoSongs,
            hideNonMusicalVideos = Settings.shared.hideNonMusicalVideos,
            hidePodcasts = Settings.shared.hidePodcasts,
            hideShortSongs = Settings.shared.hideShortSongs,
            minimumSongDurationSeconds = Settings.shared.minimumSongDurationSeconds.toLong(),
            aiSlopBlocklist = AiSlopBlocklist.snapshot(),
        )
    }
}

@ConsistentCopyVisibility
data class SongBlockSnapshot internal constructor(
    private val songIds: Set<String>,
    private val artistIds: Set<String>,
    private val artistNames: Set<String>,
    private val explicitOnly: Boolean,
    private val hideExplicit: Boolean,
    private val hideVideoSongs: Boolean,
    private val hideNonMusicalVideos: Boolean,
    private val hidePodcasts: Boolean,
    private val hideShortSongs: Boolean,
    private val minimumSongDurationSeconds: Long,
    private val aiSlopBlocklist: AiSlopBlockSnapshot,
) {
    internal fun isArtistBlocked(artist: ArtistTiny): Boolean = artist.isBlockedBy(artistIds, artistNames)

    fun isBlocked(song: SongItem): Boolean {
        if (song.isUnavailable) return false
        return song.id in songIds ||
            song.artists.any { it.isBlockedBy(artistIds, artistNames) } ||
            (!explicitOnly && hideExplicit && song.isExplicit) ||
            (explicitOnly && !song.isExplicit) ||
            (hideVideoSongs && song.isVideoSong) ||
            (hideNonMusicalVideos && song.isNonMusicalVideo) ||
            (hidePodcasts && song.isPodcast) ||
            (
                hideShortSongs &&
                    song.duration?.let { duration ->
                        duration > 0L && duration < minimumSongDurationSeconds
                    } == true
            ) || aiSlopBlocklist.matches(song)
    }
}

@Serializable
data class BlockedSong(
    val id: String,
    val title: String,
    val artistNames: List<String> = emptyList(),
    val thumbnail: String? = null,
)

@Serializable
data class BlockedArtist(
    val id: String,
    val name: String,
    val thumbnail: String? = null,
)

private fun ArtistTiny.isBlockedBy(
    artistIds: Set<String>,
    artistNames: Set<String>,
): Boolean {
    val id = id?.takeIf(String::isNotBlank)
    return if (id != null) id in artistIds else name.normalizedArtistName() in artistNames
}

private fun String.normalizedArtistName(): String = trim().lowercase()
