/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.metrolist.innertube.YouTube
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.AccountChannelHandleKey
import com.metrolist.music.constants.AccountEmailKey
import com.metrolist.music.constants.AccountNameKey
import com.metrolist.music.constants.DataSyncIdKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.VisitorDataKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.reportException
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(navController: NavController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isCompletingLogin by remember { mutableStateOf(false) }

    var webView: WebView? = null
    var visitorDataFromWeb by remember { mutableStateOf("") }
    var dataSyncIdFromWeb by remember { mutableStateOf("") }

    fun completeLogin(onClose: () -> Unit) {
        if (isCompletingLogin) return

        isCompletingLogin = true
        coroutineScope.launch {
            val currentCookie = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
            if (currentCookie.isBlank()) {
                Timber.d("Login: No YouTube Music cookie found on close, leaving login screen")
                isCompletingLogin = false
                onClose()
                return@launch
            }

            // Save extracted values from the WebView before validating
            val savedVisitorData = visitorDataFromWeb
            val savedDataSyncId = dataSyncIdFromWeb

            // Initialize YouTube object with selected authentication data
            YouTube.cookie = currentCookie
            YouTube.dataSyncId = savedDataSyncId
            YouTube.visitorData = savedVisitorData

            Timber.d("Login: Manual close detected, validating selected account...")

            YouTube
                .accountInfo()
                .onSuccess { info ->
                    Timber.d("Login: Successfully logged in as ${info.name}, restarting app...")

                    // Clean up WebView
                    webView?.apply {
                        stopLoading()
                        clearHistory()
                        clearCache(true)
                        clearFormData()
                    }

                    // Save ALL credentials atomically to DataStore, then restart the app.
                    // This replicates the exact token login pattern (saveTokenAndRestart) that
                    // works reliably: write atomically, then start the
                    // launch intent and exit the process so all services reinitialize cleanly.
                    val saved = withContext(Dispatchers.IO) {
                        context.safeDataStoreEdit { settings ->
                            settings[InnerTubeCookieKey] = currentCookie
                            settings[VisitorDataKey] = savedVisitorData
                            settings[DataSyncIdKey] = savedDataSyncId
                            settings[AccountNameKey] = info.name
                            settings[AccountEmailKey] = info.email.orEmpty()
                            settings[AccountChannelHandleKey] = info.channelHandle.orEmpty()
                        }
                    }

                    if (!saved) {
                        Timber.e("Login: Failed to persist account data")
                        isCompletingLogin = false
                        return@onSuccess
                    }

                    withContext(Dispatchers.Main) {
                        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        context.startActivity(intent)
                        Runtime.getRuntime().exit(0)
                    }
                }.onFailure {
                    Timber.e(it, "Login: Authentication validation failed after manual close")
                    reportException(it)
                    isCompletingLogin = false
                    onClose()
                }
        }
    }

    AndroidView(
        modifier =
            Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .fillMaxSize(),
        factory = { webViewContext ->
            WebView(webViewContext).apply {
                webViewClient =
                    object : WebViewClient() {
                        override fun onPageFinished(
                            view: WebView,
                            url: String?,
                        ) {
                            loadUrl("javascript:Android.onRetrieveVisitorData(window.yt.config_.VISITOR_DATA)")
                            loadUrl("javascript:Android.onRetrieveDataSyncId(window.yt.config_.DATASYNC_ID)")

                            // Auto-detect login completion: when the WebView lands on
                            // music.youtube.com with a valid cookie, complete the login.
                            if (url?.contains("music.youtube.com") == true &&
                                !isCompletingLogin &&
                                CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
                                    .isNotBlank()
                            ) {
                                Timber.d("Login: Detected authenticated session on music.youtube.com, completing login...")
                                completeLogin(navController::navigateUp)
                            }
                        }
                    }
                settings.apply {
                    javaScriptEnabled = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                }
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onRetrieveVisitorData(newVisitorData: String?) {
                            if (newVisitorData != null) {
                                visitorDataFromWeb = newVisitorData
                            }
                        }

                        @JavascriptInterface
                        fun onRetrieveDataSyncId(newDataSyncId: String?) {
                            if (newDataSyncId != null) {
                                dataSyncIdFromWeb = newDataSyncId.substringBefore("||")
                            }
                        }
                    },
                    "Android",
                )
                webView = this
                loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
            }
        },
    )

    TopAppBar(
        title = { Text(stringResource(R.string.login)) },
        navigationIcon = {
            IconButton(
                onClick = { completeLogin(navController::navigateUp) },
                onLongClick = { completeLogin(navController::backToMain) },
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )

    BackHandler {
        val currentWebView = webView
        if (currentWebView?.canGoBack() == true) {
            currentWebView.goBack()
        } else {
            completeLogin(navController::navigateUp)
        }
    }
}
