package com.metrolist.shared.ui.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.shared.auth.AuthManager
import com.metrolist.shared.discord.DiscordAuthResult
import com.metrolist.shared.discord.DiscordAuthSession
import com.metrolist.shared.discord.DiscordRpcManager
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException

internal class MobileAccountWorkflow(
    private val exchange: suspend (String, DiscordAuthSession) -> DiscordAuthResult = { code, session ->
        DiscordRpcManager.auth.exchangeCode(code, session.pkceVerifier, session.state, session.state)
    },
    private val connect: (DiscordAuthResult) -> Unit = { result ->
        DiscordRpcManager.storeAndConnect(result.accessToken, result.refreshToken, result.expiresInSec)
    },
    private val error: (String) -> Unit = { DiscordRpcManager.setError(it) },
) {
    val authManager by lazy { AuthManager().also(AuthManager::restorePersistedSession) }
    var pendingSession by mutableStateOf<DiscordAuthSession?>(null)
        private set
    var loginInProgress by mutableStateOf(false)
        private set
    private var generation = 0L

    suspend fun restoreSession() = authManager.restoreSession()

    suspend fun logout() = authManager.clearAllCredentials()

    fun startDiscordLogin(session: DiscordAuthSession) {
        generation++
        pendingSession = session
        loginInProgress = true
    }

    fun expire(session: DiscordAuthSession) {
        if (pendingSession !== session) return
        generation++
        pendingSession = null
        loginInProgress = false
        error("discord_error_timeout")
    }

    fun failDiscordLogin(session: DiscordAuthSession) {
        if (pendingSession !== session) return
        generation++
        pendingSession = null
        loginInProgress = false
        error("discord_error_no_browser")
    }

    suspend fun handleDiscordCallback(callback: String): Boolean {
        val session = pendingSession ?: return false
        if (callback.substringBefore('?').substringBefore('#') != session.redirectUri) return false
        val callbackGeneration = generation
        try {
            val parameters = Url(callback).parameters
            if (parameters["state"] != session.state) {
                error("discord_error_token_exchange_failed")
                return true
            }
            pendingSession = null
            val code = parameters["code"]
            when {
                parameters["error"] != null -> {
                    error("discord_error_denied")
                }

                code == null -> {
                    error("discord_error_token_exchange_failed")
                }

                else -> {
                    val result = exchange(code, session)
                    if (generation == callbackGeneration) connect(result)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (generation == callbackGeneration) {
                pendingSession = null
                error("discord_error_token_exchange_failed")
            }
        } finally {
            if (generation == callbackGeneration && pendingSession == null) loginInProgress = false
        }
        return true
    }
}
