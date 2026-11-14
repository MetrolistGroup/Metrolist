package com.metrolist.android.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.metrolist.android.extensions.findActivity
import com.metrolist.android.locale.AppLocale
import com.metrolist.android.locale.rememberAppLanguageChoices
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.brand_discord
import com.metrolist.shared.generated.resources.brand_google
import com.metrolist.shared.generated.resources.brand_lastfm
import com.metrolist.shared.generated.resources.continue_action
import com.metrolist.shared.generated.resources.finish
import com.metrolist.shared.generated.resources.no
import com.metrolist.shared.generated.resources.onboarding_begin_setup
import com.metrolist.shared.generated.resources.onboarding_continue_without_migration
import com.metrolist.shared.generated.resources.onboarding_crash_reports_description
import com.metrolist.shared.generated.resources.onboarding_crash_reports_title
import com.metrolist.shared.generated.resources.onboarding_finish_description
import com.metrolist.shared.generated.resources.onboarding_finish_title
import com.metrolist.shared.generated.resources.onboarding_language_lyrics_description
import com.metrolist.shared.generated.resources.onboarding_library_migrated_description
import com.metrolist.shared.generated.resources.onboarding_library_migrated_title
import com.metrolist.shared.generated.resources.onboarding_migrate_library
import com.metrolist.shared.generated.resources.onboarding_migration_complete_description
import com.metrolist.shared.generated.resources.onboarding_migration_complete_title
import com.metrolist.shared.generated.resources.onboarding_migration_description
import com.metrolist.shared.generated.resources.onboarding_migration_failed
import com.metrolist.shared.generated.resources.onboarding_migration_recovery_description
import com.metrolist.shared.generated.resources.onboarding_migration_title
import com.metrolist.shared.generated.resources.onboarding_notifications_denied
import com.metrolist.shared.generated.resources.onboarding_notifications_description
import com.metrolist.shared.generated.resources.onboarding_notifications_title
import com.metrolist.shared.generated.resources.onboarding_playback_description
import com.metrolist.shared.generated.resources.onboarding_welcome_description
import com.metrolist.shared.generated.resources.settings_localization
import com.metrolist.shared.generated.resources.settings_playback
import com.metrolist.shared.generated.resources.side_menu_welcome
import com.metrolist.shared.generated.resources.yes
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.icons.MetrolistIconsBrand
import com.metrolist.shared.ui.onboarding.OnboardingPage
import com.metrolist.shared.ui.onboarding.OnboardingPageContent
import com.metrolist.shared.ui.onboarding.OnboardingPreferences
import com.metrolist.shared.ui.settings.SettingsHostChoice
import com.metrolist.shared.ui.settings.SettingsPageId
import com.metrolist.shared.ui.settings.SettingsPlatform
import com.metrolist.shared.ui.settings.SharedSettingsHost
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun OnboardingScreen(
    migration: LegacyInstallMigration,
    onComplete: (sendCrashReports: Boolean, destination: OnboardingDestination) -> Unit,
    hasLegacyInstallationOverride: Boolean? = null,
) {
    val languages = rememberAppLanguageChoices()
    val context = LocalContext.current
    val hasLegacyInstallation =
        rememberSaveable {
            hasLegacyInstallationOverride ?: migration.hasLegacyInstallation()
        }
    var libraryStartedFresh by rememberSaveable { mutableStateOf(false) }
    val pages =
        remember(hasLegacyInstallation, libraryStartedFresh) {
            buildList {
                add(OnboardingPage.WELCOME)
                if (hasLegacyInstallation && !libraryStartedFresh) add(OnboardingPage.MIGRATION)
                if (hasLegacyInstallation && !libraryStartedFresh) add(OnboardingPage.MIGRATION_COMPLETE)
                add(OnboardingPage.CRASH_REPORTS)
                add(OnboardingPage.NOTIFICATIONS)
                add(OnboardingPage.LANGUAGE_LYRICS)
                add(OnboardingPage.PLAYBACK)
                add(OnboardingPage.FINISH)
            }
        }
    var currentPageName by rememberSaveable { mutableStateOf(OnboardingPage.WELCOME.name) }
    var sendCrashReports by rememberSaveable { mutableStateOf(false) }
    var startingFresh by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val migrationInProgress by migration.operationInProgress.collectAsState()
    val migrationResult by migration.operationResult.collectAsState()
    val working = migrationInProgress

    fun nextPage() {
        error = null
        val currentIndex = pages.indexOfFirst { it.name == currentPageName }.coerceAtLeast(0)
        if (currentIndex < pages.lastIndex) {
            currentPageName = pages[currentIndex + 1].name
        } else {
            onComplete(sendCrashReports, OnboardingDestination.HOME)
        }
    }

    fun runMigration() {
        startingFresh = false
        migration.clearResult()
        error = null
        scope.launch { migration.migrate() }
    }

    val notificationsDenied = stringResource(Res.string.onboarding_notifications_denied)
    val notificationsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Settings.shared.updateNotifications = granted
            if (granted) nextPage() else error = notificationsDenied
        }
    val safePageIndex = pages.indexOfFirst { it.name == currentPageName }.coerceAtLeast(0)
    val page = pages[safePageIndex]

    val migrationFailed = stringResource(Res.string.onboarding_migration_failed)
    LaunchedEffect(page, migrationResult, migrationInProgress) {
        val result = migrationResult ?: return@LaunchedEffect
        if (page != OnboardingPage.MIGRATION || migrationInProgress) return@LaunchedEffect
        migration.clearResult()
        result
            .onSuccess {
                if (startingFresh) {
                    libraryStartedFresh = true
                    error = null
                    currentPageName = OnboardingPage.CRASH_REPORTS.name
                } else {
                    nextPage()
                }
            }.onFailure { error = migrationFailed }
    }
    val accounts = savedAccounts()
    BackHandler(enabled = working) {}

    @Composable
    fun pageContent(index: Int): OnboardingPageContent {
        val page = pages[index]
        val pageError = error.takeIf { index == safePageIndex }
        return OnboardingPageContent(
            page = page,
            title =
                if (page == OnboardingPage.MIGRATION_COMPLETE && accounts.isEmpty()) {
                    stringResource(Res.string.onboarding_library_migrated_title)
                } else {
                    page.title()
                },
            description =
                if (page == OnboardingPage.MIGRATION && pageError != null) {
                    stringResource(Res.string.onboarding_migration_recovery_description)
                } else if (page == OnboardingPage.MIGRATION_COMPLETE && accounts.isEmpty()) {
                    stringResource(Res.string.onboarding_library_migrated_description)
                } else {
                    page.description()
                },
            icon =
                when (page) {
                    OnboardingPage.WELCOME -> MetrolistIcons.Outlined.MusicNote
                    OnboardingPage.MIGRATION -> MetrolistIcons.Outlined.LibraryMusic
                    OnboardingPage.MIGRATION_COMPLETE -> MetrolistIcons.Filled.Check
                    OnboardingPage.CRASH_REPORTS -> MetrolistIcons.Outlined.Security
                    OnboardingPage.NOTIFICATIONS -> MetrolistIcons.Outlined.NotificationsNone
                    OnboardingPage.LANGUAGE_LYRICS -> MetrolistIcons.Outlined.Language
                    OnboardingPage.PLAYBACK -> MetrolistIcons.Outlined.PlayArrow
                    OnboardingPage.FINISH -> MetrolistIcons.Filled.Check
                },
            step = index + 1,
            stepCount = pages.size,
            primaryLabel =
                when (page) {
                    OnboardingPage.WELCOME -> {
                        stringResource(Res.string.onboarding_begin_setup)
                    }

                    OnboardingPage.MIGRATION -> {
                        stringResource(Res.string.onboarding_migrate_library)
                    }

                    OnboardingPage.MIGRATION_COMPLETE -> {
                        stringResource(Res.string.continue_action)
                    }

                    OnboardingPage.CRASH_REPORTS -> {
                        stringResource(Res.string.yes)
                    }

                    OnboardingPage.NOTIFICATIONS -> {
                        stringResource(Res.string.yes)
                    }

                    OnboardingPage.LANGUAGE_LYRICS, OnboardingPage.PLAYBACK -> {
                        stringResource(Res.string.continue_action)
                    }

                    OnboardingPage.FINISH -> {
                        stringResource(Res.string.finish)
                    }
                },
            secondaryLabel =
                when (page) {
                    OnboardingPage.WELCOME -> {
                        null
                    }

                    OnboardingPage.MIGRATION -> {
                        if (pageError != null) stringResource(Res.string.onboarding_continue_without_migration) else null
                    }

                    OnboardingPage.MIGRATION_COMPLETE -> {
                        null
                    }

                    OnboardingPage.CRASH_REPORTS -> {
                        stringResource(Res.string.no)
                    }

                    OnboardingPage.NOTIFICATIONS -> {
                        stringResource(Res.string.no)
                    }

                    OnboardingPage.LANGUAGE_LYRICS, OnboardingPage.PLAYBACK -> {
                        null
                    }

                    OnboardingPage.FINISH -> {
                        null
                    }
                },
            isWorking = working,
            error = pageError,
            backLabel = stringResource(Res.string.back),
            onPrimary = {
                when (page) {
                    OnboardingPage.WELCOME -> {
                        nextPage()
                    }

                    OnboardingPage.MIGRATION -> {
                        runMigration()
                    }

                    OnboardingPage.MIGRATION_COMPLETE -> {
                        nextPage()
                    }

                    OnboardingPage.CRASH_REPORTS -> {
                        sendCrashReports = true
                        nextPage()
                    }

                    OnboardingPage.NOTIFICATIONS -> {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Settings.shared.updateNotifications = true
                            nextPage()
                        } else {
                            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    OnboardingPage.LANGUAGE_LYRICS, OnboardingPage.PLAYBACK -> {
                        nextPage()
                    }

                    OnboardingPage.FINISH -> {
                        onComplete(sendCrashReports, OnboardingDestination.HOME)
                    }
                }
            },
            onSecondary =
                when (page) {
                    OnboardingPage.WELCOME -> {
                        null
                    }

                    OnboardingPage.MIGRATION -> {
                        if (error != null) {
                            {
                                startingFresh = true
                                error = null
                                migration.clearResult()
                                scope.launch { migration.startFresh() }
                            }
                        } else {
                            null
                        }
                    }

                    OnboardingPage.MIGRATION_COMPLETE -> {
                        null
                    }

                    OnboardingPage.CRASH_REPORTS -> {
                        (
                            {
                                sendCrashReports = false
                                nextPage()
                            }
                        )
                    }

                    OnboardingPage.NOTIFICATIONS -> {
                        (
                            {
                                Settings.shared.updateNotifications = false
                                nextPage()
                            }
                        )
                    }

                    OnboardingPage.LANGUAGE_LYRICS, OnboardingPage.PLAYBACK -> {
                        null
                    }

                    OnboardingPage.FINISH -> {
                        null
                    }
                },
            onBack =
                when {
                    index > 0 -> (
                        {
                            error = null
                            currentPageName = pages[index - 1].name
                        }
                    )

                    else -> null
                },
        )
    }
    val content = pageContent(safePageIndex)
    val previousContent = if (safePageIndex > 0) pageContent(safePageIndex - 1) else null

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        OnboardingContent(
            content = content,
            previousContent = previousContent,
            preferences = { targetPage ->
                OnboardingPreferences(
                    page = targetPage,
                    host =
                        SharedSettingsHost(
                            platform = SettingsPlatform.ANDROID,
                            onClose = {},
                            availablePages = setOf(SettingsPageId.LYRICS_AI_TRANSLATION, SettingsPageId.LYRICS_ROMANIZATION),
                            hostChoices = mapOf(SettingsHostChoice.APP_LANGUAGE to languages),
                            onHostChoiceSelected = { _, selected ->
                                Settings.shared.appLanguage = selected
                                AppLocale.applySelection(context, selected)
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) context.findActivity()?.recreate()
                            },
                        ),
                )
            },
            accounts =
                if (page == OnboardingPage.MIGRATION || page == OnboardingPage.MIGRATION_COMPLETE) {
                    accounts
                } else {
                    emptyList()
                },
            onLogin = { onComplete(sendCrashReports, OnboardingDestination.LOGIN) },
            onRestore = { onComplete(sendCrashReports, OnboardingDestination.BACKUP_RESTORE) },
        )
    }
}

