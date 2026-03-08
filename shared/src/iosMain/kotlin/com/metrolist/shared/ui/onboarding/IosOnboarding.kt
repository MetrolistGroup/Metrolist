@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.app_name
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.finish
import com.metrolist.shared.generated.resources.log_in
import com.metrolist.shared.generated.resources.logo
import com.metrolist.shared.generated.resources.no
import com.metrolist.shared.generated.resources.onboarding_begin_setup
import com.metrolist.shared.generated.resources.onboarding_crash_reports_description
import com.metrolist.shared.generated.resources.onboarding_crash_reports_title
import com.metrolist.shared.generated.resources.onboarding_finish_description
import com.metrolist.shared.generated.resources.onboarding_finish_title
import com.metrolist.shared.generated.resources.onboarding_sentry_diagnostics
import com.metrolist.shared.generated.resources.onboarding_sentry_opt_in
import com.metrolist.shared.generated.resources.onboarding_sentry_private
import com.metrolist.shared.generated.resources.onboarding_settings_hint
import com.metrolist.shared.generated.resources.onboarding_welcome_privacy_description
import com.metrolist.shared.generated.resources.privacy_policy
import com.metrolist.shared.generated.resources.restore_from_backup
import com.metrolist.shared.generated.resources.side_menu_welcome
import com.metrolist.shared.generated.resources.yes
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun IosOnboarding(onComplete: (sendCrashReports: Boolean, login: Boolean, restore: Boolean) -> Unit) {
    val pages = listOf(OnboardingPage.WELCOME, OnboardingPage.CRASH_REPORTS, OnboardingPage.FINISH)
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    var consent by rememberSaveable { mutableStateOf(false) }
    val page = pages[pageIndex]

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        OnboardingContent(
            content =
                OnboardingPageContent(
                    page = page,
                    title =
                        stringResource(
                            when (page) {
                                OnboardingPage.WELCOME -> Res.string.side_menu_welcome
                                OnboardingPage.CRASH_REPORTS -> Res.string.onboarding_crash_reports_title
                                else -> Res.string.onboarding_finish_title
                            },
                        ),
                    description =
                        stringResource(
                            when (page) {
                                OnboardingPage.WELCOME -> Res.string.onboarding_welcome_privacy_description
                                OnboardingPage.CRASH_REPORTS -> Res.string.onboarding_crash_reports_description
                                else -> Res.string.onboarding_finish_description
                            },
                        ),
                    icon =
                        when (page) {
                            OnboardingPage.WELCOME -> MetrolistIcons.Outlined.MusicNote
                            OnboardingPage.CRASH_REPORTS -> MetrolistIcons.Outlined.Security
                            else -> MetrolistIcons.Filled.Check
                        },
                    step = pageIndex + 1,
                    stepCount = pages.size,
                    primaryLabel =
                        stringResource(
                            when (page) {
                                OnboardingPage.WELCOME -> Res.string.onboarding_begin_setup
                                OnboardingPage.CRASH_REPORTS -> Res.string.yes
                                else -> Res.string.finish
                            },
                        ),
                    secondaryLabel = if (page == OnboardingPage.CRASH_REPORTS) stringResource(Res.string.no) else null,
                    isWorking = false,
                    error = null,
                    backLabel = stringResource(Res.string.back),
                    onPrimary = {
                        when (page) {
                            OnboardingPage.WELCOME -> {
                                pageIndex++
                            }

                            OnboardingPage.CRASH_REPORTS -> {
                                consent = true
                                pageIndex++
                            }

                            else -> {
                                onComplete(consent, false, false)
                            }
                        }
                    },
                    onSecondary =
                        if (page == OnboardingPage.CRASH_REPORTS) {
                            {
                                consent = false
                                pageIndex++
                            }
                        } else {
                            null
                        },
                    onBack = if (pageIndex > 0) ({ pageIndex-- }) else null,
                ),
            welcomeContent = { modifier ->
                Image(
                    painterResource(Res.drawable.logo),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                    modifier = modifier,
                )
            },
            featureContent = { featurePage ->
                when (featurePage) {
                    OnboardingPage.FINISH -> {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            FilledTonalButton(
                                onClick = { onComplete(consent, true, false) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(Res.string.log_in))
                            }
                            OutlinedButton(
                                onClick = { onComplete(consent, false, true) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(Res.string.restore_from_backup))
                            }
                            Text(
                                stringResource(Res.string.onboarding_settings_hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }

                    else -> {
                    }
                }
            },
        )
    }
}
