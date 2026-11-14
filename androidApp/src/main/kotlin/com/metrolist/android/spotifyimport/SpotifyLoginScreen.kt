@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.spotifyimport

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.metrolist.android.playlistimport.ProviderLoginWindow
import com.metrolist.android.ui.isTrustedLoginUrl
import com.metrolist.android.ui.openExternalLoginUrl
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.spotify_login_page_failed
import com.metrolist.shared.generated.resources.spotify_sign_in
import com.metrolist.shared.generated.resources.spotify_sign_in_again
import com.metrolist.shared.playlistimport.awaitProviderCredentials
import com.metrolist.shared.spotifyimport.spotifySessionCookie
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.utils.BlockScreenshots
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import timber.log.Timber

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyLoginScreen(
    isAuthenticating: Boolean,
    authenticationError: String?,
    onClearAuthenticationError: () -> Unit,
    onSessionCookie: (String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    BlockScreenshots()
    val currentOnSessionCookie by rememberUpdatedState(onSessionCookie)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isPageLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var submittedCookie by remember { mutableStateOf<String?>(null) }

    fun submitCookieIfAvailable() {
        val cookie = spotifySessionCookie(CookieManager.getInstance().getCookie(SPOTIFY_OPEN_URL)) ?: return
        if (cookie == submittedCookie) return
        submittedCookie = cookie
        Timber.tag(TAG).i("Spotify WebView session cookie detected")
        currentOnSessionCookie(cookie)
        clearSpotifySessionCookie()
    }

    LaunchedEffect(webView, authenticationError) {
        if (webView == null || authenticationError != null) return@LaunchedEffect
        awaitProviderCredentials {
            spotifySessionCookie(CookieManager.getInstance().getCookie(SPOTIFY_OPEN_URL))
        }
        submitCookieIfAvailable()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                removeAllViews()
                destroy()
            }
            Timber.tag(TAG).d("Spotify login WebView destroyed")
        }
    }

    ProviderLoginWindow(onDismissRequest = onNavigateBack) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(Res.string.spotify_sign_in)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack, shapes = IconButtonDefaults.shapes()) {
                            Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            },
        ) { paddingValues ->
            Box(Modifier.fillMaxSize().padding(paddingValues)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { webViewContext ->
                        WebView(webViewContext).apply webView@{
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            webView = this
                            CookieManager.getInstance().apply {
                                setAcceptCookie(true)
                                setAcceptThirdPartyCookies(this@webView, true)
                            }
                            webViewClient =
                                object : WebViewClient() {
                                    override fun onPageStarted(
                                        view: WebView,
                                        url: String?,
                                        favicon: Bitmap?,
                                    ) {
                                        isPageLoading = true
                                        pageError = null
                                        url?.toUri()?.let { Timber.tag(TAG).d("Spotify WebView loading host=%s path=%s", it.host, it.path) }
                                    }

                                    override fun onPageFinished(
                                        view: WebView,
                                        url: String?,
                                    ) {
                                        isPageLoading = false
                                        url?.toUri()?.let { Timber.tag(TAG).d("Spotify WebView loaded host=%s path=%s", it.host, it.path) }
                                        submitCookieIfAvailable()
                                    }

                                    override fun onReceivedError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        error: WebResourceError,
                                    ) {
                                        if (!request.isForMainFrame) return
                                        Timber.tag(TAG).w(
                                            "Spotify WebView failed code=%d host=%s",
                                            error.errorCode,
                                            request.url.host,
                                        )
                                        isPageLoading = false
                                        scope.launch { pageError = getString(Res.string.spotify_login_page_failed, error.description) }
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean {
                                        if (!request.isForMainFrame ||
                                            isTrustedLoginUrl(request.url.toString(), "https://accounts.spotify.com")
                                        ) {
                                            return false
                                        }
                                        openExternalLoginUrl(view.context, request.url.toString())
                                        return true
                                    }
                                }
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                allowFileAccess = false
                                allowContentAccess = false
                                cacheMode = WebSettings.LOAD_DEFAULT
                                userAgentString = WebSettings.getDefaultUserAgent(webViewContext)
                            }
                            webChromeClient = WebChromeClient()
                            loadUrl(SPOTIFY_LOGIN_URL)
                        }
                    },
                )

                if ((isPageLoading || isAuthenticating) && pageError == null && authenticationError == null) {
                    ContainedLoadingIndicator(Modifier.align(Alignment.Center))
                }

                (authenticationError ?: pageError)?.let { error ->
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Button(
                                onClick = {
                                    submittedCookie = null
                                    pageError = null
                                    onClearAuthenticationError()
                                    webView?.reload()
                                },
                                modifier = Modifier.padding(top = 16.dp),
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(Res.string.retry))
                            }
                            if (authenticationError != null) {
                                Button(
                                    onClick = {
                                        submittedCookie = null
                                        pageError = null
                                        onClearAuthenticationError()
                                        clearSpotifySessionCookie()
                                        webView?.loadUrl(SPOTIFY_LOGIN_URL)
                                    },
                                    modifier = Modifier.padding(top = 8.dp),
                                    shapes = ButtonDefaults.shapes(),
                                ) {
                                    Text(stringResource(Res.string.spotify_sign_in_again))
                                }
                            }
                        }
                    }
                }
            }
        }

        BackHandler {
            if (webView?.canGoBack() == true && authenticationError == null && pageError == null) {
                webView?.goBack()
            } else {
                onNavigateBack()
            }
        }
    }
}

private fun clearSpotifySessionCookie() {
    CookieManager.getInstance().apply {
        setCookie(SPOTIFY_OPEN_URL, "sp_dc=; Max-Age=0; Path=/; Domain=.spotify.com; Secure")
        flush()
    }
    Timber.tag(TAG).i("Cleared stale Spotify WebView session cookie")
}

private const val TAG = "SpotifyImport"
private const val SPOTIFY_OPEN_URL = "https://open.spotify.com/"
private const val SPOTIFY_LOGIN_URL =
    "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
