package com.metrolist.desktop.ui.screens

import com.metrolist.shared.playlistimport.awaitProviderCredentials
import io.github.kdroidfilter.webview.cookie.Cookie
import io.github.kdroidfilter.webview.cookie.CookieManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WebViewAuthDataTest {
    @Test
    fun readsHttpOnlyCookiesAndPreservesTheSelectedSession() =
        runTest {
            val cookies = WebViewTestCookieManager(mapOf(MUSIC_URL to listOf(Cookie("SAPISID", "session", isHttpOnly = true))))
            val result = readWebViewAuthData(cookies) { config() }
            assertEquals(AuthData("SAPISID=session", "visitor", "channel", "2"), result)
        }

    @Test
    fun personalAccountIdentitySurvivesWebLoginExtraction() =
        runTest {
            val result =
                readWebViewAuthData(WebViewTestCookieManager()) {
                    config(cookie = "SAPISID=session", dataSyncId = "||account")
                }
            assertEquals(AuthData("SAPISID=session", "visitor", "account", "2"), result)
        }

    @Test
    fun decodesJsonQuotedNativeResultsWithoutLosingEscapes() =
        runTest {
            val snapshot = config(cookie = "__Secure-3PAPISID=session", visitorData = "visitor\"\\value")
            val result = readWebViewAuthData(WebViewTestCookieManager()) { Json.encodeToString(snapshot) }
            assertEquals("visitor\"\\value", result?.visitorData)
            assertEquals("__Secure-3PAPISID=session", result?.cookie)
        }

    @Test
    fun prefersMusicCookiesOverOtherScopesAndDocumentCookies() =
        runTest {
            val cookies =
                WebViewTestCookieManager(
                    mapOf(
                        MUSIC_URL to listOf(Cookie("SAPISID", "music")),
                        "https://www.youtube.com" to listOf(Cookie("SAPISID", "youtube"), Cookie("SID", "http-only")),
                        "https://accounts.google.com" to listOf(Cookie("SAPISID", "google")),
                    ),
                )
            val result = readWebViewAuthData(cookies) { config(cookie = "SAPISID=document; PREF=value=with=equals") }
            assertEquals("SAPISID=music; SID=http-only; PREF=value=with=equals", result?.cookie)
        }

    @Test
    fun waitsForLateConfigurationWithoutAnotherPageNavigation() =
        runTest {
            var snapshot = config(visitorData = "")
            val cookies = WebViewTestCookieManager(mapOf(MUSIC_URL to listOf(Cookie("SAPISID", "session"))))
            val result = async { awaitProviderCredentials { readWebViewAuthData(cookies) { snapshot } } }
            runCurrent()
            advanceTimeBy(12_000)
            runCurrent()
            assertFalse(result.isCompleted)
            snapshot = config()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("visitor", result.await().visitorData)
        }

    @Test
    fun rejectsVisitorCookieAsAReplacementForPageConfiguration() =
        runTest {
            val result =
                readWebViewAuthData(WebViewTestCookieManager()) {
                    config(cookie = "SAPISID=session; VISITOR_INFO1_LIVE=visitor-cookie", visitorData = "")
                }
            assertNull(result)
        }

    @Test
    fun rejectsIncompleteMalformedAndSignedOutSnapshots() =
        runTest {
            for (snapshot in listOf("", "null", "undefined", "{}", "[]", "{broken", config(), """{"visitorData":[]} """)) {
                assertNull(readWebViewAuthData(WebViewTestCookieManager()) { snapshot })
            }
        }

    @Test
    fun stillUsesDocumentCookiesWhenNativeCookieReadsFail() =
        runTest {
            val result =
                readWebViewAuthData(WebViewTestCookieManager(failure = IllegalStateException())) { config(cookie = "SAPISID=session") }
            assertEquals("SAPISID=session", result?.cookie)
        }

    @Test
    fun nativeFallbackNeverForwardsUnrelatedDomains() =
        runTest {
            val cookies =
                WebViewNativeTestCookieManager(
                    WebViewTestNativePanel(
                        listOf(
                            Cookie("SID", "unrelated", domain = "unrelated.example"),
                            Cookie("PREF", "spoof", domain = "youtube.com.attacker.example"),
                            Cookie("SAPISID", "session", domain = ".youtube.com"),
                            Cookie("ACCOUNT", "google", domain = "accounts.google.com"),
                        ),
                    ),
                )
            val result = readWebViewAuthData(cookies) { config() }
            assertEquals("SAPISID=session; ACCOUNT=google", result?.cookie)
        }

    @Test
    fun propagatesCancellationFromJavascriptAndCookieReads() =
        runTest {
            assertFailsWith<CancellationException> {
                readWebViewAuthData(WebViewTestCookieManager()) { throw CancellationException() }
            }
            assertFailsWith<CancellationException> {
                readWebViewAuthData(WebViewTestCookieManager(failure = CancellationException())) { config() }
            }
        }

    @Test
    fun acceptsOnlyTheSecureMusicOriginForExtraction() {
        assertTrue(isYouTubeMusicUrl(MUSIC_URL))
        assertTrue(isYouTubeMusicUrl("$MUSIC_URL/watch?v=track"))
        for (url in listOf(
            null,
            "http://music.youtube.com/",
            "https://music.youtube.com.attacker.example/",
            "https://music.youtube.com@attacker.example/",
            "https://accounts.google.com/",
        )) {
            assertFalse(isYouTubeMusicUrl(url))
        }
    }

    private fun config(
        cookie: String = "",
        visitorData: String = "visitor",
        dataSyncId: String = "channel||account",
    ): String =
        buildJsonObject {
            put("cookie", cookie)
            put("visitorData", visitorData)
            put("dataSyncId", dataSyncId)
            put("authUser", "2")
        }.toString()

    private companion object {
        const val MUSIC_URL = "https://music.youtube.com"
    }
}

private open class WebViewTestCookieManager(
    private val cookies: Map<String, List<Cookie>> = emptyMap(),
    private val failure: Exception? = null,
) : CookieManager {
    override suspend fun getCookies(url: String): List<Cookie> {
        failure?.let { throw it }
        return cookies[url].orEmpty()
    }

    override suspend fun setCookie(
        url: String,
        cookie: Cookie,
    ) = Unit

    override suspend fun removeAllCookies() = Unit

    override suspend fun removeCookies(url: String) = Unit
}

private class WebViewNativeTestCookieManager(
    private val panel: WebViewTestNativePanel,
) : WebViewTestCookieManager()

private class WebViewTestNativePanel(
    private val cookies: List<Cookie>,
) {
    fun getCookies(): List<Cookie> = cookies
}
