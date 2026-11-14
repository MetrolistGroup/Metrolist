/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.ui.screens.settings

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import com.metrolist.shared.discord.DiscordAuthSession
import com.metrolist.shared.discord.DiscordAuthSessionStore
import com.metrolist.shared.discord.DiscordRpcManager
import com.metrolist.shared.discord.DiscordTokenStore
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.youtube.playback.AndroidContextProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object DiscordLoginFlow {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessions get() = DiscordAuthSessionStore()
    var exchanging by mutableStateOf(false)
        private set
    val isActive get() = sessions.pending() != null || exchanging

    fun start(session: DiscordAuthSession) = sessions.save(session)

    fun clear() = sessions.clear()

    fun handleCallback(intent: Intent): Boolean {
        val data = intent.data ?: return false
        if (data.scheme != "metrolist-auth" || data.host != "discord") return false
        val session = sessions.pending() ?: return true
        val redirect = session.redirectUri.toUri()
        if (data.scheme != redirect.scheme || data.authority != redirect.authority || data.path != redirect.path) return true
        if (data.getQueryParameter("state") != session.state) return true
        clear()
        if (data.getQueryParameter("error") != null) {
            DiscordRpcManager.setError("discord_error_denied")
            return true
        }
        val code = data.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            DiscordRpcManager.setError("discord_error_token_exchange_failed")
            return true
        }
        exchanging = true
        scope.launch {
            try {
                // OAuth can return to a new process while presence is still disabled.
                DiscordTokenStore.init(AndroidContextProvider.applicationContext)
                DiscordRpcManager.init(GlobalYouTube.client)
                val result =
                    withContext(Dispatchers.IO) {
                        DiscordRpcManager.auth.exchangeCode(code, session.pkceVerifier, session.state, session.state)
                    }
                DiscordRpcManager.storeAndConnect(result.accessToken, result.refreshToken, result.expiresInSec)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                DiscordRpcManager.setError("discord_error_token_exchange_failed")
            } finally {
                exchanging = false
            }
        }
        return true
    }
}
