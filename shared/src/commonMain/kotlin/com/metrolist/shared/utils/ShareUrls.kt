package com.metrolist.shared.utils

import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.SongShareLink

object ShareUrls {
    private const val MUSIC_DOMAIN = "https://music.youtube.com"

    /** Link for sharing a song in the format chosen in settings. song.link has no timestamps, so those stay on YouTube Music. */
    fun song(
        videoId: String,
        startSeconds: Long? = null,
    ): String =
        if (startSeconds == null && Settings.shared.songShareLink == SongShareLink.SONG_LINK.name) {
            "https://song.link/y/$videoId"
        } else {
            youtubeMusicSong(videoId, startSeconds)
        }

    fun youtubeMusicSong(
        videoId: String,
        startSeconds: Long? = null,
    ): String = "$MUSIC_DOMAIN/watch?v=$videoId" + (startSeconds?.let { "&t=${it.coerceAtLeast(0)}s" } ?: "")

    fun party(
        roomCode: String,
        usePartyParam: Boolean = true,
    ): String =
        if (usePartyParam) {
            "https://metrolist.cc/party?r=$roomCode"
        } else {
            "https://metrolist.cc/listen?code=$roomCode"
        }

    fun playlist(playlistId: String): String = "$MUSIC_DOMAIN/playlist?list=${playlistId.removePrefix("VL")}"

    fun album(albumId: String): String = "$MUSIC_DOMAIN/browse/$albumId"

    fun artist(artistId: String): String = "$MUSIC_DOMAIN/channel/$artistId"

    fun podcast(podcastId: String): String = "$MUSIC_DOMAIN/browse/$podcastId"
}
