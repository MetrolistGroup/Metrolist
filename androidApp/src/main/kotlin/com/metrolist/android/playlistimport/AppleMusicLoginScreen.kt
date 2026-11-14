package com.metrolist.android.playlistimport

import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import androidx.compose.runtime.Composable
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.apple_music_login_failed
import com.metrolist.shared.generated.resources.apple_music_sign_in
import com.metrolist.shared.playlistimport.AppleMusicPlaylistImporter
import timber.log.Timber

@Composable
@Suppress("FunctionName")
fun AppleMusicLoginScreen(
    isAuthenticating: Boolean,
    authenticationError: String?,
    onClearAuthenticationError: () -> Unit,
    onSessionToken: (String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    ProviderLoginShell(
        titleRes = Res.string.apple_music_sign_in,
        loginErrorRes = Res.string.apple_music_login_failed,
        cookieUrl = "https://music.apple.com",
        loginUrl = "https://music.apple.com",
        logTag = "AppleMusicImport",
        credentialLogMessage = "Apple Music WebView session token",
        extractCredentials = { AppleMusicPlaylistImporter.extractAppleMusicUserToken(it) },
        credentialKey = { it },
        isAuthenticating = isAuthenticating,
        authenticationError = authenticationError,
        onClearAuthenticationError = onClearAuthenticationError,
        onCredentials = onSessionToken,
        onNavigateBack = onNavigateBack,
        onWebViewError = { error, request ->
            Timber.tag("AppleMusicImport").w(
                "Apple Music WebView failed code=%d host=%s",
                error.errorCode,
                request.url.host,
            )
        },
        onWebViewDisposed = { Timber.tag("AppleMusicImport").d("Apple Music login WebView destroyed") },
    )
}
