@file:Suppress("FunctionName")

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import com.metrolist.innertubex.models.response.AccountsListResponse
import com.metrolist.shared.auth.AccountDiscoveryAction
import com.metrolist.shared.auth.AuthManager
import com.metrolist.shared.auth.accountDiscoveryAction
import com.metrolist.shared.auth.hasSupportedYouTubeAuthCookie
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
import com.metrolist.shared.generated.resources.sign_in_to_youtube_music
import com.metrolist.shared.generated.resources.switch_youtube_channel
import com.metrolist.shared.generated.resources.token_login
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.components.AccountPickerList
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.sanitizeDataSyncId
import com.metrolist.shared.utils.BlockScreenshots
import com.metrolist.shared.utils.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.stringResource
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLRequest
import platform.UIKit.UIDevice
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWindowFeatures
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

private enum class IosLoginStage {
    AUTHENTICATING,
    CHECKING_ACCOUNTS,
    SELECTING_ACCOUNT,
    BROWSING_ACCOUNTS,
    SWITCHING_ACCOUNT,
    COMPLETING,
}

private data class IosAuthData(
    val cookie: String,
    val visitorData: String,
    val dataSyncId: String,
    val authUser: String,
)

@Serializable
private data class IosWebConfig(
    val visitorData: String = "",
    val dataSyncId: String = "",
    val authUser: String = "",
)

