/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.settings_home_section_dynamic
import com.metrolist.shared.generated.resources.settings_home_section_items
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.screens.HomeShelfBuilder
import com.metrolist.shared.ui.screens.homeContentText
import com.metrolist.shared.viewmodels.HomeViewModel.HomeUiState
import org.jetbrains.compose.resources.stringResource

@Composable
fun settingsHomeSections(state: HomeUiState): List<SettingsHomeSection> =
    HomeShelfBuilder.build(state, homeContentText(), 1000.dp, includeHidden = true).map { section ->
        SettingsHomeSection(
            id = section.key,
            preferenceKey = section.visibilityKey,
            title = section.title,
            description =
                if (section.items.isNotEmpty()) {
                    stringResource(Res.string.settings_home_section_items, section.items.size)
                } else {
                    stringResource(Res.string.settings_home_section_dynamic)
                },
            visible = Settings.shared.shouldShowHomeSection(section.visibilityKey, section.title, section.legacyVisibilityKey),
        )
    }
