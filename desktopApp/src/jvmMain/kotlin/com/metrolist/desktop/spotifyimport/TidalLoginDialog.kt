package com.metrolist.desktop.spotifyimport

import androidx.compose.runtime.Composable
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.import_connect_tidal_account
import com.metrolist.shared.generated.resources.import_tidal_session_sign_in
import com.metrolist.shared.tidalimport.TidalImportManager
import org.jetbrains.compose.resources.stringResource

@Composable
@Suppress("FunctionName")
internal fun TidalLoginDialog(
    onDismissRequest: () -> Unit,
    onSessionAuthenticated: (sessionId: String, userId: String) -> Unit,
) {
    ProviderLoginDialog(
        title = stringResource(Res.string.import_connect_tidal_account),
        loginUrl = TIDAL_LOGIN_URL,
        signInHint = stringResource(Res.string.import_tidal_session_sign_in),
        onDismissRequest = onDismissRequest,
        extractCredentials = { state ->
            val url = state.lastLoadedUrl.orEmpty()
            if (!url.contains("listen.tidal.com") && !url.contains("tidal.com")) {
                null
            } else {
                val cookies =
                    state.cookieManager.getCookies("https://listen.tidal.com") +
                        state.cookieManager.getCookies("https://tidal.com")
                val cookieHeader = cookies.joinToString("; ") { "${it.name}=${it.value}" }
                TidalImportManager.extractSessionCredentials(cookieHeader)
            }
        },
        onAuthenticated = { (sessionId, userId) -> onSessionAuthenticated(sessionId, userId) },
    )
}

private const val TIDAL_LOGIN_URL = "https://listen.tidal.com/login"
