package com.metrolist.shared.auth

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.AccountInfo
import com.metrolist.innertubex.models.response.AccountsListResponse
import com.metrolist.innertubex.utils.parseCookieString
import com.metrolist.innertubex.utils.sanitizeCookieString
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.constants.DelegatedSessionDataSyncIdKey
import com.metrolist.shared.constants.DelegatedSessionIdKey
import com.metrolist.shared.playlist.LibraryDeletionTracker
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalInnerTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.utils.clearWebCookies
import com.metrolist.shared.youtube.logging.YtLogger
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val supportedAuthCookieNames =
    listOf(
        "SAPISID",
        "__Secure-3PAPISID",
        "__Secure-1PAPISID",
    )

internal fun supportedYouTubeAuthCredential(cookie: String): String? {
    val parsed = parseCookieString(cookie)
    return supportedAuthCookieNames.firstNotNullOfOrNull { name -> parsed[name]?.takeIf(String::isNotEmpty) }
}

fun hasSupportedYouTubeAuthCookie(cookie: String): Boolean = supportedYouTubeAuthCredential(cookie) != null

// Detached so the optional account-list lookup cannot delay or fail a login whose credentials are already saved.
private val delegatedSessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private val delegatedSessionMutex = Mutex()

// Readers take this with the resolver's write so one account's page id is never paired with another account's dataSyncId.
private val delegatedSessionLock = ReentrantLock()
private var lastDelegatedSessionAttempt: Pair<String, TimeMark>? = null
private var settledDelegatedSessionLookup: String? = null
private val delegatedSessionRetryInterval = 5.minutes

internal fun storedDelegatedSessionId(dataSyncId: String?): String? =
    delegatedSessionLock.withLock {
        Settings.shared.delegatedSessionId.takeIf {
            it.isNotBlank() && !dataSyncId.isNullOrBlank() && Settings.shared.delegatedSessionDataSyncId == dataSyncId
        }
    }

/**
 * Retries the brand page lookup when the signed-in account has none stored, which happens after an offline start,
 * a service-only start or a failed lookup. Runs in the background, skips while a lookup is running and waits
 * [delegatedSessionRetryInterval] between attempts for the same account.
 */
internal fun requestDelegatedSessionResolution() {
    val dataSyncId = Settings.shared.dataSyncId
    if (dataSyncId.isBlank() ||
        Settings.shared.delegatedSessionDataSyncId == dataSyncId ||
        !hasSupportedYouTubeAuthCookie(Settings.shared.innerTubeCookie)
    ) {
        return
    }
    val due =
        delegatedSessionLock.withLock {
            val last = lastDelegatedSessionAttempt
            (last == null || last.first != dataSyncId || last.second.elapsedNow() >= delegatedSessionRetryInterval).also { due ->
                if (due) lastDelegatedSessionAttempt = dataSyncId to TimeSource.Monotonic.markNow()
            }
        }
    if (due) launchDelegatedSessionResolution(waitIfRunning = false)
}

private fun launchDelegatedSessionResolution(waitIfRunning: Boolean) {
    delegatedSessionScope.launch {
        if (waitIfRunning) {
            delegatedSessionMutex.lock()
        } else if (!delegatedSessionMutex.tryLock()) {
            return@launch
        }
        val installed =
            try {
                resolveDelegatedSessionSafely()
            } finally {
                delegatedSessionMutex.unlock()
            }
        // Likes refreshed by login or playback before the page id existed used the personal account.
        if (installed) SongLikeState.refreshLikesFromRemoteAsync()
    }
}

// The lookup is optional, so a failed request or secure-settings write must not fail login, startup or an account refresh.
private suspend fun resolveDelegatedSessionSafely(): Boolean {
    val dataSyncId = Settings.shared.dataSyncId
    var listedAccounts = false
    val installed =
        try {
            AuthManager().resolveDelegatedSession { fetchDelegatedSessionAccounts().also { listedAccounts = it.isNotEmpty() } }
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            YtLogger.e("YouTubeAuth", "Delegated session lookup failed type=${error::class.simpleName}")
            return false
        }
    // Failed account-list requests come back empty, so only a non-empty list proves the account has no brand page to wait for.
    if (listedAccounts) delegatedSessionLock.withLock { settledDelegatedSessionLookup = dataSyncId }
    return installed
}

