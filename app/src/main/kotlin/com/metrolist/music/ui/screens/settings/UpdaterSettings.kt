/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.BuildConfig
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.CheckForUpdatesKey
import com.metrolist.music.constants.UpdateNotificationsEnabledKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.Updater
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdaterScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (checkForUpdates, onCheckForUpdatesChange) = rememberPreference(CheckForUpdatesKey, true)
    val (updateNotifications, onUpdateNotificationsChange) = rememberPreference(UpdateNotificationsEnabledKey, true)
    
    var isChecking by remember { mutableStateOf(false) }
    var updateAvailable by remember { mutableStateOf(false) }
    var latestVersion by remember { mutableStateOf<String?>(null) }
    var showChangelog by remember { mutableStateOf(false) }
    var changelogContent by remember { mutableStateOf<String?>(null) }
    var checkError by remember { mutableStateOf<String?>(null) }
    
    val coroutineScope = rememberCoroutineScope()

    fun performManualCheck() {
        coroutineScope.launch {
            isChecking = true
            checkError = null
            withContext(Dispatchers.IO) {
                Updater.checkForUpdate(forceRefresh = true).onSuccess { (releaseInfo, hasUpdate) ->
                    if (releaseInfo != null) {
                        latestVersion = releaseInfo.versionName
                        updateAvailable = hasUpdate
                        changelogContent = releaseInfo.description
                    }
                }.onFailure {
                    checkError = "Failed to check for updates: ${it.message}"
                }
            }
            isChecking = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top
                )
            )
        )

        Spacer(Modifier.height(4.dp))

        // Current Version Info
        Material3SettingsGroup(
            title = "Current Version",
            items = listOf(
                Material3SettingsItem(
                    title = {
                        Text("Version: ${BuildConfig.VERSION_NAME}")
                    },
                    description = {
                        val arch = BuildConfig.ARCHITECTURE
                        val variant = if (BuildConfig.CAST_AVAILABLE) "GMS" else "FOSS"
                        Text("$arch - $variant")
                    }
                )
            )
        )
        
        Spacer(Modifier.height(16.dp))

        // Auto Update Settings
        Material3SettingsGroup(
            title = "Update Settings",
            items = buildList {
                add(
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.check_for_updates)) },
                        icon = painterResource(R.drawable.update),
                        trailingContent = {
                            Switch(
                                checked = checkForUpdates,
                                onCheckedChange = onCheckForUpdatesChange
                            )
                        },
                        onClick = { onCheckForUpdatesChange(!checkForUpdates) }
                    )
                )

                if (checkForUpdates) {
                    add(
                        Material3SettingsItem(
                            title = { Text(stringResource(R.string.update_notifications)) },
                            icon = painterResource(R.drawable.notification),
                            trailingContent = {
                                Switch(
                                    checked = updateNotifications,
                                    onCheckedChange = onUpdateNotificationsChange
                                )
                            },
                            onClick = { onUpdateNotificationsChange(!updateNotifications) }
                        )
                    )
                }
            }
        )

        Spacer(Modifier.height(16.dp))

        // Manual Check
        Material3SettingsGroup(
            title = "Check for Updates",
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.refresh),
                    title = { 
                        if (isChecking) {
                            Text("Checking for updates...")
                        } else if (latestVersion != null) {
                            Text("Latest: $latestVersion")
                        } else {
                            Text("Check for Updates")
                        }
                    },
                    trailingContent = {
                        if (isChecking) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 16.dp),
                                strokeWidth = 2.dp
                            )
                        } else if (updateAvailable) {
                            Icon(
                                painter = painterResource(R.drawable.download),
                                contentDescription = "Update available",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    onClick = { if (!isChecking) performManualCheck() }
                )
            )
        )

        checkError?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        if (updateAvailable && latestVersion != null) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { showChangelog = !showChangelog },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text(if (showChangelog) "Hide Changelog" else "View Changelog")
            }

            if (showChangelog && changelogContent != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = changelogContent!!,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.updater)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        }
    )
}