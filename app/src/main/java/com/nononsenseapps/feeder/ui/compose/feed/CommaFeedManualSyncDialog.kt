package com.nononsenseapps.feeder.ui.compose.feed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.ui.compose.settings.CommaFeedReadSyncState
import com.nononsenseapps.feeder.ui.compose.settings.messageResource

@Composable
fun CommaFeedManualSyncDialog(
    prompt: Boolean,
    state: CommaFeedReadSyncState,
    busy: Boolean,
    onSubmit: (String, Boolean) -> Unit,
    onDismissPrompt: () -> Unit,
    onDismissResult: () -> Unit,
) {
    if (busy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.commafeed_sync_running)) },
            text = { LinearProgressIndicator() },
            confirmButton = {},
        )
    } else if (prompt) {
        // Deliberately not rememberSaveable: passwords must never enter saved state.
        var password by remember { mutableStateOf("") }
        var save by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = onDismissPrompt,
            title = { Text(stringResource(R.string.commafeed_manual_sync)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.commafeed_credential)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                    )
                    Row {
                        Checkbox(checked = save, onCheckedChange = { save = it })
                        Text(stringResource(R.string.commafeed_save_password))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = password.isNotEmpty() && !busy, onClick = {
                    onSubmit(password, save)
                    password = ""
                }) { Text(stringResource(R.string.commafeed_manual_sync)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissPrompt) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    } else if (state is CommaFeedReadSyncState.Success || state is CommaFeedReadSyncState.Failed) {
        AlertDialog(
            onDismissRequest = onDismissResult,
            title = { Text(stringResource(R.string.commafeed_manual_sync)) },
            text = {
                Column {
                    when (state) {
                        is CommaFeedReadSyncState.Failed -> Text(stringResource(state.error.messageResource))
                        is CommaFeedReadSyncState.Success -> {
                            val result = state.result
                            val failed = result.localFailedWrites + result.remoteFailedWrites
                            if (failed > 0 || result.remoteVerificationFailed) {
                                Text(stringResource(R.string.commafeed_manual_partial, result.localMarkedRead + result.remoteMarkedRead, failed))
                                if (result.remoteFailedWrites > 0) Text(stringResource(R.string.commafeed_sync_remote_write_error))
                                if (result.localFailedWrites > 0) Text(stringResource(R.string.commafeed_sync_local_write_error))
                                if (result.remoteVerificationFailed) Text(stringResource(R.string.commafeed_sync_verification_error))
                            } else if (result.localMarkedRead + result.remoteMarkedRead == 0) {
                                Text(stringResource(R.string.commafeed_manual_unchanged))
                            } else {
                                Text(stringResource(R.string.commafeed_manual_complete, result.localMarkedRead, result.remoteMarkedRead))
                            }
                            if (result.authenticationFailed) Text(stringResource(R.string.commafeed_authentication_failed))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissResult) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
}
