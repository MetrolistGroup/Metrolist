package com.metrolist.shared.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.shared.ui.settings.SettingsItemGroup
import com.metrolist.shared.ui.settings.SettingsPageId
import com.metrolist.shared.ui.settings.SharedSettingsHost
import com.metrolist.shared.ui.settings.rememberMetrolistSettingsRegistry

@Composable
fun OnboardingPreferences(
    page: OnboardingPage,
    host: SharedSettingsHost,
) {
    var settingsRevision by remember { mutableIntStateOf(0) }
    val registry = rememberMetrolistSettingsRegistry(host, settingsRevision)
    val entries =
        when (page) {
            OnboardingPage.LANGUAGE_LYRICS -> {
                mapOf(
                    SettingsPageId.CONTENT to setOf("appLanguage"),
                    SettingsPageId.LYRICS_AI_TRANSLATION to setOf("translationEnabled"),
                    SettingsPageId.LYRICS_ROMANIZATION to setOf("romanizationEnabled"),
                )
            }

            OnboardingPage.PLAYBACK -> {
                mapOf(SettingsPageId.PLAYER to setOf("musicVideoPlaybackEnabled", "castEnabled"))
            }

            else -> {
                emptyMap()
            }
        }
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        entries.forEach { (pageId, itemIds) ->
            registry[pageId]?.groups?.forEach { group ->
                val items = group.items.filter { it.id in itemIds }
                if (items.isNotEmpty()) {
                    SettingsItemGroup(
                        group = group.copy(items = items),
                        highlightedItemId = null,
                        onSettingValueChanged = { settingsRevision++ },
                        onItemClick = {},
                        horizontalPadding = 0.dp,
                        showHeading = false,
                    )
                }
            }
        }
    }
}
