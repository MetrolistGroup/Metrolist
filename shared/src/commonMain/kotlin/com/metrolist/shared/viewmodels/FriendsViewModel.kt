package com.metrolist.shared.viewmodels

import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.api.lastfm.LastFmFriend
import com.metrolist.shared.api.lastfm.LastFmRecentTrack
import com.metrolist.shared.api.lastfm.artistName
import com.metrolist.shared.api.lastfm.largestAvatar
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.utils.getCurrentTimeMillis
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds

const val FRIENDS_REFRESH_INTERVAL_MS = 60_000L
const val FRIENDS_BACKGROUND_REFRESH_INTERVAL_MS = 5 * 60_000L
const val FRIENDS_ACTIVITY_WINDOW_DAYS = 30
private const val MAX_FRIENDS_CHECKED = 30
private const val MAX_FRIENDS_DISPLAYED = 20
private const val MAX_CONCURRENT_FRIEND_FETCHES = 4

private val friendsCacheJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

internal fun encodeFriendsCache(entries: List<FriendEntry>): String = friendsCacheJson.encodeToString(entries)

internal fun decodeFriendsCache(raw: String): List<FriendEntry> =
    try {
        friendsCacheJson.decodeFromString<List<FriendEntry>>(raw)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        emptyList()
    }

@Serializable
data class FriendEntry(
    val name: String,
    val realName: String = "",
    val avatarUrl: String? = null,
    val profileUrl: String? = null,
    val track: String? = null,
    val artist: String? = null,
    val albumCoverUrl: String? = null,
    val isNowPlaying: Boolean = false,
    val lastPlayedAtSeconds: Long? = null,
    val isSelf: Boolean = false,
)

data class FriendsUiState(
    val friends: List<FriendEntry> = emptyList(),
    val selfEntry: FriendEntry? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isEnabled: Boolean = false,
)

