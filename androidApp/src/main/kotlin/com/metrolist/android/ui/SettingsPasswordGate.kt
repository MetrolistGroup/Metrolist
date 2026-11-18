package com.metrolist.android.ui

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.settings_lock_cancel_button
import com.metrolist.shared.generated.resources.settings_lock_change_button
import com.metrolist.shared.generated.resources.settings_lock_change_title
import com.metrolist.shared.generated.resources.settings_lock_confirm_button
import com.metrolist.shared.generated.resources.settings_lock_confirm_password
import com.metrolist.shared.generated.resources.settings_lock_current_password
import com.metrolist.shared.generated.resources.settings_lock_empty
import com.metrolist.shared.generated.resources.settings_lock_enter_title
import com.metrolist.shared.generated.resources.settings_lock_mismatch
import com.metrolist.shared.generated.resources.settings_lock_new_password
import com.metrolist.shared.generated.resources.settings_lock_password
import com.metrolist.shared.generated.resources.settings_lock_set_title
import com.metrolist.shared.generated.resources.settings_lock_wrong
import org.jetbrains.compose.resources.stringResource
import java.security.MessageDigest
import java.security.SecureRandom

/** Stores a salted SHA-256 hash of the settings password. The password itself is never saved. */
private object SettingsPasswordStore {
    private const val PREFS = "settings_lock"
    private const val KEY_HASH = "hash"
    private const val KEY_SALT = "salt"

    // Stays true until the app process is killed, so moving between settings pages doesn't re-ask.
    @Volatile
    var unlockedThisSession = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasPassword(context: Context) = prefs(context).contains(KEY_HASH)

    private fun hash(
        password: String,
        salt: ByteArray,
    ): String =
        Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(salt + password.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )

    fun setPassword(
        context: Context,
        password: String,
    ) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(context)
            .edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, hash(password, salt))
            .apply()
    }

    fun verify(
        context: Context,
        password: String,
    ): Boolean {
        val p = prefs(context)
        val salt = p.getString(KEY_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val expected = p.getString(KEY_HASH, null) ?: return false
        return MessageDigest.isEqual(hash(password, salt).toByteArray(), expected.toByteArray())
    }
}

private enum class GateMode { ENTER, CHANGE }

/**
 * Shows [content] only after the correct password is entered.
 * First use asks to set a password; wrong password calls [onDenied] (return to the main screen).
 */
@Composable
fun SettingsPasswordGate(
    onDenied: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var unlocked by remember { mutableStateOf(SettingsPasswordStore.unlockedThisSession) }
    if (unlocked) {
        content()
        return
    }

    var hasPassword by remember { mutableStateOf(SettingsPasswordStore.hasPassword(context)) }
    var mode by rememberSaveable { mutableStateOf(GateMode.ENTER) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                !hasPassword -> {
                    SetPasswordForm(
                        title = stringResource(Res.string.settings_lock_set_title),
                        firstLabel = stringResource(Res.string.settings_lock_password),
                        onDone = { newPass ->
                            SettingsPasswordStore.setPassword(context, newPass)
                            hasPassword = true
                            SettingsPasswordStore.unlockedThisSession = true
                            unlocked = true
                        },
                        onCancel = onDenied,
                    )
                }

                mode == GateMode.CHANGE -> {
                    ChangePasswordForm(
                        onDone = { newPass ->
                            SettingsPasswordStore.setPassword(context, newPass)
                            mode = GateMode.ENTER
                        },
                        onCancel = { mode = GateMode.ENTER },
                        verifyCurrent = { SettingsPasswordStore.verify(context, it) },
                    )
                }

                else -> {
                    EnterPasswordForm(
                        onSubmit = { entered ->
                            if (SettingsPasswordStore.verify(context, entered)) {
                                SettingsPasswordStore.unlockedThisSession = true
                                unlocked = true
                            } else {
                                onDenied()
                            }
                        },
                        onCancel = onDenied,
                        onChange = { mode = GateMode.CHANGE },
                    )
                }
            }
        }
    }
}

@Composable
private fun EnterPasswordForm(
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    onChange: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    Text(stringResource(Res.string.settings_lock_enter_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(Res.string.settings_lock_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCancel) { Text(stringResource(Res.string.settings_lock_cancel_button)) }
        Button(onClick = { onSubmit(text) }) { Text(stringResource(Res.string.settings_lock_confirm_button)) }
    }
    Spacer(Modifier.height(24.dp))
    TextButton(onClick = onChange) {
        Text(stringResource(Res.string.settings_lock_change_button), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SetPasswordForm(
    title: String,
    firstLabel: String,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var first by rememberSaveable { mutableStateOf("") }
    var second by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<Boolean?>(null) } // true = empty, false = mismatch
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = first,
        onValueChange = { first = it },
        label = { Text(firstLabel) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = second,
        onValueChange = { second = it },
        label = { Text(stringResource(Res.string.settings_lock_confirm_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        isError = error != null,
        supportingText = {
            when (error) {
                true -> Text(stringResource(Res.string.settings_lock_empty))
                false -> Text(stringResource(Res.string.settings_lock_mismatch))
                null -> Unit
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCancel) { Text(stringResource(Res.string.settings_lock_cancel_button)) }
        Button(onClick = {
            when {
                first.isEmpty() -> error = true
                first != second -> error = false
                else -> onDone(first)
            }
        }) { Text(stringResource(Res.string.settings_lock_confirm_button)) }
    }
}

@Composable
private fun ChangePasswordForm(
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
    verifyCurrent: (String) -> Boolean,
) {
    var current by rememberSaveable { mutableStateOf("") }
    var first by rememberSaveable { mutableStateOf("") }
    var second by rememberSaveable { mutableStateOf("") }
    var currentWrong by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Boolean?>(null) }
    Text(stringResource(Res.string.settings_lock_change_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = current,
        onValueChange = { current = it },
        label = { Text(stringResource(Res.string.settings_lock_current_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        isError = currentWrong,
        supportingText = { if (currentWrong) Text(stringResource(Res.string.settings_lock_wrong)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = first,
        onValueChange = { first = it },
        label = { Text(stringResource(Res.string.settings_lock_new_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = second,
        onValueChange = { second = it },
        label = { Text(stringResource(Res.string.settings_lock_confirm_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        isError = error != null,
        supportingText = {
            when (error) {
                true -> Text(stringResource(Res.string.settings_lock_empty))
                false -> Text(stringResource(Res.string.settings_lock_mismatch))
                null -> Unit
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCancel) { Text(stringResource(Res.string.settings_lock_cancel_button)) }
        Button(onClick = {
            currentWrong = !verifyCurrent(current)
            when {
                currentWrong -> Unit
                first.isEmpty() -> error = true
                first != second -> error = false
                else -> onDone(first)
            }
        }) { Text(stringResource(Res.string.settings_lock_confirm_button)) }
    }
}
