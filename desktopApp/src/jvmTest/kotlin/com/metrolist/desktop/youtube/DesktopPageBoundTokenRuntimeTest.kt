package com.metrolist.desktop.youtube

import io.github.kdroidfilter.webview.web.WebViewNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPageBoundTokenRuntimeTest {
    @Test
    fun missingFreshDocumentTimesOutAndLeavesRuntimeUnavailable() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val runtime = DesktopPageBoundTokenRuntime { true }
                val evaluation = async { runCatching { runtime.evaluate("unused") } }
                runCurrent()
                runtime.attach(runtime.session.value, WebViewNavigator(backgroundScope), "about:blank")
                advanceTimeBy(10_000)
                runCurrent()
                assertTrue(evaluation.await().exceptionOrNull() is IllegalStateException)
                assertFalse(runtime.requested.value)
                assertFalse(runtime.available)
                runtime.reset()
                assertFalse(runtime.available)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun failedInitializationRemainsUnavailableAfterHostDisposal() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val runtime = DesktopPageBoundTokenRuntime { true }
                runtime.failInitialization(IllegalStateException("Host setup failed"))
                runtime.detach()
                assertFalse(runtime.available)
                assertFalse(runtime.requested.value)
                withTimeout(1_000.milliseconds) {
                    assertFailsWith<IllegalStateException> { runtime.evaluate("unused") }
                    runtime.reset()
                }
                assertFalse(runtime.available)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun resetUnloadsPageButRetainsHostAndIgnoresCallbacksFromOldSession() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val runtime = DesktopPageBoundTokenRuntime { true }
                assertFalse(runtime.hostRequested.value)
                val oldSession = runtime.session.value
                val first = async { runtime.evaluate("first") }
                runCurrent()
                assertTrue(runtime.requested.value)
                assertTrue(runtime.hostRequested.value)
                val oldUrl = runtime.pageUrl
                first.cancelAndJoin()
                runtime.reset()
                assertFalse(runtime.requested.value)
                assertTrue(runtime.hostRequested.value)
                assertEquals("about:blank", runtime.pageUrl)
                assertEquals(oldSession + 1, runtime.session.value)
                assertTrue(runtime.available)

                val second = async { runCatching { runtime.evaluate("second") } }
                runCurrent()
                assertNotEquals(oldUrl, runtime.pageUrl)
                val navigator = WebViewNavigator(backgroundScope)
                runtime.attach(oldSession, navigator, oldUrl)
                runtime.attach(runtime.session.value, navigator, oldUrl)
                runtime.attach(runtime.session.value, navigator, "about:blank")
                runCurrent()
                runtime.detach(runtime.session.value)
                runCurrent()
                assertTrue(second.isActive)
                runtime.attach(runtime.session.value, navigator, runtime.pageUrl)
                runCurrent()
                runtime.detach(oldSession)
                runCurrent()
                assertTrue(second.isActive)
                runtime.detach(runtime.session.value)
                assertTrue(second.await().exceptionOrNull() is IllegalStateException)
            } finally {
                Dispatchers.resetMain()
            }
        }
}