class FriendsViewModel(
    private val scope: CoroutineScope,
    private val httpClient: HttpClient = GlobalYouTube.client,
) {
    private val _uiState = MutableStateFlow(FriendsUiState())
    val uiState: StateFlow<FriendsUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null
    private var activeIntervalMs = FRIENDS_REFRESH_INTERVAL_MS
    private var generation = 0L
    private var lastWrittenCache = ""
    private var lastDataHash = 0
    private val friendFetchesSemaphore = Semaphore(MAX_CONCURRENT_FRIEND_FETCHES)

    init {
        loadCachedFriends()
    }

    private fun loadCachedFriends() {
        val username = Settings.shared.lastFmUsername
        if (!Settings.shared.lastFmShowFriends || username.isBlank() || !LastFM.apiKeyConfigured()) return
        if (username != Settings.shared.lastFmFriendsCacheUser) {
            clearCache()
            return
        }
        val nowSeconds = getCurrentTimeMillis() / 1000
        val cachedEntries = decodeFriendsCache(Settings.shared.lastFmFriendsCacheJson)
        _uiState.update {
            it.copy(friends = cachedEntries.toDisplayEntries(nowSeconds), isEnabled = true)
        }
    }

    private fun clearCache() {
        Settings.shared.lastFmFriendsCacheUser = ""
        Settings.shared.lastFmFriendsCacheJson = ""
        lastWrittenCache = ""
    }

    fun startPolling(intervalMs: Long = FRIENDS_REFRESH_INTERVAL_MS) {
        if (!shouldRestartPolling(pollingJob, activeIntervalMs, intervalMs)) return
        stopPolling()
        activeIntervalMs = intervalMs
        val requestGeneration = ++generation
        pollingJob =
            scope.launch {
                while (isActive) {
                    try {
                        refresh(requestGeneration)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        _uiState.update { it.copy(isLoading = false, error = error.message.orEmpty()) }
                    }
                    delay(intervalMs.milliseconds)
                }
            }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    suspend fun refresh() {
        refresh(generation)
    }

    private suspend fun refresh(requestGeneration: Long) {
        if (requestGeneration != this@FriendsViewModel.generation) return
        val username = Settings.shared.lastFmUsername
        val isEnabled =
            Settings.shared.lastFmShowFriends && username.isNotBlank() && LastFM.apiKeyConfigured()
        if (!isEnabled) {
            if (username.isBlank() || username != Settings.shared.lastFmFriendsCacheUser) clearCache()
            _uiState.update {
                it.copy(isEnabled = false, friends = emptyList(), error = null, isLoading = false)
            }
            return
        }
        if (username != Settings.shared.lastFmFriendsCacheUser && Settings.shared.lastFmFriendsCacheUser.isNotBlank()) {
            clearCache()
            _uiState.update { it.copy(friends = emptyList(), error = null, isLoading = false) }
        }
        _uiState.update { it.copy(isEnabled = true, isLoading = true, error = null) }

        val nowSeconds = getCurrentTimeMillis() / 1000
        coroutineScope {
            val selfEntry =
                async {
                    LastFM.getRecentTrack(httpClient, username).getOrNull()?.let { track ->
                        FriendEntry(
                            name = username,
                            avatarUrl = null,
                            profileUrl = "https://www.last.fm/user/$username",
                            track = track.name.takeIf { it.isNotBlank() },
                            artist = track.artistName(),
                            albumCoverUrl = track.image.largestAvatar(),
                            isNowPlaying = track.attr?.nowplaying == "true",
                            lastPlayedAtSeconds = track.date?.uts?.toLongOrNull(),
                            isSelf = true,
                        )
                    }
                }

            val friendsDeferred =
                async {
                    LastFM.getFriends(httpClient, username, limit = MAX_FRIENDS_CHECKED)
                }

            val friendsResult = friendsDeferred.await()
            friendsResult
                .onSuccess { friends ->
                    val entries = loadFriendEntries(friends, nowSeconds)
                    val self = selfEntry.await()
                    val allEntries =
                        if (self != null) {
                            listOf(self) + entries.filter { it.name != username }
                        } else {
                            entries
                        }
                    if (requestGeneration != this@FriendsViewModel.generation) return@onSuccess
                    _uiState.update {
                        it.copy(
                            friends = allEntries.toDisplayEntries(nowSeconds),
                            selfEntry = self,
                            isLoading = false,
                            error = null,
                        )
                    }
                    persistCache(username, allEntries)
                }.onFailure { error ->
                    if (requestGeneration != this@FriendsViewModel.generation) return@onFailure
                    val self = selfEntry.await()
                    val entries = if (self != null) listOf(self) else emptyList()
                    _uiState.update {
                        it.copy(
                            friends = entries.toDisplayEntries(nowSeconds),
                            selfEntry = self,
                            isLoading = false,
                            error = error.message.orEmpty(),
                        )
                    }
                }
        }
    }

    private fun persistCache(
        username: String,
        entries: List<FriendEntry>,
    ) {
        val key = entries.map { "${it.name}:${it.track}:${it.artist}:${it.isNowPlaying}:${it.lastPlayedAtSeconds}" }
        val dataHash = key.hashCode()
        if (dataHash == lastDataHash) return
        val encoded = encodeFriendsCache(entries)
        if (encoded == lastWrittenCache) return
        Settings.shared.lastFmFriendsCacheUser = username
        Settings.shared.lastFmFriendsCacheJson = encoded
        lastWrittenCache = encoded
        lastDataHash = dataHash
    }

    private suspend fun loadFriendEntries(
        friends: List<LastFmFriend>,
        nowSeconds: Long,
    ): List<FriendEntry> =
        coroutineScope {
            friends
                .take(MAX_FRIENDS_CHECKED)
                .map { friend ->
                    async {
                        friendFetchesSemaphore.withPermit {
                            val recentTrack = LastFM.getRecentTrack(httpClient, friend.name).getOrNull()
                            friend.toFriendEntry(recentTrack, nowSeconds)
                        }
                    }
                }.awaitAll()
        }
}

internal fun LastFmFriend.toFriendEntry(
    recentTrack: LastFmRecentTrack?,
    nowSeconds: Long,
): FriendEntry =
    FriendEntry(
        name = name,
        realName = realname,
        avatarUrl = image.largestAvatar(),
        profileUrl = url.takeIf { it.isNotBlank() },
        track = recentTrack?.name?.takeIf { it.isNotBlank() },
        artist = recentTrack?.artistName(),
        albumCoverUrl = recentTrack?.image?.largestAvatar(),
        isNowPlaying = recentTrack?.attr?.nowplaying == "true",
        lastPlayedAtSeconds = recentTrack?.date?.uts?.toLongOrNull(),
    )

internal fun List<FriendEntry>.toDisplayEntries(
    nowSeconds: Long,
    windowDays: Int = FRIENDS_ACTIVITY_WINDOW_DAYS,
): List<FriendEntry> {
    val self = firstOrNull { it.isSelf }
    val others = filter { !it.isSelf }

    val windowStartSeconds = nowSeconds - windowDays * 24L * 60L * 60L
    val filteredOthers =
        others
            .filter { entry ->
                entry.isNowPlaying || (entry.lastPlayedAtSeconds ?: 0L) >= windowStartSeconds
            }.sortedWith(
                compareByDescending<FriendEntry> { it.isNowPlaying }
                    .thenByDescending { it.lastPlayedAtSeconds },
            ).distinctBy { it.name }

    val result = mutableListOf<FriendEntry>()
    if (self != null) result.add(self)
    result.addAll(filteredOthers)
    return result.take(MAX_FRIENDS_DISPLAYED)
}

fun relativeTimeText(elapsedSeconds: Long): String {
    if (elapsedSeconds < 60) return "now"
    val minutes = elapsedSeconds / 60
    if (minutes < 60) return "${minutes}m"
    val hours = minutes / 60
    if (hours < 24) return "${hours}h"
    val days = hours / 24
    return "${days}d"
}

internal fun shouldRestartPolling(
    pollingJob: Job?,
    activeIntervalMs: Long,
    requestedIntervalMs: Long,
): Boolean {
    val activeWithSameInterval = pollingJob?.isActive == true && activeIntervalMs == requestedIntervalMs
    return !activeWithSameInterval
}
