/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.SponsorBlockCategoryPreferences
import com.metrolist.music.constants.SponsorBlockEnabledKey
import com.metrolist.music.constants.SponsorBlockNotifyOnSkipKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import com.metrolist.sponsorblock.models.SponsorBlockCategory

@Composable
private fun SponsorBlockCategory.title(): String = when (this) {
    SponsorBlockCategory.SPONSOR -> stringResource(R.string.sponsorblock_category_sponsor)
    SponsorBlockCategory.SELF_PROMO -> stringResource(R.string.sponsorblock_category_selfpromo)
    SponsorBlockCategory.INTERACTION -> stringResource(R.string.sponsorblock_category_interaction)
    SponsorBlockCategory.INTRO -> stringResource(R.string.sponsorblock_category_intro)
    SponsorBlockCategory.OUTRO -> stringResource(R.string.sponsorblock_category_outro)
    SponsorBlockCategory.PREVIEW -> stringResource(R.string.sponsorblock_category_preview)
    SponsorBlockCategory.FILLER -> stringResource(R.string.sponsorblock_category_filler)
    SponsorBlockCategory.MUSIC_OFFTOPIC -> stringResource(R.string.sponsorblock_category_music_offtopic)
}

@Composable
private fun SponsorBlockCategory.description(): String = when (this) {
    SponsorBlockCategory.SPONSOR -> stringResource(R.string.sponsorblock_category_sponsor_desc)
    SponsorBlockCategory.SELF_PROMO -> stringResource(R.string.sponsorblock_category_selfpromo_desc)
    SponsorBlockCategory.INTERACTION -> stringResource(R.string.sponsorblock_category_interaction_desc)
    SponsorBlockCategory.INTRO -> stringResource(R.string.sponsorblock_category_intro_desc)
    SponsorBlockCategory.OUTRO -> stringResource(R.string.sponsorblock_category_outro_desc)
    SponsorBlockCategory.PREVIEW -> stringResource(R.string.sponsorblock_category_preview_desc)
    SponsorBlockCategory.FILLER -> stringResource(R.string.sponsorblock_category_filler_desc)
    SponsorBlockCategory.MUSIC_OFFTOPIC -> stringResource(R.string.sponsorblock_category_music_offtopic_desc)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SponsorBlockSettings(
    navController: NavController
) {
    val (sponsorBlockEnabled, onSponsorBlockEnabledChange) = rememberPreference(
        key = SponsorBlockEnabledKey,
        defaultValue = false
    )
    val (notifyOnSkip, onNotifyOnSkipChange) = rememberPreference(
        key = SponsorBlockNotifyOnSkipKey,
        defaultValue = true
    )

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top
                )
            )
        )

        Material3SettingsGroup(
            title = stringResource(R.string.sponsorblock),
            items = buildList {
                add(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.fast_forward),
                        title = { Text(stringResource(R.string.sponsorblock_enable)) },
                        description = { Text(stringResource(R.string.sponsorblock_enable_desc)) },
                        trailingContent = {
                            Switch(
                                checked = sponsorBlockEnabled,
                                onCheckedChange = onSponsorBlockEnabledChange,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (sponsorBlockEnabled) R.drawable.check else R.drawable.close
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        }
                    )
                )
                add(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.info),
                        title = { Text(stringResource(R.string.sponsorblock_notify_on_skip)) },
                        description = { Text(stringResource(R.string.sponsorblock_notify_on_skip_desc)) },
                        enabled = sponsorBlockEnabled,
                        trailingContent = {
                            Switch(
                                checked = notifyOnSkip,
                                onCheckedChange = onNotifyOnSkipChange,
                                enabled = sponsorBlockEnabled,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (notifyOnSkip) R.drawable.check else R.drawable.close
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        }
                    )
                )
            }
        )

        Spacer(Modifier.height(16.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.sponsorblock_categories),
            items = buildList {
                SponsorBlockCategoryPreferences.forEach { preference ->
                    val (checked, onCheckedChange) = rememberPreference(
                        key = preference.key,
                        defaultValue = preference.enabledByDefault
                    )
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.music_note),
                            title = { Text(preference.category.title()) },
                            description = { Text(preference.category.description()) },
                            enabled = sponsorBlockEnabled,
                            trailingContent = {
                                Switch(
                                    checked = checked,
                                    onCheckedChange = onCheckedChange,
                                    enabled = sponsorBlockEnabled,
                                    thumbContent = {
                                        Icon(
                                            painter = painterResource(
                                                id = if (checked) R.drawable.check else R.drawable.close
                                            ),
                                            contentDescription = null,
                                            modifier = Modifier.size(SwitchDefaults.IconSize)
                                        )
                                    }
                                )
                            }
                        )
                    )
                }
            }
        )

        Spacer(Modifier.height(16.dp))

        Material3SettingsGroup(
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.info),
                    title = { Text(stringResource(R.string.sponsorblock_attribution)) },
                    description = { Text(stringResource(R.string.sponsorblock_attribution_desc)) }
                )
            )
        )

        Spacer(Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.sponsorblock)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null
                )
            }
        }
    )
}
