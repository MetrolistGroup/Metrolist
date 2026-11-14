package com.metrolist.shared.ui.haptics

import androidx.compose.runtime.Composable

private val noHaptics: (HapticEvent) -> Unit = { }

@Composable
internal actual fun rememberPlatformHaptics(): (HapticEvent) -> Unit = noHaptics
