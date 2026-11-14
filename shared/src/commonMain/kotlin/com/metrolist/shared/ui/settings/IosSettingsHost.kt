package com.metrolist.shared.ui.settings

import androidx.compose.runtime.Composable

@Composable
expect fun IosAppearanceProvider(content: @Composable () -> Unit)

@Composable
expect fun IosPlayerVisibilityEffect(
    expandedPlayerVisible: Boolean,
    lyricsVisible: Boolean,
    lyricsFullscreenVisible: Boolean,
    isPlaying: Boolean,
)

@Composable
expect fun rememberIosFontActions(
    chooseTitle: String,
    chooseDescription: String,
    resetTitle: String,
    resetDescription: String,
    onImportResult: (String?) -> Unit,
): List<SettingsHostAction>

@Composable
expect fun rememberIosLogExportAction(
    title: String,
    description: String,
): SettingsHostAction

expect fun applyIosAppLanguage(language: String)
