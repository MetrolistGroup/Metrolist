package com.metrolist.shared.discord

object DiscordTemplateRenderer {
    fun render(
        template: String,
        title: String,
        artist: String,
        album: String?,
        songId: String = "",
        artistId: String = "",
        albumId: String = "",
        unknownAlbum: String = "",
    ): String {
        val artistUrl = if (artistId.isNotEmpty()) "https://music.youtube.com/channel/$artistId" else ""
        val songUrl = if (songId.isNotEmpty()) "${DiscordDefaults.YOUTUBE_WATCH_URL}$songId" else ""
        val albumUrl = if (albumId.isNotEmpty()) "https://music.youtube.com/playlist?list=$albumId" else songUrl
        return template
            .replace("{song.name}", title)
            .replace("{song_name}", title)
            .replace("{song.title}", title)
            .replace("{song_title}", title)
            .replace("{song.url}", songUrl)
            .replace("{song_url}", songUrl)
            .replace("{artist.name}", artist)
            .replace("{artist_name}", artist)
            .replace("{artist.url}", artistUrl)
            .replace("{artist_url}", artistUrl)
            .replace("{album.name}", album ?: unknownAlbum)
            .replace("{album_name}", album ?: unknownAlbum)
            .replace("{album.url}", albumUrl)
            .replace("{album_url}", albumUrl)
            .replace("{video_id}", songId)
            .replace("{video.id}", songId)
            .replace("{song.id}", songId)
            .replace("{song_id}", songId)
    }

    val PLACEHOLDERS =
        listOf(
            "{song.name}",
            "{song_name}",
            "{song.title}",
            "{song_title}",
            "{artist.name}",
            "{artist_name}",
            "{album.name}",
            "{album_name}",
            "{video_id}",
            "{video.id}",
            "{song.id}",
            "{song_id}",
            "{song.url}",
            "{song_url}",
            "{artist.url}",
            "{artist_url}",
            "{album.url}",
            "{album_url}",
        )
}
