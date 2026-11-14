/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.ui.screens

import androidx.compose.runtime.Composable
import com.metrolist.android.ui.screens.settings.IntegrationsScreen as SettingsIntegrationsScreen

@Composable
fun IntegrationsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToListenTogether: () -> Unit,
) {
    SettingsIntegrationsScreen(onNavigateBack = onNavigateBack, onNavigateToListenTogether = onNavigateToListenTogether)
}
