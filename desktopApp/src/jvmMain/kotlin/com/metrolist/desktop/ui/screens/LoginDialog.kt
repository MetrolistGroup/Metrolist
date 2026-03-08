@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.metrolist.desktop.auth.readWindowsLoginHelperOutput
import com.metrolist.desktop.ui.DesktopDensityProvider
import com.metrolist.desktop.ui.components.WebViewAvailabilityLoading
import com.metrolist.desktop.ui.components.WebViewUnavailableContent
import com.metrolist.desktop.ui.components.rememberLinuxWebKitAvailable
import com.metrolist.desktop.utils.prepareDesktopWebViewDataDirectory
import com.metrolist.desktop.window.ClearWindowContentOnDispose
import com.metrolist.innertubex.models.response.AccountsListResponse
import com.metrolist.shared.auth.AccountDiscoveryAction
import com.metrolist.shared.auth.AuthManager
import com.metrolist.shared.auth.accountDiscoveryAction
import com.metrolist.shared.auth.selectionDataSyncId
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.account_picker_subtitle
import com.metrolist.shared.generated.resources.account_picker_title
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.channel_switch_browse_hint
import com.metrolist.shared.generated.resources.channel_switch_recovery_hint
import com.metrolist.shared.generated.resources.channel_switch_sign_in_again
import com.metrolist.shared.generated.resources.channel_switch_unavailable
import com.metrolist.shared.generated.resources.clearing_signin_session
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.complete_sign_in
import com.metrolist.shared.generated.resources.continue_action
import com.metrolist.shared.generated.resources.error
import com.metrolist.shared.generated.resources.launching_external_login
import com.metrolist.shared.generated.resources.login_failed_with_error
import com.metrolist.shared.generated.resources.login_helper_failed
import com.metrolist.shared.generated.resources.login_helper_not_found
import com.metrolist.shared.generated.resources.login_successful_syncing
import com.metrolist.shared.generated.resources.login_window_closed_or_failed
import com.metrolist.shared.generated.resources.login_window_closed_unfinished
import com.metrolist.shared.generated.resources.ready_to_start_login
import com.metrolist.shared.generated.resources.retry_cleanup
import com.metrolist.shared.generated.resources.sign_in_youtube_music
import com.metrolist.shared.generated.resources.signin_cleanup_failed
import com.metrolist.shared.generated.resources.start_login_process
import com.metrolist.shared.generated.resources.switch_youtube_channel
import com.metrolist.shared.generated.resources.webview_cleanup_failed
import com.metrolist.shared.generated.resources.windows_login_helper
import com.metrolist.shared.generated.resources.youtube_login_webview_unwritable
import com.metrolist.shared.generated.resources.youtube_signin_unavailable
import com.metrolist.shared.playlistimport.awaitProviderCredentials
import com.metrolist.shared.ui.components.AccountPickerList
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.hasAuthCookie
import com.metrolist.shared.ui.sanitizeDataSyncId
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import com.metrolist.shared.utils.OsUtil
import com.metrolist.shared.utils.clearWebCookies
import com.metrolist.shared.youtube.logging.YtLogger
import io.github.kdroidfilter.webview.cookie.Cookie
import io.github.kdroidfilter.webview.cookie.CookieManager
import io.github.kdroidfilter.webview.web.LoadingState
import io.github.kdroidfilter.webview.web.WebView
import io.github.kdroidfilter.webview.web.WebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.stringResource
import java.io.File
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

private enum class LoginStage {
    Authenticating,
    CheckingAccounts,
    SelectingAccount,
    BrowsingAccounts,
    SwitchingAccount,
    Completing,
}

private sealed interface WindowsLoginStatus {
    data object Ready : WindowsLoginStatus

    data object Launching : WindowsLoginStatus

    data object HelperNotFound : WindowsLoginStatus

    data object Success : WindowsLoginStatus

    data object ClosedUnfinished : WindowsLoginStatus

    data object ClosedOrFailed : WindowsLoginStatus

    data class Failed(
        val error: String,
    ) : WindowsLoginStatus
}

