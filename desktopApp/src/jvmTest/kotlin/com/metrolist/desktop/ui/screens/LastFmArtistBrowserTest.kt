package com.metrolist.desktop.ui.screens

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LastFmArtistBrowserTest {
    @Test
    fun navigationStaysOnLastFm() {
        assertTrue(isLastFmArtistUrl("https://www.last.fm/music/Sydney+Rose/+images"))
        for (url in listOf(
            "http://www.last.fm/",
            "https://www.last.fm.evil.test/",
            "https://www.last.fm@evil.test/",
            "file:///tmp/page",
            "https://www.last.fm:8443/",
            "invalid url",
        )) {
            assertFalse(isLastFmArtistUrl(url))
        }
    }

    @Test
    fun completionCancellationAndTimeoutReleaseTheSerializedBrowser() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                for (finish in listOf("complete", "cancel", "timeout")) {
                    val browser = LastFmArtistBrowser()
                    val first = async { browser.load("https://www.last.fm/music/First") }
                    runCurrent()
                    advanceTimeBy(1_000L)
                    val secondUrl = "https://www.last.fm/music/Second"
                    val second = async { browser.load(secondUrl) }
                    runCurrent()
                    assertEquals("https://www.last.fm/music/First", browser.request?.url)
                    when (finish) {
                        "complete" -> browser.request?.result?.complete(null)
                        "cancel" -> first.cancelAndJoin()
                        else -> advanceTimeBy(19_000L)
                    }
                    runCurrent()
                    assertEquals(secondUrl, browser.request?.url)
                    browser.request?.result?.complete("rendered HTML")
                    assertEquals("rendered HTML", second.await())
                    assertNull(browser.request)
                }
            } finally {
                Dispatchers.resetMain()
            }
        }
}
