@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.spotifyimport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.auth.readWindowsLoginHelperOutput
import com.metrolist.desktop.ui.components.WebViewAvailabilityLoading
import com.metrolist.desktop.ui.components.WebViewUnavailableContent
import com.metrolist.desktop.ui.components.rememberLinuxWebKitAvailable
import com.metrolist.desktop.utils.prepareSpotifyWebViewDataDirectory
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.spotify_import_error_sign_in_failed
import com.metrolist.shared.generated.resources.spotify_login_button
import com.metrolist.shared.generated.resources.spotify_login_open
import com.metrolist.shared.generated.resources.spotify_login_ready
import com.metrolist.shared.generated.resources.spotify_login_sign_in
import com.metrolist.shared.generated.resources.spotify_login_title
import com.metrolist.shared.generated.resources.spotify_login_unavailable
import com.metrolist.shared.generated.resources.spotify_login_waiting
import com.metrolist.shared.generated.resources.spotify_session_read_failed
import com.metrolist.shared.generated.resources.spotify_session_sign_in
import com.metrolist.shared.generated.resources.spotify_webview_not_writable
import com.metrolist.shared.playlistimport.awaitProviderCredentials
import com.metrolist.shared.spotifyimport.spotifySessionCookie
import com.metrolist.shared.utils.OsUtil
import io.github.kdroidfilter.webview.web.LoadingState
import io.github.kdroidfilter.webview.web.WebView
import io.github.kdroidfilter.webview.web.rememberWebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.stringResource
import java.io.File

@Composable
internal fun SpotifyLoginDialog(
    isAuthenticating: Boolean,
    authenticationFailed: Boolean,
    onDismissRequest: () -> Unit,
    onSessionCookie: (String) -> Unit,
) {
    ProviderLoginWindow(stringResource(Res.string.spotify_login_title), onDismissRequest) {
        val webKitAvailable = rememberLinuxWebKitAvailable()
        if (OsUtil.isWindows) {
            SpotifyWindowsHelperContent(isAuthenticating, authenticationFailed, onDismissRequest, onSessionCookie)
        } else if (webKitAvailable == null) {
            WebViewAvailabilityLoading()
        } else if (!webKitAvailable) {
            WebViewUnavailableContent(
                title = stringResource(Res.string.spotify_login_unavailable),
                onDismissRequest = onDismissRequest,
            )
        } else {
            SpotifyWebViewContent(isAuthenticating, authenticationFailed, onDismissRequest, onSessionCookie)
        }
    }
}

@Composable
private fun SpotifyWebViewContent(
    isAuthenticating: Boolean,
    authenticationFailed: Boolean,
    onDismissRequest: () -> Unit,
    onSessionCookie: (String) -> Unit,
) {
    val webViewDataDirectory = remember { runCatching { prepareSpotifyWebViewDataDirectory().absolutePath } }
    if (webViewDataDirectory.isFailure) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(Res.string.spotify_webview_not_writable))
            Text(webViewDataDirectory.exceptionOrNull()?.message.orEmpty(), color = MaterialTheme.colorScheme.error)
            Button(onClick = onDismissRequest, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.close)) }
        }
        return
    }

    val webViewState =
        rememberWebViewState(SPOTIFY_LOGIN_URL).apply {
            webSettings.desktopWebSettings.dataDirectory = webViewDataDirectory.getOrNull()
            webSettings.customUserAgentString = buildBrowserUserAgent()
        }
    val navigator = rememberWebViewNavigator()
    val currentOnSessionCookie by rememberUpdatedState(onSessionCookie)
    var extractionError by remember { mutableStateOf<String?>(null) }
    var credentialAttempt by remember { mutableIntStateOf(0) }
    var isReadingCredentials by remember { mutableStateOf(true) }
    val sessionReadFailed = stringResource(Res.string.spotify_session_read_failed)

    LaunchedEffect(webViewState, credentialAttempt) {
        isReadingCredentials = true
        try {
            val cookie =
                awaitProviderCredentials {
                    try {
                        val cookies =
                            webViewState.cookieManager.getCookies(SPOTIFY_OPEN_URL) +
                                webViewState.cookieManager.getCookies("https://spotify.com")
                        spotifySessionCookie(cookies.joinToString("; ") { "${it.name}=${it.value}" }).also {
                            extractionError = null
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        extractionError = sessionReadFailed
                        null
                    }
                }
            currentOnSessionCookie(cookie)
        } finally {
            isReadingCredentials = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (webViewState.loadingState is LoadingState.Loading || isAuthenticating) {
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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    extractionError ?: stringResource(Res.string.spotify_session_sign_in),
                    modifier = Modifier.weight(1f),
                    color = if (extractionError == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onDismissRequest, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
                if (authenticationFailed) {
                    Button(
                        enabled = !isAuthenticating && !isReadingCredentials,
                        onClick = { credentialAttempt++ },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(Res.string.retry)) }
                }
            }
        }
    }
}

