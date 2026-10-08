/**
 * 7xTune Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.metrolist.music.BuildConfig
import com.metrolist.music.LocalChangelogState
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.ReleaseNotesCard
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.Updater

private data class SettingsEntry(
    val icon: Painter,
    val title: String,
    val description: String,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    latestVersionName: String,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val isAndroid12OrLater = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val hasAndroidAuto =
        remember {
            try {
                context.packageManager.getPackageInfo("com.google.android.projection.gearhead", 0)
                true
            } catch (_: Exception) {
                false
            }
        }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
            ),
        )
        Spacer(Modifier.height(14.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(62.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.small_icon),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.size(42.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = stringResource(R.string.settings_7xtune_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        SettingsSectionCard(
            title = stringResource(R.string.settings_section_ui),
            entries = listOf(
                SettingsEntry(
                    painterResource(R.drawable.palette),
                    stringResource(R.string.appearance),
                    "Themes, colours, navigation and display",
                ) { navController.navigate("settings/appearance") },
            ),
        )

        Spacer(Modifier.height(14.dp))

        SettingsSectionCard(
            title = stringResource(R.string.settings_section_player_content),
            entries = listOf(
                SettingsEntry(
                    painterResource(R.drawable.play),
                    stringResource(R.string.player_and_audio),
                    "Playback, controls, downloads and audio behaviour",
                ) { navController.navigate("settings/player") },
                SettingsEntry(
                    painterResource(R.drawable.language),
                    stringResource(R.string.content),
                    "Language, regional content and browsing preferences",
                ) { navController.navigate("settings/content") },
                SettingsEntry(
                    painterResource(R.drawable.translate),
                    stringResource(R.string.ai_lyrics_translation),
                    "Translation providers and lyric language options",
                ) { navController.navigate("settings/ai") },
            ),
        )

        if (hasAndroidAuto) {
            Spacer(Modifier.height(14.dp))
            SettingsSectionCard(
                title = "Android Auto",
                entries = listOf(
                    SettingsEntry(
                        painterResource(R.drawable.ic_android_auto),
                        stringResource(R.string.android_auto),
                        "Control how 7xTune appears in Android Auto",
                    ) { navController.navigate("settings/android_auto") },
                ),
            )
        }

        Spacer(Modifier.height(14.dp))

        SettingsSectionCard(
            title = stringResource(R.string.settings_section_privacy),
            entries = listOf(
                SettingsEntry(
                    painterResource(R.drawable.security),
                    stringResource(R.string.privacy),
                    "Permissions, privacy controls and local app behaviour",
                ) { navController.navigate("settings/privacy") },
            ),
        )

        Spacer(Modifier.height(14.dp))

        SettingsSectionCard(
            title = stringResource(R.string.settings_section_storage),
            entries = listOf(
                SettingsEntry(
                    painterResource(R.drawable.storage),
                    stringResource(R.string.storage),
                    "Downloads, cache and storage usage",
                ) { navController.navigate("settings/storage") },
                SettingsEntry(
                    painterResource(R.drawable.restore),
                    stringResource(R.string.backup_restore),
                    "Back up and restore your 7xTune data",
                ) { navController.navigate("settings/backup_restore") },
            ),
        )

        Spacer(Modifier.height(14.dp))

        val systemEntries = buildList {
            if (isAndroid12OrLater) {
                add(
                    SettingsEntry(
                        painterResource(R.drawable.link),
                        stringResource(R.string.default_links),
                        "Choose whether 7xTune handles supported links",
                    ) {
                        try {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                                    "package:${context.packageName}".toUri(),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, R.string.open_app_settings_error, Toast.LENGTH_LONG).show()
                        } catch (_: SecurityException) {
                            Toast.makeText(context, R.string.open_app_settings_error, Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }

            if (BuildConfig.UPDATER_AVAILABLE) {
                add(
                    SettingsEntry(
                        painterResource(R.drawable.update),
                        stringResource(R.string.updater),
                        "Check and manage 7xTune updates",
                    ) { navController.navigate("settings/updater") },
                )
            }

            val showChangelog = LocalChangelogState.current
            add(
                SettingsEntry(
                    painterResource(R.drawable.newspaper),
                    stringResource(R.string.changelog),
                    "See what changed in recent releases",
                ) { showChangelog.value = true },
            )

            add(
                SettingsEntry(
                    painterResource(R.drawable.info),
                    stringResource(R.string.about),
                    "7xTune details, website and open-source licence",
                ) { navController.navigate("settings/about") },
            )
        }

        SettingsSectionCard(
            title = stringResource(R.string.settings_section_system),
            entries = systemEntries,
        )

        if (BuildConfig.UPDATER_AVAILABLE && latestVersionName != BuildConfig.BASE_VERSION_NAME) {
            val releaseInfo = Updater.getCachedLatestRelease()
            val downloadUrl = releaseInfo?.let { Updater.getDownloadUrlForCurrentVariant(it) }

            if (downloadUrl != null) {
                Spacer(Modifier.height(14.dp))
                Card(
                    onClick = { uriHandler.openUri(downloadUrl) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.update),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(26.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.new_version_available),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = latestVersionName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                            )
                        }
                        Icon(
                            painter = painterResource(R.drawable.navigate_next),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }

        if (BuildConfig.UPDATER_AVAILABLE && latestVersionName != BuildConfig.BASE_VERSION_NAME) {
            Spacer(Modifier.height(14.dp))
            ReleaseNotesCard()
        }

        Spacer(Modifier.height(20.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.settings)) },
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
        },
    )
}

@Composable
private fun SettingsSectionCard(
    title: String,
    entries: List<SettingsEntry>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            )

            entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 18.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    )
                }

                Card(
                    onClick = entry.onClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = entry.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(23.dp),
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = entry.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Icon(
                            painter = painterResource(R.drawable.navigate_next),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
