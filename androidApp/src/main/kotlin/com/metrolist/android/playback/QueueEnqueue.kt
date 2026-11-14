package com.metrolist.android.playback

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.QUEUE_SOURCE_ADDED_MANUALLY
import com.metrolist.shared.playback.isRecommendationGeneratedQueueSource

internal fun enqueueSongs(
    queue: MusicQueue,
    songs: List<SongItem>,
    playNext: Boolean,
    preventDuplicates: Boolean,
) {
    val additions = if (preventDuplicates && !playNext) songs.asReversed().distinctBy { it.id }.asReversed() else songs
    val prepared =
        additions.map { song ->
            if (playNext && song.queueSourceTitle.isRecommendationGeneratedQueueSource()) {
                song.copy(queueSourceTitle = QUEUE_SOURCE_ADDED_MANUALLY)
            } else if (!playNext && !song.queueSourceTitle.isRecommendationGeneratedQueueSource()) {
                song.copy(queueSourceTitle = song.queueSourceTitle ?: QUEUE_SOURCE_ADDED_MANUALLY)
            } else {
                song
            }
        }
    if (playNext) {
        queue.playNext(prepared, preventDuplicates)
    } else {
        val (recommended, manual) = prepared.partition { it.queueSourceTitle.isRecommendationGeneratedQueueSource() }
        if (preventDuplicates) queue.removeSongs(prepared.mapTo(mutableSetOf()) { it.id }, keepCurrent = true)
        val insertIndex =
            queue.items.value.indices.firstOrNull {
                it > queue.currentIndex.value &&
                    queue.items.value[it]
                        .queueSourceTitle
                        .isRecommendationGeneratedQueueSource()
            }
        queue.addToQueue(manual, preventDuplicates, insertIndex)
        queue.addToQueue(recommended, preventDuplicates)
    }
}