@Composable
private fun SpotifyWindowsHelperContent(
    isAuthenticating: Boolean,
    authenticationFailed: Boolean,
    onDismissRequest: () -> Unit,
    onSessionCookie: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    val loginWaiting = stringResource(Res.string.spotify_login_waiting)
    val loginFailed = stringResource(Res.string.spotify_import_error_sign_in_failed)

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(Res.string.spotify_login_sign_in), style = MaterialTheme.typography.headlineSmall)
        Text(
            if (authenticationFailed) loginFailed else status ?: stringResource(Res.string.spotify_login_ready),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isAuthenticating) LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !isRunning && !isAuthenticating,
                onClick = {
                    scope.launch {
                        isRunning = true
                        status = loginWaiting
                        try {
                            val result = runSpotifyWindowsHelper()
                            result.onSuccess(onSessionCookie).onFailure {
                                status = it.message ?: loginFailed
                            }
                        } finally {
                            isRunning = false
                        }
                    }
                },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(if (isRunning) Res.string.spotify_login_open else Res.string.spotify_login_button))
            }
            TextButton(onClick = onDismissRequest, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
        }
    }
}

private suspend fun runSpotifyWindowsHelper(): Result<String> =
    withContext(Dispatchers.IO) {
        val helper = resolveWindowsLoginHelper()
        try {
            check(helper.exists()) { "The Windows login helper was not found. Rebuild metro-wv2-helper." }
            val content = readWindowsLoginHelperOutput(ProcessBuilder(helper.absolutePath, "--provider", "spotify"))
            check(content.isNotEmpty()) { "The Spotify login window closed before sign-in completed." }
            val cookie =
                Json
                    .parseToJsonElement(content)
                    .jsonObject["spotifyCookie"]
                    ?.jsonPrimitive
                    ?.content
            require(!cookie.isNullOrBlank()) { "The login helper did not return a Spotify session." }
            Result.success(cookie)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

private fun resolveWindowsLoginHelper(): File {
    val helperName = "metro-wv2-helper.exe"
    val resourcesDirectory = System.getProperty("compose.application.resources.dir")?.let(::File)
    return resourcesDirectory
        ?.let { directory ->
            File(directory, "windows/$helperName").takeIf(File::exists) ?: File(directory, helperName)
        } ?: File("desktopApp/src/desktopMain/resources/windows/$helperName").takeIf(File::exists)
        ?: File("src/desktopMain/resources/windows/$helperName")
}

private fun buildBrowserUserAgent(): String {
    val platformToken =
        when {
            OsUtil.isMac -> "Macintosh; Intel Mac OS X 10_15_7"
            else -> "X11; Linux x86_64"
        }
    return if (OsUtil.isMac) {
        "Mozilla/5.0 ($platformToken) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.5 Safari/605.1.15"
    } else {
        "Mozilla/5.0 ($platformToken) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

private const val SPOTIFY_OPEN_URL = "https://open.spotify.com/"
private const val SPOTIFY_LOGIN_URL =
    "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
