package com.metrolist.android.playlistimport

import androidx.compose.runtime.Composable
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.import_connect_tidal_account
import com.metrolist.shared.generated.resources.tidal_login_failed
import com.metrolist.shared.tidalimport.TidalImportManager

@Composable
@Suppress("FunctionName")
fun TidalLoginScreen(
    isAuthenticating: Boolean,
    authenticationError: String?,
    onClearAuthenticationError: () -> Unit,
    onSessionAuthenticated: (sessionId: String, userId: String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    ProviderLoginShell(
        titleRes = Res.string.import_connect_tidal_account,
        loginErrorRes = Res.string.tidal_login_failed,
        cookieUrl = "https://listen.tidal.com",
        loginUrl = "https://listen.tidal.com/login",
        logTag = "TidalImport",
        credentialLogMessage = "Tidal WebView session credentials",
        extractCredentials = { TidalImportManager.extractSessionCredentials(it) },
        credentialKey = { it.first },
        isAuthenticating = isAuthenticating,
        authenticationError = authenticationError,
        onClearAuthenticationError = onClearAuthenticationError,
        onCredentials = { (sessionId, userId) -> onSessionAuthenticated(sessionId, userId) },
        onNavigateBack = onNavigateBack,
    )
}
