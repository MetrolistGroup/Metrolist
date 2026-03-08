package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.PlaybackTrackingData
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.PlayerResponse
import com.metrolist.shared.api.YouTubeRepository.PlaybackTrackingSession
import com.metrolist.shared.auth.supportedYouTubeAuthCredential
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.logging.YtLogger
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

internal class YouTubePlaybackTracking(
    private val innerTube: InnerTube,
) {
    suspend fun getPlaybackTrackingUrl(
        videoId: String,
        playlistId: String? = null,
    ): String? =
        fetchPlaybackTracking(
            videoId = videoId,
            playlistId = playlistId,
            clientPlaybackNonce = generateClientPlaybackNonce(),
            playbackSession = null,
        )?.playbackUrl

    fun playbackTrackingSession(): PlaybackTrackingSession = PlaybackTrackingSession(innerTube.sessionSnapshot())

    fun isPlaybackTrackingSessionCurrent(playbackSession: PlaybackTrackingSession): Boolean =
        innerTube.sessionSnapshot() == playbackSession.snapshot

    internal fun playbackReportingAccountKey(playbackSession: PlaybackTrackingSession = playbackTrackingSession()): String? {
        val session = playbackSession.snapshot
        supportedYouTubeAuthCredential(session.cookie.orEmpty()) ?: return null
        val identity = session.dataSyncId?.takeIf { it.isNotBlank() } ?: return null
        return "datasync:$identity:${session.authUser}"
    }

    internal suspend fun getPlaybackTracking(
        videoId: String,
        clientPlaybackNonce: String,
        playbackSession: PlaybackTrackingSession,
    ): PlaybackTrackingData? =
        fetchPlaybackTracking(
            videoId = videoId,
            playlistId = null,
            clientPlaybackNonce = clientPlaybackNonce,
            playbackSession = playbackSession,
        )

    private suspend fun fetchPlaybackTracking(
        videoId: String,
        playlistId: String?,
        clientPlaybackNonce: String,
        playbackSession: PlaybackTrackingSession?,
    ): PlaybackTrackingData? =
        try {
            val httpResponse =
                if (playbackSession == null) {
                    innerTube.player(
                        client = YouTubeClient.WEB_REMIX,
                        videoId = videoId,
                        playlistId = playlistId,
                    )
                } else {
                    innerTube.playerWithSessionBound(
                        client = YouTubeClient.WEB_REMIX,
                        videoId = videoId,
                        playlistId = playlistId,
                        signatureTimestamp = null,
                        poToken = null,
                        requestVisitorData = null,
                        requestSession = playbackSession.snapshot,
                    )
                }
            YtLogger.w("HistorySync", "Tracking status=${httpResponse.status.value}")
            val response = httpResponse.bodyLimited<PlayerResponse>(PLAYER_NEXT_RESPONSE_LIMIT)
            response.playbackTracking?.let { tracking ->
                PlaybackTrackingData(
                    clientPlaybackNonce = clientPlaybackNonce,
                    playbackUrl = tracking.videostatsPlaybackUrl?.baseUrl,
                    watchtimeUrl = tracking.videostatsWatchtimeUrl?.baseUrl,
                    scheduledFlushWalltimeSeconds = tracking.videostatsScheduledFlushWalltimeSeconds,
                    defaultFlushIntervalSeconds = tracking.videostatsDefaultFlushIntervalSeconds,
                    resolvedAtEpochMs =
                        kotlin.time.Clock.System
                            .now()
                            .toEpochMilliseconds(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            YtLogger.e("HistorySync", "Tracking failed type=${error::class.simpleName}")
            null
        }

    internal fun isPlaybackReportingEnabled(): Boolean =
        innerTube.hasSapCookieAuth() && Settings.shared.ytmSync && !Settings.shared.pauseListenHistory

    internal suspend fun registerPlayback(
        playbackTrackingUrl: String,
        clientPlaybackNonce: String,
        currentMediaTime: String,
        format: Int?,
        playbackSession: PlaybackTrackingSession,
    ): Boolean {
        if (!isPlaybackReportingEnabled() || !isPlaybackTrackingSessionCurrent(playbackSession)) return false
        val playbackUrl = normalizePlaybackStatsUrl(playbackTrackingUrl) ?: return false
        val response =
            innerTube.registerPlaybackWithSession(
                client = YouTubeClient.WEB_REMIX,
                url = playbackUrl,
                cpn = clientPlaybackNonce,
                playlistId = null,
                requestSession = playbackSession.snapshot,
                currentMediaTime = currentMediaTime,
                final = false,
                format = format,
            )
        if (!response.status.isSuccess()) YtLogger.w("HistorySync", "Playback report status=${response.status.value}")
        return response.status.isSuccess()
    }

    internal suspend fun registerWatchtime(
        watchtimeTrackingUrl: String,
        clientPlaybackNonce: String,
        startTimes: String,
        endTimes: String,
        currentMediaTime: String,
        realTime: String,
        final: Boolean,
        format: Int?,
        playbackSession: PlaybackTrackingSession,
    ): Boolean {
        if (!isPlaybackReportingEnabled() || !isPlaybackTrackingSessionCurrent(playbackSession)) return false
        val watchtimeUrl = normalizePlaybackStatsUrl(watchtimeTrackingUrl) ?: return false
        val response =
            innerTube.registerWatchtimeWithSession(
                client = YouTubeClient.WEB_REMIX,
                url = watchtimeUrl,
                cpn = clientPlaybackNonce,
                startTimes = startTimes,
                endTimes = endTimes,
                currentMediaTime = currentMediaTime,
                realTime = realTime,
                final = final,
                format = format,
                requestSession = playbackSession.snapshot,
            )
        if (!response.status.isSuccess()) YtLogger.w("HistorySync", "Watchtime report status=${response.status.value}")
        return response.status.isSuccess()
    }

    private fun normalizePlaybackStatsUrl(value: String): String? {
        val parsed = runCatching { Url(value) }.getOrNull() ?: return null
        if (parsed.protocol != URLProtocol.HTTPS || parsed.port != 443 || parsed.user != null || parsed.password != null) return null
        if (parsed.encodedPath !in setOf("/api/stats/playback", "/api/stats/watchtime")) return null
        return when (parsed.host) {
            "music.youtube.com", "s.youtube.com" -> value
            else -> null
        }
    }
}
