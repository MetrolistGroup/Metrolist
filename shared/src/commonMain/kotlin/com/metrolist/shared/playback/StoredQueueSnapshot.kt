package com.metrolist.shared.playback

import com.metrolist.shared.model.SongItem
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object StoredQueueSnapshotSerializer : KSerializer<MusicQueueSnapshot> {
    override val descriptor: SerialDescriptor = StoredQueueSnapshot.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: MusicQueueSnapshot,
    ) {
        val queue = if (value.originalIndices.size == value.displayedItems.size) value else value.forRestore(true)
        val songs = queue.originalItems.distinct()
        val indices = songs.withIndex().associate { it.value to it.index }
        encoder.encodeSerializableValue(
            StoredQueueSnapshot.serializer(),
            StoredQueueSnapshot(
                songs = songs,
                occurrences = queue.originalItems.map(indices::getValue),
                originalIndices = queue.originalIndices,
                currentIndex = queue.currentIndex,
                isShuffled = queue.isShuffled,
                recommendationsSongsOnly = queue.recommendationsSongsOnly,
            ),
        )
    }

    override fun deserialize(decoder: Decoder): MusicQueueSnapshot {
        val stored = decoder.decodeSerializableValue(StoredQueueSnapshot.serializer())
        val canonical = stored.songs?.let { songs -> stored.occurrences.map(songs::get) } ?: stored.originalItems
        val displayed = if (stored.songs != null) stored.originalIndices.map(canonical::get) else stored.displayedItems
        return MusicQueueSnapshot(
            displayed,
            canonical,
            stored.currentIndex,
            stored.isShuffled,
            stored.originalIndices,
            stored.recommendationsSongsOnly,
        )
    }
}

@Serializable
private data class StoredQueueSnapshot(
    val songs: List<SongItem>? = null,
    val occurrences: List<Int> = emptyList(),
    val originalIndices: List<Int> = emptyList(),
    val currentIndex: Int = -1,
    val isShuffled: Boolean = false,
    val recommendationsSongsOnly: Boolean = false,
    val displayedItems: List<SongItem> = emptyList(),
    val originalItems: List<SongItem> = emptyList(),
)

fun MusicQueueSnapshot.hasSameStructure(other: MusicQueueSnapshot): Boolean =
    isShuffled == other.isShuffled && recommendationsSongsOnly == other.recommendationsSongsOnly &&
        (displayedItems === other.displayedItems || displayedItems == other.displayedItems) &&
        (originalItems === other.originalItems || originalItems == other.originalItems) &&
        (originalIndices === other.originalIndices || originalIndices == other.originalIndices)
