package com.metrolist.android.ui

import com.metrolist.android.ui.screens.LoginNavigationAction
import com.metrolist.android.ui.screens.loginNavigationAction
import org.junit.Assert.assertEquals
import org.junit.Test

class LoginNavigationTest {
    @Test
    fun unrelatedAndLookalikeSitesCannotLaunchAnExternalApp() {
        for (url in listOf("https://example.org/login", "https://accounts.google.com.evil.test", "https://evilgoogle.com")) {
            assertEquals(LoginNavigationAction.BLOCK, loginNavigationAction(url, "https", false))
            assertEquals(LoginNavigationAction.BLOCK, loginNavigationAction(url, "https", true))
        }
        assertEquals(false, isTrustedLoginUrl("https://accounts.spotify.com@evil.test", "https://open.spotify.com"))
        assertEquals(false, isTrustedLoginUrl("http://accounts.spotify.com", "https://open.spotify.com"))
        assertEquals(false, isTrustedLoginUrl("https://accounts.spotify.com:444", "https://open.spotify.com"))
        assertEquals(true, isTrustedLoginUrl("https://accounts.spotify.com/login", "https://open.spotify.com"))
        assertEquals(true, isTrustedLoginUrl("https://accounts.google.com/signin", "https://open.spotify.com"))
        assertEquals(true, isTrustedLoginUrl("https://www.amazon.de/ap/signin", "https://music.amazon.de"))
        assertEquals(false, isTrustedLoginUrl("https://www.amazon.com", "https://music.amazon.de"))
    }

    @Test
    fun signInRedirectsAndMusicAppLinksStayInsideTheLoginWebView() {
        for (url in listOf(
            "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com",
            "https://accounts.youtube.com/accounts/SetSID",
            "https://consent.youtube.com/m",
            "https://music.youtube.com/",
            "https://music.youtube.com/channel_switcher",
        )) {
            assertEquals(LoginNavigationAction.LOAD_IN_CURRENT_WEBVIEW, loginNavigationAction(url, "https", false))
            assertEquals(LoginNavigationAction.LOAD_IN_MAIN_WEBVIEW, loginNavigationAction(url, "https", true))
        }
    }

    @Test
    fun redirectsCannotBypassTheSchemeHostPortOrCredentialChecks() {
        for ((url, scheme) in listOf(
            "intent://music.youtube.com/#Intent;scheme=https;package=com.google.android.apps.youtube.music;end" to "intent",
            "market://details?id=com.google.android.apps.youtube.music" to "market",
            "http://music.youtube.com" to "http",
            "https://music.youtube.com:444/" to "https",
            "https://music.youtube.com@evil.test/" to "https",
            "https://attacker@accounts.google.com/" to "https",
            "https://accounts.google.com.evil.test/" to "https",
            "https://evil.test/?continue=https://music.youtube.com" to "https",
        )) {
            assertEquals(LoginNavigationAction.BLOCK, loginNavigationAction(url, scheme, false))
            assertEquals(LoginNavigationAction.BLOCK, loginNavigationAction(url, scheme, true))
        }
    }

    @Test
    fun mainWebViewKeepsWebUrlsAndBlocksExternalSchemes() {
        assertEquals(
            LoginNavigationAction.LOAD_IN_CURRENT_WEBVIEW,
            loginNavigationAction("https://accounts.google.com/signin", "https", isPopup = false),
        )
        assertEquals(
            LoginNavigationAction.BLOCK,
            loginNavigationAction("intent://accounts.google.com", "intent", isPopup = false),
        )
    }

    @Test
    fun popupWaitsOnBlankThenHandsWebRedirectToMainView() {
        assertEquals(
            LoginNavigationAction.LOAD_IN_CURRENT_WEBVIEW,
            loginNavigationAction("about:blank", "about", isPopup = true),
        )
        assertEquals(
            LoginNavigationAction.LOAD_IN_MAIN_WEBVIEW,
            loginNavigationAction("https://accounts.google.com/signin", "https", isPopup = true),
        )
        assertEquals(
            LoginNavigationAction.BLOCK,
            loginNavigationAction("mailto:support@example.com", "mailto", isPopup = true),
        )
    }
}