/**
 * True until the signed-in account's brand page is known or a successful account list has been read for it, so plays
 * are not reported to the personal account while a brand page may still be found.
 */
internal fun isDelegatedSessionLookupPending(): Boolean {
    val dataSyncId = Settings.shared.dataSyncId
    if (dataSyncId.isBlank() || !hasSupportedYouTubeAuthCookie(Settings.shared.innerTubeCookie)) return false
    return delegatedSessionLock.withLock {
        Settings.shared.delegatedSessionDataSyncId != dataSyncId && settledDelegatedSessionLookup != dataSyncId
    }
}

private suspend fun fetchDelegatedSessionAccounts() = GlobalYouTubeRepository.instance.getAccountsList(publishExpiredSessionNotice = false)

class AuthManager {
    val cookieMap: Map<String, String>
        get() = parseCookieString(Settings.shared.innerTubeCookie)

    val isLoggedIn: Boolean
        get() {
            return hasSupportedYouTubeAuthCookie(Settings.shared.innerTubeCookie)
        }

    val accountName: String get() = Settings.shared.accountName
    val accountEmail: String get() = Settings.shared.accountEmail
    val accountChannelHandle: String get() = Settings.shared.accountChannelHandle
    val accountThumbnail: String get() = Settings.shared.accountThumbnail
    val innerTubeCookie: String get() = Settings.shared.innerTubeCookie
    val visitorData: String get() = Settings.shared.visitorData
    val dataSyncId: String get() = Settings.shared.dataSyncId
    val authUser: String get() = Settings.shared.innerTubeAuthUser

    fun saveCredentials(
        cookie: String,
        visitorData: String,
        dataSyncId: String,
        accountInfo: AccountInfo,
        authUser: String = Settings.shared.innerTubeAuthUser,
    ) {
        YouTubeSessionState.dismissExpiredSessionNotice()
        YouTubeSyncState.clear()
        LibraryDeletionTracker.clear()
        val sanitizedCookie = sanitizeCookieString(cookie)
        val sanitizedAuthUser = sanitizeAuthUser(authUser)
        Settings.shared.applyStringBatch(
            mapOf(
                "innerTubeCookie" to sanitizedCookie,
                "visitorData" to visitorData,
                "dataSyncId" to dataSyncId,
                "innerTubeAuthUser" to sanitizedAuthUser,
                "accountName" to accountInfo.name,
                "accountEmail" to accountInfo.email.orEmpty(),
                "accountChannelHandle" to accountInfo.channelHandle.orEmpty(),
                "accountThumbnail" to accountInfo.thumbnailUrl.orEmpty(),
            ),
        )
        syncToInnerTube(sanitizedCookie, visitorData, dataSyncId, sanitizedAuthUser, useLoginForBrowse = true)
        SongLikeState.activateCurrentAccount()
    }

    suspend fun fetchAccounts(
        cookie: String,
        visitorData: String,
        dataSyncId: String,
        authUser: String,
    ): List<AccountsListResponse.Account> {
        val accounts =
            candidateRepository(cookie, visitorData, dataSyncId, authUser)
                .getAccountsList(publishExpiredSessionNotice = false)
        val selectable = accounts.count { it.selectionDataSyncId(dataSyncId) != null || !it.signinUrl.isNullOrBlank() }
        YtLogger.w("YouTubeAuth", "Account discovery parsed=${accounts.size} selectable=$selectable")
        return accounts
    }

    suspend fun validateAndSaveCredentials(
        cookie: String,
        visitorData: String,
        dataSyncId: String,
        authUser: String,
    ): Boolean =
        validateAndSaveCredentials(cookie, visitorData, dataSyncId, authUser) {
            candidateRepository(cookie, visitorData, dataSyncId, authUser)
                .getAccountInfo(publishExpiredSessionNotice = false)
        }.also { saved -> if (saved) launchDelegatedSessionResolution(waitIfRunning = true) }