@Composable
fun LoginDialog(
    onDismissRequest: () -> Unit,
    onAuthDataExtracted: (cookie: String, visitorData: String, dataSyncId: String, authUser: String) -> Unit,
    isSwitchingChannel: Boolean = false,
) {
    var loginCompleted by remember { mutableStateOf(false) }
    var cleanupRequested by remember { mutableStateOf(false) }
    var cleanupError by remember { mutableStateOf<String?>(null) }
    var cleanupAttempt by remember { mutableStateOf(0) }
    val defaultCleanupError = stringResource(Res.string.webview_cleanup_failed)
    val requestDismiss: () -> Unit = {
        if (loginCompleted || isSwitchingChannel) {
            onDismissRequest()
        } else {
            cleanupRequested = true
            cleanupError = null
            cleanupAttempt += 1
        }
    }

    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    DialogWindow(
        onCloseRequest = requestDismiss,
        title =
            if (isSwitchingChannel) {
                stringResource(
                    Res.string.switch_youtube_channel,
                )
            } else {
                stringResource(Res.string.sign_in_youtube_music)
            },
        state = rememberDialogState(width = 600.dp, height = 700.dp),
        visible = true,
    ) {
        ClearWindowContentOnDispose(window)
        MaterialTheme(colorScheme = colorScheme, typography = typography, shapes = shapes) {
            Surface(Modifier.fillMaxSize()) {
                DesktopDensityProvider {
                    val webKitAvailable = rememberLinuxWebKitAvailable()
                    if (cleanupRequested) {
                        LaunchedEffect(cleanupAttempt) {
                            delay(250L.milliseconds)
                            val result = withContext(Dispatchers.IO) { runCatching { clearWebCookies() } }
                            result.onSuccess { onDismissRequest() }
                            result.onFailure {
                                cleanupError = it.message ?: defaultCleanupError
                            }
                        }
                        Column(
                            Modifier.fillMaxSize().padding(24.dp),
                            verticalArrangement =
                                Arrangement
                                    .spacedBy(12.dp),
                        ) {
                            Text(
                                if (cleanupError ==
                                    null
                                ) {
                                    stringResource(Res.string.clearing_signin_session)
                                } else {
                                    stringResource(Res.string.signin_cleanup_failed)
                                },
                            )
                            cleanupError?.let { Text(it) }
                            if (cleanupError != null) {
                                Button(
                                    onClick = {
                                        cleanupError = null
                                        cleanupAttempt++
                                    },
                                    shapes = ButtonDefaults.shapes(),
                                ) {
                                    Text(stringResource(Res.string.retry_cleanup))
                                }
                            }
                        }
                    } else if (OsUtil.isWindows && isSwitchingChannel) {
                        WindowsAccountContent(
                            onAuthDataExtracted = { cookie, visitorData, dataSyncId, authUser ->
                                loginCompleted = true
                                onAuthDataExtracted(cookie, visitorData, dataSyncId, authUser)
                            },
                            onDismissRequest = requestDismiss,
                        )
                    } else if (OsUtil.isWindows) {
                        WindowsHelperContent(
                            onAuthDataExtracted = { cookie, visitorData, dataSyncId, authUser ->
                                loginCompleted = true
                                onAuthDataExtracted(cookie, visitorData, dataSyncId, authUser)
                            },
                            onDismissRequest = requestDismiss,
                        )
                    } else if (webKitAvailable == null) {
                        WebViewAvailabilityLoading()
                    } else if (!webKitAvailable) {
                        WebViewUnavailableContent(
                            title =
                                if (isSwitchingChannel) {
                                    stringResource(
                                        Res.string.channel_switch_unavailable,
                                    )
                                } else {
                                    stringResource(Res.string.youtube_signin_unavailable)
                                },
                            onDismissRequest = onDismissRequest,
                        )
                    } else {
                        WebViewContent(
                            onAuthDataExtracted = { cookie, visitorData, dataSyncId, authUser ->
                                loginCompleted = true
                                onAuthDataExtracted(cookie, visitorData, dataSyncId, authUser)
                            },
                            onDismissRequest = requestDismiss,
                            isSwitchingChannel = isSwitchingChannel,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WindowsAccountContent(
    onAuthDataExtracted: (cookie: String, visitorData: String, dataSyncId: String, authUser: String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val authManager = remember { AuthManager() }
    var authData by remember {
        mutableStateOf(AuthData(authManager.innerTubeCookie, authManager.visitorData, authManager.dataSyncId, authManager.authUser))
    }
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<AccountsListResponse.Account>>(emptyList()) }
    var selectedAccount by remember { mutableStateOf<AccountsListResponse.Account?>(null) }
    var busy by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var needsLogin by remember { mutableStateOf(!authManager.isLoggedIn) }
    var loginUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(authData, needsLogin) {
        if (!needsLogin) {
            busy = true
            failed = false
            try {
                accounts =
                    authManager
                        .fetchAccounts(authData.cookie, authData.visitorData, authData.dataSyncId, authData.authUser)
                        .filter { authData.forAccount(it) != null || !it.signinUrl.isNullOrBlank() }
                selectedAccount = accounts.firstOrNull { it.isSelected } ?: accounts.firstOrNull()
                failed = accounts.isEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Exception) {
                YtLogger.e("YouTubeAuth", "Windows account discovery failed type=${error::class.simpleName}")
                failed = true
            } finally {
                busy = false
            }
        }
    }

    if (needsLogin) {
        WindowsHelperContent(
            onAuthDataExtracted = { cookie, visitorData, dataSyncId, authUser ->
                if (loginUrl != null) {
                    onAuthDataExtracted(cookie, visitorData, dataSyncId, authUser)
                } else {
                    authData = AuthData(cookie, visitorData, dataSyncId, authUser)
                    accounts = emptyList()
                    selectedAccount = null
                    busy = true
                    needsLogin = false
                }
            },
            onDismissRequest = {
                loginUrl = null
                needsLogin = false
            },
            loginUrl = loginUrl,
            // Refreshing the list must not save the helper's default channel.
            validateCredentials = loginUrl != null,
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(Res.string.account_picker_title), style = MaterialTheme.typography.headlineSmall)
        if (accounts.isNotEmpty()) Text(stringResource(Res.string.account_picker_subtitle))
        if (busy) ContainedLoadingIndicator()
        if (failed) {
            Text(stringResource(Res.string.channel_switch_unavailable), color = MaterialTheme.colorScheme.error)
            Text(stringResource(Res.string.channel_switch_recovery_hint))
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScrollWithDrag(rememberScrollState())) {
            AccountPickerList(accounts, selectedAccount, onAccountSelected = { if (!busy) selectedAccount = it })
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onDismissRequest, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    loginUrl = null
                    needsLogin = true
                },
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(Res.string.channel_switch_sign_in_again)) }
            Button(
                enabled = !busy && selectedAccount != null,
                onClick = {
                    val selected = selectedAccount ?: return@Button
                    scope.launch {
                        busy = true
                        failed = false
                        try {
                            val handled =
                                selectWebViewAccount(
                                    authData,
                                    selected,
                                    validateAndComplete = { candidate ->
                                        if (authManager.validateAndSaveCredentials(
                                                candidate.cookie,
                                                candidate.visitorData,
                                                candidate.dataSyncId,
                                                candidate.authUser,
                                            )
                                        ) {
                                            onAuthDataExtracted(
                                                candidate.cookie,
                                                candidate.visitorData,
                                                candidate.dataSyncId,
                                                candidate.authUser,
                                            )
                                        } else {
                                            failed = true
                                        }
                                    },
                                    navigate = { url ->
                                        loginUrl = url
                                        needsLogin = true
                                    },
                                )
                            if (!handled) failed = true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (error: Exception) {
                            YtLogger.e("YouTubeAuth", "Windows channel selection failed type=${error::class.simpleName}")
                            failed = true
                        } finally {
                            busy = false
                        }
                    }
                },
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(Res.string.continue_action)) }
        }
    }
}

@Composable
private fun WindowsHelperContent(
    onAuthDataExtracted: (cookie: String, visitorData: String, dataSyncId: String, authUser: String) -> Unit,
    onDismissRequest: () -> Unit,
    loginUrl: String? = null,
    validateCredentials: Boolean = true,
) {
    val coroutineScope = rememberCoroutineScope()
    var status by remember { mutableStateOf<WindowsLoginStatus>(WindowsLoginStatus.Ready) }
    var isRunning by remember { mutableStateOf(false) }

    val launchHelper = {
        coroutineScope.launch {
            try {
                isRunning = true
                status = WindowsLoginStatus.Launching

                val helperExeName = "metro-wv2-helper.exe"
                val resourcesDir = System.getProperty("compose.application.resources.dir")
                val helperExe =
                    if (resourcesDir != null) {
                        val dir = File(resourcesDir)
                        File(dir, "windows/$helperExeName").takeIf { it.exists() }
                            ?: File(dir, helperExeName)
                    } else {
                        File("desktopApp/src/desktopMain/resources/windows/$helperExeName").takeIf { it.exists() }
                            ?: File("src/desktopMain/resources/windows/$helperExeName")
                    }

                if (!helperExe.exists()) {
                    status = WindowsLoginStatus.HelperNotFound
                    isRunning = false
                    return@launch
                }

                val command =
                    buildList {
                        add(helperExe.absolutePath)
                        if (loginUrl != null) addAll(listOf("--login-url", loginUrl))
                    }
                val content = readWindowsLoginHelperOutput(ProcessBuilder(command))

                if (content.isNotBlank()) {
                    val json = Json.parseToJsonElement(content).jsonObject
                    val authData =
                        AuthData(
                            cookie = json["cookie"]?.jsonPrimitive?.content.orEmpty(),
                            visitorData = json["visitorData"]?.jsonPrimitive?.content.orEmpty(),
                            dataSyncId = json["dataSyncId"]?.jsonPrimitive?.content.orEmpty(),
                            authUser = json["authUser"]?.jsonPrimitive?.content.orEmpty(),
                        )
                    if (
                        authData.cookie.isNotBlank() &&
                        (
                            !validateCredentials ||
                                AuthManager().validateAndSaveCredentials(
                                    authData.cookie,
                                    authData.visitorData,
                                    authData.dataSyncId,
                                    authData.authUser,
                                )
                        )
                    ) {
                        status = WindowsLoginStatus.Success
                        onAuthDataExtracted(authData.cookie, authData.visitorData, authData.dataSyncId, authData.authUser)
                    } else {
                        status = WindowsLoginStatus.ClosedUnfinished
                    }
                } else {
                    status = WindowsLoginStatus.ClosedOrFailed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = WindowsLoginStatus.Failed(e.message ?: e::class.simpleName.orEmpty())
            } finally {
                isRunning = false
            }
        }
    }

    LaunchedEffect(Unit) {
        launchHelper()
    }

    val statusText =
        when (val currentStatus = status) {
            is WindowsLoginStatus.Ready -> stringResource(Res.string.ready_to_start_login)
            is WindowsLoginStatus.Launching -> stringResource(Res.string.launching_external_login)
            is WindowsLoginStatus.HelperNotFound -> stringResource(Res.string.login_helper_not_found)
            is WindowsLoginStatus.Success -> stringResource(Res.string.login_successful_syncing)
            is WindowsLoginStatus.ClosedUnfinished -> stringResource(Res.string.login_window_closed_unfinished)
            is WindowsLoginStatus.ClosedOrFailed -> stringResource(Res.string.login_window_closed_or_failed)
            is WindowsLoginStatus.Failed -> stringResource(Res.string.login_helper_failed, currentStatus.error)
        }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(Res.string.windows_login_helper), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(statusText, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(32.dp))

        if (!isRunning) {
            Button(onClick = { launchHelper() }, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.start_login_process))
            }
        } else {
            ContainedLoadingIndicator()
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onDismissRequest, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(Res.string.cancel))
        }
    }
}

@Composable
private fun WebViewContent(
    onAuthDataExtracted: (cookie: String, visitorData: String, dataSyncId: String, authUser: String) -> Unit,
    onDismissRequest: () -> Unit,
    isSwitchingChannel: Boolean,
) {
    val webViewDataDirectory =
        remember {
            runCatching { prepareDesktopWebViewDataDirectory().absolutePath }
        }
    webViewDataDirectory.exceptionOrNull()?.let { error ->
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text(stringResource(Res.string.youtube_login_webview_unwritable))
            Text(error.message ?: error::class.simpleName.orEmpty())
            Button(onClick = onDismissRequest, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.close)) }
        }
        return
    }
    val initialUrl =
        if (isSwitchingChannel) {
            "https://music.youtube.com"
        } else {
            "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
        }
    val webViewState =
        rememberWebViewState(initialUrl).apply {
            webSettings.backgroundColor = MaterialTheme.colorScheme.surface
            webSettings.desktopWebSettings.transparent = false
            webSettings.desktopWebSettings.dataDirectory = webViewDataDirectory.getOrNull()
            webSettings.desktopWebSettings.initScript = LOGIN_WEBVIEW_INIT_SCRIPT
            webSettings.customUserAgentString = buildBrowserUserAgent()
        }
    val navigator = rememberWebViewNavigator()
    val coroutineScope = rememberCoroutineScope()
    var loginStage by remember { mutableStateOf(LoginStage.Authenticating) }
    var pendingAuthData by remember { mutableStateOf<AuthData?>(null) }
    var pendingAccounts by remember { mutableStateOf<List<AccountsListResponse.Account>?>(null) }
    var selectedAccount by remember { mutableStateOf<AccountsListResponse.Account?>(null) }
    var hasCompletedLogin by remember { mutableStateOf(false) }
    var validationFailed by remember { mutableStateOf(false) }
    var completionAttempt by remember { mutableStateOf(0) }
    val authManager = remember { AuthManager() }

    suspend fun extractAuthData(): AuthData? {
        if (hasCompletedLogin) return null
        return awaitProviderCredentials {
            if (webViewState.loadingState is LoadingState.Finished && isYouTubeMusicUrl(webViewState.lastLoadedUrl)) {
                readWebViewAuthData(webViewState.cookieManager) { script -> evaluateJs(navigator, script) }
            } else {
                null
            }
        }
    }

    suspend fun finalizeLogin(authData: AuthData) {
        currentCoroutineContext().ensureActive()
        loginStage = LoginStage.Completing
        val validated =
            try {
                authManager.validateAndSaveCredentials(
                    authData.cookie,
                    authData.visitorData,
                    authData.dataSyncId,
                    authData.authUser,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        if (!validated) {
            validationFailed = true
            loginStage = LoginStage.Authenticating
            return
        }
        hasCompletedLogin = true
        onAuthDataExtracted(authData.cookie, authData.visitorData, authData.dataSyncId, authData.authUser)
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
                pendingAuthData = authData
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

    LaunchedEffect(webViewState, navigator, completionAttempt) {
        snapshotFlow { webViewState.loadingState to webViewState.lastLoadedUrl }
            .collect { (loadingState, lastLoadedUrl) ->
                if (hasCompletedLogin || loadingState !is LoadingState.Finished || !isYouTubeMusicUrl(lastLoadedUrl)) {
                    return@collect
                }

                when (loginStage) {
                    LoginStage.Authenticating -> {
                        val authData = extractAuthData() ?: return@collect
                        checkAvailableAccounts(authData)
                    }

                    LoginStage.SwitchingAccount -> {
                        val authData = extractAuthData() ?: return@collect
                        finalizeLogin(authData)
                    }

                    else -> {
                    }
                }
            }
    }

    if (validationFailed) {
        WebViewPromptWindow(
            onCloseRequest = { validationFailed = false },
            title = stringResource(Res.string.error),
            text = { Text(stringResource(Res.string.login_failed_with_error, stringResource(Res.string.error))) },
            confirmButton = {
                Button(onClick = { validationFailed = false }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.close))
                }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        if (loginStage == LoginStage.BrowsingAccounts) {
            Text(
                stringResource(Res.string.channel_switch_browse_hint, stringResource(Res.string.complete_sign_in)),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
        WebView(
            state = webViewState,
            navigator = navigator,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        // Native WebViews draw above Compose overlays, so keep completion controls outside the browser.
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            if (loginStage == LoginStage.CheckingAccounts || loginStage == LoginStage.Completing) {
                ContainedLoadingIndicator()
            } else {
                Button(
                    onClick = {
                        if (loginStage == LoginStage.BrowsingAccounts) loginStage = LoginStage.SwitchingAccount
                        completionAttempt++
                    },
                    enabled = loginStage != LoginStage.SelectingAccount,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.complete_sign_in))
                }
            }
        }
    }

    if (loginStage == LoginStage.SelectingAccount) {
        val accounts = pendingAccounts
        if (accounts != null) {
            WebViewPromptWindow(
                onCloseRequest = onDismissRequest,
                title = stringResource(Res.string.account_picker_title),
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(Res.string.account_picker_subtitle))
                        AccountPickerList(accounts, selectedAccount, onAccountSelected = { selectedAccount = it })
                    }
                },
                confirmButton = {
                    Button(
                        enabled = selectedAccount != null,
                        onClick = {
                            val account = selectedAccount ?: return@Button
                            val authData = pendingAuthData ?: return@Button
                            loginStage = LoginStage.Completing
                            coroutineScope.launch {
                                if (!selectWebViewAccount(
                                        authData,
                                        account,
                                        validateAndComplete = { finalizeLogin(it) },
                                        navigate = { url ->
                                            loginStage = LoginStage.SwitchingAccount
                                            navigator.loadUrl(url)
                                        },
                                    )
                                ) {
                                    loginStage = LoginStage.SelectingAccount
                                    validationFailed = true
                                }
                            }
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(Res.string.continue_action)) }
                },
                dismissButton = {
                    OutlinedButton(onClick = onDismissRequest, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
                },
            )
        }
    }
}

