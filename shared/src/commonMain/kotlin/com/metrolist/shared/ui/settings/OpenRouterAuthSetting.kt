@file:Suppress("ktlint:standard:no-wildcard-imports")

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.*
import com.metrolist.shared.settings.Settings
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun OpenRouterAuthSetting() {
    val settings = Settings.shared
    val controller = remember { OpenRouterAuthController(settings) }
    val codeState = remember { TextFieldState("") }
    val code = codeState.text.toString()
    val connected = settings.lyricsOpenRouterOAuthKey.isNotBlank()
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    LaunchedEffect(controller.awaitingCode) {
        if (!controller.awaitingCode) codeState.clearText()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (controller.awaitingCode) {
                    Modifier
                } else {
                    Modifier.clickable(enabled = !controller.busy, role = Role.Button) {
                        if (connected) controller.disconnect() else controller.start()
                    }
                },
            ).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (controller.awaitingCode) {
            Text(stringResource(Res.string.openrouter_auth_instruction), style = MaterialTheme.typography.bodyMedium)
            OutlinedSecureTextField(
                state = codeState,
                label = { Text(stringResource(Res.string.openrouter_auth_code)) },
                textObfuscationMode = TextObfuscationMode.Hidden,
                enabled = !controller.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                TextButton(
                    onClick = { controller.exchange(code) },
                    enabled = !controller.busy && code.isNotBlank(),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.complete_sign_in))
                }
                TextButton(onClick = {
                    controller.cancel()
                }, enabled = !controller.busy, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(Res.string.openrouter_sign_in_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(
                            when {
                                controller.busy -> Res.string.discord_status_authorizing
                                connected -> Res.string.openrouter_signed_in_description
                                else -> Res.string.openrouter_sign_in_description
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(if (connected) Res.string.disconnect_cast else Res.string.sign_in),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        if (controller.failed) {
            Text(stringResource(Res.string.scrobble_connection_failed), color = MaterialTheme.colorScheme.error)
        }
    }
}
