package com.metrolist.shared.api

class PlaylistNotFoundException(
    val playlistId: String,
) : Exception()
