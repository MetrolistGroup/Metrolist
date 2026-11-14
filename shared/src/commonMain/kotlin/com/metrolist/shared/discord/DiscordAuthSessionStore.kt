package com.metrolist.shared.discord

import com.metrolist.shared.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

@Serializable
private data class PendingDiscordAuth(
    val session: DiscordAuthSession,
    val expiresAt: Long,
)

class DiscordAuthSessionStore(
    private val settings: Settings = Settings.shared,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    fun save(session: DiscordAuthSession) {
        settings.discordPendingAuthSession = Json.encodeToString(PendingDiscordAuth(session, now() + 120_000L))
    }

    fun pending(): DiscordAuthSession? {
        val raw = settings.discordPendingAuthSession.takeIf(String::isNotBlank) ?: return null
        val pending = runCatching { Json.decodeFromString<PendingDiscordAuth>(raw) }.getOrNull()
        if (pending == null || now() >= pending.expiresAt) {
            clear()
            return null
        }
        return pending.session
    }

    fun clear() {
        settings.discordPendingAuthSession = ""
    }
}
