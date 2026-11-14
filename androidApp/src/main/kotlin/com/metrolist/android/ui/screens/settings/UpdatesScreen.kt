@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.screens.settings

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.icu.text.RelativeDateTimeFormatter
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.metrolist.android.LocalMiniPlayerBottomInset
import com.metrolist.android.update.AppUpdateManager
import com.metrolist.android.update.AppUpdateState
import com.metrolist.android.update.GitHubRelease
import com.metrolist.android.update.UpdateFailure
import com.metrolist.android.update.formatReleaseAge
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_download
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.crash_action_download
import com.metrolist.shared.generated.resources.crash_back
import com.metrolist.shared.generated.resources.crash_cancel
import com.metrolist.shared.generated.resources.crash_file_size_unit_byte
import com.metrolist.shared.generated.resources.crash_file_size_unit_gigabyte
import com.metrolist.shared.generated.resources.crash_file_size_unit_kilobyte
import com.metrolist.shared.generated.resources.crash_file_size_unit_megabyte
import com.metrolist.shared.generated.resources.crash_file_size_unit_terabyte
import com.metrolist.shared.generated.resources.crash_updates_changelog
import com.metrolist.shared.generated.resources.crash_updates_check
import com.metrolist.shared.generated.resources.crash_updates_checking
import com.metrolist.shared.generated.resources.crash_updates_date
import com.metrolist.shared.generated.resources.crash_updates_digest_failed
import com.metrolist.shared.generated.resources.crash_updates_download_failed
import com.metrolist.shared.generated.resources.crash_updates_error
import com.metrolist.shared.generated.resources.crash_updates_install
import com.metrolist.shared.generated.resources.crash_updates_installer_failed
import com.metrolist.shared.generated.resources.crash_updates_installing_description
import com.metrolist.shared.generated.resources.crash_updates_installing_title
import com.metrolist.shared.generated.resources.crash_updates_last_check
import com.metrolist.shared.generated.resources.crash_updates_no_updates
import com.metrolist.shared.generated.resources.crash_updates_pending_title
import com.metrolist.shared.generated.resources.crash_updates_release_unavailable
import com.metrolist.shared.generated.resources.crash_updates_retry
import com.metrolist.shared.generated.resources.crash_updates_size
import com.metrolist.shared.generated.resources.crash_updates_version
import com.metrolist.shared.generated.resources.general
import com.metrolist.shared.generated.resources.settings_notification_access_summary
import com.metrolist.shared.generated.resources.settings_release_overview
import com.metrolist.shared.generated.resources.settings_share_apk_summary
import com.metrolist.shared.generated.resources.update_pending
import com.metrolist.shared.generated.resources.updates_auto_check
import com.metrolist.shared.generated.resources.updates_changelog
import com.metrolist.shared.generated.resources.updates_changelogs
import com.metrolist.shared.generated.resources.updates_check
import com.metrolist.shared.generated.resources.updates_checking
import com.metrolist.shared.generated.resources.updates_date
import com.metrolist.shared.generated.resources.updates_digest_failed
import com.metrolist.shared.generated.resources.updates_download_failed
import com.metrolist.shared.generated.resources.updates_error
import com.metrolist.shared.generated.resources.updates_install
import com.metrolist.shared.generated.resources.updates_installer_failed
import com.metrolist.shared.generated.resources.updates_installing_description
import com.metrolist.shared.generated.resources.updates_installing_title
import com.metrolist.shared.generated.resources.updates_last_check
import com.metrolist.shared.generated.resources.updates_no_updates
import com.metrolist.shared.generated.resources.updates_notification_settings
import com.metrolist.shared.generated.resources.updates_notifications
import com.metrolist.shared.generated.resources.updates_pending_title
import com.metrolist.shared.generated.resources.updates_release_date_age
import com.metrolist.shared.generated.resources.updates_release_unavailable
import com.metrolist.shared.generated.resources.updates_retry
import com.metrolist.shared.generated.resources.updates_share_apk
import com.metrolist.shared.generated.resources.updates_share_apk_error
import com.metrolist.shared.generated.resources.updates_share_app
import com.metrolist.shared.generated.resources.updates_size
import com.metrolist.shared.generated.resources.updates_status_current
import com.metrolist.shared.generated.resources.updates_title
import com.metrolist.shared.generated.resources.updates_version
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.settings.LocalSettingsPageHasTopBar
import com.metrolist.shared.ui.settings.settingsPageSubtitle
import com.metrolist.shared.ui.update.GitHubMarkdown
import com.metrolist.shared.ui.update.UpdateInfo
import com.metrolist.shared.ui.utils.rememberFileSizeFormatter
import com.metrolist.shared.utils.FileSizeUnits
import com.metrolist.shared.utils.formatFileSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import com.metrolist.shared.settings.Settings as AppSettings

