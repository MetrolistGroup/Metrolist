@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.metrolist.android.ui.isTrustedLoginUrl
import com.metrolist.android.utils.startActivitySafely
import com.metrolist.innertubex.models.response.AccountsListResponse
import com.metrolist.shared.auth.AccountDiscoveryAction
import com.metrolist.shared.auth.AuthManager
import com.metrolist.shared.auth.accountDiscoveryAction
import com.metrolist.shared.auth.selectionDataSyncId
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.authentication_data_extraction_failed
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.channel_switch_browse_hint
import com.metrolist.shared.generated.resources.choose_youtube_channel
import com.metrolist.shared.generated.resources.choose_youtube_channel_description
import com.metrolist.shared.generated.resources.complete_sign_in
import com.metrolist.shared.generated.resources.continue_action
import com.metrolist.shared.generated.resources.error
import com.metrolist.shared.generated.resources.login_failed_with_error
import com.metrolist.shared.generated.resources.page_load_failed_with_error
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.sign_in_on_tv
import com.metrolist.shared.generated.resources.sign_in_to_youtube_music
import com.metrolist.shared.generated.resources.switch_youtube_channel
import com.metrolist.shared.generated.resources.token_login
import com.metrolist.shared.generated.resources.tv_sign_in_warning
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.AccountPickerList
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.sanitizeDataSyncId
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.utils.BlockScreenshots
import com.metrolist.shared.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

private enum class LoginStage {
    Authenticating,
    CheckingAccounts,
    SelectingAccount,
    BrowsingAccounts,
    SwitchingAccount,
    Completing,
}

internal data class AuthData(
    val cookie: String,
    val visitorData: String,
    val dataSyncId: String,
    val authUser: String,
)

internal suspend fun awaitLoginAuthData(
    cookie: String?,
    visitorData: Deferred<String?>,
    dataSyncId: Deferred<String?>,
    authUser: Deferred<String?>,
): AuthData? =
    withTimeoutOrNull(5_000) {
        fun String?.configValue() = this?.takeUnless { it.isBlank() || it == "null" || it == "undefined" }
        val visitor = visitorData.await().configValue()
        if (cookie.isNullOrEmpty() || visitor == null) return@withTimeoutOrNull null
        AuthData(
            cookie,
            visitor,
            dataSyncId
                .await()
                .configValue()
                ?.let(::sanitizeDataSyncId)
                .orEmpty(),
            authUser.await().configValue().orEmpty(),
        )
    }

internal enum class LoginNavigationAction {
    LOAD_IN_CURRENT_WEBVIEW,
    LOAD_IN_MAIN_WEBVIEW,
    BLOCK,
}

internal fun loginNavigationAction(
    url: String,
    scheme: String?,
    isPopup: Boolean,
): LoginNavigationAction =
    when {
        isPopup && url.equals("about:blank", ignoreCase = true) -> {
            LoginNavigationAction.LOAD_IN_CURRENT_WEBVIEW
        }

        scheme.equals("https", ignoreCase = true) && isTrustedLoginUrl(url, "https://music.youtube.com") -> {
            if (isPopup) LoginNavigationAction.LOAD_IN_MAIN_WEBVIEW else LoginNavigationAction.LOAD_IN_CURRENT_WEBVIEW
        }

        else -> {
            LoginNavigationAction.BLOCK
        }
    }

@SuppressLint("SetJavaScriptEnabled")
private fun WebSettings.configureForLogin(userAgent: String) {
    javaScriptEnabled = true
    domStorageEnabled = true
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    allowFileAccess = false
    allowContentAccess = false
    setSupportZoom(true)
    builtInZoomControls = true
    displayZoomControls = false
    cacheMode = WebSettings.LOAD_DEFAULT
    setSupportMultipleWindows(true)
    userAgentString = userAgent
}

private fun WebView.disposeLoginWebView() {
    stopLoading()
    removeJavascriptInterface("Android")
    removeAllViews()
    destroy()
}

