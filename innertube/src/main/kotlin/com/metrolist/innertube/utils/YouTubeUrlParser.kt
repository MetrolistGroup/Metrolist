package com.metrolist.innertube.utils

import com.metrolist.innertube.models.WatchEndpoint

/**
 * Utility class for parsing YouTube and YouTube Music URLs.
 * Extracts video IDs, playlist IDs, and creates WatchEndpoints from URLs.
 */
object YouTubeUrlParser {
    /**
     * Represents the type of YouTube link parsed.
     */
    sealed class ParsedUrl {
        abstract val id: String

        data class Video(
            override val id: String,
        ) : ParsedUrl()

        data class Playlist(
            override val id: String,
        ) : ParsedUrl()

        data class Album(
            override val id: String,
        ) : ParsedUrl()

        data class Artist(
            override val id: String,
        ) : ParsedUrl()
    }

    /**
     * Pattern for matching YouTube video URLs.
     * Matches:
     * - https://www.youtube.com/watch?v=VIDEO_ID
     * - https://music.youtube.com/watch?v=VIDEO_ID
     * - https://youtu.be/VIDEO_ID
     * - https://www.youtube.com/shorts/VIDEO_ID
     */
    private val VIDEO_URL_PATTERNS =
        listOf(
            Regex("""(?:https?://)?(?:www\.)?(?:music\.)?youtube\.com/watch\?.*v=([a-zA-Z0-9_-]{11})"""),
            Regex("""(?:https?://)?(?:www\.)?(?:music\.)?youtube\.com/watch\?v=([a-zA-Z0-9_-]{11})"""),
            Regex("""(?:https?://)?youtu\.be/([a-zA-Z0-9_-]{11})"""),
            Regex("""(?:https?://)?(?:www\.)?youtube\.com/shorts/([a-zA-Z0-9_-]{11})"""),
        )

    /**
     * Pattern for matching YouTube playlist URLs.
     * Matches:
     * - https://www.youtube.com/playlist?list=PLAYLIST_ID
     * - https://music.youtube.com/playlist?list=PLAYLIST_ID
     */
    private val PLAYLIST_URL_PATTERN =
        Regex(
            """(?:https?://)?(?:www\.)?(?:music\.)?youtube\.com/playlist\?.*list=([a-zA-Z0-9_-]+)""",
        )

    /**
     * Pattern for matching YouTube Music album URLs.
     * Albums are essentially playlists with specific browse IDs.
     * Matches:
     * - https://music.youtube.com/playlist?list=PLAYLIST_ID
     */
    private val ALBUM_URL_PATTERN =
        Regex(
            """(?:https?://)?(?:www\.)?music\.youtube\.com/playlist\?.*list=([a-zA-Z0-9_-]+)""",
        )

    /**
     * Pattern for matching YouTube Music artist URLs.
     * Matches:
     * - https://music.youtube.com/channel/CHANNEL_ID
     * - https://music.youtube.com/browse/MPREb_... (artist browse ID)
     */
    private val ARTIST_URL_PATTERNS =
        listOf(
            Regex("""(?:https?://)?(?:www\.)?music\.youtube\.com/channel/([a-zA-Z0-9_-]+)"""),
            Regex("""(?:https?://)?(?:www\.)?music\.youtube\.com/browse/(MPRE[a-zA-Z0-9_-]+)"""),
        )

    /**
     * Checks if the given text is a YouTube URL.
     */
    fun isYouTubeUrl(text: String): Boolean = parse(text) != null

    /**
     * Parses a YouTube URL and returns the parsed result.
     *
     * @param url The URL to parse
     * @return ParsedUrl if valid, null otherwise
     */
    fun parse(url: String): ParsedUrl? {
        val trimmedUrl = url.trim()

        // Check for video URLs
        for (pattern in VIDEO_URL_PATTERNS) {
            pattern.find(trimmedUrl)?.let { matchResult ->
                matchResult.groupValues.getOrNull(1)?.let { videoId ->
                    return ParsedUrl.Video(videoId)
                }
            }
        }

        // Check for playlist URLs (non-music.youtube.com)
        if (!trimmedUrl.contains("music.youtube.com")) {
            PLAYLIST_URL_PATTERN.find(trimmedUrl)?.let { matchResult ->
                matchResult.groupValues.getOrNull(1)?.let { playlistId ->
                    return ParsedUrl.Playlist(playlistId)
                }
            }
        }

        // Check for album URLs (music.youtube.com playlists)
        if (trimmedUrl.contains("music.youtube.com")) {
            ALBUM_URL_PATTERN.find(trimmedUrl)?.let { matchResult ->
                matchResult.groupValues.getOrNull(1)?.let { playlistId ->
                    // Albums on YouTube Music use playlist URLs
                    // We'll treat them as albums and let the API determine the actual type
                    return ParsedUrl.Album(playlistId)
                }
            }

            // Check for artist URLs
            for (pattern in ARTIST_URL_PATTERNS) {
                pattern.find(trimmedUrl)?.let { matchResult ->
                    matchResult.groupValues.getOrNull(1)?.let { artistId ->
                        return ParsedUrl.Artist(artistId)
                    }
                }
            }
        }

        return null
    }

    /**
     * Extracts video ID from a YouTube URL.
     *
     * @param url The URL to parse
     * @return Video ID if found, null otherwise
     */
    fun extractVideoId(url: String): String? = (parse(url) as? ParsedUrl.Video)?.id

    /**
     * Extracts playlist ID from a YouTube URL.
     *
     * @param url The URL to parse
     * @return Playlist ID if found, null otherwise
     */
    fun extractPlaylistId(url: String): String? {
        val parsed = parse(url)
        return when (parsed) {
            is ParsedUrl.Playlist -> parsed.id
            is ParsedUrl.Album -> parsed.id
            else -> null
        }
    }

    /**
     * Creates a WatchEndpoint from a YouTube video URL.
     *
     * @param url The URL to parse
     * @return WatchEndpoint if valid video URL, null otherwise
     */
    fun createWatchEndpoint(url: String): WatchEndpoint? =
        extractVideoId(url)?.let { videoId ->
            WatchEndpoint(videoId = videoId)
        }
}
