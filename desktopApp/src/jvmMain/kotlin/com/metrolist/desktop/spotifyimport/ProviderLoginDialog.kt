@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.spotifyimport

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.metrolist.desktop.ui.components.WebViewAvailabilityLoading
import com.metrolist.desktop.ui.components.WebViewUnavailableContent
import com.metrolist.desktop.ui.components.rememberLinuxWebKitAvailable
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.import_error_generic
import com.metrolist.shared.playlistimport.awaitProviderCredentials
import com.metrolist.shared.ui.components.Dialog
import com.metrolist.shared.utils.OsUtil
import io.github.kdroidfilter.webview.web.LoadingState
import io.github.kdroidfilter.webview.web.WebView
import io.github.kdroidfilter.webview.web.WebViewState
import io.github.kdroidfilter.webview.web.rememberWebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewState
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource

@Composable
@Suppress("FunctionName")
internal fun <T : Any> ProviderLoginDialog(
    title: String,
    loginUrl: String,
    signInHint: String,
    onDismissRequest: () -> Unit,
    extractCredentials: suspend (WebViewState) -> T?,
    onAuthenticated: (T) -> Unit,
) {
    ProviderLoginWindow(title, onDismissRequest) {
        val webKitAvailable = rememberLinuxWebKitAvailable()
        if (webKitAvailable == null) {
            WebViewAvailabilityLoading()
        } else if (!webKitAvailable && !OsUtil.isWindows && !OsUtil.isMac) {
            WebViewUnavailableContent(
                title = title,
                onDismissRequest = onDismissRequest,
            )
        } else {
            ProviderLoginContent(
                loginUrl = loginUrl,
                signInHint = signInHint,
                onDismissRequest = onDismissRequest,
                extractCredentials = extractCredentials,
                onAuthenticated = onAuthenticated,
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun <T : Any> ProviderLoginContent(
    loginUrl: String,
    signInHint: String,
    onDismissRequest: () -> Unit,
    extractCredentials: suspend (WebViewState) -> T?,
    onAuthenticated: (T) -> Unit,
) {
    val webViewState =
        rememberWebViewState(loginUrl).apply {
            webSettings.customUserAgentString = buildProviderBrowserUserAgent()
        }
    val navigator = rememberWebViewNavigator()
    val currentExtractor by rememberUpdatedState(extractCredentials)
    val currentOnAuthenticated by rememberUpdatedState(onAuthenticated)
    var extractionFailed by remember { mutableStateOf(false) }

    LaunchedEffect(webViewState) {
        awaitProviderCredentials {
            try {
                currentExtractor(webViewState).also { extractionFailed = false }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                extractionFailed = true
                null
            }
        }.let { credentials ->
            currentOnAuthenticated(credentials)
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (webViewState.loadingState is LoadingState.Loading) {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        WebView(
            state = webViewState,
            navigator = navigator,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (extractionFailed) stringResource(Res.string.import_error_generic) else signInHint,
                    modifier = Modifier.weight(1f),
                    color = if (extractionFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onDismissRequest, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
            }
        }
    }
}

private fun buildProviderBrowserUserAgent(): String {
    val platformToken =
        when {
            OsUtil.isWindows -> "Windows NT 10.0; Win64; x64"
            OsUtil.isMac -> "Macintosh; Intel Mac OS X 10_15_7"
            else -> "X11; Linux x86_64"
        }
    return "Mozilla/5.0 ($platformToken) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
}

@Composable
internal fun ProviderLoginWindow(
    title: String,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(
            modifier =
                Modifier
                    .widthIn(max = 920.dp)
                    .fillMaxWidth()
                    .heightIn(max = 800.dp)
                    .fillMaxSize(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}
