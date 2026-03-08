package com.metrolist.desktop.auth

import com.metrolist.shared.discord.DiscordAuthSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal class DiscordLoginNavigation(
    private val session: DiscordAuthSession,
    private val scope: CoroutineScope,
    private val exchangeCode: suspend (String) -> Unit,
    private val onComplete: () -> Unit,
    private val onDismissRequest: () -> Unit,
) {
    private val completed = AtomicBoolean(false)

    fun onNavigate(url: String): Boolean {
        val callback = parseDiscordLoginCallback(url, session.redirectUri) ?: return true
        if (!completed.compareAndSet(false, true)) return false
        scope.launch {
            try {
                val code = callback.code
                if (callback.error != null || code.isNullOrBlank() || callback.state != session.state) {
                    onDismissRequest()
                    return@launch
                }
                exchangeCode(code)
                onComplete()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                onDismissRequest()
            }
        }
        return false
    }
}
