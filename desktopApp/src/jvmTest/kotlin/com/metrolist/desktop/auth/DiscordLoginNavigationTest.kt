package com.metrolist.desktop.auth

import com.metrolist.shared.discord.DiscordAuthSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiscordLoginNavigationTest {
    private val session =
        DiscordAuthSession("https://discord.com/oauth2/authorize", "metrolist-auth://discord/callback", "verifier", "expected-state")

    @Test
    fun allowsLoginNavigationAndRejectsRedirectLookalikes() =
        runTest {
            var exchanges = 0
            val navigation = DiscordLoginNavigation(session, this, { exchanges++ }, {}, {})
            listOf(
                session.authorizeUrl,
                "metrolist-auth://discord/callback-extra?code=code&state=expected-state",
                "metrolist-auth://discord.evil.invalid/callback?code=code&state=expected-state",
                "https://discord/callback?code=code&state=expected-state",
                "metrolist-auth://discord/callback?code=%ZZ",
            ).forEach { assertTrue(navigation.onNavigate(it)) }
            runCurrent()
            assertEquals(0, exchanges)
        }

    @Test
    fun exchangesDecodedCodeOnceAcrossConcurrentRedirectSources() =
        runTest {
            val exchanged = mutableListOf<String>()
            var completions = 0
            var dismissals = 0
            val pendingExchange = CompletableDeferred<Unit>()
            val navigation =
                DiscordLoginNavigation(
                    session,
                    this,
                    { code ->
                        exchanged += code
                        pendingExchange.await()
                    },
                    { completions++ },
                    { dismissals++ },
                )
            val callback = "${session.redirectUri}?code=code%2Bwith%2Fescapes&state=${session.state}"
            assertFalse(navigation.onNavigate(callback))
            runCurrent()
            assertFalse(navigation.onNavigate(callback))
            pendingExchange.complete(Unit)
            runCurrent()
            assertFalse(navigation.onNavigate(callback))
            runCurrent()
            assertEquals(listOf("code+with/escapes"), exchanged)
            assertEquals(1, completions)
            assertEquals(0, dismissals)
        }

    @Test
    fun rejectsMissingCredentialsAndMismatchedStateBeforeExchange() =
        runTest {
            var exchanges = 0
            var completions = 0
            var dismissals = 0
            listOf(
                "error=access_denied",
                "error=access_denied&code=code&state=expected-state",
                "code=&state=expected-state",
                "code=code",
                "code=code&state=wrong-state",
            ).forEach { query ->
                val navigation = DiscordLoginNavigation(session, this, { exchanges++ }, { completions++ }, { dismissals++ })
                assertFalse(navigation.onNavigate("${session.redirectUri}?$query"))
                runCurrent()
            }
            assertEquals(0, exchanges)
            assertEquals(0, completions)
            assertEquals(5, dismissals)
        }

    @Test
    fun exchangeFailureDismissesWithoutCompleting() =
        runTest {
            var completions = 0
            var dismissals = 0
            val navigation = DiscordLoginNavigation(session, this, { error("Exchange failed") }, { completions++ }, { dismissals++ })
            assertFalse(navigation.onNavigate("${session.redirectUri}?code=code&state=${session.state}"))
            runCurrent()
            assertEquals(0, completions)
            assertEquals(1, dismissals)
        }

    @Test
    fun disposingLoginCancelsPendingExchangeWithoutCallbacks() =
        runTest {
            var completions = 0
            var dismissals = 0
            val pendingExchange = CompletableDeferred<Unit>()
            val exchangeStarted = CompletableDeferred<Unit>()
            val navigation =
                DiscordLoginNavigation(
                    session,
                    backgroundScope,
                    {
                        exchangeStarted.complete(Unit)
                        pendingExchange.await()
                    },
                    { completions++ },
                    { dismissals++ },
                )
            assertFalse(navigation.onNavigate("${session.redirectUri}?code=code&state=${session.state}"))
            exchangeStarted.await()
            backgroundScope.cancel()
            pendingExchange.complete(Unit)
            runCurrent()
            assertEquals(0, completions)
            assertEquals(0, dismissals)
        }
}
