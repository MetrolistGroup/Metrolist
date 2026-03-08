package com.metrolist.desktop.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.shared.discord.DiscordAuthSession
import com.metrolist.shared.discord.DiscordAuthSessionStore
import com.metrolist.shared.discord.DiscordRpcManager
import com.metrolist.shared.state.GlobalYouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object DesktopDiscordLogin {
    val flow =
        DesktopDiscordLoginFlow(
            sessions = DiscordAuthSessionStore(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            exchangeCode = { session, code ->
                DiscordRpcManager.init(GlobalYouTube.client)
                val result =
                    withContext(Dispatchers.IO) {
                        DiscordRpcManager.auth.exchangeCode(code, session.pkceVerifier, session.state, session.state)
                    }
                DiscordRpcManager.storeAndConnect(result.accessToken, result.refreshToken, result.expiresInSec)
            },
            onError = { DiscordRpcManager.setError(it) },
        )
}

internal class DesktopDiscordLoginFlow(
    private val sessions: DiscordAuthSessionStore,
    private val scope: CoroutineScope,
    private val exchangeCode: suspend (DiscordAuthSession, String) -> Unit,
    private val onError: (String) -> Unit,
) {
    var session by mutableStateOf(sessions.pending())
        private set
    var exchanging by mutableStateOf(false)
        private set
    private var exchangeJob: Job? = null

    fun start(session: DiscordAuthSession) {
        cancel()
        sessions.save(session)
        this.session = session
    }

    fun cancel() {
        exchangeJob?.cancel()
        exchangeJob = null
        sessions.clear()
        session = null
        exchanging = false
    }

    fun handleCallback(url: String): Boolean {
        val callback = parseDiscordLoginCallback(url) ?: return false
        val pending = sessions.pending()
        if (pending == null) {
            if (!exchanging && session != null) {
                session = null
                onError("discord_error_token_exchange_failed")
            }
            return true
        }
        if (parseDiscordLoginCallback(url, pending.redirectUri) == null || callback.state != pending.state) return true
        sessions.clear()
        when {
            callback.error != null -> {
                session = null
                onError("discord_error_denied")
            }

            callback.code.isNullOrBlank() -> {
                session = null
                onError("discord_error_token_exchange_failed")
            }

            else -> {
                exchanging = true
                exchangeJob =
                    scope.launch {
                        try {
                            exchangeCode(pending, callback.code)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            onError("discord_error_token_exchange_failed")
                        } finally {
                            if (session == pending) {
                                session = null
                                exchanging = false
                            }
                        }
                    }
            }
        }
        return true
    }
}
