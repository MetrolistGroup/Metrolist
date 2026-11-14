package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.innertubex.bodyAsTextLimited
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.PlaybackTrackingData
import com.metrolist.innertubex.models.AccountInfo
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.YouTubeDataPage
import com.metrolist.innertubex.models.response.AccountsListResponse
import com.metrolist.innertubex.models.response.BrowseResponse
import com.metrolist.innertubex.models.response.GetQueueResponse
import com.metrolist.innertubex.models.response.PlayerResponse
import com.metrolist.shared.api.YouTubeRadioParser.parsePlaylistPanelItem
import com.metrolist.shared.api.YouTubeRadioParser.parseRadioQueueResponse
import com.metrolist.shared.api.YouTubeRadioParser.parseRadioResponse
import com.metrolist.shared.api.YouTubeResponseParser.asArray
import com.metrolist.shared.api.YouTubeResponseParser.asObject
import com.metrolist.shared.api.YouTubeResponseParser.browseId
import com.metrolist.shared.api.YouTubeResponseParser.cleanSubscriberCount
import com.metrolist.shared.api.YouTubeResponseParser.feedbackToken
import com.metrolist.shared.api.YouTubeResponseParser.findAllObjects
import com.metrolist.shared.api.YouTubeResponseParser.findFirstObject
import com.metrolist.shared.api.YouTubeResponseParser.longValue
import com.metrolist.shared.api.YouTubeResponseParser.parseBrowseResponse
import com.metrolist.shared.api.YouTubeResponseParser.parseIntegerCount
import com.metrolist.shared.api.YouTubeResponseParser.parseItem
import com.metrolist.shared.api.YouTubeResponseParser.parseUploadedSongItems
import com.metrolist.shared.api.YouTubeResponseParser.parseUploadedSongRenderer
import com.metrolist.shared.api.YouTubeResponseParser.primitiveText
import com.metrolist.shared.api.YouTubeResponseParser.runText
import com.metrolist.shared.api.YouTubeResponseParser.sectionListRenderer
import com.metrolist.shared.api.YouTubeResponseParser.textContent
import com.metrolist.shared.api.YouTubeResponseParser.uploadShelfObjects
import com.metrolist.shared.api.musicvideo.MusicVideoCounterpart
import com.metrolist.shared.api.musicvideo.parseMusicVideoCounterpart
import com.metrolist.shared.auth.YouTubeSessionState
import com.metrolist.shared.auth.YouTubeSyncState
import com.metrolist.shared.auth.invalidateYouTubeSession
import com.metrolist.shared.auth.invalidateYouTubeSessionIfCurrent
import com.metrolist.shared.auth.isExpiredYouTubeSessionResponse
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.OfflineSongAvailability
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.isAvailableOffline
import com.metrolist.shared.db.loadOfflineAlbum
import com.metrolist.shared.db.loadOfflineArtist
import com.metrolist.shared.db.loadOfflinePlaylist
import com.metrolist.shared.db.removeUnavailableRemotePlaylist
import com.metrolist.shared.db.searchOfflineMedia
import com.metrolist.shared.db.storeAlbumPage
import com.metrolist.shared.db.syncYouTubeHistory
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.albums
import com.metrolist.shared.generated.resources.artists
import com.metrolist.shared.generated.resources.playlists
import com.metrolist.shared.generated.resources.songs
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.BrowsePageData
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.HomePageData
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_LIVE
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.PodcastPage
import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SearchResultPage
import com.metrolist.shared.model.SearchSuggestions
import com.metrolist.shared.model.SearchSummary
import com.metrolist.shared.model.SearchSummaryPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.isEditablePlaylistId
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.model.parseDisplayCount
import com.metrolist.shared.model.toSongItem
import com.metrolist.shared.playlist.LibraryDeletionTracker
import com.metrolist.shared.playlist.PlaylistChanges
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.viewmodels.LIBRARY_REFRESH_TTL_MILLIS
import com.metrolist.shared.youtube.logging.YtLogger
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.metrolist.shared.youtube.playback.StreamResolver
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.getString
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

internal const val SMALL_RESPONSE_LIMIT = 2 * 1024 * 1024
internal const val PLAYER_NEXT_RESPONSE_LIMIT = 4 * 1024 * 1024
internal const val FEED_RESPONSE_LIMIT = 16 * 1024 * 1024
private const val PLAYLIST_EDITABILITY_CONCURRENCY = 6

internal class YouTubeSessionExpiredException : Exception()

class PlaylistRemovalFailedException : Exception()

class PlaylistSongAlreadyPresentException : Exception()

internal val repositoryJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

internal var responseDecodeDispatcher: CoroutineDispatcher = Dispatchers.Default

internal suspend inline fun <reified T> HttpResponse.bodyLimited(maxBytes: Int): T {
    val text = bodyAsTextLimited(maxBytes)
    return withContext(responseDecodeDispatcher) {
        if (T::class == JsonObject::class) decodeYouTubeJson(text) as T else repositoryJson.decodeFromString(text)
    }
}

data class YouTubeHistoryEntry(
    val song: SongItem,
    val playedText: String,
    val feedbackToken: String?,
)

data class YouTubeHistoryResult(
    val entries: List<YouTubeHistoryEntry>,
    val complete: Boolean,
)

data class YouTubeSearchHistoryEntry(
    val query: String,
    val feedbackToken: String?,
)

data class SongPlayerInformation(
    val title: String? = null,
    val artist: String? = null,
    val thumbnail: String? = null,
    val durationSeconds: Long? = null,
    val viewCount: Long? = null,
    val musicVideoType: String? = null,
    val isLive: Boolean = false,
)

data class SongWatchInformation(
    val artist: String? = null,
    val subscriberCount: String? = null,
    val likeCount: Long? = null,
    val viewCount: Long? = null,
    val description: String? = null,
)

internal data class SongVoteBatchResult(
    val authenticated: Boolean,
    val succeededIds: List<String>,
)

data class UploadSongResult(
    val success: Boolean,
    val failure: UploadFailure? = null,
    val httpStatus: Int? = null,
)

enum class UploadFailure {
    SIGN_IN_REQUIRED,
    UNSUPPORTED_TYPE,
    EMPTY_FILE,
    TOO_LARGE,
    ALREADY_UPLOADED,
    HTTP,
    UNKNOWN,
}

data class LibraryItemsResult(
    val items: List<YTItem>,
    val complete: Boolean,
    val unresolvedPlaylistEditabilityIds: Set<String> = emptySet(),
    val uploadedSongs: List<SongItem>? = null,
)

fun Map<String, List<YTItem>>.playlistSongs(): List<SongItem> {
    val songs = playlistSongOccurrences()
    val playlist = get("header")?.filterIsInstance<PlaylistItem>()?.firstOrNull()
    if (playlist?.id?.isMixPlaylistId() == true) return songs.distinctBy { it.id }
    val seen = mutableSetOf<List<Any?>>()
    return songs.filter {
        if (it.playlistSetVideoId == null && it.playlistLocalMappingId == null && it.playlistAddedOrder == null) {
            true
        } else {
            seen.add(listOf(it.id, it.playlistSetVideoId, it.playlistLocalMappingId, it.playlistAddedOrder))
        }
    }
}

internal fun Map<String, List<YTItem>>.playlistSongOccurrences(): List<SongItem> {
    fun List<YTItem>.asSongs(): List<SongItem> =
        mapNotNull { item ->
            when (item) {
                is SongItem -> item
                is EpisodeItem -> item.toSongItem()
                else -> null
            }
        }

    val namedTracks = (get("Tracks").orEmpty() + get("Continuation").orEmpty()).asSongs()
    if (namedTracks.isNotEmpty()) return namedTracks

    return entries
        .asSequence()
        .filterNot { (section) ->
            section.equals("header", ignoreCase = true) || section.contains("suggest", ignoreCase = true)
        }.flatMap { (_, items) -> items.asSequence() }
        .mapNotNull { item ->
            when (item) {
                is SongItem -> item
                is EpisodeItem -> item.toSongItem()
                else -> null
            }
        }.toList()
}

fun Map<String, List<YTItem>>.playlistSuggestions(): List<SongItem> {
    val playlistSongIds = playlistSongs().mapTo(mutableSetOf()) { it.id }
    return entries
        .asSequence()
        .filter { (title) -> title.contains("suggest", ignoreCase = true) }
        .flatMap { (_, items) -> items.asSequence().filterIsInstance<SongItem>() }
        .filterNot { it.id in playlistSongIds }
        .distinctBy { it.id }
        .toList()
}

internal fun podcastLikePlaylistId(podcastId: String): String = podcastId.removePrefix("VL").removePrefix("MPSP")

internal data class SearchContinuationShelf(
    val items: List<JsonObject>,
    val continuation: String?,
)

internal fun JsonObject.searchContinuationShelf(): SearchContinuationShelf? {
    val shelf =
        this["continuationContents"]
            ?.jsonObject
            ?.get("musicShelfContinuation")
            ?.jsonObject
            ?: return null
    val contents = shelf["contents"]?.jsonArray.orEmpty().map { it.jsonObject }
    val continuation = shelf.findContinuationToken()
    return SearchContinuationShelf(
        items = contents.filterNot { "continuationItemRenderer" in it },
        continuation = continuation,
    )
}

