package com.metrolist.shared.ui.settings

import androidx.compose.runtime.Composable

@Composable
actual fun IosAppearanceProvider(content: @Composable () -> Unit) = content()

@Composable
actual fun IosPlayerVisibilityEffect(
    expandedPlayerVisible: Boolean,
    lyricsVisible: Boolean,
    lyricsFullscreenVisible: Boolean,
    isPlaying: Boolean,
) = Unit

@Suppress("UNUSED_PARAMETER")
@Composable
actual fun rememberIosFontActions(
    chooseTitle: String,
    chooseDescription: String,
    resetTitle: String,
    resetDescription: String,
    onImportResult: (String?) -> Unit,
): List<SettingsHostAction> = emptyList()

@Suppress("UNUSED_PARAMETER")
@Composable
actual fun rememberIosLogExportAction(
    title: String,
    description: String,
): SettingsHostAction = SettingsHostAction("iosExportLogs", title, description, enabled = false, onClick = {})

@Suppress("UNUSED_PARAMETER")
actual fun applyIosAppLanguage(language: String) = Unit
