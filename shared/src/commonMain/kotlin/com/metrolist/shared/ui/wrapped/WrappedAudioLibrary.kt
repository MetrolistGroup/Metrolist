package com.metrolist.shared.ui.wrapped

import com.metrolist.shared.model.SongItem

// Files belong only to this recap session and never become library downloads or playback history.
internal interface WrappedAudioLibrary {
    suspend fun prepare(song: SongItem): String

    /** Claims audio output once regular playback is saved and paused; downloads run before this. */
    fun activate() {}

    fun close()
}

internal expect fun createWrappedAudioLibrary(onInterrupted: () -> Unit): WrappedAudioLibrary

internal const val WRAPPED_AUDIO_FILE_LIMIT = 32L * 1024 * 1024
internal const val WRAPPED_AUDIO_RESERVE = 16L * 1024 * 1024
