package com.metrolist.desktop.spotifyimport

import androidx.compose.runtime.Composable
import com.metrolist.shared.deezerimport.DeezerImportManager
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.import_connect_deezer_account
import com.metrolist.shared.generated.resources.import_deezer_session_sign_in
import org.jetbrains.compose.resources.stringResource

@Composable
@Suppress("FunctionName")
internal fun DeezerLoginDialog(
    onDismissRequest: () -> Unit,
    onSessionToken: (String) -> Unit,
) {
    ProviderLoginDialog(
        title = stringResource(Res.string.import_connect_deezer_account),
        loginUrl = DEEZER_LOGIN_URL,
        signInHint = stringResource(Res.string.import_deezer_session_sign_in),
        onDismissRequest = onDismissRequest,
        extractCredentials = { state ->
            val cookies =
                state.cookieManager.getCookies(DEEZER_URL) +
                    state.cookieManager.getCookies("https://www.deezer.com")
            val cookieHeader = cookies.joinToString("; ") { "${it.name}=${it.value}" }
            DeezerImportManager.extractArl(cookieHeader)
        },
        onAuthenticated = onSessionToken,
    )
}

private const val DEEZER_URL = "https://www.deezer.com"
private const val DEEZER_LOGIN_URL = "https://www.deezer.com/"
