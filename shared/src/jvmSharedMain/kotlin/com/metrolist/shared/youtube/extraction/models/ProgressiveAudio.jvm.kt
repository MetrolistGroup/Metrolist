package com.metrolist.shared.youtube.extraction.models

// ExoPlayer and the desktop FFmpeg decoder skip the video track unless a video surface is attached.
internal actual val playbackPlaysProgressiveAudio: Boolean = true
