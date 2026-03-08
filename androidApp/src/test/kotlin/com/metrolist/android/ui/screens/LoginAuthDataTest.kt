package com.metrolist.android.ui.screens

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginAuthDataTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun absentJavascriptCallbacksTimeOut() =
        runTest {
            val result = async { awaitLoginAuthData("cookie", CompletableDeferred(), CompletableDeferred(), CompletableDeferred()) }
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(result.isCompleted)
            assertNull(result.await())
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun missingConfigRejectsAuthWithoutWaitingForOtherCallbacks() =
        runTest {
            for (value in listOf(null, "null", "undefined", "")) {
                assertNull(awaitLoginAuthData("cookie", CompletableDeferred(value), CompletableDeferred(), CompletableDeferred()))
            }
            assertEquals(0, testScheduler.currentTime)
        }

    @Test
    fun personalAccountIdentitySurvivesWebLoginExtraction() =
        runTest {
            assertEquals(
                AuthData("cookie", "visitor", "account", "0"),
                awaitLoginAuthData(
                    "cookie",
                    CompletableDeferred("visitor"),
                    CompletableDeferred("||account"),
                    CompletableDeferred("0"),
                ),
            )
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun configPreservesAccountAndParentCancellation() =
        runTest {
            assertEquals(
                AuthData("cookie", "visitor", "channel", "2"),
                awaitLoginAuthData(
                    "cookie",
                    CompletableDeferred("visitor"),
                    CompletableDeferred("channel||user"),
                    CompletableDeferred("2"),
                ),
            )
            val result = async { awaitLoginAuthData("cookie", CompletableDeferred(), CompletableDeferred(), CompletableDeferred()) }
            runCurrent()
            result.cancel()
            runCurrent()
            assertTrue(result.isCancelled)
        }
}
