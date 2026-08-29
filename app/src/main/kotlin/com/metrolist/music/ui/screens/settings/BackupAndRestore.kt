/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.db.entities.Song
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.menu.AddToPlaylistDialogOnline
import com.metrolist.music.ui.menu.CsvColumnMappingDialog
import com.metrolist.music.ui.menu.CsvImportProgressDialog
import com.metrolist.music.ui.menu.LoadingScreen
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.viewmodels.BackupPreviewInfo
import com.metrolist.music.viewmodels.BackupRestoreViewModel
import com.metrolist.music.viewmodels.ConvertedSongLog
import com.metrolist.music.viewmodels.CsvImportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupAndRestore(
    navController: NavController,
    viewModel: BackupRestoreViewModel = hiltViewModel(),
) {
    var importedTitle by remember { mutableStateOf("") }
    val importedSongs = remember { mutableStateListOf<Song>() }
    var showChoosePlaylistDialogOnline by rememberSaveable {
        mutableStateOf(false)
    }

    var currentImportSong by rememberSaveable { mutableStateOf("") }
    var isProgressStarted by rememberSaveable {
        mutableStateOf(false)
    }

    var progressPercentage by rememberSaveable {
        mutableIntStateOf(0)
    }

    // CSV column mapping state
    var csvImportState by remember { mutableStateOf<CsvImportState?>(null) }
    var showCsvColumnMapping by rememberSaveable { mutableStateOf(false) }
    var showCsvImportProgress by rememberSaveable { mutableStateOf(false) }
    var csvImportProgress by rememberSaveable { mutableIntStateOf(0) }
    val csvRecentLogs = remember { mutableStateListOf<ConvertedSongLog>() }
    var pendingCsvUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // Restore confirmation dialog state
    var showRestoreConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var backupPreviewInfo by remember { mutableStateOf<BackupPreviewInfo?>(null) }
    var isLoadingAccountInfo by remember { mutableStateOf(false) }
    var accountCheckFailed by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val backupLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) {
                viewModel.backup(context, uri)
            }
        }
    val restoreLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                pendingRestoreUri = uri
                val preview = viewModel.previewBackup(context, uri)
                backupPreviewInfo = preview
                showRestoreConfirmDialog = true

                // Fetch account info asynchronously if backup has auth data
                accountCheckFailed = false
                if (preview.hasAuthData && preview.cookie != null) {
                    isLoadingAccountInfo = true
                    coroutineScope.launch(Dispatchers.IO) {
                        val accountInfo = viewModel.fetchAccountInfoFromBackup(preview.cookie)
                        if (accountInfo != null) {
                            backupPreviewInfo = accountInfo
                        } else {
                            accountCheckFailed = true
                        }
                        isLoadingAccountInfo = false
                    }
                }
            }
        }
    val importPlaylistFromCsv =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            pendingCsvUri = uri
            val previewState = viewModel.previewCsvFile(context, uri)
            csvImportState = previewState
            showCsvColumnMapping = true
        }
    val importM3uLauncherOnline =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val result = viewModel.loadM3UOnline(context, uri)
            importedSongs.clear()
            importedSongs.addAll(result)

            if (importedSongs.isNotEmpty()) {
                showChoosePlaylistDialogOnline = true
            }
        }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top,
                ),
            ),
        )

        val appName = stringResource(R.string.app_name)
        Material3SettingsGroup(
            items =
                listOf(
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.action_backup)) },
                        icon = painterResource(R.drawable.backup),
                        onClick = {
                            val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                            backupLauncher.launch(
                                "${appName}_${
                                    LocalDateTime.now().format(formatter)
                                }.backup",
                            )
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.action_restore)) },
                        icon = painterResource(R.drawable.restore),
                        onClick = {
                            restoreLauncher.launch(arrayOf("application/octet-stream"))
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.import_online)) },
                        icon = painterResource(R.drawable.playlist_add),
                        onClick = {
                            importM3uLauncherOnline.launch(arrayOf("audio/*"))
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.import_csv)) },
                        icon = painterResource(R.drawable.playlist_add),
                        onClick = {
                            importPlaylistFromCsv.launch(
                                arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain"),
                            )
                        },
                    ),
                ),
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.backup_restore)) },
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

    AddToPlaylistDialogOnline(
        isVisible = showChoosePlaylistDialogOnline,
        allowSyncing = false,
        initialTextFieldValue = importedTitle,
        songs = importedSongs,
        onDismiss = { showChoosePlaylistDialogOnline = false },
        onProgressStart = { newVal -> isProgressStarted = newVal },
        onPercentageChange = { newPercentage -> progressPercentage = newPercentage },
        onSongChange = { currentImportSong = it },
    )

    LoadingScreen(
        isVisible = isProgressStarted,
        value = progressPercentage,
        songTitle = currentImportSong,
    )

    // CSV column mapping dialog
    csvImportState?.let { state ->
        CsvColumnMappingDialog(
            isVisible = showCsvColumnMapping,
            csvState = state,
            onDismiss = {
                showCsvColumnMapping = false
                csvImportState = null
            },
            onConfirm = { mappingState ->
                showCsvColumnMapping = false
                csvImportState = mappingState
                pendingCsvUri?.let { uri ->
                    showCsvImportProgress = true
                    coroutineScope.launch(Dispatchers.Default) {
                        val result =
                            viewModel.importPlaylistFromCsv(
                                context,
                                uri,
                                mappingState,
                                onProgress = { progress ->
                                    csvImportProgress = progress
                                },
                                onLogUpdate = { logs ->
                                    csvRecentLogs.clear()
                                    csvRecentLogs.addAll(logs)
                                },
                            )
                        importedSongs.clear()
                        importedSongs.addAll(result)
                        if (result.isNotEmpty()) {
                            showCsvImportProgress = false
                            csvImportProgress = 0
                            csvRecentLogs.clear()
                            showChoosePlaylistDialogOnline = true
                        }
                    }
                }
            },
        )
    }

    // CSV import progress dialog
    CsvImportProgressDialog(
        isVisible = showCsvImportProgress,
        progress = csvImportProgress,
        recentLogs = csvRecentLogs.toList(),
        onDismiss = {
            // Cannot dismiss while importing
        },
    )

    // Restore confirmation dialog
    if (showRestoreConfirmDialog) {
        DefaultDialog(
            onDismiss = {
                showRestoreConfirmDialog = false
                pendingRestoreUri = null
                backupPreviewInfo = null
                accountCheckFailed = false
            },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.restore),
                    contentDescription = null,
                )
            },
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            buttons = {
                TextButton(
                    onClick = {
                        showRestoreConfirmDialog = false
                        pendingRestoreUri = null
                        backupPreviewInfo = null
                        accountCheckFailed = false
                    },
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        showRestoreConfirmDialog = false
                        pendingRestoreUri?.let { uri ->
                            viewModel.restore(context, uri, clearAuthData = false)
                        }
                        pendingRestoreUri = null
                        backupPreviewInfo = null
                        accountCheckFailed = false
                    },
                ) {
                    Text(stringResource(R.string.restore))
                }
            },
        ) {
            // Supporting text
            Text(
                text = stringResource(R.string.restore_confirm_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )

            // Show warning about account sign out if account found
            if (backupPreviewInfo?.accountName != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.restore_account_warning),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            // Show loading or account info
            if (isLoadingAccountInfo) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.size(16.dp))
                    Text(
                        text = stringResource(R.string.checking_previous_account),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            // Show "No account found" if check failed OR backup has no auth data
            val hasNoAccount =
                backupPreviewInfo?.let {
                    !it.hasAuthData || (it.hasAuthData && it.accountName == null && !isLoadingAccountInfo)
                } ?: false
            if (!isLoadingAccountInfo && (accountCheckFailed || hasNoAccount)) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.no_account_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            // Show account info if backup contains auth data and we have account details
            backupPreviewInfo?.let { preview ->
                if (!isLoadingAccountInfo && preview.hasAuthData && preview.accountName != null) {
                    Spacer(modifier = Modifier.height(16.dp))

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (preview.accountImageUrl != null) {
                            AsyncImage(
                                model = preview.accountImageUrl,
                                contentDescription = null,
                                modifier =
                                    Modifier
                                        .size(40.dp)
                                        .clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(
                                modifier =
                                    Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.person),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.size(16.dp))

                        Text(
                            text = preview.accountEmail ?: preview.accountName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}
