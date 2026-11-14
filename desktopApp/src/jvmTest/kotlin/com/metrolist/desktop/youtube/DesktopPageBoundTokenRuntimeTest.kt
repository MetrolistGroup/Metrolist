package com.metrolist.desktop.youtube

import io.github.kdroidfilter.webview.web.WebViewNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPageBoundTokenRuntimeTest {
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
    fun resetDropsHostAndIgnoresCallbacksFromOldSession() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val runtime = DesktopPageBoundTokenRuntime { true }
                val oldSession = runtime.session.value
                val first = async { runtime.evaluate("first") }
                runCurrent()
                assertTrue(runtime.requested.value)
                first.cancelAndJoin()
                runtime.reset()
                assertFalse(runtime.requested.value)
                assertEquals(oldSession + 1, runtime.session.value)
                assertTrue(runtime.available)

                val second = async { runCatching { runtime.evaluate("second") } }
                runCurrent()
                runtime.attach(oldSession, WebViewNavigator(backgroundScope))
                runCurrent()
                runtime.detach(runtime.session.value)
                runCurrent()
                assertTrue(second.isActive)
                runtime.attach(runtime.session.value, WebViewNavigator(backgroundScope))
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