class YouTubeRepository(
    private val innerTube: InnerTube,
    private val streamResolverProvider: () -> StreamResolver? = { null },
    private val databaseProvider: () -> MusicDatabase? = { null },
    private val offlineSongAvailabilityProvider: () -> OfflineSongAvailability? = { null },
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    companion object {
        private const val TAG = "YouTubeRepository"
        private const val UPLOAD_TRACKS_BROWSE_ID = "FEmusic_library_privately_owned_tracks"
        private const val MAX_UPLOAD_BYTES = 314_572_800L
        private const val HTTP_CONFLICT = 409
        private const val UPLOAD_PROCESSING_POLLS = 12
        private val UPLOAD_PROCESSING_POLL_INTERVAL = 10.seconds
        val SUPPORTED_UPLOAD_EXTENSIONS = setOf("mp3", "m4a", "wma", "flac", "ogg", "opus")
    }

    class PlaybackTrackingSession internal constructor(
        internal val snapshot: InnerTube.SessionSnapshot,
    )

    private val playlistMembership = YouTubePlaylistMembership(nowMs)
    private val playlistEdits =
        YouTubePlaylistEdits(
            innerTube,
            { invalidateExpiredSession(it) },
            { playlistId, songId -> playlistContainsSong(playlistId, songId, allowOfflineFallback = false) },
            ::onPlaylistNotFound,
            playlistMembership::added,
        )

    private suspend fun onPlaylistNotFound(id: String) {
        playlistMembership.invalidate(innerTube.sessionSnapshot(), id)
        LibraryDeletionTracker.trackDeletedPlaylist(id)
        databaseProvider()?.removeUnavailableRemotePlaylist(id)
    }

    var lastAccountInfo: AccountInfo? = null

    fun setCookie(cookie: String?) {
        innerTube.cookie = cookie
    }

    suspend fun getAccountInfo(publishExpiredSessionNotice: Boolean = true): AccountInfo? {
        val requestSession = innerTube.sessionSnapshot()

        fun failed() {
            if (publishExpiredSessionNotice && innerTube.hasSapCookieAuth(requestSession) &&
                innerTube.sessionSnapshot() == requestSession
            ) {
                YouTubeSyncState.failed("account") { if (innerTube.sessionSnapshot() == requestSession) getAccountInfo() }
            }
        }
        return try {
            var httpResponse = innerTube.accountMenu(YouTubeClient.WEB_REMIX)
            requireCurrentSession(requestSession)
            if (isExpiredYouTubeSessionResponse(
                    httpResponse.status.value,
                    innerTube.hasSapCookieAuth(requestSession),
                    forbiddenExpiresSession = true,
                )
            ) {
                httpResponse.bodyAsChannel().cancel()
                delay(1.seconds)
                requireCurrentSession(requestSession)
                httpResponse = innerTube.accountMenu(YouTubeClient.WEB_REMIX)
                requireCurrentSession(requestSession)
                if (isExpiredYouTubeSessionResponse(
                        httpResponse.status.value,
                        innerTube.hasSapCookieAuth(requestSession),
                        forbiddenExpiresSession = true,
                    )
                ) {
                    invalidateExpiredSession(requestSession, publishExpiredSessionNotice)
                    return null
                }
            }
            YtLogger.w(
                TAG,
                "Account menu status=${httpResponse.status.value} authUser=${requestSession.authUser} " +
                    "hasDataSyncId=${!requestSession.dataSyncId.isNullOrBlank()}",
            )
            if (!httpResponse.status.isSuccess()) {
                failed()
                return null
            }
            val raw = httpResponse.bodyAsTextLimited(SMALL_RESPONSE_LIMIT)
            if (innerTube.hasSapCookieAuth(requestSession) && repositoryJson.parseToJsonElement(raw).jsonObject.isYouTubeLoggedOut()) {
                invalidateExpiredSession(requestSession, publishExpiredSessionNotice)
                return null
            }
            val info = withContext(responseDecodeDispatcher) { parseAccountInfoResponse(raw) }
            if (innerTube.sessionSnapshot() != requestSession) throw CancellationException("YouTube session changed")
            if (info != null) {
                lastAccountInfo = info
                if (publishExpiredSessionNotice) YouTubeSyncState.succeeded("account")
            } else {
                YtLogger.w(TAG, "getAccountInfo: active account details unavailable")
                failed()
            }
            info
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            YtLogger.e(TAG, "getAccountInfo failed type=${e::class.simpleName}")
            failed()
            null
        }
    }

    suspend fun getAccountsList(publishExpiredSessionNotice: Boolean = true): List<AccountsListResponse.Account> {
        var status: Int? = null
        return try {
            val requestSession = innerTube.sessionSnapshot()
            // Use the www channel switcher, as the legacy app does; the Music host left testers with only the active account.
            val httpResponse = innerTube.accountsList(YouTubeClient.WEB)
            status = httpResponse.status.value
            requireCurrentSession(requestSession)
            YtLogger.w(TAG, "Accounts list status=$status")
            if (!httpResponse.status.isSuccess()) {
                httpResponse.bodyAsChannel().cancel()
                if (isExpiredYouTubeSessionResponse(status, innerTube.hasSapCookieAuth(requestSession))) {
                    invalidateExpiredSession(requestSession, publishExpiredSessionNotice)
                }
                return emptyList()
            }
            val raw = httpResponse.bodyAsTextLimited(SMALL_RESPONSE_LIMIT)
            val accounts = withContext(responseDecodeDispatcher) { parseAccountsListResponse(raw) }
            requireCurrentSession(requestSession)
            YtLogger.w(TAG, "Accounts list status=$status parsed=${accounts.size}")
            accounts
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            YtLogger.e(TAG, "Accounts list failed status=$status type=${error::class.simpleName}")
            emptyList()
        }
    }

    private fun requireCurrentSession(requestSession: InnerTube.SessionSnapshot) {
        if (innerTube.sessionSnapshot() != requestSession) throw CancellationException("YouTube session changed")
    }

    private fun invalidateExpiredSession(
        expectedSession: InnerTube.SessionSnapshot? = null,
        publishExpiredSessionNotice: Boolean = true,
    ) {
        YtLogger.w(TAG, "YouTube session appears expired (401/403 received). Invalidating...")
        val invalidated =
            if (expectedSession == null) {
                invalidateYouTubeSession(innerTube)
                true
            } else {
                invalidateYouTubeSessionIfCurrent(innerTube, expectedSession)
            }
        if (!invalidated) return
        lastAccountInfo = null
        if (publishExpiredSessionNotice) {
            YtLogger.w(TAG, "YouTube session successfully invalidated; prompting user for re-login")
            YouTubeSessionState.notifyExpiredSession()
        }
    }

    private suspend fun readAccountBrowse(
        session: InnerTube.SessionSnapshot,
        endpoint: String,
        continuation: Boolean = false,
        voteRevision: Long = SongLikeState.voteObservationRevision(),
        request: suspend () -> HttpResponse,
    ): BrowseResponse =
        withContext(responseDecodeDispatcher) {
            requireCurrentSession(session)
            val response =
                try {
                    request()
                } catch (error: InnerTubeHttpException) {
                    requireCurrentSession(session)
                    YtLogger.w(TAG, "Account browse endpoint=$endpoint continuation=$continuation status=${error.status.value}")
                    if (innerTube.hasSapCookieAuth(session) && error.status.value in setOf(401, 403)) getAccountInfo()
                    throw error
                }
            requireCurrentSession(session)
            YtLogger.w(
                TAG,
                "Account browse endpoint=$endpoint continuation=$continuation status=${response.status.value} " +
                    "authenticated=${innerTube.hasSapCookieAuth(session)} authUser=${session.authUser} " +
                    "hasDataSyncId=${!session.dataSyncId.isNullOrBlank()} hl=${session.locale.hl} gl=${session.locale.gl}",
            )
            if (innerTube.hasSapCookieAuth(session) && response.status.value in setOf(401, 403)) {
                getAccountInfo()
                throw IllegalStateException("Account browse rejected: $endpoint HTTP ${response.status.value}")
            }
            check(response.status.isSuccess()) { "Account browse failed: $endpoint HTTP ${response.status.value}" }
            try {
                val raw = response.bodyLimited<JsonObject>(FEED_RESPONSE_LIMIT)
                requireCurrentSession(session)
                if (innerTube.hasSapCookieAuth(session) && raw.isYouTubeLoggedOut()) {
                    invalidateExpiredSession(session)
                    throw YouTubeSessionExpiredException()
                }
                if (innerTube.hasSapCookieAuth(session)) reconcileSongVotes(raw, voteRevision)
                repositoryJson.decodeFromJsonElement<BrowseResponse>(raw)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                YtLogger.e(
                    TAG,
                    "Account browse endpoint=$endpoint continuation=$continuation " +
                        "decode failed type=${error::class.simpleName}",
                )
                throw error
            }
        }

    private val browseOperations = YouTubeBrowseOperations(innerTube)

    suspend fun getHomePageData(
        params: String? = null,
        browseId: String = "FEmusic_home",
        setLogin: Boolean = innerTube.useLoginForBrowse,
    ): HomePageData = browseOperations.getHomePageData(params, browseId, setLogin)

    internal fun browseUsesLogin(browseId: String?): Boolean =
        innerTube.useLoginForBrowse || browseId?.startsWith("FEmusic_library") == true ||
            browseId?.startsWith("FEmusic_moods_and_genres") == true || browseId == "VLSE" || browseId == "VLRDPN"

    suspend fun getBrowsePageData(
        browseId: String,
        params: String? = null,
        title: String? = null,
        loadAllPages: Boolean = true,
    ): BrowsePageData = browseOperations.getBrowsePageData(browseId, params, title, loadAllPages)

    suspend fun getHomeSectionsContinuation(
        token: String,
        isPersonal: Boolean = false,
        loadAllPages: Boolean = true,
    ): Pair<List<HomeSection>, String?> = browseOperations.getHomeSectionsContinuation(token, isPersonal, loadAllPages)

    suspend fun getBrowseShelfContinuation(
        token: String,
        setLogin: Boolean = innerTube.useLoginForBrowse,
    ): YouTubeDataPage<YTItem> = browseOperations.getBrowseShelfContinuation(token, setLogin)

    suspend fun getLibrary(): YouTubeDataPage<YTItem> =
        try {
            val response =
                innerTube
                    .browse(
                        YouTubeClient.WEB_REMIX,
                        browseId = "FEmusic_library_landing",
                        setLogin = true,
                    ).bodyLimited<BrowseResponse>(FEED_RESPONSE_LIMIT)
            val parsed = parseBrowseResponse(response)
            YouTubeDataPage(parsed.sections.values.flatten(), parsed.continuation)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            YouTubeDataPage(emptyList())
        }

    suspend fun getCompleteLibraryItemsResult(force: Boolean = true): LibraryItemsResult {
        val requestSession = innerTube.sessionSnapshot()
        if (!innerTube.hasSapCookieAuth(requestSession)) return LibraryItemsResult(emptyList(), complete = true)
        val results =
            coroutineScope {
                listOf(
                    UPLOAD_TRACKS_BROWSE_ID,
                    "FEmusic_liked_videos",
                    "FEmusic_liked_albums",
                    "FEmusic_library_corpus_artists",
                    "FEmusic_liked_playlists",
                ).map { browseId ->
                    async {
                        try {
                            Result.success(
                                if (browseId == "FEmusic_liked_playlists") {
                                    getPlaylistLibraryResult(requestSession)
                                } else if (browseId == UPLOAD_TRACKS_BROWSE_ID) {
                                    LibraryItemsResult(
                                        getLibraryUploadSongsResult(limit = null, force = force).getOrThrow(),
                                        complete = true,
                                    )
                                } else {
                                    getAllBrowseItems(browseId, requestSession = requestSession).let {
                                        LibraryItemsResult(it.items, it.complete)
                                    }
                                },
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            YtLogger.e(TAG, "Library sync endpoint=$browseId failed type=${e::class.simpleName}")
                            Result.failure(e)
                        }
                    }
                }.awaitAll()
            }
        requireCurrentSession(requestSession)
        val unresolvedPlaylistEditabilityIds =
            results.flatMap { it.getOrNull()?.unresolvedPlaylistEditabilityIds.orEmpty() }.toMutableSet()
        val resolvedPlaylists =
            results
                .last()
                .getOrNull()
                ?.items
                .orEmpty()
                .associateBy { it.id }
        val items =
            results.flatMap { it.getOrNull()?.items.orEmpty() }.distinctBy { it.id }.map { item ->
                resolvedPlaylists[item.id] ?: if (item is PlaylistItem && item.isUserPlaylist()) {
                    item
                        .withResolvedEditability(requestSession)
                        .also {
                            if (!it.resolved) unresolvedPlaylistEditabilityIds += item.id
                        }.playlist
                } else {
                    item
                }
            }
        requireCurrentSession(requestSession)
        val uploadedSongs =
            results
                .first()
                .getOrNull()
                ?.items
                ?.filterIsInstance<SongItem>()
        val uploadedIds = uploadedSongs?.mapTo(mutableSetOf()) { it.id }
        return LibraryItemsResult(
            items = items.filterNot { it is SongItem && it.isUploaded && uploadedIds != null && it.id !in uploadedIds },
            complete = results.all { it.getOrNull()?.complete == true } && unresolvedPlaylistEditabilityIds.isEmpty(),
            unresolvedPlaylistEditabilityIds = unresolvedPlaylistEditabilityIds,
            uploadedSongs = uploadedSongs,
        )
    }

    private suspend fun getAllBrowseItems(
        browseId: String,
        params: String? = null,
        requestSession: InnerTube.SessionSnapshot = innerTube.sessionSnapshot(),
    ): PaginationResult<YTItem> {
        val voteRevision = SongLikeState.voteObservationRevision()
        requireCurrentSession(requestSession)
        val firstResponse =
            readAccountBrowse(requestSession, browseId, voteRevision = voteRevision) {
                innerTube.browse(YouTubeClient.WEB_REMIX, browseId = browseId, params = params, setLogin = true)
            }
        check(firstResponse.contents != null) { "Missing library contents: $browseId" }
        val first = parseBrowseResponse(firstResponse)
        requireCurrentSession(requestSession)
        return collectPages(
            initial = ParsedPage(first.sections.values.flatten(), first.continuation, first.continuationAmbiguous),
            maxRequests = Int.MAX_VALUE,
        ) { continuation ->
            val continuationRevision = SongLikeState.voteObservationRevision()
            requireCurrentSession(requestSession)
            val nextResponse =
                readAccountBrowse(requestSession, browseId, continuation = true, voteRevision = continuationRevision) {
                    innerTube.browse(YouTubeClient.WEB_REMIX, continuation = continuation, setLogin = true)
                }
            val next = parseBrowseResponse(nextResponse)
            requireCurrentSession(requestSession)
            ParsedPage(next.sections.values.flatten(), next.continuation, next.continuationAmbiguous)
        }
    }

    suspend fun getLibraryUploadSongs(limit: Int? = 100): List<SongItem> = getLibraryUploadSongsResult(limit).getOrDefault(emptyList())

    private class UploadRefresh(
        val session: InnerTube.SessionSnapshot,
        val completion: CompletableDeferred<Result<List<SongItem>>> = CompletableDeferred(),
        var completedAt: Long? = null,
    )

    private val uploadRefreshLock = SynchronizedObject()
    private var uploadRefresh: UploadRefresh? = null

    private fun invalidateUploadedSongs() = synchronized(uploadRefreshLock) { uploadRefresh = null }

    suspend fun getLibraryUploadSongsResult(
        limit: Int? = 100,
        force: Boolean = true,
    ): Result<List<SongItem>> {
        if (limit != null) return loadLibraryUploadSongs(limit)
        val session = innerTube.sessionSnapshot()
        while (true) {
            currentCoroutineContext().ensureActive()
            requireCurrentSession(session)
            val (refresh, ownsRefresh) =
                synchronized(uploadRefreshLock) {
                    val active = uploadRefresh?.takeIf { it.session == session }
                    val age = active?.completedAt?.let { nowMs() - it }
                    if (active != null && (age == null || (!force && age in 0 until LIBRARY_REFRESH_TTL_MILLIS))) {
                        active to false
                    } else {
                        UploadRefresh(session).also { uploadRefresh = it } to true
                    }
                }
            if (!ownsRefresh) {
                try {
                    return refresh.completion.await().also { requireCurrentSession(session) }
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    requireCurrentSession(session)
                    continue
                }
            }
            try {
                val result = loadLibraryUploadSongs(limit = null)
                requireCurrentSession(session)
                currentCoroutineContext().ensureActive()
                synchronized(uploadRefreshLock) {
                    if (uploadRefresh !== refresh) throw CancellationException("Uploads changed during refresh")
                    if (result.isSuccess) refresh.completedAt = nowMs() else uploadRefresh = null
                }
                refresh.completion.complete(result)
                return result
            } finally {
                if (!refresh.completion.isCompleted) {
                    synchronized(uploadRefreshLock) {
                        if (uploadRefresh === refresh) uploadRefresh = null
                    }
                    refresh.completion.cancel()
                }
            }
        }
    }

    private suspend fun loadLibraryUploadSongs(limit: Int?): Result<List<SongItem>> =
        try {
            if (limit == 0) return Result.success(emptyList())
            val requestSession = innerTube.sessionSnapshot()
            check(innerTube.hasSapCookieAuth(requestSession)) { "Upload browse requires authentication" }

            suspend fun browseUploads(continuation: String? = null): BrowseResponse {
                requireCurrentSession(requestSession)
                return readAccountBrowse(requestSession, UPLOAD_TRACKS_BROWSE_ID, continuation != null) {
                    innerTube.browse(
                        YouTubeClient.WEB_REMIX,
                        browseId = UPLOAD_TRACKS_BROWSE_ID.takeIf { continuation == null },
                        continuation = continuation,
                        setLogin = true,
                    )
                }.also { requireCurrentSession(requestSession) }
            }
            val songs = mutableListOf<SongItem>()
            val response = browseUploads()
            check(response.contents != null) { "Missing upload library contents" }
            val shelves = response.uploadShelfObjects()
            shelves.forEach { shelf ->
                songs += parseUploadedSongItems(shelf["contents"].asArray())
            }
            if (songs.isEmpty()) {
                val roots =
                    listOfNotNull(
                        response.contents?.singleColumnBrowseResultsRenderer,
                        response.contents?.twoColumnBrowseResultsRenderer,
                        response.contents?.sectionListRenderer,
                    )
                roots.forEach { root ->
                    root.findAllObjects("musicResponsiveListItemRenderer").forEach { renderer ->
                        parseUploadedSongRenderer(renderer)?.let(songs::add)
                    }
                }
            }

            fun uploadContinuation(page: BrowseResponse): String? {
                val tokens =
                    page.uploadShelfObjects().flatMap { it.findContinuationTokens() }.toSet() +
                        parseBrowseResponse(page).continuationBranches.map { it.token }
                check(tokens.size <= 1) { "Ambiguous upload continuations" }
                return tokens.singleOrNull()
            }
            var continuation = uploadContinuation(response)
            val seen = mutableSetOf<String>()
            while (continuation != null && (limit == null || songs.size < limit)) {
                check(seen.add(continuation)) { "Upload continuation cycle" }
                val continuationResponse = browseUploads(continuation)
                val continuationShelves = continuationResponse.uploadShelfObjects()
                check(continuationShelves.isNotEmpty()) { "Missing upload continuation shelf" }
                continuationShelves.forEach { shelf ->
                    songs += parseUploadedSongItems(shelf["contents"].asArray())
                }
                continuation = uploadContinuation(continuationResponse)
            }

            val distinctSongs = songs.distinctBy { it.id }
            Result.success(if (limit == null) distinctSongs else distinctSongs.take(limit))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            YtLogger.e(TAG, "getLibraryUploadSongs failed type=${e::class.simpleName ?: "unknown"}")
            Result.failure(e)
        }

    suspend fun awaitProcessedUpload(
        knownIds: Set<String>,
        onUpdate: (List<SongItem>) -> Unit = {},
    ): List<SongItem>? {
        var processed: List<SongItem>? = null
        repeat(UPLOAD_PROCESSING_POLLS) {
            delay(UPLOAD_PROCESSING_POLL_INTERVAL)
            val songs = getLibraryUploadSongsResult(limit = null).getOrNull() ?: return@repeat
            if (songs.any { it.id !in knownIds }) {
                processed = songs
                onUpdate(songs)
            }
        }
        return processed
    }

    private suspend fun offlineArtist(channelId: String): ArtistPageResult =
        databaseProvider()
            ?.loadOfflineArtist(channelId, offlineSongAvailabilityProvider()?.songIds())
            ?.let(BlockedContent::filterArtistPage) ?: ArtistPageResult(emptyMap())

    suspend fun getArtist(channelId: String): ArtistPageResult {
        if (!PlaybackNetworkStateStore.current.connected) return offlineArtist(channelId)
        var failureStage = "artist_browse"
        var httpStatus: Int? = null
        return try {
            val response = innerTube.browse(YouTubeClient.WEB_REMIX, browseId = channelId, setLogin = true)
            httpStatus = response.status.value
            failureStage = "artist_response"
            YouTubeArtistParser.parseArtist(response.bodyLimited<BrowseResponse>(FEED_RESPONSE_LIMIT), channelId)
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            YtLogger.e(TAG, "getArtist failed type=${error::class.simpleName ?: "unknown"}")
            PlaybackEventLog.error(
                kind = PlaybackEventKind.NETWORK,
                message = "Artist page request or response handling failed.",
                details =
                    mapOf(
                        "failureStage" to failureStage,
                        "httpStatus" to ((error as? InnerTubeHttpException)?.status?.value ?: httpStatus)?.toString().orEmpty(),
                    ),
                error = error,
            )
            offlineArtist(channelId).takeIf { it.sections.isNotEmpty() } ?: throw error
        }
    }

    suspend fun getArtistSongs(
        channelId: String,
        page: ArtistPageResult? = null,
        onFailure: suspend () -> Unit = {},
    ): List<SongItem> =
        try {
            loadArtistSongs(page ?: getArtist(channelId)) { browseId, params ->
                val result = getAllBrowseItems(browseId, params)
                check(result.complete) { "Artist songs pagination ended before the final page" }
                result.items
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            onFailure()
            emptyList()
        }

    suspend fun getPlaylist(
        playlistId: String,
        applyContentFilters: Boolean = true,
        allowOfflineFallback: Boolean = true,
    ): Map<String, List<YTItem>> = getPlaylistResult(playlistId, applyContentFilters, allowOfflineFallback).getOrDefault(emptyMap())

    suspend fun getPlaylistResult(
        playlistId: String,
        applyContentFilters: Boolean = true,
        allowOfflineFallback: Boolean = true,
    ): Result<Map<String, List<YTItem>>> =
        try {
            val initial = getPlaylistPageData(playlistId, applyContentFilters)
            check(!initial.isOffline || allowOfflineFallback) { "Playlist is only available offline" }
            Result.success(completePlaylistPage(initial, applyContentFilters).sections)
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            YtLogger.e(TAG, "getPlaylist failed type=${error::class.simpleName ?: "unknown"}")
            Result.failure(error)
        }

    suspend fun getPlaylistPageData(
        playlistId: String,
        applyContentFilters: Boolean = true,
    ): PlaylistPage {
        val id = playlistId.toPlaylistBrowseId()
        val voteRevision = SongLikeState.voteObservationRevision()
        val requestSession = innerTube.sessionSnapshot()
        val membershipSnapshot = if (applyContentFilters) null else playlistMembership.snapshot(requestSession, playlistId)
        if (!PlaybackNetworkStateStore.current.connected) {
            databaseProvider()
                ?.loadOfflinePlaylist(playlistId, offlineSongAvailabilityProvider()?.songIds())
                ?.let { sections ->
                    return PlaylistPage(
                        if (applyContentFilters) BlockedContent.filterItemSections(sections) else sections,
                        emptyList(),
                        emptySet(),
                        requestSession,
                        isOffline = true,
                    )
                }
        }
        return try {
            requireCurrentSession(requestSession)
            val httpResponse =
                innerTube
                    .browse(
                        YouTubeClient.WEB_REMIX,
                        browseId = id,
                        setLogin = true,
                    )
            requireCurrentSession(requestSession)
            if (!httpResponse.status.isSuccess()) Logger.w(TAG, "Playlist browse failed browseId=$id HTTP ${httpResponse.status.value}")
            if (httpResponse.status.value == 404 && innerTube.hasSapCookieAuth(requestSession) && playlistId.isEditablePlaylistId()) {
                throw PlaylistNotFoundException(playlistId)
            }
            check(httpResponse.status.isSuccess()) { "Playlist browse failed with HTTP ${httpResponse.status.value}" }
            val response = httpResponse.browseResponseWithVotes(innerTube, requestSession, voteRevision)
            val parsed = parseBrowseResponse(response)
            val sections = YouTubePlaylistParser.parsePlaylistSections(response, parsed, playlistId)

            requireCurrentSession(requestSession)
            PlaylistPage(
                if (applyContentFilters) BlockedContent.filterItemSections(sections) else sections,
                parsed.continuationBranches.toList(),
                emptySet(),
                requestSession,
                membershipSnapshot = membershipSnapshot,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            requireCurrentSession(requestSession)
            if (innerTube.hasSapCookieAuth(requestSession) && playlistId.isEditablePlaylistId() &&
                (e is PlaylistNotFoundException || (e is InnerTubeHttpException && e.status.value == 404))
            ) {
                onPlaylistNotFound(playlistId)
                throw PlaylistNotFoundException(playlistId)
            }
            Logger.w(TAG, "Playlist browse failed browseId=$id type=${e::class.simpleName}")
            val sections =
                databaseProvider()?.loadOfflinePlaylist(playlistId, offlineSongAvailabilityProvider()?.songIds())
                    ?: throw e
            PlaylistPage(
                if (applyContentFilters) BlockedContent.filterItemSections(sections) else sections,
                emptyList(),
                emptySet(),
                requestSession,
                isOffline = true,
            )
        }
    }

    suspend fun completePlaylistPage(
        initial: PlaylistPage,
        applyContentFilters: Boolean = true,
    ): PlaylistPage {
        if (!initial.hasMore) return initial.also { rememberPlaylistMembership(it, applyContentFilters) }
        val sections = linkedMapOf<String, MutableList<YTItem>>()
        var last = initial
        playlistPages(initial, applyContentFilters).collect { page ->
            page.sections.forEach { (title, items) -> sections.getOrPut(title) { mutableListOf() }.addAll(items) }
            last = page
        }
        val collected = sections.mapValues { it.value.toList() }
        return last
            .copy(sections = if (applyContentFilters) BlockedContent.filterItemSections(collected) else collected)
            .withAlbumMetadata()
            .also { page ->
                rememberPlaylistMembership(page, applyContentFilters)
                page.album?.let { databaseProvider()?.storeAlbumPage(AlbumPage(it, page.songs, playlistPage = page)) }
            }
    }

    fun playlistPages(
        initial: PlaylistPage,
        applyContentFilters: Boolean = true,
    ): Flow<PlaylistPage> =
        flow {
            var page = initial
            emit(page)
            while (page.hasMore) {
                page = loadPlaylistPage(page, applyContentFilters)
                emit(page)
            }
        }

    fun getPlaylistPages(
        playlistId: String,
        applyContentFilters: Boolean = true,
    ): Flow<PlaylistPage> =
        flow {
            emitAll(playlistPages(getPlaylistPageData(playlistId, applyContentFilters), applyContentFilters))
        }

    suspend fun getPlaylistPlaybackPage(playlistId: String): PlaylistPage {
        var page = getPlaylistPageData(playlistId, applyContentFilters = false)
        while (page.songs.isEmpty() && page.hasMore) {
            page = continuePlaylistPage(page, applyContentFilters = false)
        }
        return page
    }

    suspend fun continuePlaylistPage(
        page: PlaylistPage,
        applyContentFilters: Boolean = true,
    ): PlaylistPage {
        if (!page.hasMore) return page
        val delta = loadPlaylistPage(page, applyContentFilters)
        val sections = page.sections.toMutableMap()
        delta.sections.forEach { (title, items) -> sections[title] = sections[title].orEmpty() + items }
        return delta
            .copy(sections = if (applyContentFilters) BlockedContent.filterItemSections(sections) else sections)
            .withAlbumMetadata()
            .also { next ->
                next.album?.let { album ->
                    val newIds = delta.songs.mapTo(mutableSetOf()) { it.id }
                    databaseProvider()?.storeAlbumPage(
                        AlbumPage(album, next.songs, playlistPage = next),
                        incrementalSongs = next.songs.filter { it.id in newIds },
                    )
                }
            }
    }

    private suspend fun loadPlaylistPage(
        page: PlaylistPage,
        applyContentFilters: Boolean,
    ): PlaylistPage {
        val delta =
            page.continuation.mutex.withLock {
                requireCurrentSession(page.session)
                page.continuation.delta ?: loadUnfilteredPlaylistPage(page).also { page.continuation.delta = it }
            }
        return if (applyContentFilters) delta.copy(sections = BlockedContent.filterItemSections(delta.sections)) else delta
    }

    private suspend fun loadUnfilteredPlaylistPage(page: PlaylistPage): PlaylistPage {
        val branch = page.pending.firstOrNull() ?: return page
        val voteRevision = SongLikeState.voteObservationRevision()
        requireCurrentSession(page.session)
        val response =
            try {
                innerTube.browse(YouTubeClient.WEB_REMIX, continuation = branch.token, setLogin = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Playlist continuation failed type=${e::class.simpleName}")
                throw e
            }
        requireCurrentSession(page.session)
        if (!response.status.isSuccess()) Logger.w(TAG, "Playlist continuation failed HTTP ${response.status.value}")
        check(response.status.isSuccess()) { "Playlist continuation failed with HTTP ${response.status.value}" }
        val parsed = parseBrowseResponse(response.browseResponseWithVotes(innerTube, page.session, voteRevision))
        val sections = linkedMapOf<String, MutableList<YTItem>>()
        parsed.sections.forEach { (title, items) ->
            val inheritedTitle = if (title == "Continuation") branch.sectionTitle ?: title else title
            sections.getOrPut(inheritedTitle) { mutableListOf() }.addAll(items)
        }
        val seen = page.seen + branch.token
        check(parsed.continuationBranches.none { it.token in seen }) { "Playlist continuation cycle" }
        val pending =
            (
                page.pending.drop(1) +
                    parsed.continuationBranches.map {
                        if (it.sectionTitle == null) it.copy(sectionTitle = branch.sectionTitle) else it
                    }
            ).distinctBy { it.token }.filterNot { it.token in seen }
        requireCurrentSession(page.session)
        return page.copy(
            sections = sections,
            pending = pending,
            seen = seen,
            continuation = PlaylistContinuation(),
        )
    }

    suspend fun playlistSongIds(
        playlistId: String,
        allowOfflineFallback: Boolean = true,
        refresh: Boolean = false,
    ): Set<String> {
        if (refresh) playlistMembership.invalidate(innerTube.sessionSnapshot(), playlistId)
        return loadPlaylistMembership(playlistId, null, allowOfflineFallback).ids
    }

    suspend fun playlistContainsSong(
        playlistId: String,
        videoId: String,
        allowOfflineFallback: Boolean = true,
    ): Boolean = videoId in loadPlaylistMembership(playlistId, videoId, allowOfflineFallback).ids

    private fun rememberPlaylistMembership(
        page: PlaylistPage,
        applyContentFilters: Boolean,
    ) {
        val snapshot = page.membershipSnapshot ?: return
        if (applyContentFilters || page.isOffline || page.hasMore || page.sections["header"]?.firstOrNull() !is PlaylistItem) return
        playlistMembership.remember(snapshot, PlaylistMembership(page.songs.mapTo(mutableSetOf()) { it.id }, true, true))
    }

    private suspend fun loadPlaylistMembership(
        playlistId: String,
        videoId: String?,
        allowOfflineFallback: Boolean,
    ): PlaylistMembership {
        val session = innerTube.sessionSnapshot()
        val membership =
            playlistMembership.get(session, playlistId, videoId) {
                val initial = getPlaylistPageData(playlistId, applyContentFilters = false)
                val hasHeader = initial.sections["header"]?.firstOrNull() is PlaylistItem
                check(videoId != null || hasHeader) { "Playlist is unavailable" }
                check(!initial.isOffline || allowOfflineFallback) { "Playlist is only available offline" }
                val namedIds = mutableSetOf<String>()
                val fallbackIds = mutableSetOf<String>()
                var complete = false
                playlistPages(initial, applyContentFilters = false).firstOrNull { page ->
                    val named = page.sections.filterKeys { it == "Tracks" || it == "Continuation" }.playlistSongs()
                    named.mapTo(namedIds) { it.id }
                    if (namedIds.isEmpty()) page.songs.mapTo(fallbackIds) { it.id }
                    complete = !page.hasMore
                    videoId != null && videoId in namedIds.ifEmpty { fallbackIds }
                }
                requireCurrentSession(session)
                PlaylistMembership(namedIds.ifEmpty { fallbackIds }, complete, online = !initial.isOffline && hasHeader)
            }
        requireCurrentSession(session)
        return membership
    }

    private val podcastCacheLock = SynchronizedObject()
    private val podcastFirstPages = linkedMapOf<Pair<InnerTube.SessionSnapshot, String>, Pair<Long, PodcastPage>>()

    fun podcastPageLoader(podcastId: String): (PodcastPage?) -> Flow<PodcastPage> {
        val session = innerTube.sessionSnapshot()
        return { initial ->
            flow {
                requireCurrentSession(session)
                emitAll(getPodcastPages(podcastId, initialPage = initial))
            }
        }
    }

    fun getPodcastPages(
        podcastId: String,
        savedEpisodes: List<EpisodeItem> = emptyList(),
        initialPage: PodcastPage? = null,
    ): Flow<PodcastPage> =
        flow {
            val session = innerTube.sessionSnapshot()
            require(initialPage == null || initialPage.podcast.id == podcastId)
            val key = session to podcastId
            val now = Clock.System.now().toEpochMilliseconds()
            val cached =
                synchronized(podcastCacheLock) {
                    podcastFirstPages.entries.removeAll { it.key.first != session || now - it.value.first >= 5 * 60_000L }
                    podcastFirstPages[key]?.second
                }
            val first =
                initialPage ?: cached ?: run {
                    val response = innerTube.browse(YouTubeClient.WEB_REMIX, browseId = podcastId, setLogin = true)
                    check(response.status.isSuccess()) { "Podcast browse failed with HTTP ${response.status.value}" }
                    val parsed =
                        withContext(responseDecodeDispatcher) {
                            YouTubePodcastParser.parsePodcast(
                                response.bodyLimited<BrowseResponse>(FEED_RESPONSE_LIMIT),
                                podcastId,
                                emptyList(),
                            )
                        }
                    requireCurrentSession(session)
                    synchronized(podcastCacheLock) {
                        podcastFirstPages[key] = now to parsed
                        while (podcastFirstPages.size > 8) podcastFirstPages.remove(podcastFirstPages.keys.first())
                    }
                    parsed
                }
            val savedById = savedEpisodes.associateBy { it.id }
            val decorated =
                first.copy(
                    episodes =
                        first.episodes.map { episode ->
                            savedById[episode.id]?.let { episode.copy(saved = true, playlistSetVideoId = it.playlistSetVideoId) } ?: episode
                        },
                )
            requireCurrentSession(session)
            emitAll(
                YouTubePodcastParser.pages(decorated, savedEpisodes) { token ->
                    requireCurrentSession(session)
                    val next = innerTube.browse(YouTubeClient.WEB_REMIX, continuation = token, setLogin = true)
                    check(next.status.isSuccess()) { "Podcast continuation failed with HTTP ${next.status.value}" }
                    withContext(responseDecodeDispatcher) {
                        next.bodyLimited<BrowseResponse>(FEED_RESPONSE_LIMIT)
                    }.also { requireCurrentSession(session) }
                },
            )
        }

    suspend fun getLatestPodcastEpisode(podcastId: String): EpisodeItem? =
        getPodcastPages(podcastId)
            .firstOrNull { it.episodes.isNotEmpty() }
            ?.episodes
            ?.firstOrNull()

    suspend fun getPodcast(
        podcastId: String,
        initialPage: PodcastPage? = null,
    ): PodcastPage? =
        try {
            val session = innerTube.sessionSnapshot()
            val savedEpisodes = getSavedEpisodes(limit = null)
            requireCurrentSession(session)
            val episodes = mutableListOf<EpisodeItem>()
            var last: PodcastPage? = null
            try {
                getPodcastPages(podcastId, savedEpisodes, initialPage).collect { page ->
                    episodes.addAll(page.episodes)
                    last = page
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (last == null) throw e
            }
            last?.copy(episodes = episodes.toList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            YtLogger.e(TAG, "getPodcast failed type=${e::class.simpleName ?: "unknown"}")
            null
        }

    suspend fun getLibraryPodcasts(limit: Int? = 100): List<PodcastItem> =
        try {
            if (!innerTube.hasSapCookieAuth() || limit == 0) return emptyList()
            val page =
                getBrowseItems("FEmusic_library_non_music_audio_list")
                    .filterIsInstance<PodcastItem>()
                    .distinctBy { it.id }
            if (limit == null) page else page.take(limit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

    suspend fun getLibraryPodcastsForSync(): List<PodcastItem> {
        require(innerTube.hasSapCookieAuth()) { "YouTube Music sign-in is required" }
        val result = getAllBrowseItems("FEmusic_library_non_music_audio_list")
        check(result.complete) { "Podcast library pagination ended before the final page" }
        return result.items
            .filterIsInstance<PodcastItem>()
            .distinctBy { it.id }
    }

    suspend fun getSavedEpisodes(limit: Int? = 100): List<EpisodeItem> =
        try {
            val items =
                getPlaylist("SE")
                    .playlistSongs()
                    .map { it.toEpisodeItem().copy(saved = true) }
                    .distinctBy { it.id }
            if (limit == null) items else items.take(limit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

    suspend fun getSavedEpisodesForSync(): List<EpisodeItem> {
        require(innerTube.hasSapCookieAuth()) { "YouTube Music sign-in is required" }
        val result = getAllBrowseItems("VLSE")
        check(result.complete) { "Saved episode pagination ended before the final page" }
        return result.items
            .mapNotNull { item ->
                when (item) {
                    is EpisodeItem -> item.copy(saved = true)
                    is SongItem -> item.toEpisodeItem().copy(saved = true)
                    else -> null
                }
            }.distinctBy { it.id }
    }

    suspend fun setPodcastSaved(
        podcastId: String,
        saved: Boolean,
    ): Boolean =
        try {
            // Podcast browse IDs use MPSP as an alias; like endpoints require the underlying PL playlist ID.
            val playlistId = podcastLikePlaylistId(podcastId)
            val response =
                if (saved) {
                    innerTube.likePlaylist(YouTubeClient.WEB_REMIX, playlistId)
                } else {
                    innerTube.unlikePlaylist(YouTubeClient.WEB_REMIX, playlistId)
                }
            check(response.status.isSuccess()) { "Podcast save failed with HTTP ${response.status.value}" }
            synchronized(podcastCacheLock) { podcastFirstPages.keys.removeAll { it.second == podcastId } }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            YtLogger.e(TAG, "setPodcastSaved failed type=${e::class.simpleName ?: "unknown"}")
            false
        }

    suspend fun saveEpisode(episodeId: String): Boolean =
        setEpisodeSaved(episodeId, saved = true)
            .onFailure { error -> YtLogger.e(TAG, "saveEpisode failed type=${error::class.simpleName ?: "unknown"}") }
            .isSuccess

    suspend fun setEpisodeSaved(
        episodeId: String,
        saved: Boolean,
        playlistSetVideoId: String? = null,
    ): Result<Unit> = setEpisodeSavedWithToken(episodeId, saved, playlistSetVideoId).map { }

    suspend fun setEpisodeSavedWithToken(
        episodeId: String,
        saved: Boolean,
        playlistSetVideoId: String? = null,
    ): Result<String?> =
        try {
            val videoId = episodeId.removePrefix("MPED")
            var addedToken: String? = null
            playlistEdits.runSessionPlaylistEdit("SE", requireEditableId = false) { requestTube, id ->
                val response =
                    if (saved) {
                        requestTube.addToPlaylist(YouTubeClient.WEB_REMIX, id, videoId)
                    } else {
                        val setVideoId =
                            playlistSetVideoId
                                ?: YouTubeRepository(requestTube)
                                    .getPlaylistPages(id, applyContentFilters = false)
                                    .firstOrNull { page -> page.songs.any { it.id == videoId && it.playlistSetVideoId != null } }
                                    ?.songs
                                    ?.firstOrNull { it.id == videoId && it.playlistSetVideoId != null }
                                    ?.playlistSetVideoId
                                ?: error("Saved episode entry was not found")
                        requestTube.removePlaylistSong(YouTubeClient.WEB_REMIX, id, setVideoId, videoId)
                    }
                check(response.status.isSuccess()) { "Episode library update failed with HTTP ${response.status.value}" }
                val acknowledgement = response.bodyLimited<JsonObject>(SMALL_RESPONSE_LIMIT)
                check(acknowledgement["status"]?.jsonPrimitive?.contentOrNull == "STATUS_SUCCEEDED") {
                    "Episode library update was rejected"
                }
                if (saved) {
                    addedToken =
                        try {
                            acknowledgement["playlistEditResults"]
                                ?.jsonArray
                                ?.firstNotNullOfOrNull { result ->
                                    result.jsonObject["playlistEditVideoAddedResultData"]
                                        ?.jsonObject
                                        ?.get("setVideoId")
                                        ?.jsonPrimitive
                                        ?.contentOrNull
                                }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                }
            }
            Result.success(addedToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    suspend fun getBrowseItems(
        browseId: String,
        params: String? = null,
    ): List<YTItem> =
        try {
            val result = getAllBrowseItems(browseId, params)
            check(result.complete) { "Browse pagination ended before the final page" }
            result.items
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

    suspend fun getAlbum(
        albumId: String,
        loadAllPages: Boolean = true,
        applyContentFilters: Boolean = true,
    ): AlbumPage? {
        val database = databaseProvider()

        suspend fun loadLocal(): AlbumPage? =
            database
                ?.loadOfflineAlbum(albumId, offlineSongAvailabilityProvider()?.songIds())
                ?.let { if (applyContentFilters) BlockedContent.filterAlbumPage(it) else it }
        if (!PlaybackNetworkStateStore.current.connected) return loadLocal()
        var stage = "browse"
        return try {
            val requestSession = innerTube.sessionSnapshot()
            val response =
                readAccountBrowse(requestSession, albumId) {
                    innerTube.browse(YouTubeClient.WEB_REMIX, browseId = albumId, setLogin = true)
                }
            stage = "header"
            val albumHeader = YouTubeAlbumParser.parseAlbumHeader(response, albumId)
            if (albumHeader == null) {
                YtLogger.w(TAG, "Album browse id=$albumId stage=$stage missing album header")
                return loadLocal()
            }
            val albumItem = albumHeader.item
            val playlistId = albumItem.playlistId ?: return loadLocal()

            val secondaryContents =
                response.contents
                    ?.twoColumnBrowseResultsRenderer
                    ?.get("secondaryContents")
                    ?.jsonObject
                    ?.get("sectionListRenderer")
                    ?.jsonObject
                    ?.get("contents")
                    ?.jsonArray ?: JsonArray(emptyList())

            requireCurrentSession(requestSession)
            stage = "tracks"
            val initialTracks = getPlaylistPlaybackPage(playlistId).copy(album = albumItem)
            val tracks =
                (
                    if (loadAllPages) {
                        completePlaylistPage(
                            initialTracks,
                            applyContentFilters = false,
                        )
                    } else {
                        initialTracks
                    }
                ).withAlbumMetadata()

            val (otherVersions, artistAlbums) = YouTubeAlbumParser.parseAlbumRecommendations(secondaryContents)

            val page =
                AlbumPage(
                    album = albumItem,
                    songs = tracks.songs,
                    playlistPage = tracks,
                    otherVersions = otherVersions,
                    artistAlbums = artistAlbums,
                    description = albumHeader.description,
                )
            database?.storeAlbumPage(page)
            if (applyContentFilters) BlockedContent.filterAlbumPage(page) else page
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            YtLogger.e(TAG, "Album browse id=$albumId stage=$stage failed type=${error::class.simpleName}")
            loadLocal()
        }
    }

    suspend fun getAlbumPlaybackPage(albumId: String): PlaylistPage? {
        val page = getAlbum(albumId, loadAllPages = false) ?: return null
        return page.playlistPage ?: PlaylistPage(
            sections = mapOf("header" to listOf(page.album), "songs" to page.songs),
            pending = emptyList(),
            seen = emptySet(),
            session = innerTube.sessionSnapshot(),
            album = page.album,
        )
    }

    suspend fun getAlbumVideoPage(albumId: String): AlbumPage? {
        return try {
            val response =
                innerTube
                    .browse(
                        YouTubeClient.WEB_REMIX,
                        browseId = albumId,
                        setLogin = true,
                    ).bodyLimited<BrowseResponse>(FEED_RESPONSE_LIMIT)

            val secondaryContents =
                response.contents
                    ?.twoColumnBrowseResultsRenderer
                    ?.get("secondaryContents")
                    ?.jsonObject
                    ?.get("sectionListRenderer")
                    ?.jsonObject
                    ?.get("contents")
                    ?.jsonArray ?: JsonArray(emptyList())

            val songsShelf =
                secondaryContents
                    .mapNotNull {
                        it.jsonObject["musicShelfRenderer"]?.jsonObject
                            ?: it.jsonObject["musicPlaylistShelfRenderer"]?.jsonObject
                    }.firstOrNull() ?: return null

            val directSongs = mutableListOf<SongItem>()
            val shelfContents = songsShelf["contents"]?.jsonArray ?: emptyList()
            shelfContents.forEach {
                val item = parseItem(it.jsonObject["musicResponsiveListItemRenderer"]?.jsonObject)
                if (item is SongItem) {
                    directSongs.add(item)
                }
            }
            if (directSongs.isEmpty()) return null

            AlbumPage(
                album = AlbumItem(id = albumId, title = "", thumbnail = null, artists = emptyList()),
                songs = directSongs,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private val searchOperations = YouTubeSearchOperations(innerTube)

    suspend fun search(
        query: String,
        recordSearchHistory: Boolean = false,
    ): List<YTItem> {
        if (!PlaybackNetworkStateStore.current.connected) return searchOffline(query)
        return searchOperations.search(query, recordSearchHistory) ?: searchOffline(query)
    }

    private suspend fun searchOffline(query: String): List<YTItem> {
        val database = databaseProvider() ?: return emptyList()
        return BlockedContent.filterItems(
            database.searchOfflineMedia(
                query,
                offlineSongAvailabilityProvider()?.songIds(),
            ),
        )
    }

    suspend fun getSearchSummary(
        query: String,
        recordSearchHistory: Boolean = false,
    ): SearchSummaryPage {
        val wasConnected = PlaybackNetworkStateStore.current.connected
        val remote =
            if (wasConnected) {
                searchOperations.getSearchSummary(
                    query,
                    recordSearchHistory,
                )
            } else {
                null
            }
        if (remote != null) return remote
        val local = searchOffline(query)
        return SearchSummaryPage(
            listOf(
                SearchSummary(getString(Res.string.songs), local.filterIsInstance<SongItem>()),
                SearchSummary(getString(Res.string.albums), local.filterIsInstance<AlbumItem>()),
                SearchSummary(getString(Res.string.artists), local.filterIsInstance<ArtistItem>()),
                SearchSummary(getString(Res.string.playlists), local.filterIsInstance<PlaylistItem>()),
            ).filter { it.items.isNotEmpty() },
            onlineSearchFailed = wasConnected,
        )
    }

    suspend fun getSearchSuggestions(query: String): SearchSuggestions? = searchOperations.getSearchSuggestions(query)

    internal val sessionFlow get() = innerTube.sessionFlow

    internal fun sessionSnapshot(): InnerTube.SessionSnapshot = innerTube.sessionSnapshot()

    suspend fun getSearchHistory(): List<YouTubeSearchHistoryEntry>? = getSearchHistory(innerTube.sessionSnapshot())

    internal suspend fun getSearchHistory(session: InnerTube.SessionSnapshot): List<YouTubeSearchHistoryEntry>? =
        withSearchHistorySession(session) { it.getSearchHistory() }

    suspend fun removeSearchHistoryItems(feedbackTokens: List<String>) =
        removeSearchHistoryItems(feedbackTokens, innerTube.sessionSnapshot())

    internal suspend fun removeSearchHistoryItems(
        feedbackTokens: List<String>,
        session: InnerTube.SessionSnapshot,
    ) = withSearchHistorySession(session) { it.removeSearchHistoryItems(feedbackTokens) }

    private suspend fun <T> withSearchHistorySession(
        session: InnerTube.SessionSnapshot,
        block: suspend (YouTubeSearchOperations) -> T,
    ): T {
        requireCurrentSession(session)
        val requestTube = innerTube.createIsolatedSession(true)
        requestTube.replaceSession(session.cookie, session.visitorData, session.dataSyncId, session.authUser, session.useLoginForBrowse)
        try {
            return block(YouTubeSearchOperations(requestTube)).also { requireCurrentSession(session) }
        } finally {
            requestTube.close()
        }
    }

    suspend fun clearSearchHistory(initialFeedbackTokens: List<String>) = searchOperations.clearSearchHistory(initialFeedbackTokens)

    suspend fun searchFiltered(
        query: String,
        filter: SearchFilter,
        continuation: String? = null,
        recordSearchHistory: Boolean = false,
        allowOfflineFallback: Boolean = true,
    ): SearchResultPage {
        check(allowOfflineFallback || PlaybackNetworkStateStore.current.connected) { "Network unavailable for track resolution" }
        val wasConnected = PlaybackNetworkStateStore.current.connected
        val remote =
            if (wasConnected) {
                searchOperations.searchFiltered(
                    query,
                    filter,
                    continuation,
                    recordSearchHistory,
                    throwOnFailure = !allowOfflineFallback,
                )
            } else {
                null
            }
        if (remote != null) return remote
        check(continuation == null) { "Search continuation failed" }
        val local =
            searchOffline(query).filter {
                when (filter) {
                    SearchFilter.SONG -> it is SongItem && !it.isPodcast && !it.isVideoSong
                    SearchFilter.VIDEO -> it is SongItem && it.isVideoSong
                    SearchFilter.EPISODE -> it is SongItem && it.isPodcast
                    SearchFilter.ALBUM -> it is AlbumItem
                    SearchFilter.ARTIST -> it is ArtistItem
                    SearchFilter.FEATURED_PLAYLIST, SearchFilter.COMMUNITY_PLAYLIST -> it is PlaylistItem
                    SearchFilter.PODCAST, SearchFilter.PROFILE -> false
                }
            }
        return SearchResultPage(local, onlineSearchFailed = wasConnected)
    }

    suspend fun getNewPodcastEpisodes(): List<SongItem> =
        try {
            val response =
                innerTube
                    .browse(
                        YouTubeClient.WEB_REMIX,
                        browseId = "VLRDPN",
                        setLogin = true,
                    ).bodyLimited<JsonObject>(FEED_RESPONSE_LIMIT)

            BlockedContent.filterSongs(YouTubePodcastEpisodeParser.parseNewPodcastEpisodes(response))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            YtLogger.e(TAG, "getNewPodcastEpisodes failed type=${e::class.simpleName ?: "unknown"}")
            emptyList()
        }

    suspend fun getStreamUrl(videoId: String): String? {
        val startTime =
            Clock.System
                .now()
                .toEpochMilliseconds()
        YtLogger.d(TAG, "getStreamUrl start")
        val resolver = streamResolverProvider() ?: return null
        return try {
            val resource =
                resolver.resolve(
                    videoId,
                    ContentHints(),
                )
            YtLogger.d(
                TAG,
                "getStreamUrl resolved elapsed=${
                    Clock.System
                        .now()
                        .toEpochMilliseconds() - startTime
                }ms",
            )
            resource.url
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            YtLogger.w(TAG, "getStreamUrl resolver failed type=${e::class.simpleName ?: "unknown"}")
            null
        }
    }

    suspend fun getSongPlayerInformation(videoId: String): SongPlayerInformation? =
        try {
            // WEB can omit live-video details; ANDROID exposes isLiveContent for those responses.
            val webResponse =
                innerTube.player(YouTubeClient.WEB, videoId).bodyLimited<PlayerResponse>(PLAYER_NEXT_RESPONSE_LIMIT)
            val details =
                webResponse.videoDetails?.takeIf {
                    it.title?.isNotBlank() == true && webResponse.playabilityStatus.status == "OK"
                } ?: run {
                    val androidResponse =
                        innerTube
                            .player(YouTubeClient.ANDROID, videoId)
                            .bodyLimited<PlayerResponse>(PLAYER_NEXT_RESPONSE_LIMIT)
                    androidResponse.videoDetails
                } ?: return null
            val isLive = details.isLiveContent == true
            SongPlayerInformation(
                title = details.title?.takeIf { it.isNotBlank() },
                artist = details.author?.takeIf { it.isNotBlank() },
                thumbnail =
                    details.thumbnail
                        ?.thumbnails
                        ?.lastOrNull()
                        ?.url,
                durationSeconds = details.lengthSeconds?.toLongOrNull(),
                viewCount = details.viewCount?.toLongOrNull(),
                musicVideoType = if (isLive) MUSIC_VIDEO_TYPE_LIVE else details.musicVideoType,
                isLive = isLive,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    suspend fun getSongWatchInformation(
        videoId: String,
        languageTag: String,
    ): SongWatchInformation? {
        val session = innerTube.sessionSnapshot()
        val requestTube = innerTube.createIsolatedSession(true)
        requestTube.locale = requestTube.locale.copy(hl = languageTag)
        return try {
            val response =
                requestTube.next(YouTubeClient.WEB, videoId = videoId).bodyLimited<JsonObject>(PLAYER_NEXT_RESPONSE_LIMIT)
            val secondaryInfo = response.findFirstObject("videoSecondaryInfoRenderer")
            val owner =
                secondaryInfo
                    ?.get("owner")
                    .asObject()
                    ?.get("videoOwnerRenderer")
                    .asObject()
            val likeEntity = response.findFirstObject("likeCountEntity")
            val viewEntity = response.findFirstObject("viewCountEntity")

            SongWatchInformation(
                artist =
                    owner
                        ?.get("title")
                        .asObject()
                        .runText()
                        ?.takeIf { it.isNotBlank() },
                subscriberCount =
                    owner
                        ?.get("subscriberCountText")
                        .asObject()
                        .runText()
                        .cleanSubscriberCount(),
                likeCount =
                    likeEntity?.longValue("likeCountIfIndifferentNumber")
                        ?: likeEntity?.longValue("likeCountIfLikedNumber")
                        ?: likeEntity?.get("expandedLikeCountIfIndifferent").textContent().parseIntegerCount()
                        ?: likeEntity?.get("expandedLikeCountIfLiked").textContent().parseIntegerCount(),
                viewCount =
                    viewEntity?.longValue("viewCountNumber")
                        ?: viewEntity?.get("unlabeledViewCountValue").textContent().parseIntegerCount(),
                description =
                    secondaryInfo
                        ?.get("attributedDescription")
                        .asObject()
                        ?.get("content")
                        .primitiveText()
                        ?.takeIf { it.isNotBlank() }
                        ?: secondaryInfo
                            ?.get("description")
                            .asObject()
                            .runText()
                            ?.takeIf { it.isNotBlank() },
            ).also { requireCurrentSession(session) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            requestTube.close()
        }
    }

    suspend fun likeVideo(videoId: String): Boolean =
        runSongVote { session -> innerTube.likeVideo(YouTubeClient.WEB_REMIX, videoId, session) }

    internal suspend fun likeVideos(
        videoIds: List<String>,
        onLiked: suspend (String) -> Unit = {},
    ): SongVoteBatchResult {
        val requestSession = innerTube.sessionSnapshot()
        if (!innerTube.hasSapCookieAuth(requestSession)) {
            return SongVoteBatchResult(
                authenticated = false,
                succeededIds = emptyList(),
            )
        }
        val succeeded = mutableListOf<String>()
        videoIds.forEach { videoId ->
            val success =
                try {
                    runSongVote(requestSession) { session ->
                        innerTube.likeVideo(
                            YouTubeClient.WEB_REMIX,
                            videoId,
                            session,
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
            if (success) {
                onLiked(videoId)
                succeeded += videoId
            }
        }
        return SongVoteBatchResult(authenticated = true, succeededIds = succeeded)
    }

    suspend fun dislikeVideo(videoId: String): Boolean =
        runSongVote { session -> innerTube.dislikeVideo(YouTubeClient.WEB_REMIX, videoId, session) }

    suspend fun unlikeVideo(videoId: String): Boolean =
        runSongVote { session -> innerTube.unlikeVideo(YouTubeClient.WEB_REMIX, videoId, session) }

    suspend fun deleteUploadedSong(entityId: String): Boolean =
        try {
            invalidateUploadedSongs()
            runSongVote { innerTube.deletePrivatelyOwnedEntity(YouTubeClient.WEB_REMIX, entityId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            YtLogger.e(TAG, "deleteUploadedSong failed (${e::class.simpleName})")
            false
        } finally {
            invalidateUploadedSongs()
        }

    private suspend fun runSongVote(request: suspend (InnerTube.SessionSnapshot) -> HttpResponse): Boolean =
        runSongVote(innerTube.sessionSnapshot(), request)

    private suspend fun runSongVote(
        requestSession: InnerTube.SessionSnapshot,
        request: suspend (InnerTube.SessionSnapshot) -> HttpResponse,
    ): Boolean {
        if (!innerTube.hasSapCookieAuth(requestSession)) return false
        if (innerTube.sessionSnapshot() != requestSession) throw CancellationException("YouTube session changed")
        val response =
            try {
                request(requestSession)
            } catch (error: ResponseException) {
                if (isExpiredYouTubeSessionResponse(error.response.status.value, hasAuthenticatedSession = true)) {
                    invalidateExpiredSession(requestSession)
                    return false
                }
                throw error
            }
        if (innerTube.sessionSnapshot() != requestSession) throw CancellationException("YouTube session changed")
        if (isExpiredYouTubeSessionResponse(response.status.value, hasAuthenticatedSession = true)) {
            invalidateExpiredSession(requestSession)
            return false
        }
        check(response.status.isSuccess()) { "Song vote failed with HTTP ${response.status.value}" }
        return true
    }

    suspend fun uploadSong(
        fileName: String,
        contentLength: Long,
        content: () -> ByteReadChannel,
    ): UploadSongResult =
        try {
            if (!innerTube.hasSapCookieAuth()) {
                return UploadSongResult(false, UploadFailure.SIGN_IN_REQUIRED)
            }
            val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            if (extension !in SUPPORTED_UPLOAD_EXTENSIONS) {
                return UploadSongResult(false, UploadFailure.UNSUPPORTED_TYPE)
            }
            if (contentLength <= 0L) {
                return UploadSongResult(
                    false,
                    UploadFailure.EMPTY_FILE,
                )
            }
            if (contentLength >= MAX_UPLOAD_BYTES) {
                return UploadSongResult(false, UploadFailure.TOO_LARGE)
            }

            invalidateUploadedSongs()
            val response =
                try {
                    innerTube.uploadSong(fileName, contentLength, content)
                } finally {
                    invalidateUploadedSongs()
                }
            val status = response.status.value
            response.bodyAsChannel().cancel(null)
            if (status in 200..299) {
                UploadSongResult(true)
            } else if (status == HTTP_CONFLICT) {
                // YouTube rejects files whose processed copy is already in the account's uploads.
                UploadSongResult(false, UploadFailure.ALREADY_UPLOADED, status)
            } else {
                UploadSongResult(false, UploadFailure.HTTP, status)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            YtLogger.e(TAG, "uploadSong failed (${e::class.simpleName})")
            UploadSongResult(false, UploadFailure.UNKNOWN)
        }

    private val historyOperations =
        YouTubeHistoryOperations(
            innerTube,
            ::requireCurrentSession,
            { session, browseId, continuation ->
                readAccountBrowse(session, "FEmusic_history", continuation != null) {
                    innerTube.browse(YouTubeClient.WEB_REMIX, browseId = browseId, continuation = continuation, setLogin = true)
                }
            },
            {
                val database = databaseProvider()
                if (database != null) database.syncYouTubeHistory(this) else getHistoryResult()
            },
        )

    fun isYouTubeHistoryAvailable(): Boolean = historyOperations.isYouTubeHistoryAvailable()

    fun isHistorySyncEnabled(): Boolean = historyOperations.isHistorySyncEnabled()

    suspend fun getHistory(): List<YouTubeHistoryEntry> = historyOperations.getHistory()

    suspend fun getHistoryResult(): YouTubeHistoryResult = historyOperations.getHistoryResult()

    suspend fun removeHistoryItems(feedbackTokens: List<String>) = historyOperations.removeHistoryItems(feedbackTokens)

    private val playbackTracking = YouTubePlaybackTracking(innerTube)

    suspend fun getPlaybackTrackingUrl(
        videoId: String,
        playlistId: String? = null,
    ): String? = playbackTracking.getPlaybackTrackingUrl(videoId, playlistId)

    fun playbackTrackingSession(): PlaybackTrackingSession = playbackTracking.playbackTrackingSession()

    fun isPlaybackTrackingSessionCurrent(playbackSession: PlaybackTrackingSession): Boolean =
        playbackTracking.isPlaybackTrackingSessionCurrent(playbackSession)

    internal fun playbackReportingAccountKey(playbackSession: PlaybackTrackingSession = playbackTrackingSession()): String? =
        playbackTracking.playbackReportingAccountKey(playbackSession)

    internal suspend fun getPlaybackTracking(
        videoId: String,
        clientPlaybackNonce: String,
        playbackSession: PlaybackTrackingSession,
    ): PlaybackTrackingData? = playbackTracking.getPlaybackTracking(videoId, clientPlaybackNonce, playbackSession)

    internal fun isPlaybackReportingEnabled(): Boolean = playbackTracking.isPlaybackReportingEnabled()

    internal suspend fun registerPlayback(
        playbackTrackingUrl: String,
        clientPlaybackNonce: String,
        currentMediaTime: String,
        format: Int?,
        playbackSession: PlaybackTrackingSession,
    ): Boolean =
        playbackTracking.registerPlayback(
            playbackTrackingUrl,
            clientPlaybackNonce,
            currentMediaTime,
            format,
            playbackSession,
        )

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
    ): Boolean =
        playbackTracking.registerWatchtime(
            watchtimeTrackingUrl,
            clientPlaybackNonce,
            startTimes,
            endTimes,
            currentMediaTime,
            realTime,
            final,
            format,
            playbackSession,
        )

    suspend fun getUserPlaylists(): List<PlaylistItem> = getUserPlaylistsResult().items.filterIsInstance<PlaylistItem>()

    private data class CachedPlaylistCount(
        val resolvedAt: Long,
        val sourceCount: String?,
        val count: String?,
        val author: String?,
    )

    private val playlistCounts = mutableMapOf<Pair<InnerTube.SessionSnapshot, String>, CachedPlaylistCount>()

    suspend fun resolveMissingPlaylistCounts(
        items: List<YTItem>,
        onResolved: suspend (PlaylistItem) -> Unit = {},
    ): List<YTItem> =
        coroutineScope {
            val requestSession = innerTube.sessionSnapshot()
            val now = Clock.System.now().toEpochMilliseconds()
            synchronized(playlistLibraryLock) {
                playlistCounts.entries.removeAll { it.key.first != requestSession || now - it.value.resolvedAt >= 5 * 60_000L }
            }
            items.chunked(PLAYLIST_EDITABILITY_CONCURRENCY).flatMap { batch ->
                batch
                    .map { item ->
                        async {
                            if (item !is PlaylistItem || parseDisplayCount(item.songCount) != null) return@async item
                            val key = requestSession to item.id
                            val cached =
                                synchronized(playlistLibraryLock) { playlistCounts[key] }
                                    ?.takeIf { it.sourceCount == item.songCount }
                            val resolved =
                                cached ?: try {
                                    requireCurrentSession(requestSession)
                                    val detail =
                                        getPlaylistPageData(item.id, applyContentFilters = false)
                                            .sections["header"]
                                            ?.firstOrNull() as? PlaylistItem
                                    requireCurrentSession(requestSession)
                                    CachedPlaylistCount(
                                        now,
                                        item.songCount,
                                        detail?.songCount?.takeIf { parseDisplayCount(it) != null },
                                        detail?.author,
                                    ).also { synchronized(playlistLibraryLock) { playlistCounts[key] = it } }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    null
                                }
                            item.copy(songCount = resolved?.count ?: item.songCount, author = item.author ?: resolved?.author)
                        }
                    }.awaitAll()
                    .also { resolved ->
                        requireCurrentSession(requestSession)
                        resolved.zip(batch).forEach { (item, original) ->
                            if (item is PlaylistItem && item != original) onResolved(item)
                        }
                    }
            }
        }

    suspend fun getUserPlaylistsResult(): LibraryItemsResult =
        try {
            val result = getPlaylistLibraryResult(innerTube.sessionSnapshot())
            val playlists = result.items.filterIsInstance<PlaylistItem>().filter(PlaylistItem::isUserPlaylist)
            result.copy(items = LibraryDeletionTracker.filterDeleted(playlists))
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            YtLogger.e(TAG, "Playlist library failed type=${error::class.simpleName}")
            LibraryItemsResult(emptyList(), complete = false)
        }

    private val playlistLibraryLock = SynchronizedObject()
    private val playlistLibraryRequests =
        mutableMapOf<InnerTube.SessionSnapshot, CompletableDeferred<LibraryItemsResult>>()

    private suspend fun getPlaylistLibraryResult(requestSession: InnerTube.SessionSnapshot): LibraryItemsResult {
        requireCurrentSession(requestSession)
        while (true) {
            val request = CompletableDeferred<LibraryItemsResult>()
            val pending = synchronized(playlistLibraryLock) { playlistLibraryRequests.getOrPut(requestSession) { request } }
            if (pending !== request) {
                try {
                    return pending.await().also { requireCurrentSession(requestSession) }
                } catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    requireCurrentSession(requestSession)
                    synchronized(playlistLibraryLock) {
                        if (playlistLibraryRequests[requestSession] ===
                            pending
                        ) {
                            playlistLibraryRequests.remove(requestSession)
                        }
                    }
                    continue
                }
            }
            try {
                val result = getAllBrowseItems("FEmusic_liked_playlists", requestSession = requestSession)
                val unresolvedIds = mutableSetOf<String>()
                val items =
                    coroutineScope {
                        result.items
                            .chunked(PLAYLIST_EDITABILITY_CONCURRENCY)
                            .flatMap { batch ->
                                batch
                                    .map { item ->
                                        async {
                                            if (item is PlaylistItem && item.isUserPlaylist()) {
                                                item.withResolvedEditability(requestSession).let { item to it }
                                            } else {
                                                item to null
                                            }
                                        }
                                    }.awaitAll()
                            }.map { (item, resolution) ->
                                if (resolution == null) {
                                    item
                                } else {
                                    if (!resolution.resolved) unresolvedIds += item.id
                                    resolution.playlist
                                }
                            }
                    }
                requireCurrentSession(requestSession)
                return LibraryItemsResult(items, result.complete && unresolvedIds.isEmpty(), unresolvedIds).also(request::complete)
            } catch (error: Throwable) {
                request.completeExceptionally(error)
                throw error
            } finally {
                synchronized(playlistLibraryLock) {
                    if (playlistLibraryRequests[requestSession] ===
                        request
                    ) {
                        playlistLibraryRequests.remove(requestSession)
                    }
                }
            }
        }
    }

    private val playlistEditability = mutableMapOf<Pair<InnerTube.SessionSnapshot, String>, Pair<Long, Boolean>>()

    private data class PlaylistEditabilityResolution(
        val playlist: PlaylistItem,
        val resolved: Boolean,
    )

    private suspend fun PlaylistItem.withResolvedEditability(requestSession: InnerTube.SessionSnapshot): PlaylistEditabilityResolution =
        if (!id.isEditablePlaylistId()) {
            PlaylistEditabilityResolution(copy(isEditable = false), resolved = true)
        } else if (isEditable) {
            PlaylistEditabilityResolution(this, resolved = true)
        } else {
            try {
                val isEditable = resolvePlaylistEditability(id, requestSession)
                PlaylistEditabilityResolution(
                    playlist = if (isEditable == null) this else copy(isEditable = isEditable),
                    resolved = isEditable != null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: PlaylistNotFoundException) {
                PlaylistEditabilityResolution(copy(isEditable = false), resolved = true)
            } catch (_: Exception) {
                requireCurrentSession(requestSession)
                PlaylistEditabilityResolution(this, resolved = false)
            }
        }

    fun canManagePlaylists(): Boolean = playlistEdits.canManagePlaylists()

    internal suspend fun saveAlbumToLibrary(albumId: String): AlbumItem {
        val session = innerTube.sessionSnapshot()
        check(innerTube.hasSapCookieAuth(session))
        val response = innerTube.browse(YouTubeClient.WEB_REMIX, browseId = albumId, setLogin = true)
        check(response.status.isSuccess()) { "Album browse failed with HTTP ${response.status.value}" }
        val album =
            checkNotNull(
                YouTubeAlbumParser.parseAlbumHeader(response.bodyLimited<BrowseResponse>(FEED_RESPONSE_LIMIT), albumId),
            ).item
        check(album.title.isNotBlank())
        val playlistId = checkNotNull(album.playlistId?.takeIf { it.isNotBlank() })
        requireCurrentSession(session)
        setPlaylistSaved(playlistId, true).getOrThrow()
        requireCurrentSession(session)
        return album
    }

    suspend fun setPlaylistSaved(
        playlistId: String,
        saved: Boolean,
    ): Result<Unit> = playlistEdits.setPlaylistSaved(playlistId, saved)

    private suspend fun resolvePlaylistEditability(
        playlistId: String,
        requestSession: InnerTube.SessionSnapshot,
    ): Boolean? {
        requireCurrentSession(requestSession)
        if (!playlistId.isEditablePlaylistId()) return false
        val key = requestSession to playlistId
        val now =
            Clock.System
                .now()
                .toEpochMilliseconds()
        synchronized(playlistLibraryLock) {
            playlistEditability.entries.removeAll { it.key.first != requestSession || now - it.value.first >= 5 * 60_000L }
            playlistEditability[key]?.let { return it.second }
        }
        val response =
            try {
                innerTube.browse(
                    YouTubeClient.WEB_REMIX,
                    browseId = playlistId.toPlaylistBrowseId(),
                    setLogin = true,
                )
            } catch (e: InnerTubeHttpException) {
                if (
                    isExpiredYouTubeSessionResponse(
                        e.status.value,
                        innerTube.hasSapCookieAuth(requestSession),
                    )
                ) {
                    invalidateExpiredSession(requestSession)
                    throw YouTubeSessionExpiredException()
                }
                requireCurrentSession(requestSession)
                if (e.status.value == 404) {
                    onPlaylistNotFound(playlistId)
                    throw PlaylistNotFoundException(playlistId)
                }
                return null
            }
        requireCurrentSession(requestSession)
        if (response.status.value == 404) {
            onPlaylistNotFound(playlistId)
            throw PlaylistNotFoundException(playlistId)
        }
        if (
            isExpiredYouTubeSessionResponse(
                response.status.value,
                innerTube.hasSapCookieAuth(requestSession),
            )
        ) {
            invalidateExpiredSession(requestSession)
            throw YouTubeSessionExpiredException()
        }
        if (!response.status.isSuccess()) return null
        return withContext(responseDecodeDispatcher) {
            response
                .bodyLimited<JsonObject>(FEED_RESPONSE_LIMIT)
                .hasPlaylistEditControls()
        }.also { editable ->
            requireCurrentSession(requestSession)
            synchronized(playlistLibraryLock) {
                playlistEditability[key] = now to editable
                while (playlistEditability.size > 500) playlistEditability.remove(playlistEditability.keys.first())
            }
        }
    }

    suspend fun createPlaylist(title: String): Result<String> = playlistEdits.createPlaylist(title)

    suspend fun renamePlaylist(
        playlistId: String,
        title: String,
    ): Result<Unit> = playlistEdits.renamePlaylist(playlistId, title)

    suspend fun setPlaylistDescription(
        playlistId: String,
        description: String,
    ): Result<Unit> = playlistEdits.setPlaylistDescription(playlistId, description)

    suspend fun setPlaylistThumbnail(
        playlistId: String,
        image: ByteArray,
    ): Result<String?> = playlistEdits.setPlaylistThumbnail(playlistId, image)

    suspend fun removePlaylistThumbnail(playlistId: String): Result<String?> = playlistEdits.removePlaylistThumbnail(playlistId)

    suspend fun deletePlaylist(playlistId: String): Result<Unit> =
        editPlaylistMembership(playlistId) { playlistEdits.deletePlaylist(playlistId) }

    suspend fun addPlaylistToPlaylist(
        playlistId: String,
        sourcePlaylistId: String,
    ): Result<Unit> =
        editPlaylistMembership(playlistId) { playlistEdits.addPlaylistToPlaylist(playlistId, sourcePlaylistId) }.also {
            if (it.isSuccess) PlaylistChanges.notifyChanged(playlistId)
        }

    suspend fun addToPlaylist(
        playlistId: String,
        videoId: String,
        allowDuplicate: Boolean = true,
        notifyChange: Boolean = true,
    ): Result<Unit> =
        try {
            playlistEdits.addToPlaylist(playlistId, videoId, allowDuplicate).also {
                if (it.isFailure && it.exceptionOrNull() !is PlaylistSongAlreadyPresentException) {
                    playlistMembership.invalidate(innerTube.sessionSnapshot(), playlistId)
                }
                if (it.isSuccess && notifyChange) PlaylistChanges.notifyChanged(playlistId)
            }
        } catch (error: CancellationException) {
            playlistMembership.invalidate(innerTube.sessionSnapshot(), playlistId)
            throw error
        }

    suspend fun movePlaylistSong(
        playlistId: String,
        setVideoId: String,
        successorSetVideoId: String?,
    ): Result<Unit> = playlistEdits.movePlaylistSong(playlistId, setVideoId, successorSetVideoId)

    suspend fun removePlaylistSong(
        playlistId: String,
        setVideoId: String,
        videoId: String,
    ): Result<Unit> = editPlaylistMembership(playlistId) { playlistEdits.removePlaylistSong(playlistId, setVideoId, videoId) }

    suspend fun removePlaylistSongs(
        playlistId: String,
        songs: List<Pair<String, String>>,
    ): Result<Unit> = editPlaylistMembership(playlistId) { playlistEdits.removePlaylistSongs(playlistId, songs) }

    private suspend fun editPlaylistMembership(
        playlistId: String,
        edit: suspend () -> Result<Unit>,
    ): Result<Unit> {
        val session = innerTube.sessionSnapshot()
        playlistMembership.invalidate(session, playlistId)
        return try {
            edit()
        } finally {
            playlistMembership.invalidate(session, playlistId)
        }
    }

    private data class NextRequestKey(
        val session: InnerTube.SessionSnapshot,
        val videoId: String?,
        val playlistId: String?,
        val params: String?,
        val continuation: String?,
    )

    private val nextRequestsLock = SynchronizedObject()
    private val nextRequests = mutableMapOf<NextRequestKey, CompletableDeferred<JsonObject>>()

    private suspend fun nextResponse(
        videoId: String? = null,
        playlistId: String? = null,
        params: String? = null,
        continuation: String? = null,
    ): JsonObject {
        val session = innerTube.sessionSnapshot()
        val voteRevision = SongLikeState.voteObservationRevision()
        val key = NextRequestKey(session, videoId, playlistId, params, continuation)
        while (true) {
            val request = CompletableDeferred<JsonObject>()
            val pending = synchronized(nextRequestsLock) { nextRequests.getOrPut(key) { request } }
            if (pending !== request) {
                try {
                    return pending.await().also { requireCurrentSession(session) }
                } catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    requireCurrentSession(session)
                    synchronized(nextRequestsLock) { if (nextRequests[key] === pending) nextRequests.remove(key) }
                    continue
                }
            }
            try {
                requireCurrentSession(session)
                val response =
                    innerTube.next(
                        YouTubeClient.WEB_REMIX,
                        videoId,
                        playlistId,
                        params = params,
                        continuation = continuation,
                    )
                if (!response.status.isSuccess()) YtLogger.w(TAG, "Song next status=${response.status.value}")
                check(response.status.isSuccess()) { "Next request failed with HTTP ${response.status.value}" }
                val body = response.bodyLimited<JsonObject>(PLAYER_NEXT_RESPONSE_LIMIT)
                requireCurrentSession(session)
                if (session.useLoginForBrowse && innerTube.hasSapCookieAuth(session)) {
                    reconcileSongVotes(body, voteRevision)
                }
                return body.also(request::complete)
            } catch (error: Throwable) {
                request.completeExceptionally(error)
                throw error
            } finally {
                synchronized(nextRequestsLock) { if (nextRequests[key] === request) nextRequests.remove(key) }
            }
        }
    }

    suspend fun startRadio(videoId: String): List<SongItem> = startRadioQueue(videoId).songs

    suspend fun startRadioQueue(
        videoId: String,
        playlistId: String = "RDAMVM$videoId",
        params: String? = null,
    ): RadioQueuePage {
        val page = parseRadioQueueResponse(nextResponse(videoId, playlistId, params))
        return page.copy(songs = BlockedContent.filterSongs(page.songs))
    }

    suspend fun continueRadioQueue(continuation: String): RadioQueuePage {
        val page = parseRadioQueueResponse(nextResponse(continuation = continuation))
        return page.copy(songs = BlockedContent.filterSongs(page.songs))
    }

    suspend fun getMusicVideoCounterpart(
        videoId: String,
        params: String? = null,
    ): MusicVideoCounterpart? {
        val response = nextResponse(videoId = videoId, params = params)
        return parseMusicVideoCounterpart(response, videoId).also { counterpart ->
            if (counterpart == null) {
                YtLogger.d(TAG, "No authoritative music video counterpart in /next response")
            }
        }
    }

    suspend fun startArtistRadio(channelId: String): List<SongItem> {
        return try {
            val session = innerTube.sessionSnapshot()
            val artistResponse =
                innerTube
                    .browse(YouTubeClient.WEB_REMIX, browseId = channelId, setLogin = true)
                    .bodyLimited<BrowseResponse>(FEED_RESPONSE_LIMIT)
            requireCurrentSession(session)
            val endpoint = YouTubeArtistParser.parseArtistRadioEndpoint(artistResponse) ?: return emptyList()
            val playlistId = endpoint["playlistId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return emptyList()
            val response =
                nextResponse(
                    videoId = endpoint["videoId"]?.jsonPrimitive?.contentOrNull,
                    playlistId = playlistId,
                    params = endpoint["params"]?.jsonPrimitive?.contentOrNull,
                )
            requireCurrentSession(session)
            BlockedContent.filterSongs(parseRadioResponse(response))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getSongMetadata(videoId: String): SongItem? = getSongsMetadata(listOf(videoId)).firstOrNull()

    suspend fun getSongsMetadata(videoIds: List<String>): List<SongItem> {
        if (!PlaybackNetworkStateStore.current.connected) return offlineSongMetadata(videoIds)
        val session = innerTube.sessionSnapshot()
        return videoIds.distinct().chunked(50).flatMap { ids ->
            try {
                requireCurrentSession(session)
                val response = innerTube.getQueue(YouTubeClient.WEB_REMIX, videoIds = ids, playlistId = null)
                if (!response.status.isSuccess()) YtLogger.w(TAG, "Song queue metadata status=${response.status.value}")
                check(response.status.isSuccess())
                val songs =
                    response
                        .bodyLimited<GetQueueResponse>(PLAYER_NEXT_RESPONSE_LIMIT)
                        .queueDatas
                        .orEmpty()
                        .mapNotNull { it.content?.let(::parsePlaylistPanelItem) }
                        .filter { it.id in ids && it.title.isNotBlank() }
                requireCurrentSession(session)
                songs
            } catch (e: CancellationException) {
                throw e
            } catch (error: Exception) {
                YtLogger.e(TAG, "Song queue metadata failed type=${error::class.simpleName}")
                offlineSongMetadata(ids)
            }
        }
    }

    private suspend fun offlineSongMetadata(videoIds: List<String>): List<SongItem> {
        val available = offlineSongAvailabilityProvider()?.songIds()
        val songs =
            databaseProvider()
                ?.dao()
                ?.getSongsByIds(videoIds)
                ?.filter { it.isAvailableOffline(available) }
                ?.associateBy { it.id }
                .orEmpty()
        return videoIds.distinct().mapNotNull { songs[it]?.toSongItem() }
    }

    suspend fun resolveSongAlbum(song: SongItem): AlbumTiny? {
        if (song.isLocal || song.isUploaded || song.isPodcast || song.album?.id.isAlbumBrowseId()) return song.album
        val resolved =
            getSongAlbum(song.id)?.takeIf { it.id.isAlbumBrowseId() }
                ?: getSongMetadata(song.id)?.album?.takeIf { it.id.isAlbumBrowseId() }
                ?: song.album?.takeIf { it.id == null }
        YtLogger.w(TAG, "Song album videoId=${song.id} suppliedId=${song.album?.id} resolvedId=${resolved?.id}")
        return resolved
    }

    suspend fun getSongAlbum(videoId: String): AlbumTiny? {
        return try {
            val response = nextResponse(videoId = videoId)

            val contents =
                response["contents"]
                    ?.jsonObject
                    ?.get("singleColumnMusicWatchNextResultsRenderer")
                    ?.jsonObject
                    ?.get("tabbedRenderer")
                    ?.jsonObject
                    ?.get("watchNextTabbedResultsRenderer")
                    ?.jsonObject
                    ?.get("tabs")
                    ?.jsonArray
                    ?.getOrNull(0)
                    ?.jsonObject
                    ?.get("tabRenderer")
                    ?.jsonObject
                    ?.get("content")
                    ?.jsonObject
                    ?.get("musicQueueRenderer")
                    ?.jsonObject
                    ?.get("content")
                    ?.jsonObject
                    ?.get("playlistPanelRenderer")
                    ?.jsonObject
                    ?.get("contents")
                    ?.jsonArray

            val renderer =
                contents
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("playlistPanelVideoRenderer")
                    ?.jsonObject ?: return null

            if (renderer["videoId"]?.jsonPrimitive?.contentOrNull != videoId) return null

            val runs = renderer["longBylineText"]?.jsonObject?.get("runs")?.jsonArray
            runs?.forEach { run ->
                val text = run.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val browseId =
                    run.jsonObject["navigationEndpoint"]
                        ?.jsonObject
                        ?.get("browseEndpoint")
                        ?.jsonObject
                        ?.get("browseId")
                        ?.jsonPrimitive
                        ?.contentOrNull
                if (browseId != null &&
                    (browseId.startsWith("MPREb") || browseId.contains("release_detail"))
                ) {
                    return AlbumTiny(browseId, text)
                }
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            YtLogger.e(TAG, "Song album videoId=$videoId lookup failed type=${error::class.simpleName}")
            null
        }
    }

    internal val hasAuthenticatedSession: Boolean get() = innerTube.hasSapCookieAuth()

    suspend fun subscribeChannel(
        channelId: String,
        subscribe: Boolean,
    ): Boolean =
        try {
            val response =
                if (subscribe) {
                    innerTube.subscribeChannel(YouTubeClient.WEB_REMIX, channelId)
                } else {
                    innerTube.unsubscribeChannel(YouTubeClient.WEB_REMIX, channelId)
                }
            check(response.status.isSuccess()) { "Subscription update failed with HTTP ${response.status.value}" }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    suspend fun fetchArtistThumbnail(browseId: String): String? =
        try {
            val response =
                innerTube
                    .browse(
                        YouTubeClient.WEB_REMIX,
                        browseId = browseId,
                        setLogin = true,
                    ).bodyLimited<BrowseResponse>(FEED_RESPONSE_LIMIT)
            val header = response.header
            val h =
                header?.musicImmersiveHeaderRenderer
                    ?: header?.musicVisualHeaderRenderer
                    ?: header?.musicHeaderRenderer
                    ?: header?.musicDetailHeaderRenderer
            val thumbnail =
                h
                    ?.get(
                        "thumbnail",
                    )?.jsonObject
                    ?.get(
                        "musicThumbnailRenderer",
                    )?.jsonObject
                    ?.get(
                        "thumbnail",
                    )?.jsonObject
                    ?.get("thumbnails")
                    ?.jsonArray
                    ?.lastOrNull()
                    ?.jsonObject
                    ?.get("url")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: h
                        ?.get(
                            "foregroundThumbnail",
                        )?.jsonObject
                        ?.get(
                            "musicThumbnailRenderer",
                        )?.jsonObject
                        ?.get(
                            "thumbnail",
                        )?.jsonObject
                        ?.get("thumbnails")
                        ?.jsonArray
                        ?.lastOrNull()
                        ?.jsonObject
                        ?.get("url")
                        ?.jsonPrimitive
                        ?.contentOrNull
                    ?: h
                        ?.get(
                            "backgroundImage",
                        )?.jsonObject
                        ?.get(
                            "musicThumbnailRenderer",
                        )?.jsonObject
                        ?.get(
                            "thumbnail",
                        )?.jsonObject
                        ?.get("thumbnails")
                        ?.jsonArray
                        ?.lastOrNull()
                        ?.jsonObject
                        ?.get("url")
                        ?.jsonPrimitive
                        ?.contentOrNull
            thumbnail
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
}

internal fun artistSectionsWithLatestRelease(sections: Map<String, List<YTItem>>): Map<String, List<YTItem>> {
    // shortcut: Year-only cards use server order for ties; use full dates when the API exposes them.
    val artist = sections["header"]?.filterIsInstance<ArtistItem>()?.firstOrNull()
    val latest =
        sections.values
            .flatten()
            .filterIsInstance<AlbumItem>()
            .filter { album ->
                artist == null || album.artists.isEmpty() ||
                    album.artists.any { it.id == artist.id || it.name == artist.title }
            }.maxWithOrNull(compareBy { it.year?.toIntOrNull() })
            ?: return sections.filterKeys { it != "latest_release" }
    return linkedMapOf<String, List<YTItem>>("latest_release" to listOf(latest)).apply {
        putAll(sections.filterKeys { it != "latest_release" })
    }
}

internal fun PlaylistItem.isUserPlaylist(): Boolean =
    !id.equals("LM", ignoreCase = true) &&
        !id.equals("VLLM", ignoreCase = true) &&
        !id.equals("RDPN", ignoreCase = true) &&
        !id.equals("VLRDPN", ignoreCase = true) &&
        !id.equals("SE", ignoreCase = true) &&
        !id.equals("VLSE", ignoreCase = true)

internal fun String.isMixPlaylistId(): Boolean = removePrefix("VL").let { it.startsWith("RD") && !it.startsWith("RDCL") && it != "RDPN" }

internal fun String.toPlaylistBrowseId(): String =
    if (
        startsWith("FEmusic_library_") ||
        startsWith("VL") ||
        startsWith("LM:")
    ) {
        this
    } else {
        "VL$this"
    }

internal fun String?.isAlbumBrowseId(): Boolean = this != null && (startsWith("MPREb") || startsWith("FEmusic_release_detail"))

internal fun JsonObject.isYouTubeLoggedOut(): Boolean =
    this["responseContext"]
        .asObject()
        ?.get("mainAppWebResponseContext")
        .asObject()
        ?.get("loggedOut")
        ?.jsonPrimitive
        ?.booleanOrNull == true
