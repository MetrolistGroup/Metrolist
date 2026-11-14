package com.metrolist.shared.ui.settings

import androidx.compose.runtime.Composable

@Composable
actual fun rememberIosAppIconActions(onError: () -> Unit): IosAppIconActions = IosAppIconActions(emptyList()) {}
