/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.LyricsRomanizeAsMainKey
import com.metrolist.music.constants.LyricsRomanizeCyrillicByLineKey
import com.metrolist.music.constants.LyricsRomanizeList
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference

val defaultList = mutableListOf(
    "Japanese" to true,
    "Korean" to true,
    "Chinese" to true,
    "Hindi" to true,
    "Punjabi" to true,
    "Russian" to true,
    "Ukrainian" to true,
    "Serbian" to true,
    "Bulgarian" to true,
    "Belarusian" to true,
    "Kyrgyz" to true,
    "Macedonian" to true,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RomanizationSettings(
    navController: NavController
) {
    val (pref, prefValue) = rememberPreference(LyricsRomanizeList, "")

    val initialList = remember(pref) {
        if (pref.isEmpty()) defaultList
        else {
            val savedMap = pref.split(",").associate { entry ->
                val (lang, checked) = entry.split(":")
                lang to checked.toBoolean()
            }

            defaultList.map { (lang, defaultChecked) ->
                Pair(lang, savedMap[lang] ?: defaultChecked)
            }
        }
    }

    val states = remember(initialList) { mutableStateListOf(*initialList.toTypedArray()) }

    val parentState = when {
        states.all { it.component2() } -> ToggleableState.On
        states.none { it.component2() } -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }

    val (lyricsRomanizeAsMain, onLyricsRomanizeAsMainChange) = rememberPreference(
        LyricsRomanizeAsMainKey,
        defaultValue = false
    )

    val (lyricsRomanizeCyrillicByLine, onLyricsRomanizeCyrillicByLineChange) = rememberPreference(
        LyricsRomanizeCyrillicByLineKey,
        defaultValue = false
    )

    val checkboxesList: MutableList<Material3SettingsItem> = mutableListOf()

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Material3SettingsGroup(
            title = stringResource(R.string.options),
            items = listOf(
                Material3SettingsItem(
                    title = { Text(stringResource(R.string.lyrics_romanize_as_main)) },
                    trailingContent = {
                        Switch(
                            checked = lyricsRomanizeAsMain,
                            onCheckedChange = onLyricsRomanizeAsMainChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (lyricsRomanizeAsMain) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            }
                        )
                    },
                    icon = painterResource(R.drawable.queue_music)
                ),
                Material3SettingsItem(
                    title = { Text(stringResource(R.string.line_by_line_option_title)) },
                    trailingContent = {
                        Switch(
                            checked = lyricsRomanizeCyrillicByLine,
                            onCheckedChange = onLyricsRomanizeCyrillicByLineChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (lyricsRomanizeCyrillicByLine) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            }
                        )
                    },
                    icon = painterResource(R.drawable.info)
                )
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        checkboxesList += Material3SettingsItem(
            title = { Text("Play all") },
            trailingContent = {
                TriStateCheckbox(
                    state = parentState,
                    onClick = {
                        val newState = parentState != ToggleableState.On
                        states.forEachIndexed { index, (language, _) ->
                            states[index] = Pair(language, newState)
                        }
                        prefValue(states.joinToString(",") { (lang, c) -> "$lang:$c" })
                    }
                )
            },
            icon = painterResource(R.drawable.info)
        )

        states.forEachIndexed { index, (language, checked) ->
            checkboxesList += Material3SettingsItem(
                title = { Text(language) },
                trailingContent = {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { isChecked ->
                            states[index] = Pair(language, isChecked)
                            prefValue(states.joinToString(",") { (lang, c) -> "$lang:$c" })
                        }
                    )
                },
                icon = painterResource(R.drawable.language)
            )
        }

        Material3SettingsGroup(
            title = stringResource(R.string.content_language),
            items = checkboxesList
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.lyrics_romanize_title)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        }
    )
}
