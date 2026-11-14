package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.BrowseResponse
import com.metrolist.shared.api.YouTubeHistoryParser.parseHistoryResponse
import com.metrolist.shared.auth.YouTubeSyncState
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.logging.YtLogger
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

internal class YouTubeHistoryOperations(
    private val innerTube: InnerTube,
    private val requireCurrentSession: (InnerTube.SessionSnapshot) -> Unit,
    private val readAccountBrowse: suspend (InnerTube.SessionSnapshot, String?, String?) -> BrowseResponse,
    private val retrySync: suspend () -> Unit,
) {
    fun isYouTubeHistoryAvailable(): Boolean = innerTube.hasSapCookieAuth()

    fun isHistorySyncEnabled(): Boolean = isYouTubeHistoryAvailable() && Settings.shared.ytmSync && !Settings.shared.pauseListenHistory

    suspend fun getHistory(): List<YouTubeHistoryEntry> = getHistoryResult().entries

    suspend fun getHistoryResult(): YouTubeHistoryResult {
        val requestSession = innerTube.sessionSnapshot()
        return try {
            if (!Settings.shared.ytmSync || Settings.shared.pauseListenHistory || !innerTube.hasSapCookieAuth(requestSession)) {
                return YouTubeHistoryResult(emptyList(), complete = true)
            }

            fun requireHistorySync() {
                requireCurrentSession(requestSession)
                if (!Settings.shared.ytmSync || Settings.shared.pauseListenHistory) {
                    throw CancellationException("History sync disabled")
                }
            }

            suspend fun loadPage(
                browseId: String? = null,
                continuation: String? = null,
            ): BrowseResponse {
                requireHistorySync()
                return readAccountBrowse(requestSession, browseId, continuation).also { requireHistorySync() }
            }

            val first = parseHistoryResponse(loadPage(browseId = "FEmusic_history"))
            val result =
                collectPageBranches(first, maxRequests = Int.MAX_VALUE) { branch ->
                    parseHistoryResponse(loadPage(continuation = branch.token), branch.sectionTitle)
                }
            requireHistorySync()
            if (result.complete) YouTubeSyncState.succeeded("history") else notifyFailure(requestSession)
            YouTubeHistoryResult(result.items, result.complete)
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            YtLogger.e("HistorySync", "Refresh failed type=${error::class.simpleName}")
            notifyFailure(requestSession)
            YouTubeHistoryResult(emptyList(), complete = false)
        }
    }

    private fun notifyFailure(session: InnerTube.SessionSnapshot) {
        if (!innerTube.hasSapCookieAuth(session) || innerTube.sessionSnapshot() != session) return
        YouTubeSyncState.failed("history") {
            if (innerTube.sessionSnapshot() == session) retrySync()
        }
    }

    suspend fun removeHistoryItems(feedbackTokens: List<String>) {
        val tokens = feedbackTokens.filter { it.isNotBlank() }
        if (tokens.isEmpty() || !isHistorySyncEnabled()) return
        val response = innerTube.feedback(YouTubeClient.WEB_REMIX, tokens)
        check(response.status.isSuccess()) { "History removal failed with HTTP ${response.status.value}" }
    }
}
