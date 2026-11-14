@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.AwtWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.metrolist.desktop.auth.DesktopDiscordLogin
import com.metrolist.desktop.auth.DiscordLoginNavigation
import com.metrolist.desktop.auth.registerDesktopDiscordCallback
import com.metrolist.desktop.ui.DesktopDensityProvider
import com.metrolist.desktop.ui.components.WebViewAvailabilityLoading
import com.metrolist.desktop.ui.components.WebViewUnavailableContent
import com.metrolist.desktop.ui.components.rememberLinuxWebKitAvailable
import com.metrolist.desktop.utils.prepareDesktopWebViewDataDirectory
import com.metrolist.desktop.web.LinuxWebViewCanvas
import com.metrolist.desktop.window.ClearWindowContentOnDispose
import com.metrolist.shared.discord.DiscordAuthSession
import com.metrolist.shared.discord.DiscordRpcManager
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.discord_browser_open_failed
import com.metrolist.shared.generated.resources.discord_continue_in_browser
import com.metrolist.shared.generated.resources.discord_login_webview_unwritable
import com.metrolist.shared.generated.resources.discord_open_browser_again
import com.metrolist.shared.generated.resources.discord_sign_in_unavailable
import com.metrolist.shared.generated.resources.sign_in_discord
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.utils.OsUtil
import com.metrolist.shared.utils.tryOpenUrl
import io.github.kdroidfilter.webview.request.RequestInterceptor
import io.github.kdroidfilter.webview.request.WebRequest
import io.github.kdroidfilter.webview.request.WebRequestInterceptResult
import io.github.kdroidfilter.webview.web.WebView
import io.github.kdroidfilter.webview.web.WebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JDialog
import javax.swing.WindowConstants
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun DiscordLoginDialog(
    session: DiscordAuthSession,
    onDismissRequest: () -> Unit,
    onComplete: () -> Unit,
) {
    val title = stringResource(Res.string.sign_in_discord)
    var browserLogin by remember(session) { mutableStateOf<Boolean?>(null) }
    var browserFailed by remember(session) { mutableStateOf(false) }
    val exchanging = DesktopDiscordLogin.flow.exchanging
    val openBrowser = { tryOpenUrl(session.authorizeUrl) { browserFailed = !it } }
    LaunchedEffect(session) {
        browserLogin = withContext(Dispatchers.IO) { registerDesktopDiscordCallback(title) }
        if (browserLogin == true) openBrowser()
    }
    if (browserLogin == false) {
        EmbeddedDiscordLoginDialog(session, onDismissRequest, onComplete)
        return
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            Column {
                if (browserLogin == null || exchanging) CircularProgressIndicator()
                Text(stringResource(Res.string.discord_continue_in_browser))
                if (browserFailed) Text(stringResource(Res.string.discord_browser_open_failed))
            }
        },
        confirmButton = {
            TextButton(onClick = openBrowser, enabled = browserLogin == true && !exchanging) {
                Text(stringResource(Res.string.discord_open_browser_again))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

@Composable
private fun EmbeddedDiscordLoginDialog(
    session: DiscordAuthSession,
    onDismissRequest: () -> Unit,
    onComplete: () -> Unit,
) {
    val webViewDataDirectory =
        remember {
            runCatching { prepareDesktopWebViewDataDirectory().absolutePath }
        }
    val coroutineScope = rememberCoroutineScope()
    val webKitAvailable = rememberLinuxWebKitAvailable()
    val currentOnComplete by rememberUpdatedState(onComplete)
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val navigation =
        remember(session, coroutineScope) {
            DiscordLoginNavigation(
                session = session,
                scope = coroutineScope,
                exchangeCode = { code ->
                    val result =
                        withContext(Dispatchers.IO) {
                            DiscordRpcManager.auth.exchangeCode(
                                code = code,
                                verifier = session.pkceVerifier,
                                state = session.state,
                                expectedState = session.state,
                            )
                        }
                    DiscordRpcManager.storeAndConnect(
                        accessToken = result.accessToken,
                        refreshToken = result.refreshToken,
                        expiresInSec = result.expiresInSec,
                    )
                },
                onComplete = { currentOnComplete() },
                onDismissRequest = { currentOnDismissRequest() },
            )
        }
    if (OsUtil.isLinux && webKitAvailable == true && webViewDataDirectory.isSuccess) {
        LinuxDiscordLoginWindow(
            session = session,
            dataDirectory = webViewDataDirectory.getOrThrow(),
            navigation = navigation,
            onDismissRequest = onDismissRequest,
        )
        return
    }

    DialogWindow(
        onCloseRequest = onDismissRequest,
        title = stringResource(Res.string.sign_in_discord),
        state = rememberDialogState(width = 600.dp, height = 700.dp),
        visible = true,
    ) {
        ClearWindowContentOnDispose(window)
        DesktopDensityProvider {
            if (webKitAvailable == null) {
                WebViewAvailabilityLoading()
                return@DesktopDensityProvider
            }
            if (!webKitAvailable) {
                WebViewUnavailableContent(
                    title = stringResource(Res.string.discord_sign_in_unavailable),
                    onDismissRequest = onDismissRequest,
                )
                return@DesktopDensityProvider
            }
            if (webViewDataDirectory.isFailure) {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text(stringResource(Res.string.discord_login_webview_unwritable))
                    Button(onClick = onDismissRequest, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.close)) }
                }
                return@DesktopDensityProvider
            }

            val webViewState =
                rememberWebViewState(session.authorizeUrl).apply {
                    webSettings.desktopWebSettings.dataDirectory = webViewDataDirectory.getOrNull()
                    webSettings.customUserAgentString = buildBrowserUserAgent()
                }

            val navigator =
                rememberWebViewNavigator(
                    requestInterceptor =
                        object : RequestInterceptor {
                            override fun onInterceptUrlRequest(
                                request: WebRequest,
                                navigator: WebViewNavigator,
                            ): WebRequestInterceptResult =
                                if (navigation.onNavigate(request.url)) {
                                    WebRequestInterceptResult.Allow
                                } else {
                                    WebRequestInterceptResult.Reject
                                }
                        },
                )

            LaunchedEffect(webViewState) {
                snapshotFlow { webViewState.lastLoadedUrl }
                    .collect { url ->
                        if (url != null) {
                            navigation.onNavigate(url)
                        }
                    }
            }

            Box(Modifier.fillMaxSize()) {
                WebView(
                    state = webViewState,
                    navigator = navigator,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun LinuxDiscordLoginWindow(
    session: DiscordAuthSession,
    dataDirectory: String,
    navigation: DiscordLoginNavigation,
    onDismissRequest: () -> Unit,
) {
    val title = stringResource(Res.string.sign_in_discord)
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val density = LocalDensity.current
    val owner = remember { KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow }
    var webView: LinuxWebViewCanvas? by remember { mutableStateOf(null) }

    AwtWindow(
        create = {
            JDialog(owner, title).apply {
                defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                addWindowListener(
                    object : WindowAdapter() {
                        override fun windowClosing(event: WindowEvent) {
                            currentOnDismissRequest()
                        }
                    },
                )
                val panel =
                    LinuxWebViewCanvas(
                        initialUrl = session.authorizeUrl,
                        userAgent = buildBrowserUserAgent(),
                        dataDirectory = dataDirectory,
                        onNavigate = navigation::onNavigate,
                        onFailure = { currentOnDismissRequest() },
                    )
                contentPane.add(panel)
                val transform = graphicsConfiguration.defaultTransform
                contentPane.preferredSize =
                    Dimension(
                        (with(density) { 600.dp.toPx() } / transform.scaleX).roundToInt(),
                        (with(density) { 700.dp.toPx() } / transform.scaleY).roundToInt(),
                    )
                pack()
                setLocationRelativeTo(owner)
                webView = panel
                isVisible = true
            }
        },
        dispose = { dialog ->
            webView = null
            dialog.dispose()
        },
        update = { dialog -> dialog.title = title },
    )

    LaunchedEffect(webView) {
        val panel = webView ?: return@LaunchedEffect
        while (true) {
            panel.updateWebView()
            panel.currentUrl?.let(navigation::onNavigate)
            delay(250.milliseconds)
        }
    }
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