@SuppressLint("JavascriptInterface")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoginScreen(
    onNavigateBack: () -> Unit,
    onNavigateToTokenLogin: () -> Unit,
    isSwitchingChannel: Boolean = false,
) {
    BlockScreenshots()

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val authManager = AuthManager()
    val coroutineScope = rememberCoroutineScope()
    var loginStage by remember { mutableStateOf(LoginStage.Authenticating) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val loginError = stringResource(Res.string.login_failed_with_error, stringResource(Res.string.error))
    val isTv = LocalIsTv.current
    var tvWarningAccepted by remember { mutableStateOf(!isTv) }
    val tvWebViewFocusRequester = remember { FocusRequester() }

    val jsInterface = remember { LoginJsInterface() }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val popupWebViews = remember { mutableSetOf<WebView>() }
    var pendingAccounts by remember { mutableStateOf<List<AccountsListResponse.Account>?>(null) }
    var selectedAccount by remember { mutableStateOf<AccountsListResponse.Account?>(null) }

    fun navigateToSignInUrl(signinUrl: String?) {
        val url = signinUrl ?: return
        val absoluteUrl =
            when {
                url.startsWith("http://") || url.startsWith("https://") -> url
                url.startsWith("/") -> "https://music.youtube.com$url"
                else -> "https://music.youtube.com/$url"
            }
        loginStage = LoginStage.SwitchingAccount
        webViewRef?.loadUrl(absoluteUrl)
    }

    suspend fun extractAuthData(webView: WebView?): AuthData? {
        val view = webView ?: return null
        val cookie = CookieManager.getInstance().getCookie(view.url)

        val visitorDataDeferred = CompletableDeferred<String?>()
        val dataSyncIdDeferred = CompletableDeferred<String?>()
        val authUserDeferred = CompletableDeferred<String?>()

        jsInterface.onVisitorDataReceived = { vData ->
            if (!visitorDataDeferred.isCompleted) visitorDataDeferred.complete(vData)
        }
        jsInterface.onDataSyncIdReceived = { dSyncId ->
            if (!dataSyncIdDeferred.isCompleted) dataSyncIdDeferred.complete(dSyncId)
        }
        jsInterface.onAuthUserReceived = { authUser ->
            if (!authUserDeferred.isCompleted) authUserDeferred.complete(authUser)
        }

        return try {
            view.loadUrl("javascript:Android.onRetrieveVisitorData(window.yt && window.yt.config_ ? window.yt.config_.VISITOR_DATA : null)")
            view.loadUrl("javascript:Android.onRetrieveDataSyncId(window.yt && window.yt.config_ ? window.yt.config_.DATASYNC_ID : null)")
            view.loadUrl("javascript:Android.onRetrieveAuthUser(window.yt && window.yt.config_ ? window.yt.config_.SESSION_INDEX : null)")
            awaitLoginAuthData(cookie, visitorDataDeferred, dataSyncIdDeferred, authUserDeferred)
        } finally {
            jsInterface.onVisitorDataReceived = null
            jsInterface.onDataSyncIdReceived = null
            jsInterface.onAuthUserReceived = null
        }
    }

    suspend fun finalizeLogin(authData: AuthData) {
        currentCoroutineContext().ensureActive()
        try {
            if (
                !authManager.validateAndSaveCredentials(
                    cookie = authData.cookie,
                    visitorData = authData.visitorData,
                    dataSyncId = authData.dataSyncId,
                    authUser = authData.authUser,
                )
            ) {
                errorMessage = loginError
                loginStage = LoginStage.Authenticating
                return
            }
            SongLikeState.refreshLikesFromRemoteAsync()

            delay(300.milliseconds)

            webViewRef?.apply {
                stopLoading()
                clearHistory()
                clearCache(true)
                clearFormData()
            }

            if (isSwitchingChannel) {
                onNavigateBack()
            } else {
                context.packageManager
                    .getLaunchIntentForPackage(context.packageName)
                    ?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }?.let(context::startActivitySafely)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("LoginScreen", "Login failed type=${e::class.simpleName}")
            errorMessage = loginError
            loginStage = LoginStage.Authenticating
        }
    }

    suspend fun checkAvailableAccounts(authData: AuthData) {
        loginStage = LoginStage.CheckingAccounts
        val accounts =
            try {
                authManager.fetchAccounts(
                    cookie = authData.cookie,
                    visitorData = authData.visitorData,
                    dataSyncId = authData.dataSyncId,
                    authUser = authData.authUser,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }

        when (accountDiscoveryAction(accounts.size, isSwitchingChannel)) {
            AccountDiscoveryAction.SELECT_ACCOUNT -> {
                pendingAccounts = accounts
                selectedAccount = accounts.firstOrNull { it.isSelected } ?: accounts.firstOrNull()
                loginStage = LoginStage.SelectingAccount
            }

            AccountDiscoveryAction.BROWSE_ACCOUNTS -> {
                loginStage = LoginStage.BrowsingAccounts
            }

            AccountDiscoveryAction.COMPLETE_LOGIN -> {
                finalizeLogin(authData)
            }
        }
    }

    fun disposePopup(
        parent: WebView,
        popup: WebView,
    ) {
        parent.post {
            if (popupWebViews.remove(popup)) popup.disposeLoginWebView()
        }
    }

    fun handlePopupNavigation(
        parent: WebView,
        popup: WebView,
        url: String,
        scheme: String?,
    ): Boolean =
        when (loginNavigationAction(url, scheme, isPopup = true)) {
            LoginNavigationAction.LOAD_IN_CURRENT_WEBVIEW -> {
                false
            }

            LoginNavigationAction.LOAD_IN_MAIN_WEBVIEW -> {
                parent.loadUrl(url)
                disposePopup(parent, popup)
                true
            }

            LoginNavigationAction.BLOCK -> {
                disposePopup(parent, popup)
                true
            }
        }

    DisposableEffect(Unit) {
        onDispose {
            popupWebViews.toList().forEach(WebView::disposeLoginWebView)
            popupWebViews.clear()
            webViewRef?.disposeLoginWebView()
        }
    }

    fun onPageFinishedForLogin(
        url: String?,
        completeSignIn: Boolean = false,
    ) {
        val pageUri = url?.toUri() ?: return
        if (pageUri.scheme != "https" || pageUri.host != "music.youtube.com") return

        when (loginStage) {
            LoginStage.Authenticating -> {
                loginStage = LoginStage.CheckingAccounts
                coroutineScope.launch {
                    val authData =
                        extractAuthData(webViewRef) ?: run {
                            errorMessage = getString(Res.string.authentication_data_extraction_failed)
                            loginStage = LoginStage.Authenticating
                            return@launch
                        }
                    checkAvailableAccounts(authData)
                }
            }

            LoginStage.SwitchingAccount, LoginStage.BrowsingAccounts -> {
                if (loginStage == LoginStage.BrowsingAccounts && !completeSignIn) return
                loginStage = LoginStage.Completing
                coroutineScope.launch {
                    val authData =
                        extractAuthData(webViewRef) ?: run {
                            errorMessage = getString(Res.string.authentication_data_extraction_failed)
                            loginStage = LoginStage.Authenticating
                            return@launch
                        }
                    finalizeLogin(authData)
                }
            }

            else -> {
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (isSwitchingChannel) Res.string.switch_youtube_channel else Res.string.sign_in_to_youtube_music),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
                    }
                },
                actions = {
                    if (loginStage == LoginStage.BrowsingAccounts) {
                        TextButton(
                            onClick = { onPageFinishedForLogin(webViewRef?.url, completeSignIn = true) },
                            enabled = !isLoading,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(stringResource(Res.string.complete_sign_in)) }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
        bottomBar = {
            if (loginStage == LoginStage.BrowsingAccounts) {
                Text(
                    stringResource(Res.string.channel_switch_browse_hint, stringResource(Res.string.complete_sign_in)),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                )
            }
        },
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            if (tvWarningAccepted) {
                AndroidView(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (isTv) {
                                    Modifier
                                        .focusRequester(tvWebViewFocusRequester)
                                        .onFocusChanged { focusState ->
                                            if (focusState.hasFocus) {
                                                webViewRef?.requestFocus(View.FOCUS_DOWN)
                                            }
                                        }.focusable()
                                } else {
                                    Modifier
                                },
                            ),
                    factory = { webViewContext ->
                        WebView(webViewContext).apply {
                            if (isTv) {
                                isFocusable = true
                                isFocusableInTouchMode = true
                                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                                post { requestFocus(View.FOCUS_DOWN) }
                            }
                            webViewRef = this
                            webViewClient =
                                object : WebViewClient() {
                                    override fun onPageStarted(
                                        view: WebView,
                                        url: String?,
                                        favicon: Bitmap?,
                                    ) {
                                        super.onPageStarted(view, url, favicon)
                                        isLoading = true
                                    }

                                    override fun onPageFinished(
                                        view: WebView,
                                        url: String?,
                                    ) {
                                        isLoading = false
                                        onPageFinishedForLogin(url)
                                    }

                                    override fun onReceivedError(
                                        view: WebView,
                                        request: WebResourceRequest?,
                                        error: WebResourceError,
                                    ) {
                                        super.onReceivedError(view, request, error)
                                        Timber.tag("LoginScreen").e(
                                            "WebView error: ${error.description} (${error.errorCode}) for ${request?.url?.host}",
                                        )
                                        if (request?.isForMainFrame != true) return
                                        scope.launch { errorMessage = getString(Res.string.page_load_failed_with_error, error.description) }
                                        isLoading = false
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean {
                                        if (!request.isForMainFrame) return false
                                        return handleNavigation(request.url)
                                    }

                                    private fun handleNavigation(uri: Uri): Boolean {
                                        val action = loginNavigationAction(uri.toString(), uri.scheme, isPopup = false)
                                        return action != LoginNavigationAction.LOAD_IN_CURRENT_WEBVIEW
                                    }
                                }
                            settings.configureForLogin(WebSettings.getDefaultUserAgent(webViewContext))
                            webChromeClient =
                                object : WebChromeClient() {
                                    override fun onCreateWindow(
                                        view: WebView,
                                        isDialog: Boolean,
                                        isUserGesture: Boolean,
                                        resultMsg: Message,
                                    ): Boolean {
                                        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                                        val popup =
                                            WebView(view.context).apply {
                                                settings.configureForLogin(view.settings.userAgentString)
                                                webViewClient =
                                                    object : WebViewClient() {
                                                        override fun onPageStarted(
                                                            popupView: WebView,
                                                            url: String?,
                                                            favicon: Bitmap?,
                                                        ) {
                                                            super.onPageStarted(popupView, url, favicon)
                                                            url?.let {
                                                                handlePopupNavigation(
                                                                    parent = view,
                                                                    popup = popupView,
                                                                    url = it,
                                                                    scheme = it.toUri().scheme,
                                                                )
                                                            }
                                                        }

                                                        override fun shouldOverrideUrlLoading(
                                                            popupView: WebView,
                                                            request: WebResourceRequest,
                                                        ): Boolean {
                                                            if (!request.isForMainFrame) return false
                                                            return handlePopupNavigation(
                                                                parent = view,
                                                                popup = popupView,
                                                                url = request.url.toString(),
                                                                scheme = request.url.scheme,
                                                            )
                                                        }
                                                    }
                                            }
                                        popupWebViews += popup
                                        transport.webView = popup
                                        resultMsg.sendToTarget()
                                        return true
                                    }
                                }
                            addJavascriptInterface(jsInterface, "Android")
                            isLoading = true
                            loadUrl(
                                if (isSwitchingChannel) {
                                    "https://music.youtube.com"
                                } else {
                                    "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
                                },
                            )
                        }
                    },
                )
                if (isTv) {
                    LaunchedEffect(tvWarningAccepted) {
                        delay(100.milliseconds)
                        runCatching { tvWebViewFocusRequester.requestFocus() }
                        webViewRef?.post { webViewRef?.requestFocus(View.FOCUS_DOWN) }
                    }
                }
            }

            PopupVisibility(visible = !tvWarningAccepted) {
                AlertDialog(
                    onDismissRequest = onNavigateBack,
                    title = { Text(stringResource(Res.string.sign_in_on_tv)) },
                    text = { Text(stringResource(Res.string.tv_sign_in_warning)) },
                    dismissButton = {
                        Row {
                            TextButton(
                                onClick = onNavigateToTokenLogin,
                                modifier = Modifier.tvFocusHighlight(RoundedCornerShape(100.dp)),
                                shapes = ButtonDefaults.shapes(),
                            ) { Text(stringResource(Res.string.token_login)) }
                            TextButton(
                                onClick = onNavigateBack,
                                modifier = Modifier.tvFocusHighlight(RoundedCornerShape(100.dp)),
                                shapes = ButtonDefaults.shapes(),
                            ) { Text(stringResource(Res.string.cancel)) }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { tvWarningAccepted = true },
                            modifier =
                                Modifier.tvFocusHighlight(
                                    RoundedCornerShape(100.dp),
                                ),
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(stringResource(Res.string.continue_action)) }
                    },
                )
            }

            if (tvWarningAccepted && isLoading && loginStage != LoginStage.SelectingAccount) {
                ContainedLoadingIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            errorMessage?.let { error ->
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = {
                            errorMessage = null
                            loginStage = LoginStage.Authenticating
                            webViewRef?.reload()
                        },
                        modifier = Modifier.padding(top = 16.dp),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(Res.string.retry))
                    }
                }
            }

            if (loginStage == LoginStage.SelectingAccount) {
                val accounts = pendingAccounts
                if (accounts != null) {
                    AlertDialog(
                        onDismissRequest = onNavigateBack,
                        title = { Text(stringResource(Res.string.choose_youtube_channel)) },
                        text = {
                            Column(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                            ) {
                                Text(
                                    text = stringResource(Res.string.choose_youtube_channel_description),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(16.dp))
                                Column(
                                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                                ) {
                                    AccountPickerList(
                                        accounts = accounts,
                                        selectedAccount = selectedAccount,
                                        onAccountSelected = { selectedAccount = it },
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val account = selectedAccount ?: return@Button
                                    if (account.isSelected || account.selectionDataSyncId("") != null) {
                                        loginStage = LoginStage.Completing
                                        coroutineScope.launch {
                                            val authData =
                                                extractAuthData(webViewRef) ?: run {
                                                    errorMessage = getString(Res.string.authentication_data_extraction_failed)
                                                    loginStage = LoginStage.Authenticating
                                                    return@launch
                                                }
                                            val identity = account.selectionDataSyncId(authData.dataSyncId)
                                            if (identity != null) {
                                                finalizeLogin(authData.copy(dataSyncId = identity))
                                            } else if (!account.signinUrl.isNullOrBlank()) {
                                                navigateToSignInUrl(account.signinUrl)
                                            } else {
                                                errorMessage = loginError
                                                loginStage = LoginStage.Authenticating
                                            }
                                        }
                                    } else if (!account.signinUrl.isNullOrBlank()) {
                                        navigateToSignInUrl(account.signinUrl)
                                    } else {
                                        errorMessage = loginError
                                        loginStage = LoginStage.Authenticating
                                    }
                                },
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(Res.string.continue_action))
                            }
                        },
                    )
                }
            }
        }
    }

    BackHandler(enabled = webViewRef?.canGoBack() == true && errorMessage == null && loginStage == LoginStage.Authenticating) {
        webViewRef?.goBack()
    }
}

class LoginJsInterface {
    var onVisitorDataReceived: ((String?) -> Unit)? = null
    var onDataSyncIdReceived: ((String?) -> Unit)? = null
    var onAuthUserReceived: ((String?) -> Unit)? = null

    @JavascriptInterface
    fun onRetrieveVisitorData(newVisitorData: String?) {
        onVisitorDataReceived?.invoke(newVisitorData)
    }

    @JavascriptInterface
    fun onRetrieveDataSyncId(newDataSyncId: String?) {
        onDataSyncIdReceived?.invoke(newDataSyncId)
    }

    @JavascriptInterface
    fun onRetrieveAuthUser(newAuthUser: String?) {
        onAuthUserReceived?.invoke(newAuthUser)
    }
}