@OptIn(ExperimentalForeignApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun IosLoginScreen(
    isSwitchingChannel: Boolean,
    onBack: () -> Unit,
    onComplete: () -> Unit,
    onTokenLogin: () -> Unit,
) {
    BlockScreenshots()

    val authManager = remember { AuthManager() }
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WKWebView?>(null) }
    var stage by remember { mutableStateOf(IosLoginStage.AUTHENTICATING) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var accounts by remember { mutableStateOf<List<AccountsListResponse.Account>>(emptyList()) }
    var selectedAccount by remember { mutableStateOf<AccountsListResponse.Account?>(null) }
    val extractionError = stringResource(Res.string.authentication_data_extraction_failed)
    val genericError = stringResource(Res.string.error)
    val loginError = stringResource(Res.string.login_failed_with_error, genericError)
    val pageError = stringResource(Res.string.page_load_failed_with_error, genericError)

    suspend fun finalizeLogin(authData: IosAuthData) {
        currentCoroutineContext().ensureActive()
        stage = IosLoginStage.COMPLETING
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
                stage = IosLoginStage.AUTHENTICATING
                return
            }
            SongLikeState.refreshLikesFromRemoteAsync()
            onComplete()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.w("IosLogin", "Login failed type=${error::class.simpleName}")
            errorMessage = loginError
            stage = IosLoginStage.AUTHENTICATING
        }
    }

    fun extractAndContinue(
        switchingAccount: Boolean,
        account: AccountsListResponse.Account? = null,
    ) {
        val view = webView ?: return
        stage = IosLoginStage.CHECKING_ACCOUNTS
        scope.launch {
            val authData = extractIosAuthData(view)
            if (authData == null) {
                errorMessage = extractionError
                stage = IosLoginStage.AUTHENTICATING
                return@launch
            }
            if (switchingAccount) {
                val identity = if (account == null) authData.dataSyncId else account.selectionDataSyncId(authData.dataSyncId)
                if (identity == null) {
                    errorMessage = loginError
                    stage = IosLoginStage.AUTHENTICATING
                    return@launch
                }
                finalizeLogin(authData.copy(dataSyncId = identity))
                return@launch
            }
            val availableAccounts =
                try {
                    authManager.fetchAccounts(
                        cookie = authData.cookie,
                        visitorData = authData.visitorData,
                        dataSyncId = authData.dataSyncId,
                        authUser = authData.authUser,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Logger.w("IosLogin", "Account discovery failed type=${error::class.simpleName}")
                    emptyList()
                }
            when (accountDiscoveryAction(availableAccounts.size, isSwitchingChannel)) {
                AccountDiscoveryAction.SELECT_ACCOUNT -> {
                    accounts = availableAccounts
                    selectedAccount = availableAccounts.firstOrNull { it.isSelected } ?: availableAccounts.first()
                    stage = IosLoginStage.SELECTING_ACCOUNT
                }

                AccountDiscoveryAction.BROWSE_ACCOUNTS -> {
                    stage = IosLoginStage.BROWSING_ACCOUNTS
                }

                AccountDiscoveryAction.COMPLETE_LOGIN -> {
                    finalizeLogin(authData)
                }
            }
        }
    }

    val delegate = remember { IosLoginDelegate() }
    delegate.onStart = { isLoading = true }
    delegate.onFinish = { view ->
        isLoading = false
        val url = view.URL
        if (url?.scheme == "https" && url.host == "music.youtube.com") {
            when (stage) {
                IosLoginStage.AUTHENTICATING -> extractAndContinue(switchingAccount = false)
                IosLoginStage.SWITCHING_ACCOUNT -> extractAndContinue(switchingAccount = true)
                else -> Unit
            }
        }
    }
    delegate.onError = { error ->
        isLoading = false
        Logger.w("IosLogin", "Page load failed code=${error.code}")
        errorMessage = pageError
    }

    fun close() {
        scope.cancel()
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isSwitchingChannel) Res.string.switch_youtube_channel else Res.string.sign_in_to_youtube_music,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = ::close, shapes = IconButtonDefaults.shapes()) {
                        Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
                    }
                },
                actions = {
                    if (stage == IosLoginStage.BROWSING_ACCOUNTS) {
                        TextButton(
                            onClick = {
                                val url = webView?.URL
                                if (url?.scheme == "https" && url.host == "music.youtube.com") {
                                    extractAndContinue(switchingAccount = true)
                                }
                            },
                            enabled = !isLoading,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(stringResource(Res.string.complete_sign_in)) }
                    }
                    TextButton(
                        onClick = {
                            scope.cancel()
                            onTokenLogin()
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(Res.string.token_login)) }
                },
            )
        },
        bottomBar = {
            if (stage == IosLoginStage.BROWSING_ACCOUNTS) {
                Text(
                    stringResource(Res.string.channel_switch_browse_hint, stringResource(Res.string.complete_sign_in)),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            UIKitView(
                factory = {
                    val configuration =
                        WKWebViewConfiguration().apply {
                            applicationNameForUserAgent =
                                "Version/${UIDevice.currentDevice.systemVersion} Safari/604.1"
                        }
                    WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = configuration).apply {
                        navigationDelegate = delegate
                        UIDelegate = delegate
                        allowsBackForwardNavigationGestures = true
                        webView = this
                        loadLoginRequest(
                            url = if (isSwitchingChannel) MUSIC_URL else LOGIN_URL,
                            clearCookies = !isSwitchingChannel,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = {
                    it.navigationDelegate = delegate
                    it.UIDelegate = delegate
                },
                onRelease = {
                    it.stopLoading()
                    it.navigationDelegate = null
                    it.UIDelegate = null
                    if (webView === it) webView = null
                },
            )

            if (
                isLoading ||
                stage == IosLoginStage.CHECKING_ACCOUNTS ||
                stage == IosLoginStage.COMPLETING
            ) {
                ContainedLoadingIndicator(Modifier.align(Alignment.Center))
            }

            errorMessage?.let { message ->
                Surface(Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(
                        Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        Button(
                            onClick = {
                                errorMessage = null
                                stage = IosLoginStage.AUTHENTICATING
                                webView?.reload()
                            },
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(stringResource(Res.string.retry)) }
                    }
                }
            }
        }
    }

    if (stage == IosLoginStage.SELECTING_ACCOUNT) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(Res.string.choose_youtube_channel)) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    Text(
                        stringResource(Res.string.choose_youtube_channel_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        AccountPickerList(accounts, selectedAccount, onAccountSelected = { selectedAccount = it })
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val account = selectedAccount ?: return@Button
                        if (account.isSelected || account.selectionDataSyncId("") != null) {
                            extractAndContinue(switchingAccount = true, account = account)
                        } else {
                            val signInUrl = account.signinUrl?.takeIf(String::isNotBlank)
                            if (signInUrl == null) {
                                errorMessage = loginError
                                stage = IosLoginStage.AUTHENTICATING
                                return@Button
                            }
                            stage = IosLoginStage.SWITCHING_ACCOUNT
                            webView?.loadRequest(
                                NSURLRequest.requestWithURL(
                                    requireNotNull(NSURL.URLWithString(resolveYouTubeUrl(signInUrl))),
                                ),
                            )
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.continue_action)) }
            },
            dismissButton = {
                TextButton(onClick = ::close, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun extractIosAuthData(webView: WKWebView): IosAuthData? {
    repeat(40) {
        val config =
            suspendCancellableCoroutine { continuation ->
                webView.evaluateJavaScript(
                    "JSON.stringify({ visitorData: globalThis.ytcfg?.get?.('VISITOR_DATA') || globalThis.yt?.config_?.VISITOR_DATA || '', dataSyncId: globalThis.ytcfg?.get?.('DATASYNC_ID') || globalThis.yt?.config_?.DATASYNC_ID || '', authUser: String(globalThis.ytcfg?.get?.('SESSION_INDEX') ?? globalThis.yt?.config_?.SESSION_INDEX ?? '') })",
                ) { result, _ ->
                    val config = (result as? String)?.let { runCatching { Json.decodeFromString<IosWebConfig>(it) }.getOrNull() }
                    if (continuation.isActive) continuation.resume(config)
                }
            }
        val cookies =
            suspendCancellableCoroutine { continuation ->
                webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { values ->
                    if (continuation.isActive) {
                        continuation.resume(values.orEmpty().filterIsInstance<NSHTTPCookie>())
                    }
                }
            }
        val youtubeCookies =
            cookies.filter { it.domain.removePrefix(".").let { domain -> domain == "youtube.com" || domain.endsWith(".youtube.com") } }
        val cookie = NSHTTPCookie.requestHeaderFieldsWithCookies(youtubeCookies)["Cookie"] as? String
        if (config != null && !cookie.isNullOrBlank() && config.visitorData.isNotBlank() && hasSupportedYouTubeAuthCookie(cookie)) {
            return IosAuthData(
                cookie = cookie,
                visitorData = config.visitorData,
                dataSyncId = sanitizeDataSyncId(config.dataSyncId),
                authUser = config.authUser,
            )
        }
        delay(250.milliseconds)
    }
    return null
}

private fun WKWebView.loadLoginRequest(
    url: String,
    clearCookies: Boolean,
) {
    val request = NSURLRequest.requestWithURL(requireNotNull(NSURL.URLWithString(url)))
    if (!clearCookies) {
        loadRequest(request)
        return
    }
    configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies ->
        val storedCookies = cookies.orEmpty().filterIsInstance<NSHTTPCookie>()
        var remaining = storedCookies.size
        if (remaining == 0) {
            loadRequest(request)
            return@getAllCookies
        }
        storedCookies.forEach { cookie ->
            configuration.websiteDataStore.httpCookieStore.deleteCookie(cookie) {
                remaining--
                if (remaining == 0) loadRequest(request)
            }
        }
    }
}

private fun resolveYouTubeUrl(url: String): String =
    when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("/") -> "$MUSIC_URL$url"
        else -> "$MUSIC_URL/$url"
    }

@OptIn(ExperimentalForeignApi::class)
private class IosLoginDelegate :
    NSObject(),
    WKNavigationDelegateProtocol,
    WKUIDelegateProtocol {
    var onStart: () -> Unit = {}
    var onFinish: (WKWebView) -> Unit = {}
    var onError: (NSError) -> Unit = {}

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didStartProvisionalNavigation: WKNavigation?,
    ) = onStart()

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFinishNavigation: WKNavigation?,
    ) = onFinish(webView)

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailNavigation: WKNavigation?,
        withError: NSError,
    ) {
        if (withError.code != NSURLErrorCancelled) onError(withError)
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: NSError,
    ) {
        if (withError.code != NSURLErrorCancelled) onError(withError)
    }

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val scheme = decidePolicyForNavigationAction.request.URL?.scheme
        decisionHandler(
            if (scheme == "http" || scheme == "https") {
                WKNavigationActionPolicy.WKNavigationActionPolicyAllow
            } else {
                WKNavigationActionPolicy.WKNavigationActionPolicyCancel
            },
        )
    }

    override fun webView(
        webView: WKWebView,
        createWebViewWithConfiguration: WKWebViewConfiguration,
        forNavigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures,
    ): WKWebView? {
        forNavigationAction.request.takeIf { it.URL?.scheme in setOf("http", "https") }?.let(webView::loadRequest)
        return null
    }
}

private const val MUSIC_URL = "https://music.youtube.com"
private const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
