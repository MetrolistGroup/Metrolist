package com.metrolist.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal data class SongDisplayMetadata(
    val canonicalTitle: String,
    val canonicalArtists: List<ArtistTiny>,
    val customTitle: String?,
    val customArtist: String?,
    val replacedCanonicalTitle: String? = null,
    val replacedCanonicalArtists: List<ArtistTiny>? = null,
)

object SongMetadataState {
    private data class MetadataSnapshot(
        val loaded: Boolean = false,
        val values: Map<String, SongDisplayMetadata> = emptyMap(),
        val durations: Map<String, Long> = emptyMap(),
    )

    private var snapshot by mutableStateOf(MetadataSnapshot())
    private val snapshotLock = SynchronizedObject()
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()
    private var database: MusicDatabase? = null
    private var collectionJob: Job? = null
    private var bindingGeneration = 0L

    fun bind(
        database: MusicDatabase,
        scope: CoroutineScope,
    ) {
        if (this.database === database && collectionJob?.isActive == true) return
        collectionJob?.cancel()
        val generation =
            synchronized(snapshotLock) {
                this.database = database
                snapshot = MetadataSnapshot()
                ++bindingGeneration
            }
        collectionJob =
            scope.launch {
                combine(
                    database.dao().allSongsWithCustomMetadata().displayMetadataChanges(),
                    database
                        .dao()
                        .knownSongDurations()
                        .distinctUntilChanged()
                        .map { rows -> rows.associate { it.id to it.duration } }
                        .distinctUntilChanged(),
                ) { metadata, durations -> metadata to durations }
                    .collect { (metadata, durations) ->
                        synchronized(snapshotLock) {
                            if (generation != bindingGeneration || this@SongMetadataState.database !== database) return@synchronized
                            val reset = snapshot.values.mapValues { (_, value) -> value.copy(customTitle = null, customArtist = null) }
                            val updated = MetadataSnapshot(loaded = true, values = reset + metadata, durations = durations)
                            if (snapshot != updated) {
                                snapshot = updated
                                _revision.value++
                            }
                        }
                    }
            }
        collectionJob?.invokeOnCompletion {
            synchronized(snapshotLock) {
                if (generation == bindingGeneration && this.database === database) {
                    this.database = null
                    snapshot = MetadataSnapshot()
                    _revision.value++
                }
            }
        }
    }

    fun publish(
        database: MusicDatabase,
        song: SongItem,
        replacedSong: SongItem? = null,
    ) {
        synchronized(snapshotLock) {
            if (this.database !== database) return@synchronized
            val previous =
                snapshot.values[song.id]
                    ?: replacedSong?.embeddedMetadata(replacedSong.customTitle, replacedSong.customArtist)
            val metadata = song.embeddedMetadata(song.customTitle, song.customArtist)
            val published =
                if (metadata.customTitle == null && metadata.customArtist == null && previous != null) {
                    val canonicalChanged =
                        previous.canonicalTitle != metadata.canonicalTitle ||
                            previous.canonicalArtists != metadata.canonicalArtists
                    metadata.copy(
                        replacedCanonicalTitle = if (canonicalChanged) previous.canonicalTitle else previous.replacedCanonicalTitle,
                        replacedCanonicalArtists = if (canonicalChanged) previous.canonicalArtists else previous.replacedCanonicalArtists,
                    )
                } else {
                    metadata
                }
            snapshot = snapshot.copy(values = snapshot.values + (song.id to published))
            _revision.value++
        }
    }

    fun apply(song: SongItem): SongItem {
        val current = synchronized(snapshotLock) { snapshot }
        val metadata =
            current.values[song.id]
                ?: song.embeddedMetadata(
                    customTitle = song.customTitle.takeUnless { current.loaded },
                    customArtist = song.customArtist.takeUnless { current.loaded },
                )
        val displayed = song.withStoredMetadata(metadata)
        val duration = song.duration?.takeIf { it > 0L } ?: current.durations[song.id]
        return if (displayed.duration == duration) displayed else displayed.copy(duration = duration)
    }
}

internal fun Flow<List<Song>>.displayMetadataChanges(): Flow<Map<String, SongDisplayMetadata>> =
    map { rows -> rows.associate { it.id to it.displayMetadata() } }.distinctUntilChanged()

private fun Song.displayMetadata(): SongDisplayMetadata =
    SongDisplayMetadata(
        canonicalTitle = song.title,
        canonicalArtists =
            orderedArtists.map { artist ->
                ArtistTiny(
                    id = artist.id.takeUnless { artist.isLocal },
                    name = artist.name,
                    thumbnail = artist.thumbnailUrl,
                )
            },
        customTitle = song.customTitle,
        customArtist = song.customArtist,
    )

private fun SongItem.embeddedMetadata(
    customTitle: String?,
    customArtist: String?,
): SongDisplayMetadata =
    SongDisplayMetadata(
        canonicalTitle = canonicalTitle ?: title,
        canonicalArtists = canonicalArtists ?: artists,
        customTitle = customTitle,
        customArtist = customArtist,
    )

internal fun SongItem.withStoredMetadata(metadata: SongDisplayMetadata): SongItem {
    if (metadata.customTitle == null && metadata.customArtist == null) {
        val incoming = embeddedMetadata(customTitle = null, customArtist = null)
        val isReplacedPayload =
            incoming.canonicalTitle == metadata.replacedCanonicalTitle &&
                incoming.canonicalArtists == metadata.replacedCanonicalArtists
        if (!isReplacedPayload) return withCustomMetadata(incoming)
    }
    return withCustomMetadata(metadata)
}

internal fun SongItem.withCustomMetadata(metadata: SongDisplayMetadata): SongItem {
    val artistName = metadata.customArtist?.takeIf(String::isNotBlank)
    val title = metadata.customTitle?.takeIf(String::isNotBlank) ?: metadata.canonicalTitle
    val artists =
        artistName?.let { name ->
            listOf(metadata.canonicalArtists.firstOrNull()?.copy(name = name) ?: ArtistTiny(null, name))
        } ?: metadata.canonicalArtists
    if (this.title == title && this.artists == artists && customTitle == metadata.customTitle &&
        customArtist == metadata.customArtist && canonicalTitle == metadata.canonicalTitle &&
        canonicalArtists == metadata.canonicalArtists
    ) {
        return this
    }
    return copy(
        title = title,
        artists = artists,
        customTitle = metadata.customTitle,
        customArtist = metadata.customArtist,
        canonicalTitle = metadata.canonicalTitle,
        canonicalArtists = metadata.canonicalArtists,
    )
}
