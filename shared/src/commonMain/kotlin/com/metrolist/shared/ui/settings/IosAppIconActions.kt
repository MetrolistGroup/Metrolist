package com.metrolist.shared.ui.settings

import androidx.compose.runtime.Composable

class IosAppIconActions(
    val choices: List<SettingsChoice>,
    val select: (String) -> Unit,
)

@Composable
expect fun rememberIosAppIconActions(onError: () -> Unit): IosAppIconActions
