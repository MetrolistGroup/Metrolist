package com.metrolist.shared.scrobble

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ScrobbleAccount(
    val provider: ScrobbleProvider,
    val account: String,
    val credential: String,
)

object ScrobbleSessions {
    private val mutex = Mutex()

    private var lastFmAccount: ScrobbleAccount? by mutableStateOf(loadLastFmAccount())

    private var listenBrainzAccount: ScrobbleAccount? by mutableStateOf(loadListenBrainzAccount())

    var showLastFmExpiredNotice by mutableStateOf(Settings.shared.lastFmSessionExpired)
        private set

    /** Shows (true) or clears (false) a system notification for an expired Last.fm session; platforms set it at startup. */
    var onLastFmSessionExpiredChanged: ((Boolean) -> Unit)? = null

    fun current(provider: ScrobbleProvider): ScrobbleAccount? =
        when (provider) {
            ScrobbleProvider.LAST_FM -> lastFmAccount
            ScrobbleProvider.LISTEN_BRAINZ -> listenBrainzAccount
        }

    fun restorePersistedAccounts() {
        lastFmAccount = loadLastFmAccount()
        LastFM.sessionKey = lastFmAccount?.credential
        listenBrainzAccount = loadListenBrainzAccount()
        showLastFmExpiredNotice = Settings.shared.lastFmSessionExpired
    }

    internal fun markLastFmSessionExpired(account: ScrobbleAccount) {
        if (current(ScrobbleProvider.LAST_FM) != account || Settings.shared.lastFmSessionExpired) return
        Settings.shared.lastFmSessionExpired = true
        showLastFmExpiredNotice = true
        onLastFmSessionExpiredChanged?.invoke(true)
    }

    fun dismissLastFmExpiredNotice() {
        showLastFmExpiredNotice = false
    }

    suspend fun enqueueIfCurrent(
        account: ScrobbleAccount,
        scrobble: PendingScrobble,
    ) = mutex.withLock {
        if (current(account.provider) == account) PendingScrobbleStore.upsert(scrobble)
    }

    suspend fun enqueueIfCurrent(scrobbles: List<Pair<ScrobbleAccount, PendingScrobble>>) =
        mutex.withLock {
            PendingScrobbleStore.upsert(scrobbles.filter { (account, _) -> current(account.provider) == account }.map { it.second })
        }

    suspend fun connectLastFm(
        account: String,
        credential: String,
    ) = mutex.withLock {
        require(account.isNotBlank() && credential.isNotBlank())
        // Plays held while the session was expired belong to this account, so signing back in sends them.
        val resumesExpiredAccount = Settings.shared.lastFmSessionExpired && account == Settings.shared.lastFmUsername
        if (!resumesExpiredAccount) PendingScrobbleStore.clear(ScrobbleProvider.LAST_FM)
        Settings.shared.lastFmSessionExpired = false
        showLastFmExpiredNotice = false
        onLastFmSessionExpiredChanged?.invoke(false)
        Settings.shared.lastFmUsername = account
        Settings.shared.lastFmSession = credential
        LastFM.sessionKey = credential
        lastFmAccount = ScrobbleAccount(ScrobbleProvider.LAST_FM, account, credential)
    }

    suspend fun connectListenBrainz(
        account: String,
        credential: String,
    ) = mutex.withLock {
        require(account.isNotBlank() && credential.isNotBlank())
        PendingScrobbleStore.clear(ScrobbleProvider.LISTEN_BRAINZ)
        Settings.shared.listenBrainzUsername = account
        Settings.shared.listenBrainzToken = credential
        Settings.shared.listenBrainzEnabled = true
        listenBrainzAccount = ScrobbleAccount(ScrobbleProvider.LISTEN_BRAINZ, account, credential)
    }

    suspend fun disconnect(
        provider: ScrobbleProvider,
        expectedCredential: String? = null,
    ): Boolean =
        mutex.withLock {
            val current = current(provider)
            if (expectedCredential != null && current?.credential != expectedCredential) return@withLock false
            when (provider) {
                ScrobbleProvider.LAST_FM -> {
                    lastFmAccount = null
                    LastFM.sessionKey = null
                    Settings.shared.lastFmSession = ""
                    Settings.shared.lastFmSessionExpired = false
                    showLastFmExpiredNotice = false
                    onLastFmSessionExpiredChanged?.invoke(false)
                    Settings.shared.lastFmUsername = ""
                }

                ScrobbleProvider.LISTEN_BRAINZ -> {
                    listenBrainzAccount = null
                    Settings.shared.listenBrainzToken = ""
                    Settings.shared.listenBrainzUsername = ""
                }
            }
            PendingScrobbleStore.clear(provider)
            true
        }

    private fun loadLastFmAccount(): ScrobbleAccount? {
        val credential = Settings.shared.lastFmSession
        val account = Settings.shared.lastFmUsername
        return if (credential.isBlank() || account.isBlank()) {
            null
        } else {
            ScrobbleAccount(ScrobbleProvider.LAST_FM, account, credential)
        }
    }

    private fun loadListenBrainzAccount(): ScrobbleAccount? {
        val credential = Settings.shared.listenBrainzToken
        val account = Settings.shared.listenBrainzUsername
        return if (credential.isBlank() || account.isBlank()) {
            null
        } else {
            ScrobbleAccount(ScrobbleProvider.LISTEN_BRAINZ, account, credential)
        }
    }
}
