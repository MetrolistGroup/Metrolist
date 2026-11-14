package com.metrolist.shared.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.metrolist.shared.ui.navigation.LocalUseDesktopUi
import com.metrolist.shared.ui.navigation.blockInput

private val AccountPopupMaxWidth = 480.dp

@Composable
fun AccountPopupDialog(
    onDismiss: () -> Unit,
    isSignedIn: Boolean,
    accountName: String?,
    accountSubtitle: String?,
    accountImageUrl: String?,
    onSignIn: () -> Unit,
    onTokenLogin: () -> Unit,
    onSwitchChannel: () -> Unit,
    onNavigateToStatistics: () -> Unit,
    onNavigateToIntegrations: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onLogout: (onSuccess: () -> Unit) -> Unit,
    onNavigateToHistory: (() -> Unit)? = null,
    isLoggingOut: Boolean = false,
    showStatistics: Boolean = true,
    showIntegrations: Boolean = true,
    initialFocusRequester: FocusRequester? = null,
    focusModifier: @Composable (Shape) -> Modifier = { Modifier },
    afterMenuContent: (@Composable ColumnScope.((() -> Unit) -> Unit) -> Unit)? = null,
) {
    if (LocalUseDesktopUi.current) {
        AccountMenu(
            onDismiss = onDismiss,
            isSignedIn = isSignedIn,
            accountName = accountName,
            accountSubtitle = accountSubtitle,
            accountImageUrl = accountImageUrl,
            onSignIn = onSignIn,
            onTokenLogin = onTokenLogin,
            onSwitchChannel = onSwitchChannel,
            onNavigateToStatistics = onNavigateToStatistics,
            onNavigateToIntegrations = onNavigateToIntegrations,
            onNavigateToSettings = onNavigateToSettings,
            onLogout = onLogout,
            onNavigateToHistory = onNavigateToHistory,
            isLoggingOut = isLoggingOut,
            showStatistics = showStatistics,
            showIntegrations = showIntegrations,
            initialFocusRequester = initialFocusRequester,
            focusModifier = focusModifier,
            afterMenuContent = afterMenuContent,
            alignToAnchorEnd = true,
        )
        return
    }
    val dismissal = remember { AccountMenuDismissal() }
    val dismiss = { dismissal.dismiss() }
    val density = LocalDensity.current
    PopupVisibility(dismissal.visible, onHidden = { dismissal.finish(onDismiss) }) {
        val interactive = checkNotNull(LocalPopupSession.current).state.acceptsInput
        val dismissRequest = { if (interactive && !isLoggingOut) dismiss() }
        if (dismissal.visible) {
            Dialog(
                onDismissRequest = dismissRequest,
                properties =
                    DialogProperties(
                        usePlatformDefaultWidth = false,
                        dismissOnClickOutside = true,
                    ),
            ) {
                CompositionLocalProvider(LocalDensity provides density) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .blockInput(!interactive)
                                .pointerInput(dismissRequest) {
                                    detectTapGestures(onTap = { dismissRequest() })
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        AccountMenuContainer(
                            modifier =
                                Modifier
                                    .padding(16.dp)
                                    .widthIn(max = AccountPopupMaxWidth)
                                    .fillMaxWidth(),
                            shape = RoundedCornerShape(28.dp),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            AccountMenuContent(
                                isSignedIn = isSignedIn,
                                accountName = accountName,
                                accountSubtitle = accountSubtitle,
                                accountImageUrl = accountImageUrl,
                                onDismiss = dismissal::dismiss,
                                onSignIn = onSignIn,
                                onTokenLogin = onTokenLogin,
                                onSwitchChannel = onSwitchChannel,
                                onNavigateToHistory = onNavigateToHistory,
                                onNavigateToStatistics = onNavigateToStatistics,
                                onNavigateToIntegrations = onNavigateToIntegrations,
                                onNavigateToSettings = onNavigateToSettings,
                                onLogout = { onLogout(dismiss) },
                                isLoggingOut = isLoggingOut,
                                showStatistics = showStatistics,
                                showIntegrations = showIntegrations,
                                modifier = Modifier.padding(14.dp),
                                initialFocusRequester = initialFocusRequester,
                                focusModifier = focusModifier,
                                afterMenuContent = afterMenuContent,
                            )
                        }
                    }
                }
            }
        }
    }
}
