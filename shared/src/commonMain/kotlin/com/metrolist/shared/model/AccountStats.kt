package com.metrolist.shared.model

data class ArtistStat(
    val name: String,
    val playCount: Int,
)

data class SongStat(
    val title: String,
    val artist: String,
    val playCount: Int,
)
