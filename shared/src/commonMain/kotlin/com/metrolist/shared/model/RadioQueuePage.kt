package com.metrolist.shared.model

import kotlin.random.Random

data class RadioQueuePage(
    val songs: List<SongItem>,
    val chips: List<RadioQueueChip>,
    val continuation: String? = null,
)

data class RadioQueueChip(
    val id: String,
    val title: String,
    val playlistId: String,
    val params: String?,
    val isSelected: Boolean,
)

fun List<RadioQueueChip>.selectRadioQueueChip(selectedChipId: String): List<RadioQueueChip> =
    map { chip -> chip.copy(isSelected = chip.id == selectedChipId) }

fun varyRadioRecommendations(
    songs: List<SongItem>,
    classic: Boolean,
    random: Random = Random.Default,
): List<SongItem> = if (classic) songs else songs.shuffled(random)

fun buildRadioQueueRecommendations(
    songs: List<SongItem>,
    queuedSongIds: Set<String>,
    generatedQueueSourceTitle: String,
    isBlocked: (SongItem) -> Boolean,
    songsOnly: Boolean = false,
): List<SongItem> =
    songs
        .asSequence()
        .filter { it.id.isNotBlank() }
        .filterNot { songsOnly && (it.isVideoSong || it.isPodcast) }
        .filterNot(isBlocked)
        .filterNot { it.id in queuedSongIds }
        .distinctBy(SongItem::id)
        .map { song ->
            if (song.queueSourceTitle == null) {
                song.copy(queueSourceTitle = generatedQueueSourceTitle)
            } else {
                song
            }
        }.toList()
