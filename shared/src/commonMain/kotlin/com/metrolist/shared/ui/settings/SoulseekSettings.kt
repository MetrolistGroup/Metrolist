/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.account
import com.metrolist.shared.generated.resources.account_logout_confirm
import com.metrolist.shared.generated.resources.action_login
import com.metrolist.shared.generated.resources.brand_soulseek
import com.metrolist.shared.generated.resources.logout
import com.metrolist.shared.generated.resources.settings_soulseek_overview
import com.metrolist.shared.generated.resources.soulseek_helper_missing
import com.metrolist.shared.generated.resources.soulseek_login_description
import com.metrolist.shared.generated.resources.soulseek_signed_in_as
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.stringResource

/** Desktop-only: Soulseek downloads run through the `metrolist-slsk` helper, which owns the stored login. */
data class SoulseekSettingsHost(
    val account: String?,
    val helperInstalled: Boolean,
    val onLogin: () -> Unit,
    val onLogout: () -> Unit,
)

@Composable
@NonRestartableComposable
internal fun SettingsUiRegistryBuilder.soulseekPage(host: SoulseekSettingsHost) {
    page(
        id = SettingsPageId.SOULSEEK,
        title = stringResource(Res.string.brand_soulseek),
        subtitle =
            settingsPageSubtitle(
                stringResource(Res.string.settings_soulseek_overview),
                integrationStatus(enabled = host.helperInstalled, account = host.account, requiresAccount = true),
            ),
        icon = MetrolistIcons.Outlined.Download,
        tone = SettingsTone.GREEN,
    ) {
        group("soulseekAccount", stringResource(Res.string.account)) {
            when {
                !host.helperInstalled -> {
                    action(
                        id = "soulseekHelperMissing",
                        title = stringResource(Res.string.action_login),
                        description = stringResource(Res.string.soulseek_helper_missing),
                        enabled = false,
                    ) {}
                }

                host.account == null -> {
                    action(
                        id = "soulseekLogin",
                        title = stringResource(Res.string.action_login),
                        description = stringResource(Res.string.soulseek_login_description),
                        onClick = host.onLogin,
                    )
                }

                else -> {
                    action(
                        id = "soulseekLogout",
                        title = stringResource(Res.string.soulseek_signed_in_as, host.account),
                        valueLabel = stringResource(Res.string.logout),
                        confirmationMessage = stringResource(Res.string.account_logout_confirm),
                        onClick = host.onLogout,
                    )
                }
            }
        }
    }
}
