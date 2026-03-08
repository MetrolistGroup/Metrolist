@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.playlistimport

import android.annotation.SuppressLint
import android.graphics.Bitmap
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.metrolist.android.ui.isTrustedLoginUrl
import com.metrolist.android.ui.openExternalLoginUrl
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.playlistimport.awaitProviderCredentials
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.utils.BlockScreenshots
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import timber.log.Timber

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionName")
internal fun <Credentials> ProviderLoginShell(
    titleRes: StringResource,
    loginErrorRes: StringResource,
    cookieUrl: String,
    loginUrl: String,
    logTag: String,
    credentialLogMessage: String,
    extractCredentials: (String) -> Credentials?,
    credentialKey: (Credentials) -> String,
    isAuthenticating: Boolean,
    authenticationError: String?,
    onClearAuthenticationError: () -> Unit,
    onCredentials: (Credentials) -> Unit,
    onNavigateBack: () -> Unit,
    onWebViewError: (WebResourceError, WebResourceRequest) -> Unit = { _, _ -> },
    onWebViewDisposed: () -> Unit = {},
) {
    BlockScreenshots()
    val loginError = stringResource(loginErrorRes)
    val currentOnCredentials by rememberUpdatedState(onCredentials)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isPageLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var submittedCredentialKey by remember { mutableStateOf<String?>(null) }

    fun submitCredentialsIfAvailable() {
        val cookieHeader = CookieManager.getInstance().getCookie(cookieUrl) ?: return
        val credentials = extractCredentials(cookieHeader) ?: return
        val key = credentialKey(credentials)
        if (key == submittedCredentialKey) return
        submittedCredentialKey = key
        Timber.tag(logTag).i("$credentialLogMessage detected")
        currentOnCredentials(credentials)
    }

    LaunchedEffect(webView, authenticationError) {
        if (webView == null || authenticationError != null) return@LaunchedEffect
        awaitProviderCredentials {
            extractCredentials(CookieManager.getInstance().getCookie(cookieUrl).orEmpty())
        }
        submitCredentialsIfAvailable()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                removeAllViews()
                destroy()
            }
            onWebViewDisposed()
        }
    }

    ProviderLoginWindow(onDismissRequest = onNavigateBack) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(titleRes)) },
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
                                        submitCredentialsIfAvailable()
                                    }

                                    override fun onPageFinished(
                                        view: WebView,
                                        url: String?,
                                    ) {
                                        isPageLoading = false
                                        submitCredentialsIfAvailable()
                                    }

                                    override fun onLoadResource(
                                        view: WebView?,
                                        url: String?,
                                    ) {
                                        super.onLoadResource(view, url)
                                        submitCredentialsIfAvailable()
                                    }

                                    override fun onReceivedError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        error: WebResourceError,
                                    ) {
                                        if (!request.isForMainFrame) return
                                        onWebViewError(error, request)
                                        isPageLoading = false
                                        pageError = loginError
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean {
                                        if (!request.isForMainFrame || isTrustedLoginUrl(request.url.toString(), loginUrl)) return false
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
                            loadUrl(loginUrl)
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
                            Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                            Button(
                                onClick = {
                                    submittedCredentialKey = null
                                    pageError = null
                                    onClearAuthenticationError()
                                    webView?.reload()
                                },
                                modifier = Modifier.padding(top = 16.dp),
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(Res.string.retry))
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