// Compose dialogs share the parent canvas and can be covered by the native WebView.
@Composable
private fun WebViewPromptWindow(
    onCloseRequest: () -> Unit,
    title: String,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit = {},
) {
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    DialogWindow(
        onCloseRequest = onCloseRequest,
        title = title,
        state = rememberDialogState(width = 480.dp, height = Dp.Unspecified),
        resizable = false,
        onPreviewKeyEvent = { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                onCloseRequest()
                true
            } else {
                false
            }
        },
    ) {
        ClearWindowContentOnDispose(window)
        MaterialTheme(colorScheme = colorScheme, typography = typography, shapes = shapes) {
            DesktopDensityProvider {
                Surface(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(title, style = MaterialTheme.typography.headlineSmall)
                        Column(Modifier.weight(1f, fill = false).verticalScrollWithDrag(rememberScrollState())) {
                            text()
                        }
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            dismissButton()
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}

internal data class AuthData(
    val cookie: String,
    val visitorData: String,
    val dataSyncId: String,
    val authUser: String,
)

internal fun AuthData.forAccount(account: AccountsListResponse.Account): AuthData? {
    val selectedDataSyncId = account.selectionDataSyncId(dataSyncId) ?: return null
    return copy(dataSyncId = selectedDataSyncId)
}

internal suspend fun selectWebViewAccount(
    authData: AuthData,
    account: AccountsListResponse.Account,
    validateAndComplete: suspend (AuthData) -> Unit,
    navigate: (String) -> Unit,
): Boolean {
    val candidate = authData.forAccount(account)
    if (candidate != null) {
        validateAndComplete(candidate)
        return true
    }
    val signinUrl = account.signinUrl?.takeIf(String::isNotBlank) ?: return false
    val absoluteUrl =
        when {
            signinUrl.startsWith("http://") || signinUrl.startsWith("https://") -> signinUrl
            signinUrl.startsWith("/") -> "https://music.youtube.com$signinUrl"
            else -> "https://music.youtube.com/$signinUrl"
        }
    navigate(absoluteUrl)
    return true
}

private const val LOGIN_WEBVIEW_INIT_SCRIPT =
    """
    window.open = function(url) {
        if (typeof url === 'string' && url.length > 0) {
            window.location.assign(url);
        }
        return window;
    };
    document.addEventListener('click', function(event) {
        var element = event.target;
        while (element && element.tagName !== 'A') {
            element = element.parentElement;
        }
        if (element && element.target === '_blank' && element.href) {
            event.preventDefault();
            window.location.assign(element.href);
        }
    }, true);
    document.addEventListener('submit', function(event) {
        if (event.target && event.target.target && event.target.target !== '_self') {
            event.target.target = '_self';
        }
    }, true);
    """

internal fun isYouTubeMusicUrl(url: String?): Boolean =
    url == "https://music.youtube.com" || url?.startsWith("https://music.youtube.com/") == true

private const val LOGIN_WEB_CONFIG_SCRIPT =
    """
    (() => {
        if (location.origin !== 'https://music.youtube.com') return null;
        return JSON.stringify({
            cookie: document.cookie,
            visitorData: globalThis.ytcfg?.get?.('VISITOR_DATA') || globalThis.yt?.config_?.VISITOR_DATA || '',
            dataSyncId: globalThis.ytcfg?.get?.('DATASYNC_ID') || globalThis.yt?.config_?.DATASYNC_ID || '',
            authUser: String(globalThis.ytcfg?.get?.('SESSION_INDEX') ?? globalThis.yt?.config_?.SESSION_INDEX ?? '')
        });
    })()
    """

@Serializable
private data class WebViewAuthConfig(
    val cookie: String,
    val visitorData: String,
    val dataSyncId: String,
    val authUser: String,
)

internal suspend fun readWebViewAuthData(
    cookieManager: CookieManager,
    evaluateJavaScript: suspend (String) -> String,
): AuthData? {
    val config =
        runCatching {
            val result = evaluateJavaScript(LOGIN_WEB_CONFIG_SCRIPT).trim()
            val decoded = runCatching { Json.decodeFromString<String>(result) }.getOrDefault(result)
            Json.decodeFromString<WebViewAuthConfig>(decoded)
        }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: return null
    if (config.visitorData.isBlank()) return null

    val urlCookies =
        try {
            val musicCookies = cookieManager.getCookies("https://music.youtube.com")
            val youtubeCookies = cookieManager.getCookies("https://www.youtube.com") + cookieManager.getCookies("https://youtube.com")
            val googleCookies = cookieManager.getCookies("https://accounts.google.com") + cookieManager.getCookies("https://google.com")
            buildCookiePairs(musicCookies, youtubeCookies + googleCookies)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    val nativeCookies = if (hasAuthCookie(buildCookieString(urlCookies))) emptyList() else readNativeCookiePairs(cookieManager)
    val jsCookies =
        config.cookie
            .split(';')
            .mapNotNull { entry ->
                val parts = entry.trim().split('=', limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
    val cookie = buildCookieString(urlCookies + nativeCookies + jsCookies)
    if (!hasAuthCookie(cookie)) return null
    return AuthData(
        cookie = cookie,
        visitorData = config.visitorData,
        dataSyncId = sanitizeDataSyncId(config.dataSyncId),
        authUser = config.authUser,
    )
}

private suspend fun evaluateJs(
    navigator: WebViewNavigator,
    script: String,
): String =
    try {
        withTimeoutOrNull(2000L.milliseconds) {
            suspendCancellableCoroutine { continuation ->
                navigator.evaluateJavaScript(script) { result ->
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }
            }
        } ?: ""
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        ""
    }

private fun buildBrowserUserAgent(): String {
    val platformToken =
        when {
            OsUtil.isWindows -> "Windows NT 10.0; Win64; x64"
            OsUtil.isMac -> "Macintosh; Intel Mac OS X 10_15_7"
            else -> "X11; Linux x86_64"
        }
    if (OsUtil.isMac) {
        return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.5 Safari/605.1.15"
    }
    return "Mozilla/5.0 ($platformToken) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
}

private fun buildCookiePairs(
    primaryCookies: List<Cookie>,
    fallbackCookies: List<Cookie>,
): List<Pair<String, String>> {
    val cookieByName = LinkedHashMap<String, String>()
    for (cookie in primaryCookies) {
        if (cookie.name.isNotBlank()) {
            cookieByName[cookie.name] = cookie.value
        }
    }
    for (cookie in fallbackCookies) {
        if (cookie.name.isNotBlank() && !cookieByName.containsKey(cookie.name)) {
            cookieByName[cookie.name] = cookie.value
        }
    }
    return cookieByName.map { it.key to it.value }
}

private fun buildCookieString(cookies: List<Pair<String, String>>): String =
    cookies
        .filter { it.first.isNotBlank() }
        .distinctBy { it.first }
        .joinToString(separator = "; ") { "${it.first}=${it.second}" }

private suspend fun readNativeCookiePairs(cookieManager: Any): List<Pair<String, String>> =
    withContext(Dispatchers.IO) {
        runCatching {
            val panelField = cookieManager.javaClass.getDeclaredField("panel").apply { isAccessible = true }
            val panel = panelField.get(cookieManager) ?: return@runCatching emptyList()
            val cookies = panel.javaClass.getMethod("getCookies").invoke(panel) as? List<*> ?: return@runCatching emptyList()
            cookies.mapNotNull { cookie ->
                cookie ?: return@mapNotNull null
                val domain =
                    (cookie.javaClass.getMethod("getDomain").invoke(cookie) as? String)
                        ?.removePrefix(".")
                        ?.lowercase() ?: return@mapNotNull null
                if (domain != "youtube.com" && !domain.endsWith(".youtube.com") &&
                    domain != "google.com" && !domain.endsWith(".google.com")
                ) {
                    return@mapNotNull null
                }
                val name = cookie.javaClass.getMethod("getName").invoke(cookie) as? String ?: return@mapNotNull null
                val value = cookie.javaClass.getMethod("getValue").invoke(cookie) as? String ?: return@mapNotNull null
                name to value
            }
        }.getOrDefault(emptyList())
    }
