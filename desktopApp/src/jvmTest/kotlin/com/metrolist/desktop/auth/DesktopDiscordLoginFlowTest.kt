package com.metrolist.desktop.auth

import com.metrolist.shared.discord.DiscordAuthSession
import com.metrolist.shared.discord.DiscordAuthSessionStore
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.PropertiesSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopDiscordLoginFlowTest {
    private val session = DiscordAuthSession("authorize", "metrolist-auth://discord/callback", "verifier", "expected")

    private fun sessions() = DiscordAuthSessionStore(Settings(PropertiesSettings(Properties()))) { 0L }

    @Test
    fun restoredSessionExchangesDecodedCodeOnceAndClearsVerifierBeforeExchange() =
        runTest {
            val store = sessions()
            store.save(session)
            val codes = mutableListOf<String>()
            val complete = CompletableDeferred<Unit>()
            val flow =
                DesktopDiscordLoginFlow(store, this, { pending, code ->
                    assertEquals(session, pending)
                    assertNull(store.pending())
                    codes += code
                    complete.await()
                }, { error(it) })
            val callback = "${session.redirectUri}?code=a%2Bb%2Fc&state=expected"
            assertTrue(flow.handleCallback(callback))
            runCurrent()
            assertTrue(flow.exchanging)
            assertTrue(flow.handleCallback(callback))
            complete.complete(Unit)
            runCurrent()
            assertEquals(listOf("a+b/c"), codes)
            assertNull(flow.session)
            assertFalse(flow.exchanging)
        }

    @Test
    fun wrongStateAndRedirectLookalikesLeavePendingSessionAvailable() =
        runTest {
            val flow = DesktopDiscordLoginFlow(sessions(), this, { _, _ -> error("Unexpected exchange") }, { error(it) })
            flow.start(session)
            assertTrue(flow.handleCallback("${session.redirectUri}?code=code&state=wrong"))
            assertTrue(flow.handleCallback("${session.redirectUri}?error=access_denied&state=wrong"))
            assertTrue(flow.handleCallback("${session.redirectUri}?code=code"))
            assertTrue(flow.handleCallback("${session.redirectUri}?code=code&state="))
            listOf(
                "metrolist-auth://discord.evil/callback?code=code&state=expected",
                "metrolist-auth://discord/callback-extra?code=code&state=expected",
                "metrolist-auth://user@discord/callback?code=code&state=expected",
                "metrolist-auth://discord:123/callback?code=code&state=expected",
                "https://discord/callback?code=code&state=expected",
                "${session.redirectUri}?code=%ZZ&state=expected",
                "${session.redirectUri}?code=code&state=wrong&state=expected",
                "${session.redirectUri}?code=code&state=expected#fragment",
            ).forEach { assertFalse(flow.handleCallback(it), it) }
            runCurrent()
            assertEquals(session, flow.session)
            flow.cancel()
        }

    @Test
    fun matchingDenialMissingCodeAndExchangeFailureReportErrors() =
        runTest {
            val errors = mutableListOf<String>()
            val flow = DesktopDiscordLoginFlow(sessions(), this, { _, _ -> error("Exchange failed") }, errors::add)
            listOf("error=access_denied&state=expected", "state=expected", "code=code&state=expected").forEach {
                flow.start(session)
                assertTrue(flow.handleCallback("${session.redirectUri}?$it"))
                runCurrent()
                assertNull(flow.session)
                assertFalse(flow.exchanging)
            }
            assertEquals(
                listOf("discord_error_denied", "discord_error_token_exchange_failed", "discord_error_token_exchange_failed"),
                errors,
            )
        }

    @Test
    fun cancellationExpiryAndNoPendingSessionPreventExchange() =
        runTest {
            var now = 0L
            val store = DiscordAuthSessionStore(Settings(PropertiesSettings(Properties()))) { now }
            var exchanges = 0
            val flow = DesktopDiscordLoginFlow(store, this, { _, _ -> exchanges++ }, {})
            val callback = "${session.redirectUri}?code=code&state=expected"
            assertTrue(flow.handleCallback(callback))
            flow.start(session)
            flow.cancel()
            assertTrue(flow.handleCallback(callback))
            flow.start(session)
            now = 120_000L
            assertTrue(flow.handleCallback(callback))
            runCurrent()
            assertEquals(0, exchanges)
            assertNull(store.pending())
            assertNull(flow.session)
            flow.cancel()
        }

    @Test
    fun cancelStopsPendingExchangeWithoutReportingFailure() =
        runTest {
            val waiting = CompletableDeferred<Unit>()
            var completed = false
            val flow =
                DesktopDiscordLoginFlow(sessions(), this, { _, _ ->
                    waiting.await()
                    completed = true
                }, { error(it) })
            flow.start(session)
            flow.handleCallback("${session.redirectUri}?code=code&state=expected")
            runCurrent()
            flow.cancel()
            waiting.complete(Unit)
            runCurrent()
            assertFalse(completed)
            assertFalse(flow.exchanging)
            assertNull(flow.session)
        }
}
