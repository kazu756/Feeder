package com.nononsenseapps.feeder.ui.compose.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import java.util.Locale

@Composable
fun CommaFeedDiagnosticSection(
    initialBaseUrl: String,
    initialUsername: String,
    state: CommaFeedDiagnosticState,
    onRun: (String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var baseUrl by remember { mutableStateOf(initialBaseUrl) }
    var username by remember { mutableStateOf(initialUsername) }
    // Password exists only in this composable's memory, never in preferences or saved state.
    var password by remember { mutableStateOf("") }

    SettingsGroup(title = R.string.commafeed_experimental) {
        Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.commafeed_read_only_description))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(stringResource(R.string.commafeed_base_url)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.commafeed_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.commafeed_credential)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.commafeed_credential_note), style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = {
                    onRun(baseUrl, username, password)
                    password = ""
                },
                enabled = state !is CommaFeedDiagnosticState.Running && baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
            ) {
                Text(stringResource(R.string.commafeed_run_diagnostic))
            }
            when (val current = state) {
                CommaFeedDiagnosticState.Idle -> Unit
                CommaFeedDiagnosticState.Running -> Text(stringResource(R.string.commafeed_running))
                is CommaFeedDiagnosticState.Failed -> {
                    Text(stringResource(R.string.commafeed_connection_failed), color = MaterialTheme.colorScheme.error)
                    val error =
                        when (current.error) {
                            CommaFeedDiagnosticError.CONNECTION_REFUSED -> R.string.commafeed_connection_refused
                            CommaFeedDiagnosticError.TIMEOUT -> R.string.commafeed_timeout
                            CommaFeedDiagnosticError.AUTHENTICATION -> R.string.commafeed_authentication_failed
                            CommaFeedDiagnosticError.HTTP -> R.string.commafeed_http_error
                            CommaFeedDiagnosticError.INVALID_RESPONSE -> R.string.commafeed_invalid_response
                            CommaFeedDiagnosticError.PAGINATION -> R.string.commafeed_pagination_error
                            CommaFeedDiagnosticError.INVALID_URL -> R.string.commafeed_invalid_url
                            CommaFeedDiagnosticError.PARTIAL_FAILURE -> R.string.commafeed_partial_failure
                        }
                    Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                }
                is CommaFeedDiagnosticState.Success -> {
                    val result = current.result
                    Text(stringResource(R.string.commafeed_connection_success))
                    Text(stringResource(R.string.commafeed_scope))
                    Text(stringResource(R.string.commafeed_remote_fetched, result.remoteFetched))
                    Text(stringResource(R.string.commafeed_local_considered, result.localConsidered))
                    Text(stringResource(R.string.commafeed_matched, result.matchedTotal))
                    Text(stringResource(R.string.commafeed_guid, result.matchedGuid))
                    Text(stringResource(R.string.commafeed_url, result.matchedUrl))
                    Text(stringResource(R.string.commafeed_ambiguous, result.ambiguousLocal, result.ambiguousRemote))
                    Text(stringResource(R.string.commafeed_local_only, result.unmatchedLocal))
                    Text(stringResource(R.string.commafeed_remote_only, result.unmatchedRemote))
                    Text(stringResource(R.string.commafeed_read_same, result.readSame))
                    Text(stringResource(R.string.commafeed_read_different, result.readDifferent))
                    Text(stringResource(R.string.commafeed_local_rate, String.format(Locale.getDefault(), "%.1f", result.localMatchRate)))
                    Text(stringResource(R.string.commafeed_remote_rate, String.format(Locale.getDefault(), "%.1f", result.remoteMatchRate)))
                    if (result.matchingFeeds == 0) Text(stringResource(R.string.commafeed_no_matching_feeds))
                    if (result.matchingFeeds > 0 && result.matchedTotal == 0) Text(stringResource(R.string.commafeed_no_matching_articles))
                    result.details.forEach { detail ->
                        val reason =
                            when (detail.reason) {
                                "Local only" -> R.string.commafeed_local_only_reason
                                "Remote only" -> R.string.commafeed_remote_only_reason
                                "Ambiguous" -> R.string.commafeed_ambiguous_reason
                                else -> R.string.commafeed_read_mismatch_reason
                            }
                        Text(
                            stringResource(
                                R.string.commafeed_detail,
                                detail.feedTitle,
                                detail.title,
                                stringResource(reason),
                                detail.localRead?.let { stringResource(if (it) R.string.commafeed_read else R.string.commafeed_unread) } ?: "-",
                                detail.remoteRead?.let { stringResource(if (it) R.string.commafeed_read else R.string.commafeed_unread) } ?: "-",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