@Composable
fun UpdatesScreen(
    onNavigateBack: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val context = LocalContext.current
    val state by AppUpdateManager.state.collectAsState()
    val releases by AppUpdateManager.releases.collectAsState()
    val scope = rememberCoroutineScope()
    val shareApkError = stringResource(Res.string.updates_share_apk_error)
    var expandedRelease by remember { mutableStateOf<GitHubRelease?>(null) }

    var notificationsAllowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleResumeEffect(context) {
        notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppSettings.shared.updateNotifications = granted
            notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    LaunchedEffect(Unit) {
        AppUpdateManager.loadReleaseHistory()
        if (state is AppUpdateState.Idle) AppUpdateManager.check(context)
    }

    SettingsSubScreen(
        title = stringResource(Res.string.updates_title),
        onNavigateBack = onNavigateBack,
    ) {
        SettingsSubScreenGroup(items = listOf(statusItem(state, onOpenDetails)))

        SettingsSubScreenGroup(
            title = stringResource(Res.string.general),
            items =
                listOf(
                    switchSettingsItem(
                        title = stringResource(Res.string.updates_auto_check),
                        wrapTitle = true,
                        checked = AppSettings.shared.checkForUpdates,
                        onCheckedChange = { AppSettings.shared.checkForUpdates = it },
                    ),
                    switchSettingsItem(
                        title = stringResource(Res.string.updates_notifications),
                        wrapTitle = true,
                        checked = AppSettings.shared.updateNotifications && notificationsAllowed,
                        onCheckedChange = { enabled ->
                            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else if (enabled && !notificationsAllowed) {
                                AppSettings.shared.updateNotifications = true
                                context.startActivity(appNotificationSettingsIntent(context))
                            } else {
                                AppSettings.shared.updateNotifications = enabled
                            }
                        },
                    ),
                    SettingsSubScreenItem(
                        title = stringResource(Res.string.updates_notification_settings),
                        onClick = {
                            context.startActivity(appNotificationSettingsIntent(context))
                        },
                        description = stringResource(Res.string.settings_notification_access_summary),
                    ),
                ),
        )

        SettingsSubScreenGroup(
            title = stringResource(Res.string.updates_share_app),
            items =
                listOf(
                    SettingsSubScreenItem(
                        title = stringResource(Res.string.updates_share_apk),
                        icon = MetrolistIcons.Outlined.Share,
                        onClick = {
                            scope.launch {
                                try {
                                    shareInstalledApk(context)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    Toast.makeText(context, shareApkError, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        description = stringResource(Res.string.settings_share_apk_summary),
                    ),
                ),
        )

        if (releases.isNotEmpty()) {
            val locale = LocalConfiguration.current.locales[0]
            val formatter = remember(locale) { RelativeDateTimeFormatter.getInstance(locale) }
            val now by produceState(Instant.now()) {
                while (true) {
                    delay(60_000)
                    value = Instant.now()
                }
            }
            SettingsSubScreenGroup(
                title = stringResource(Res.string.updates_changelogs),
                items =
                    releases.map { release ->
                        SettingsSubScreenItem(
                            title = release.version,
                            description =
                                settingsPageSubtitle(
                                    stringResource(Res.string.settings_release_overview),
                                    release.publishedAt?.let { publishedAt ->
                                        val date = publishedAt.substringBefore('T')
                                        val age = formatReleaseAge(publishedAt, now, formatter)
                                        if (age == null) date else stringResource(Res.string.updates_release_date_age, date, age)
                                    },
                                ),
                            onClick = { expandedRelease = release },
                        )
                    },
            )
        }
    }

    expandedRelease?.let { release ->
        ReleaseNotesSheet(release = release, onDismiss = { expandedRelease = null })
    }
}

private suspend fun shareInstalledApk(context: Context) {
    val shareTitle = getString(Res.string.updates_share_apk)
    val apkUri =
        withContext(Dispatchers.IO) {
            val sourceApk = File(context.applicationInfo.sourceDir)
            check(sourceApk.isFile) { "Installed APK is unavailable" }
            val directory = File(context.cacheDir, "shared_apks").apply { mkdirs() }
            val now = System.currentTimeMillis()
            directory
                .listFiles()
                ?.filter { it.name.startsWith("metrolist-") && now - it.lastModified() > APK_SHARE_CACHE_MAX_AGE_MS }
                ?.forEach(File::delete)
            val destination = File.createTempFile("metrolist-", ".apk", directory)
            try {
                sourceApk.copyTo(destination, overwrite = true)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destination)
            } catch (error: Exception) {
                destination.delete()
                throw error
            }
        }
    val sendIntent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, apkUri)
            clipData = ClipData.newUri(context.contentResolver, shareTitle, apkUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(sendIntent, shareTitle))
}

private const val APK_SHARE_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L

@Composable
private fun statusItem(
    state: AppUpdateState,
    onClick: () -> Unit,
): SettingsSubScreenItem {
    val pending = state.release != null
    return SettingsSubScreenItem(
        title = stringResource(Res.string.updates_title),
        description =
            settingsPageSubtitle(
                stringResource(Res.string.settings_release_overview),
                when {
                    pending -> stringResource(Res.string.update_pending)
                    state is AppUpdateState.Checking -> stringResource(Res.string.updates_checking)
                    state is AppUpdateState.Error -> stringResource(Res.string.updates_error)
                    else -> stringResource(Res.string.updates_status_current, AppUpdateManager.currentVersion)
                },
            ),
        icon =
            when {
                pending -> MetrolistIcons.Filled.NewReleases
                state is AppUpdateState.Error -> MetrolistIcons.Filled.Error
                else -> MetrolistIcons.Filled.Verified
            },
        iconTint =
            when {
                pending -> MaterialTheme.colorScheme.tertiary
                state is AppUpdateState.Error -> MaterialTheme.colorScheme.error
                state is AppUpdateState.Checking -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.primary
            },
        onClick = onClick,
    )
}

private val AppUpdateState.release: GitHubRelease?
    get() =
        when (this) {
            is AppUpdateState.Available -> release
            is AppUpdateState.Downloading -> release
            is AppUpdateState.Downloaded -> release
            is AppUpdateState.Error -> release
            else -> null
        }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateDetailsScreen(
    onNavigateBack: () -> Unit,
    crashRecovery: Boolean = false,
) {
    val fileSizeFormatter =
        if (crashRecovery) {
            val units =
                FileSizeUnits(
                    byte = stringResource(Res.string.crash_file_size_unit_byte),
                    kilobyte = stringResource(Res.string.crash_file_size_unit_kilobyte),
                    megabyte = stringResource(Res.string.crash_file_size_unit_megabyte),
                    gigabyte = stringResource(Res.string.crash_file_size_unit_gigabyte),
                    terabyte = stringResource(Res.string.crash_file_size_unit_terabyte),
                )
            remember(units) { { bytes: Long -> formatFileSize(bytes, units) } }
        } else {
            rememberFileSizeFormatter()
        }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by AppUpdateManager.state.collectAsState()
    var installAfterPermission by remember { mutableStateOf<AppUpdateState.Downloaded?>(null) }
    var autoInstallHandled by rememberSaveable { mutableStateOf(false) }
    var installerError by remember { mutableStateOf(false) }

    val installPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            installAfterPermission?.let { downloaded ->
                if (AppUpdateManager.canInstallPackages(context)) {
                    scope.launch { installerError = !AppUpdateManager.install(context, downloaded, crashRecovery) }
                    installAfterPermission = null
                }
            }
        }

    fun install(downloaded: AppUpdateState.Downloaded) {
        installerError = false
        if (AppUpdateManager.canInstallPackages(context)) {
            scope.launch { installerError = !AppUpdateManager.install(context, downloaded, crashRecovery) }
        } else {
            installAfterPermission = downloaded
            val permissionIntent =
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri(),
                )
            installerError =
                runCatching {
                    check(permissionIntent.resolveActivity(context.packageManager) != null)
                    installPermissionLauncher.launch(permissionIntent)
                }.isFailure
        }
    }

    LaunchedEffect(state) {
        val downloaded = state as? AppUpdateState.Downloaded ?: return@LaunchedEffect
        if (!autoInstallHandled && !downloaded.installing) {
            autoInstallHandled = true
            install(downloaded)
        }
    }

    val current = state
    val release = current.release
    val installing = current is AppUpdateState.Downloading || (current as? AppUpdateState.Downloaded)?.installing == true
    val title =
        when {
            current is AppUpdateState.Error -> {
                when (current.failure) {
                    UpdateFailure.NO_APK -> {
                        stringResource(
                            if (crashRecovery) Res.string.crash_updates_release_unavailable else Res.string.updates_release_unavailable,
                        )
                    }

                    UpdateFailure.DIGEST -> {
                        stringResource(
                            if (crashRecovery) Res.string.crash_updates_digest_failed else Res.string.updates_digest_failed,
                        )
                    }

                    UpdateFailure.DOWNLOAD -> {
                        stringResource(
                            if (crashRecovery) Res.string.crash_updates_download_failed else Res.string.updates_download_failed,
                        )
                    }

                    UpdateFailure.INSTALL -> {
                        stringResource(
                            if (crashRecovery) Res.string.crash_updates_installer_failed else Res.string.updates_installer_failed,
                        )
                    }

                    UpdateFailure.CHECK -> {
                        stringResource(if (crashRecovery) Res.string.crash_updates_error else Res.string.updates_error)
                    }
                }
            }

            installing -> {
                stringResource(if (crashRecovery) Res.string.crash_updates_installing_title else Res.string.updates_installing_title)
            }

            release != null -> {
                stringResource(if (crashRecovery) Res.string.crash_updates_pending_title else Res.string.updates_pending_title)
            }

            current is AppUpdateState.Checking -> {
                stringResource(if (crashRecovery) Res.string.crash_updates_checking else Res.string.updates_checking)
            }

            else -> {
                stringResource(if (crashRecovery) Res.string.crash_updates_no_updates else Res.string.updates_no_updates)
            }
        }
    val miniPlayerInset = LocalMiniPlayerBottomInset.current

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (!LocalSettingsPageHasTopBar.current) {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack, shapes = IconButtonDefaults.shapes()) {
                            Icon(
                                MetrolistIcons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(if (crashRecovery) Res.string.crash_back else Res.string.back),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        },
        bottomBar = {
            val buttonModifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp + miniPlayerInset)
                    .heightIn(min = 56.dp)
            when {
                current is AppUpdateState.Downloading -> {
                    OutlinedButton(
                        onClick = AppUpdateManager::cancelDownload,
                        modifier = buttonModifier,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(if (crashRecovery) Res.string.crash_cancel else Res.string.cancel))
                    }
                }

                current is AppUpdateState.Downloaded -> {
                    Button(
                        enabled = !current.installing,
                        onClick = { install(current) },
                        modifier = buttonModifier,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(if (crashRecovery) Res.string.crash_updates_install else Res.string.updates_install))
                    }
                }

                current is AppUpdateState.Available -> {
                    Button(
                        onClick = { scope.launch { AppUpdateManager.download(context, current.release, crashRecovery) } },
                        modifier = buttonModifier,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(if (crashRecovery) Res.string.crash_action_download else Res.string.action_download))
                    }
                }

                current is AppUpdateState.Error -> {
                    Button(
                        onClick = {
                            scope.launch {
                                if (release == null || current.failure == UpdateFailure.CHECK) {
                                    AppUpdateManager.check(context, crashRecovery = crashRecovery)
                                } else {
                                    AppUpdateManager.download(context, release, crashRecovery)
                                }
                            }
                        },
                        modifier = buttonModifier,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(if (crashRecovery) Res.string.crash_updates_retry else Res.string.updates_retry))
                    }
                }

                else -> {
                    Button(
                        enabled = current !is AppUpdateState.Checking,
                        onClick = { scope.launch { AppUpdateManager.check(context, crashRecovery = crashRecovery) } },
                        modifier = buttonModifier,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(if (crashRecovery) Res.string.crash_updates_check else Res.string.updates_check))
                    }
                }
            }
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineLarge)

            if (installing || current is AppUpdateState.Checking) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (current is AppUpdateState.Downloading) {
                        LinearWavyProgressIndicator(progress = { current.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (installing) {
                        Text(
                            text =
                                stringResource(
                                    if (crashRecovery) {
                                        Res.string.crash_updates_installing_description
                                    } else {
                                        Res.string.updates_installing_description
                                    },
                                ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            if (installerError) {
                Text(
                    text =
                        stringResource(
                            if (crashRecovery) Res.string.crash_updates_installer_failed else Res.string.updates_installer_failed,
                        ),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (release == null) {
                UpdateInfo(
                    version = AppUpdateManager.currentVersion,
                    versionLabel = stringResource(if (crashRecovery) Res.string.crash_updates_version else Res.string.updates_version),
                    details =
                        listOf(
                            stringResource(if (crashRecovery) Res.string.crash_updates_last_check else Res.string.updates_last_check) to
                                (
                                    if (crashRecovery) {
                                        (state as? AppUpdateState.UpToDate)?.checkedAt ?: 0L
                                    } else {
                                        AppSettings.shared.lastUpdateCheckTimestamp
                                    }
                                ).takeIf { it > 0 }
                                    ?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
                                    .orEmpty(),
                        ),
                )
            } else {
                UpdateInfo(
                    version = release.version,
                    versionLabel = stringResource(if (crashRecovery) Res.string.crash_updates_version else Res.string.updates_version),
                    details =
                        listOf(
                            stringResource(if (crashRecovery) Res.string.crash_updates_size else Res.string.updates_size) to
                                release.apk
                                    ?.size
                                    ?.let(fileSizeFormatter)
                                    .orEmpty(),
                            stringResource(if (crashRecovery) Res.string.crash_updates_date else Res.string.updates_date) to
                                release.publishedAt?.substringBefore('T').orEmpty(),
                        ),
                )
                Text(
                    stringResource(if (crashRecovery) Res.string.crash_updates_changelog else Res.string.updates_changelog),
                    style = MaterialTheme.typography.headlineLarge,
                )
                GitHubMarkdown(release.body.orEmpty())
            }
        }
    }
}

// Per-app notification settings only exist from Android 8; older versions manage them on the app details page.
private fun appNotificationSettingsIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
    }