    internal suspend fun validateAndSaveCredentials(
        cookie: String,
        visitorData: String,
        dataSyncId: String,
        authUser: String,
        fetchAccountInfo: suspend () -> AccountInfo?,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        val accountInfo = fetchAccountInfo() ?: return false
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.IO) { saveCredentials(cookie, visitorData, dataSyncId, accountInfo, authUser) }
        GlobalYouTubeRepository.instance.lastAccountInfo = accountInfo
        return true
    }

    suspend fun clearAllCredentials(): Result<Unit> = clearAllCredentials(::clearWebCookies)

    internal suspend fun clearAllCredentials(clearCookies: suspend () -> Unit): Result<Unit> {
        val persistenceResult =
            runCatching {
                invalidateYouTubeSession(GlobalInnerTube.instance)
                GlobalYouTubeRepository.instance.lastAccountInfo = null
            }
        val cookieClearResult =
            try {
                clearCookies()
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        return if (persistenceResult.isFailure) persistenceResult else cookieClearResult
    }

    suspend fun fetchAndSaveAccountInfo() = fetchAndSaveAccountInfo { GlobalYouTubeRepository.instance.getAccountInfo() }

    internal suspend fun fetchAndSaveAccountInfo(fetch: suspend () -> AccountInfo?): Boolean {
        val expectedCookie = Settings.shared.innerTubeCookie
        val expectedDataSyncId = Settings.shared.dataSyncId
        val expectedAuthUser = Settings.shared.innerTubeAuthUser

        fun failed() {
            if (hasSupportedYouTubeAuthCookie(expectedCookie) &&
                expectedCookie == Settings.shared.innerTubeCookie &&
                expectedDataSyncId == Settings.shared.dataSyncId && expectedAuthUser == Settings.shared.innerTubeAuthUser
            ) {
                YouTubeSyncState.failed("account") { fetchAndSaveAccountInfo(fetch) }
            }
        }
        return try {
            val info = fetch()
            if (info == null ||
                expectedCookie != Settings.shared.innerTubeCookie ||
                expectedDataSyncId != Settings.shared.dataSyncId ||
                expectedAuthUser != Settings.shared.innerTubeAuthUser
            ) {
                if (info == null) failed()
                false
            } else {
                Settings.shared.accountName = info.name
                Settings.shared.accountEmail = info.email ?: ""
                Settings.shared.accountChannelHandle = info.channelHandle ?: ""
                Settings.shared.accountThumbnail = info.thumbnailUrl ?: ""
                YouTubeSyncState.succeeded("account")
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            YtLogger.e("YouTubeAuth", "Account refresh failed type=${error::class.simpleName}")
            failed()
            false
        }
    }

    /** Returns true when a brand page was installed on the live session. */
    suspend fun resolveDelegatedSession(): Boolean =
        delegatedSessionMutex.withLock {
            resolveDelegatedSessionSafely()
        }

    // The stored dataSyncId alone cannot tell a brand page from a personal account, so the account list decides once per identity.
    internal suspend fun resolveDelegatedSession(fetchAccounts: suspend () -> List<AccountsListResponse.Account>): Boolean {
        val cookie = Settings.shared.innerTubeCookie
        val dataSyncId = Settings.shared.dataSyncId
        val authUser = Settings.shared.innerTubeAuthUser
        if (!hasSupportedYouTubeAuthCookie(cookie) || dataSyncId.isBlank() || Settings.shared.delegatedSessionDataSyncId == dataSyncId) {
            return false
        }
        val delegatedSessionId = delegatedSessionIdFor(dataSyncId, fetchAccounts()) ?: return false
        currentCoroutineContext().ensureActive()

        fun credentialsUnchanged() =
            !Settings.shared.credentialsPending &&
                cookie == Settings.shared.innerTubeCookie &&
                dataSyncId == Settings.shared.dataSyncId &&
                authUser == Settings.shared.innerTubeAuthUser
        if (!credentialsUnchanged()) return false
        delegatedSessionLock.withLock {
            Settings.shared.applyStringBatch(
                mapOf(
                    DelegatedSessionIdKey to delegatedSessionId,
                    DelegatedSessionDataSyncIdKey to dataSyncId,
                ),
            )
            // Installed under the same lock so isDelegatedSessionLookupPending never clears before the brand session is live.
            if (delegatedSessionId.isNotEmpty()) {
                syncToInnerTube(cookie, Settings.shared.visitorData, dataSyncId, authUser, useLoginForBrowse = true)
            }
        }
        YtLogger.w("YouTubeAuth", "Delegated session resolved brand=${delegatedSessionId.isNotEmpty()}")
        if (delegatedSessionId.isEmpty()) return false
        // Logout or an account switch can run between the check above and this sync; undo the stale values if it did.
        if (credentialsUnchanged()) return true
        delegatedSessionLock.withLock {
            if (Settings.shared.delegatedSessionDataSyncId == dataSyncId && Settings.shared.dataSyncId != dataSyncId) {
                Settings.shared.applyStringBatch(mapOf(DelegatedSessionIdKey to "", DelegatedSessionDataSyncIdKey to ""))
            }
        }
        restorePersistedSession()
        return false
    }

    fun restorePersistedSession() {
        syncToInnerTube(
            cookie = Settings.shared.innerTubeCookie,
            visitorData = Settings.shared.visitorData,
            dataSyncId = Settings.shared.dataSyncId,
            authUser = Settings.shared.innerTubeAuthUser,
            useLoginForBrowse = Settings.shared.innerTubeCookie.isNotBlank(),
        )
    }

    suspend fun restoreSession() = restoreSession { GlobalInnerTube.instance.fetchFreshVisitorData() }

    internal suspend fun restoreSession(fetchVisitorData: suspend () -> String?) {
        if (Settings.shared.credentialsPending) return
        restorePersistedSession()
        withContext(Dispatchers.Default) {
            val cookie = Settings.shared.innerTubeCookie
            var vData = Settings.shared.visitorData
            val dSyncId = Settings.shared.dataSyncId
            val authUser = Settings.shared.innerTubeAuthUser
            val previousVisitorData = vData

            if (vData.isBlank()) {
                try {
                    val freshVisitorData = fetchVisitorData()
                    if (!freshVisitorData.isNullOrBlank()) {
                        vData = freshVisitorData
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Exception) {
                    YtLogger.w("YouTubeAuth", "Visitor refresh failed type=${error::class.simpleName}")
                }
            }

            currentCoroutineContext().ensureActive()
            if (Settings.shared.credentialsPending ||
                cookie != Settings.shared.innerTubeCookie ||
                dSyncId != Settings.shared.dataSyncId ||
                authUser != Settings.shared.innerTubeAuthUser ||
                previousVisitorData != Settings.shared.visitorData
            ) {
                return@withContext
            }
            Settings.shared.visitorData = vData
            syncToInnerTube(
                cookie = cookie,
                visitorData = vData,
                dataSyncId = dSyncId,
                authUser = authUser,
                useLoginForBrowse = cookie.isNotBlank(),
            )

            if (cookie.isNotBlank()) {
                fetchAndSaveAccountInfo()
                resolveDelegatedSession()
                SongLikeState.refreshLikesFromRemoteAsync()
            }
        }
    }

    private fun candidateRepository(
        cookie: String,
        visitorData: String,
        dataSyncId: String,
        authUser: String,
    ): YouTubeRepository = YouTubeRepository(candidateInnerTube(cookie, visitorData, dataSyncId, authUser))

    // account_menu rejects raw system locale extensions with HTTP 400; use the resolved content locale.
    internal fun candidateInnerTube(
        cookie: String,
        visitorData: String,
        dataSyncId: String,
        authUser: String,
    ): InnerTube =
        GlobalInnerTube.instance.createIsolatedSession(includeAccount = false).apply {
            replaceSession(
                cookie = sanitizeCookieString(cookie),
                visitorData = visitorData,
                dataSyncId = dataSyncId,
                authUser = sanitizeAuthUser(authUser),
                useLoginForBrowse = true,
            )
        }

    private fun syncToInnerTube(
        cookie: String,
        visitorData: String,
        dataSyncId: String,
        authUser: String,
        useLoginForBrowse: Boolean,
    ) {
        val innerTube = GlobalInnerTube.instance
        innerTube.replaceSession(
            cookie = cookie,
            visitorData = visitorData.takeIf { it.isNotEmpty() },
            dataSyncId = dataSyncId.takeIf { it.isNotEmpty() },
            authUser = authUser,
            useLoginForBrowse = useLoginForBrowse,
            delegatedSessionId = storedDelegatedSessionId(dataSyncId),
        )
    }

    private fun sanitizeAuthUser(authUser: String): String = authUser.filter { it.isDigit() }.ifBlank { "0" }
}
