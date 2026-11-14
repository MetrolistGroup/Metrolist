/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsWithProvider
import com.metrolist.shared.network.QuietHttpFailures
import com.metrolist.shared.repository.usableProviderLyrics
import com.metrolist.shared.settings.Settings
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

interface LyricsFetcher {
    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
        videoId: String? = null,
        provider: LyricsProvider = LyricsProvider.AUTO,
        isVideoContext: Boolean = false,
    ): LyricsWithProvider?

    suspend fun getLyricsResults(
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
        videoId: String? = null,
        provider: LyricsProvider = LyricsProvider.AUTO,
        isVideoContext: Boolean = false,
    ): List<LyricsWithProvider> = listOfNotNull(getLyrics(title, artist, duration, album, videoId, provider, isVideoContext))
}

class LyricsClient(
    httpClient: HttpClient,
    youtubeLyricsApi: YouTubeLyricsApi,
    private val lyprioLyricsProvider: LyprioLyricsProvider?,
) : LyricsFetcher {
    private val betterLyricsProvider = BetterLyricsProvider(httpClient)
    private val unisonLyricsProvider = UnisonProvider(httpClient)
    private val lrcLibProvider = LrcLibLyricsProvider(httpClient)
    private val lrcredLyricsProvider = LrcredLyricsProvider(httpClient)
    private val simpMusicProvider = SimpMusicLyricsProvider(httpClient)
    private val kuGouLyricsProvider = KuGouLyricsProvider(httpClient)
    private val paxsenixLyricsProvider = PaxsenixLyricsProvider(httpClient)
    private val youtubeLyricsProvider = YouTubeLyricsProvider(youtubeLyricsApi)
    private val youtubeSubtitleLyricsProvider = YouTubeSubtitleLyricsProvider(youtubeLyricsApi)

    // Lyrics sources fail for many outside reasons; their errors and timeouts are not worth logging.
    override suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Long?,
        album: String?,
        videoId: String?,
        provider: LyricsProvider,
        isVideoContext: Boolean,
    ): LyricsWithProvider? =
        withContext(QuietHttpFailures) { fetchLyrics(title, artist, duration, album, videoId, provider, isVideoContext) }

    private suspend fun fetchLyrics(
        title: String,
        artist: String,
        duration: Long?,
        album: String?,
        videoId: String?,
        provider: LyricsProvider,
        isVideoContext: Boolean,
    ): LyricsWithProvider? {
        val durationMs = duration ?: 0L
        val durationSec = durationMs / 1000
        return when (provider) {
            LyricsProvider.USER -> {
                null
            }

            LyricsProvider.LYPRIO -> {
                val lyprio = checkNotNull(lyprioLyricsProvider) { "Lyprio is not configured" }
                lyprio.getLyrics(title, artist, duration, album, videoId)
            }

            LyricsProvider.BETTERLYRICS -> {
                result("BetterLyrics") { betterLyricsProvider.getLyrics(title, artist, duration, album) }
            }

            LyricsProvider.LRCRED -> {
                result("lrc.red") { lrcredLyricsProvider.getLyrics(title, artist, duration, album, videoId) }
            }

            LyricsProvider.UNISON -> {
                result("Unison") { unisonLyricsProvider.getLyrics(title, artist, duration, album, videoId, isVideoContext) }
            }

            LyricsProvider.SIMPMUSIC -> {
                result("SimpMusic") { videoId?.let { simpMusicProvider.getLyrics(it, duration) } }
            }

            LyricsProvider.LRCLIB -> {
                result("LrcLib") { lrcLibProvider.getLyrics(title, artist, duration, album) }
            }

            LyricsProvider.KUGOU -> {
                result("KuGou") { kuGouLyricsProvider.getLyrics(title, artist, durationSec.toInt(), album) }
            }

            LyricsProvider.PAXSENIX -> {
                result("Paxsenix") { paxsenixLyricsProvider.getLyrics(title, artist, duration, album) }
            }

            LyricsProvider.YOUTUBESUBTITLE -> {
                result("YouTube Subtitle") { videoId?.let { youtubeSubtitleLyricsProvider.getLyricsOrNull(it) } }
            }

            LyricsProvider.YOUTUBE -> {
                result("YouTube Music") { videoId?.let { youtubeLyricsProvider.getLyrics(it) } }
            }

            LyricsProvider.AUTO -> {
                var failure: Exception? = null
                var lyrics: LyricsWithProvider? = null
                val providers =
                    LyricsProvider.preferredProvidersForFetch(
                        LyricsProvider.parseUserOrderFromStoredCsv(Settings.shared.lyricsProviderOrder),
                        LyricsProvider.parseEnabledFromStoredCsv(Settings.shared.lyricsEnabledProviders),
                    )
                for (candidate in providers) {
                    try {
                        for (result in listOfNotNull(getLyrics(title, artist, duration, album, videoId, candidate, isVideoContext))) {
                            val parsed = LyricsUtils.parseLyrics(result.lyrics)
                            if (usableProviderLyrics(parsed) && (lyrics == null || LyricsUtils.hasPlaybackTiming(parsed))) lyrics = result
                        }
                        if (lyrics?.let { LyricsUtils.hasPlaybackTiming(LyricsUtils.parseLyrics(it.lyrics)) } == true) break
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        failure = error
                    }
                }
                if (lyrics == null) failure?.let { throw it }
                lyrics
            }
        }
    }

    override suspend fun getLyricsResults(
        title: String,
        artist: String,
        duration: Long?,
        album: String?,
        videoId: String?,
        provider: LyricsProvider,
        isVideoContext: Boolean,
    ): List<LyricsWithProvider> =
        withContext(QuietHttpFailures) {
            if (provider == LyricsProvider.LYPRIO) {
                val lyprio = checkNotNull(lyprioLyricsProvider) { "Lyprio is not configured" }
                lyprio.getLyricsResults(title, artist, duration, album, videoId).map { LyricsWithProvider(it, "Lyprio") }
            } else {
                listOfNotNull(fetchLyrics(title, artist, duration, album, videoId, provider, isVideoContext))
            }
        }

    private suspend fun result(
        provider: String,
        fetch: suspend () -> String?,
    ): LyricsWithProvider? = fetch()?.let { LyricsWithProvider(it, provider) }
}
