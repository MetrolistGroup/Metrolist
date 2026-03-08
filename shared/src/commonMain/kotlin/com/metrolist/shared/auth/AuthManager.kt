package com.metrolist.shared.auth

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.AccountInfo
import com.metrolist.innertubex.models.response.AccountsListResponse
import com.metrolist.innertubex.utils.parseCookieString
import com.metrolist.innertubex.utils.sanitizeCookieString
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.playlist.LibraryDeletionTracker
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalInnerTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.utils.clearWebCookies
import com.metrolist.shared.youtube.logging.YtLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

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
        }

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
        )
    }

    private fun sanitizeAuthUser(authUser: String): String = authUser.filter { it.isDigit() }.ifBlank { "0" }
}
