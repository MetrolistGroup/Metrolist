@file:Suppress("ktlint:standard:function-naming")

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.shared.backup.BackupValidationException
import com.metrolist.shared.backup.BackupValidationFailure
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.backup_create_failed
import com.metrolist.shared.generated.resources.backup_create_success
import com.metrolist.shared.generated.resources.backup_invalid_data
import com.metrolist.shared.generated.resources.backup_restore_busy
import com.metrolist.shared.generated.resources.backup_restore_export_desc
import com.metrolist.shared.generated.resources.backup_restore_import_desc
import com.metrolist.shared.generated.resources.backup_sensitive_message
import com.metrolist.shared.generated.resources.backup_sensitive_title
import com.metrolist.shared.generated.resources.backup_too_large
import com.metrolist.shared.generated.resources.backup_unsupported_database
import com.metrolist.shared.generated.resources.backup_unsupported_format
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.create_backup
import com.metrolist.shared.generated.resources.create_backup_countdown
import com.metrolist.shared.generated.resources.restore_backup
import com.metrolist.shared.generated.resources.restore_confirm_message
import com.metrolist.shared.generated.resources.restore_confirm_title
import com.metrolist.shared.generated.resources.restore_failed
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.PopupVisibility
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

enum class BackupRestoreStatus {
    BUSY,
    BACKUP_CREATED,
    BACKUP_FAILED,
    RESTORE_FAILED,
}

@Composable
fun BackupRestorePage(
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    status: BackupRestoreStatus? = null,
    failure: Throwable? = null,
) {
    var showBackupWarning by remember { mutableStateOf(false) }
    var showRestoreWarning by remember { mutableStateOf(false) }
    var warningSeconds by remember { mutableIntStateOf(5) }

    LaunchedEffect(showBackupWarning) {
        if (showBackupWarning) {
            warningSeconds = 5
            while (warningSeconds > 0) {
                delay(1_000L)
                warningSeconds--
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(Res.string.backup_sensitive_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(Res.string.backup_sensitive_message), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(Res.string.backup_restore_export_desc), style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = { showBackupWarning = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = status != BackupRestoreStatus.BUSY,
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(stringResource(Res.string.create_backup))
        }
        Text(stringResource(Res.string.backup_restore_import_desc), style = MaterialTheme.typography.bodyMedium)
        status?.let {
            if (it == BackupRestoreStatus.BUSY) {
                LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            }
            val message =
                when (it) {
                    BackupRestoreStatus.BUSY -> {
                        stringResource(Res.string.backup_restore_busy)
                    }

                    BackupRestoreStatus.BACKUP_CREATED -> {
                        stringResource(Res.string.backup_create_success)
                    }

                    BackupRestoreStatus.BACKUP_FAILED -> {
                        stringResource(Res.string.backup_create_failed)
                    }

                    BackupRestoreStatus.RESTORE_FAILED -> {
                        stringResource(
                            when ((failure as? BackupValidationException)?.reason) {
                                BackupValidationFailure.UNSUPPORTED_FORMAT -> Res.string.backup_unsupported_format
                                BackupValidationFailure.UNSUPPORTED_DATABASE -> Res.string.backup_unsupported_database
                                BackupValidationFailure.TOO_LARGE -> Res.string.backup_too_large
                                BackupValidationFailure.INVALID_DATA -> Res.string.backup_invalid_data
                                null -> Res.string.restore_failed
                            },
                        )
                    }
                }
            Text(
                message,
                color =
                    when (it) {
                        BackupRestoreStatus.BUSY -> MaterialTheme.colorScheme.onSurfaceVariant
                        BackupRestoreStatus.BACKUP_CREATED -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.error
                    },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(
            onClick = { showRestoreWarning = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = status != BackupRestoreStatus.BUSY,
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(stringResource(Res.string.restore_backup))
        }
    }

    PopupVisibility(visible = showRestoreWarning) {
        AlertDialog(
            onDismissRequest = { showRestoreWarning = false },
            title = { Text(stringResource(Res.string.restore_confirm_title)) },
            text = { Text(stringResource(Res.string.restore_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreWarning = false
                        onImport()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.restore_backup)) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreWarning = false }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    PopupVisibility(visible = showBackupWarning) {
        AlertDialog(
            onDismissRequest = { showBackupWarning = false },
            title = { Text(stringResource(Res.string.backup_sensitive_title)) },
            text = {
                Text(
                    stringResource(Res.string.backup_sensitive_message),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            confirmButton = {
                Button(
                    enabled = warningSeconds == 0,
                    onClick = {
                        showBackupWarning = false
                        onExport()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(
                        if (warningSeconds == 0) {
                            stringResource(Res.string.create_backup)
                        } else {
                            stringResource(Res.string.create_backup_countdown, warningSeconds)
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupWarning = false }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}