@Composable
private fun OnboardingPage.title(): String =
    stringResource(
        when (this) {
            OnboardingPage.WELCOME -> Res.string.side_menu_welcome
            OnboardingPage.MIGRATION -> Res.string.onboarding_migration_title
            OnboardingPage.MIGRATION_COMPLETE -> Res.string.onboarding_migration_complete_title
            OnboardingPage.CRASH_REPORTS -> Res.string.onboarding_crash_reports_title
            OnboardingPage.NOTIFICATIONS -> Res.string.onboarding_notifications_title
            OnboardingPage.LANGUAGE_LYRICS -> Res.string.settings_localization
            OnboardingPage.PLAYBACK -> Res.string.settings_playback
            OnboardingPage.FINISH -> Res.string.onboarding_finish_title
        },
    )

@Composable
private fun OnboardingPage.description(): String =
    stringResource(
        when (this) {
            OnboardingPage.WELCOME -> Res.string.onboarding_welcome_description
            OnboardingPage.MIGRATION -> Res.string.onboarding_migration_description
            OnboardingPage.MIGRATION_COMPLETE -> Res.string.onboarding_migration_complete_description
            OnboardingPage.CRASH_REPORTS -> Res.string.onboarding_crash_reports_description
            OnboardingPage.NOTIFICATIONS -> Res.string.onboarding_notifications_description
            OnboardingPage.LANGUAGE_LYRICS -> Res.string.onboarding_language_lyrics_description
            OnboardingPage.PLAYBACK -> Res.string.onboarding_playback_description
            OnboardingPage.FINISH -> Res.string.onboarding_finish_description
        },
    )

