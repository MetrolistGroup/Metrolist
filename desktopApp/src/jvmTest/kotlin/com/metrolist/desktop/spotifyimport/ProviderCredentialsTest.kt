package com.metrolist.desktop.spotifyimport

import com.metrolist.shared.playlistimport.awaitProviderCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderCredentialsTest {
    @Test
    fun detectsCredentialsCreatedWithoutPageNavigation() =
        runTest {
            var credentials: String? = null
            var attempts = 0
            val result =
                async {
                    awaitProviderCredentials {
                        attempts++
                        credentials
                    }
                }
            runCurrent()
            assertFalse(result.isCompleted)
            credentials = "session"
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("session", result.await())
            assertEquals(2, attempts)
        }

    @Test
    fun stopsReadingCookiesWhenLoginCloses() =
        runTest {
            var attempts = 0
            val result =
                async {
                    awaitProviderCredentials<String> {
                        attempts++
                        null
                    }
                }
            runCurrent()
            result.cancel()
            advanceTimeBy(5_000)
            assertFailsWith<CancellationException> { result.await() }
            assertEquals(1, attempts)
        }

    @Test
    fun propagatesCancellationFromCookieExtraction() =
        runTest {
            assertFailsWith<CancellationException> {
                awaitProviderCredentials<String> { throw CancellationException("Login closed") }
            }
        }
}
