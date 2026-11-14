/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.state.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.content.HomeState
import com.metrolist.desktop.state.content.LibraryContentState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.innertubex.models.AccountInfo
import com.metrolist.innertubex.utils.sanitizeCookieString
import com.metrolist.shared.auth.AuthManager
import com.metrolist.shared.auth.YouTubeSyncState
import com.metrolist.shared.model.HomePageData
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.sanitizeAuthUser
import com.metrolist.shared.youtube.logging.YtLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AuthState {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var accountInfoJob: Job? = null
    private var accountInfoGeneration = 0L

    val isSignedIn: Boolean
        get() = AuthManager().isLoggedIn
    var profilePicUrl by mutableStateOf<String?>(null)

    fun updateAuth(
        cookie: String,
        visitorData: String,
        dataSyncId: String,
        authUser: String = Settings.shared.innerTubeAuthUser,
        accountInfo: AccountInfo? = null,
        refreshData: Boolean = true,
    ) {
        val sanitizedCookie = sanitizeCookieString(cookie)
        val sanitizedAuthUser = sanitizeAuthUser(authUser)
        val accountChanged =
            sanitizedCookie != Settings.shared.innerTubeCookie ||
                dataSyncId != Settings.shared.dataSyncId ||
                sanitizedAuthUser != Settings.shared.innerTubeAuthUser
        val identity =
            accountInfo ?: if (accountChanged) {
                AccountInfo("", null, null, null)
            } else {
                AccountInfo(
                    Settings.shared.accountName,
                    Settings.shared.accountEmail,
                    Settings.shared.accountChannelHandle,
                    Settings.shared.accountThumbnail,
                )
            }
        AuthManager().saveCredentials(sanitizedCookie, visitorData, dataSyncId, identity, sanitizedAuthUser)
        GlobalYouTube.updateSession(sanitizedCookie, visitorData, dataSyncId, sanitizedAuthUser, useLoginForBrowse = true)
        profilePicUrl = identity.thumbnailUrl
        SongLikeState.refreshLikesFromRemoteAsync()

        UiState.showSignIn = false
        UiState.showSwitchChannel = false
        fetchAccountInfo()
        PlayerState.refreshHistory()
        if (refreshData) {
            HomeState.fetchHomeData()
            LibraryContentState.fetchLibraryData(force = true)
        }
    }

    fun loadSession() {
        YtLogger.w(
            "DesktopAuth",
            "Restore credentialsPending=${Settings.shared.credentialsPending} " +
                "hasCookie=${Settings.shared.innerTubeCookie.isNotBlank()} supportedAuth=$isSignedIn " +
                "hasDataSyncId=${Settings.shared.dataSyncId.isNotBlank()} authUser=${Settings.shared.innerTubeAuthUser}",
        )
        if (Settings.shared.credentialsPending) return
        val cookies = Settings.shared.innerTubeCookie
        val visitorData = Settings.shared.visitorData
        val dataSyncId = Settings.shared.dataSyncId
        val authUser = Settings.shared.innerTubeAuthUser

        GlobalYouTube.updateSession(
            cookie = cookies,
            visitorData = visitorData,
            dataSyncId = dataSyncId,
            authUser = authUser,
            useLoginForBrowse = cookies.isNotBlank(),
        )

        if (isSignedIn) {
            profilePicUrl = Settings.shared.accountThumbnail
            fetchAccountInfo()
            LibraryContentState.fetchLibraryData(force = true)
        }

        PlayerState.refreshHistory()
        HomeState.fetchHomeData()
    }

    fun fetchAccountInfo() {
        val generation = ++accountInfoGeneration
        accountInfoJob?.cancel()
        accountInfoJob =
            scope.launch {
                try {
                    val info = GlobalYouTubeRepository.instance.getAccountInfo()
                    if (info != null && info.name != "Unknown" && generation == accountInfoGeneration && isSignedIn) {
                        profilePicUrl = info.thumbnailUrl
                        Settings.shared.accountName = info.name
                        Settings.shared.accountEmail = info.email.orEmpty()
                        Settings.shared.accountChannelHandle = info.channelHandle.orEmpty()
                        Settings.shared.accountThumbnail = info.thumbnailUrl ?: ""
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    YtLogger.e("DesktopAuth", "Account refresh failed type=${error::class.simpleName}")
                    if (generation == accountInfoGeneration && isSignedIn) {
                        YouTubeSyncState.failed("account") { fetchAccountInfo() }
                    }
                }
            }
    }

    suspend fun signOut(): Throwable? {
        accountInfoGeneration++
        accountInfoJob?.cancel()
        accountInfoJob = null
        val cleanupFailure = withContext(Dispatchers.IO) { AuthManager().clearAllCredentials().exceptionOrNull() }
        profilePicUrl = null
        HomeState.homePageData = HomePageData()
        LibraryContentState.fetchLibraryData(force = true)
        HomeState.fetchHomeData()
        return cleanupFailure
    }
}