@Composable
private fun savedAccounts(): List<OnboardingAccount> {
    val google = stringResource(Res.string.brand_google)
    val lastFm = stringResource(Res.string.brand_lastfm)
    val discord = stringResource(Res.string.brand_discord)
    return buildList {
        if (
            Settings.shared.accountName.isNotBlank() ||
            Settings.shared.accountEmail.isNotBlank() ||
            Settings.shared.accountThumbnail.isNotBlank()
        ) {
            add(
                OnboardingAccount(
                    service = google,
                    displayName = Settings.shared.accountName.ifBlank { Settings.shared.accountEmail },
                    imageUrl = Settings.shared.accountThumbnail.ifBlank { null },
                    fallbackIcon = MetrolistIcons.Outlined.AccountCircle,
                    fetchGoogleImage = true,
                ),
            )
        }
        if (Settings.shared.lastFmUsername.isNotBlank() || Settings.shared.lastFmSession.isNotBlank()) {
            add(
                OnboardingAccount(
                    service = lastFm,
                    displayName = Settings.shared.lastFmUsername.ifBlank { lastFm },
                    imageUrl = null,
                    fallbackIcon = MetrolistIconsBrand.LastFm,
                    fetchLastFmImage = true,
                ),
            )
        }
        if (
            Settings.shared.discordName.isNotBlank() ||
            Settings.shared.discordUsername.isNotBlank() ||
            Settings.shared.discordAvatar.isNotBlank()
        ) {
            add(
                OnboardingAccount(
                    service = discord,
                    displayName = Settings.shared.discordName.ifBlank { Settings.shared.discordUsername },
                    imageUrl = Settings.shared.discordAvatar.ifBlank { null },
                    fallbackIcon = MetrolistIconsBrand.Discord,
                ),
            )
        }
    }
}
