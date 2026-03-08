package com.metrolist.shared.playback

import com.metrolist.shared.model.SongItem

const val QUEUE_SOURCE_ADDED_MANUALLY = "metrolist:queue-source:added-manually"
const val QUEUE_SOURCE_AUTO_MIX = "metrolist:queue-source:auto-mix"
const val QUEUE_SOURCE_RECOMMENDED = "metrolist:queue-source:recommended"

fun String?.isRecommendedQueueSource(): Boolean = this == QUEUE_SOURCE_RECOMMENDED || this == "Recommended"

fun String?.isAutoMixQueueSource(): Boolean = this == QUEUE_SOURCE_AUTO_MIX

fun String?.isRecommendationGeneratedQueueSource(): Boolean = isRecommendedQueueSource() || isAutoMixQueueSource()

fun SongItem.asManuallyAddedQueueItem(): SongItem =
    copy(
        queueSourceTitle =
            queueSourceTitle
                ?.takeUnless(String::isRecommendationGeneratedQueueSource)
                ?: QUEUE_SOURCE_ADDED_MANUALLY,
    )
