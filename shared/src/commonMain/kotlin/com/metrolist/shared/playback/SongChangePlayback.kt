package com.metrolist.shared.playback

fun shouldPlayAfterSongChange(
    keepPaused: Boolean,
    hasCurrentSong: Boolean,
    playbackRequested: Boolean,
): Boolean = !keepPaused || !hasCurrentSong || playbackRequested
