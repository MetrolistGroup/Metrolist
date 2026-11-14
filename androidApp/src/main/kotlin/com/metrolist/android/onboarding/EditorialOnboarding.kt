@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.metrolist.android.R
import com.metrolist.android.ui.component.Material3SettingsGroup
import com.metrolist.android.ui.component.Material3SettingsItem
import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_login
import com.metrolist.shared.generated.resources.migrated
import com.metrolist.shared.generated.resources.onboarding_account_picture
import com.metrolist.shared.generated.resources.onboarding_no_saved_accounts
import com.metrolist.shared.generated.resources.onboarding_restore_backup
import com.metrolist.shared.generated.resources.onboarding_settings_hint
import com.metrolist.shared.generated.resources.ready_to_migrate
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.onboarding.OnboardingPage
import com.metrolist.shared.ui.onboarding.OnboardingPageContent
import org.jetbrains.compose.resources.stringResource
import com.metrolist.shared.BuildConfig as SharedBuildConfig
import com.metrolist.shared.ui.onboarding.OnboardingContent as SharedOnboardingContent

@Composable
internal fun OnboardingContent(
    content: OnboardingPageContent,
    preferences: @Composable (OnboardingPage) -> Unit,
    accounts: List<OnboardingAccount>,
    onLogin: () -> Unit,
    onRestore: () -> Unit,
    previousContent: OnboardingPageContent? = null,
) {
    val isTv = LocalIsTv.current
    val preferredActionFocusRequester = remember { FocusRequester() }
    LaunchedEffect(isTv, content.page, content.isWorking) {
        if (isTv && !content.isWorking) {
            withFrameNanos { }
            preferredActionFocusRequester.requestFocus()
        }
    }
    SharedOnboardingContent(
        content = content,
        previousContent = previousContent,
        preferredActionModifier =
            if (isTv) {
                Modifier.focusRequester(preferredActionFocusRequester)
            } else {
                Modifier
            },
        welcomeContent = { modifier ->
            val iconRes =
                if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
                    R.drawable.app_icon_zenith_white_foreground
                } else {
                    R.drawable.app_icon_zenith_black_foreground
                }
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = modifier,
            )
        },
        featureContent = { page ->
            if (page == OnboardingPage.LANGUAGE_LYRICS || page == OnboardingPage.PLAYBACK) preferences(page)
            EditorialFeature(page, accounts, onLogin, onRestore)
            if (content.isWorking) {
                ContainedLoadingIndicator(Modifier.padding(top = 28.dp).size(48.dp))
            }
        },
    )
}

@Composable
private fun EditorialFeature(
    page: OnboardingPage,
    accounts: List<OnboardingAccount>,
    onLogin: () -> Unit,
    onRestore: () -> Unit,
) {
    when (page) {
        OnboardingPage.MIGRATION,
        OnboardingPage.MIGRATION_COMPLETE,
        -> {
            MigratedAccountsCard(accounts, migrated = page == OnboardingPage.MIGRATION_COMPLETE)
        }

        OnboardingPage.FINISH -> {
            FinishActions(onLogin, onRestore)
        }

        OnboardingPage.LANGUAGE_LYRICS,
        OnboardingPage.PLAYBACK,
        OnboardingPage.WELCOME,
        OnboardingPage.CRASH_REPORTS,
        OnboardingPage.NOTIFICATIONS,
        -> {
        }
    }
}

@Composable
private fun FinishActions(
    onLogin: () -> Unit,
    onRestore: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalButton(
            onClick = onLogin,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shapes = ButtonDefaults.shapes(),
        ) {
            Icon(MetrolistIcons.AutoMirrored.Filled.Login, contentDescription = null)
            Text(stringResource(Res.string.action_login), modifier = Modifier.padding(start = 10.dp))
        }
        OutlinedButton(
            onClick = onRestore,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shapes = ButtonDefaults.shapes(),
        ) {
            Icon(MetrolistIcons.Outlined.SettingsBackupRestore, contentDescription = null)
            Text(stringResource(Res.string.onboarding_restore_backup), modifier = Modifier.padding(start = 10.dp))
        }
        Text(
            stringResource(Res.string.onboarding_settings_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun MigratedAccountsCard(
    accounts: List<OnboardingAccount>,
    migrated: Boolean,
) {
    Box(Modifier.fillMaxWidth().padding(top = 32.dp)) {
        Material3SettingsGroup(
            items =
                if (accounts.isEmpty()) {
                    listOf(
                        Material3SettingsItem(
                            icon = MetrolistIcons.Filled.Info,
                            title = { Text(stringResource(Res.string.onboarding_no_saved_accounts)) },
                        ),
                    )
                } else {
                    accounts.map { account ->
                        Material3SettingsItem(
                            leadingContent = { OnboardingAccountAvatar(account, migrated) },
                            title = { Text(account.displayName, maxLines = 1) },
                            description = { Text(account.service) },
                        )
                    }
                },
            horizontalPadding = 0.dp,
        )
    }
}

@Composable
private fun OnboardingAccountAvatar(
    account: OnboardingAccount,
    migrated: Boolean,
) {
    val resolvedImage by
        produceState(account.imageUrl, account.service, account.displayName) {
            if (value.isNullOrBlank() && account.fetchGoogleImage && Settings.shared.innerTubeCookie.isNotBlank()) {
                GlobalYouTube.updateCookie(Settings.shared.innerTubeCookie)
                value = GlobalYouTubeRepository.instance.getAccountInfo()?.thumbnailUrl
                if (!value.isNullOrBlank()) Settings.shared.accountThumbnail = value.orEmpty()
            }
            if (value.isNullOrBlank() && account.fetchLastFmImage && SharedBuildConfig.LASTFM_API_KEY.isNotBlank()) {
                value =
                    LastFM
                        .getUserInfo(GlobalYouTube.client, account.displayName, SharedBuildConfig.LASTFM_API_KEY)
                        .getOrNull()
                        ?.image
                        ?.lastOrNull { it.url.isNotBlank() }
                        ?.url
            }
        }
    Box(Modifier.size(54.dp)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(50.dp).align(Alignment.Center),
        ) {
            if (resolvedImage.isNullOrBlank()) {
                Icon(account.fallbackIcon, contentDescription = null, modifier = Modifier.padding(13.dp))
            } else {
                SubcomposeAsyncImage(
                    model = resolvedImage,
                    contentDescription = stringResource(Res.string.onboarding_account_picture, account.service),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = { Icon(account.fallbackIcon, contentDescription = null, modifier = Modifier.padding(13.dp)) },
                    error = { Icon(account.fallbackIcon, contentDescription = null, modifier = Modifier.padding(13.dp)) },
                )
            }
        }
        Surface(
            shape = CircleShape,
            color = if (migrated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (migrated) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(21.dp).align(Alignment.BottomEnd),
        ) {
            Icon(
                if (migrated) MetrolistIcons.Filled.Check else MetrolistIcons.Filled.Info,
                contentDescription = stringResource(if (migrated) Res.string.migrated else Res.string.ready_to_migrate),
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}
