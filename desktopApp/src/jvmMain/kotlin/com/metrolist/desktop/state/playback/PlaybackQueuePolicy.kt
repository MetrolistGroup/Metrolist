package com.metrolist.desktop.state.playback

import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue

internal enum class PlaybackQueueAction {
    REPLAY_CURRENT,
    PLAY_NEXT,
    RESTART_QUEUE,
    AUTOPLAY,
    STOP,
}

internal data class PlaybackQueueDecision(
    val action: PlaybackQueueAction,
    val song: SongItem? = null,
)

internal fun PlaybackQueueAction.replacesCastMedia(): Boolean =
    this == PlaybackQueueAction.REPLAY_CURRENT ||
        this == PlaybackQueueAction.PLAY_NEXT ||
        this == PlaybackQueueAction.RESTART_QUEUE

internal fun decidePlaybackQueueTransition(
    repeatMode: RepeatMode,
    fromEof: Boolean,
    currentTrack: SongItem?,
    queueItems: List<SongItem>,
    currentIndex: Int,
    repeatCurrentAttempted: Boolean = false,
    autoLoadMore: Boolean = true,
): PlaybackQueueDecision {
    if (repeatMode == RepeatMode.ONE && fromEof && currentTrack != null && !repeatCurrentAttempted) {
        return PlaybackQueueDecision(PlaybackQueueAction.REPLAY_CURRENT, currentTrack)
    }

    queueItems.getOrNull(currentIndex + 1)?.let { next ->
        return PlaybackQueueDecision(PlaybackQueueAction.PLAY_NEXT, next)
    }

    if (repeatMode == RepeatMode.ALL && queueItems.isNotEmpty()) {
        return PlaybackQueueDecision(PlaybackQueueAction.RESTART_QUEUE, queueItems.first())
    }

    return if (autoLoadMore && repeatMode == RepeatMode.OFF) {
        PlaybackQueueDecision(PlaybackQueueAction.AUTOPLAY)
    } else {
        PlaybackQueueDecision(PlaybackQueueAction.STOP)
    }
}

internal suspend fun <T : Any> resolveNextQueueOccurrence(
    queue: MusicQueue,
    isCurrent: () -> Boolean,
    resolve: suspend (SongItem) -> T?,
): Pair<SongItem, T?>? {
    while (isCurrent()) {
        val state = queue.state.value
        val target = state.entries.getOrNull(state.currentIndex + 1) ?: return null
        val resource = resolve(target.song)
        if (!isCurrent()) return null
        val latest = queue.state.value
        if (latest.entries.getOrNull(latest.currentIndex + 1)?.key != target.key) continue
        val selected = queue.getNext() ?: return null
        return selected to resource
    }
    return null
}
