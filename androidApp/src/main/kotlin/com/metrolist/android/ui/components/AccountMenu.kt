package com.metrolist.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import com.metrolist.shared.auth.AuthManager
import com.metrolist.shared.ui.components.AccountPopupDialog
import com.metrolist.shared.ui.tv.RequestTvFocus
import com.metrolist.shared.ui.tv.tvFocusHighlight

@Composable
fun AccountMenu(
    onClose: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onNavigateToTokenLogin: () -> Unit,
    onNavigateToSwitchChannel: () -> Unit,
    onNavigateToHistory: (() -> Unit)? = null,
    onNavigateToStatistics: () -> Unit,
    onNavigateToIntegrations: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val authManager = remember(context) { AuthManager() }
    val initialFocusRequester = remember { FocusRequester() }
    var logoutAfterClose by remember { mutableStateOf(false) }
    RequestTvFocus(initialFocusRequester)

    AccountPopupDialog(
        isSignedIn = authManager.isLoggedIn,
        accountName = authManager.accountName.takeIf { it.isNotBlank() },
        accountSubtitle =
            authManager.accountChannelHandle
                .ifBlank { authManager.accountEmail }
                .takeIf { it.isNotBlank() },
        accountImageUrl = authManager.accountThumbnail.takeIf { it.isNotBlank() },
        onDismiss = {
            onClose()
            if (logoutAfterClose) onLogout()
        },
        onSignIn = onNavigateToLogin,
        onTokenLogin = onNavigateToTokenLogin,
        onSwitchChannel = onNavigateToSwitchChannel,
        onNavigateToHistory = onNavigateToHistory,
        onNavigateToStatistics = onNavigateToStatistics,
        onNavigateToIntegrations = onNavigateToIntegrations,
        onNavigateToSettings = onNavigateToSettings,
        onLogout = { onSuccess ->
            logoutAfterClose = true
            onSuccess()
        },
        initialFocusRequester = initialFocusRequester,
        focusModifier = { shape -> Modifier.tvFocusHighlight(shape) },
    )
}
