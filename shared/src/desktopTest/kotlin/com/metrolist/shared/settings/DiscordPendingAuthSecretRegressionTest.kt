package com.metrolist.shared.settings

import com.metrolist.shared.constants.DiscordPendingAuthSessionKey
import com.metrolist.shared.discord.DiscordAuthSession
import com.metrolist.shared.discord.DiscordAuthSessionStore
import java.io.File
import java.util.Properties
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DiscordPendingAuthSecretRegressionTest {
    @Test
    fun pendingPkceSessionIsStoredOutsidePublicSettings() {
        val root = createTempDirectory("discord-pkce-secret").toFile()
        try {
            val settingsFile = File(root, "settings.properties")
            val secrets = UnavailableSecretStore(File(root, "secrets.properties"), RuntimeException("Test store"))
            val backing = DesktopFileSettings(settingsFile, Properties(), secrets)
            val session =
                DiscordAuthSession(
                    authorizeUrl = "https://discord.com/oauth2/authorize?state=state-sentinel",
                    redirectUri = "metrolist-auth://discord/callback",
                    pkceVerifier = "pkce-verifier-sentinel",
                    state = "state-sentinel",
                )

            DiscordAuthSessionStore(Settings(backing)) { 1_000L }.save(session)
            backing.flush()

            val publicText = settingsFile.takeIf(File::exists)?.readText().orEmpty()
            assertFalse(publicText.contains(DiscordPendingAuthSessionKey), "The pending OAuth session key is public")
            assertFalse(publicText.contains(session.pkceVerifier), "The PKCE verifier is public")
            assertFalse(publicText.contains(session.state), "The OAuth state is public")
            assertNotNull(secrets.get(DiscordPendingAuthSessionKey), "The pending OAuth session was not routed to secure storage")
            assertEquals(session, DiscordAuthSessionStore(Settings(backing)) { 1_001L }.pending())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyPublicPkceSessionMigratesToSecureStorage() {
        val root = createTempDirectory("discord-pkce-migration").toFile()
        try {
            val settingsFile = File(root, "settings.properties")
            val public = Properties().apply { setProperty(DiscordPendingAuthSessionKey, "legacy-pkce-session") }
            settingsFile.outputStream().use { public.store(it, null) }
            val secrets = UnavailableSecretStore(File(root, "secrets.properties"), RuntimeException("Test store"))

            migratePublicSecrets(public, secrets, settingsFile)

            assertFalse(public.containsKey(DiscordPendingAuthSessionKey))
            assertFalse(loadPublicProperties(settingsFile).containsKey(DiscordPendingAuthSessionKey))
            assertEquals("legacy-pkce-session", secrets.get(DiscordPendingAuthSessionKey))
            assertNull(loadPublicProperties(settingsFile).getProperty(DiscordPendingAuthSessionKey))
        } finally {
            root.deleteRecursively()
        }
    }
}
