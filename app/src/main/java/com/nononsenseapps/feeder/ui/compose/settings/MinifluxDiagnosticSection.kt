package com.nononsenseapps.feeder.ui.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.miniflux.MinifluxError
import com.nononsenseapps.feeder.miniflux.MinifluxSettingsController
import com.nononsenseapps.feeder.miniflux.MinifluxStep
import com.nononsenseapps.feeder.miniflux.MinifluxSupport
import com.nononsenseapps.feeder.ui.compose.theme.LocalDimens

@Composable
fun MinifluxDiagnosticSection(
    controller: MinifluxSettingsController,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    var baseUrl by remember(state.baseUrl) { mutableStateOf(state.baseUrl) }
    // Never saveable, in SavedStateHandle, or restored from the credential store.
    var token by remember { mutableStateOf("") }
    SettingsGroup(title = R.string.miniflux_experimental, modifier = modifier) {
        Column(Modifier.width(LocalDimens.current.maxContentWidth).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.miniflux_description))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                enabled = !state.busy,
                label = { Text(stringResource(R.string.miniflux_base_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.miniflux_http_note))
            Text(stringResource(if (state.saved) R.string.miniflux_token_saved else R.string.miniflux_token_not_saved))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                enabled = !state.busy,
                label = { Text(stringResource(R.string.miniflux_api_token)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(enabled = !state.busy, onClick = {
                val submitted = token
                token = ""
                controller.save(baseUrl, submitted)
            }) { Text(stringResource(R.string.miniflux_save_token)) }
            TextButton(enabled = state.saved && !state.busy, onClick = {
                token = ""
                controller.clear()
            }) {
                Text(stringResource(R.string.miniflux_delete_token))
            }
            Button(enabled = !state.busy, onClick = {
                token = ""
                controller.check(baseUrl)
            }) {
                Text(stringResource(R.string.miniflux_check))
            }
            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                TextButton(onClick = controller::cancel) { Text(stringResource(android.R.string.cancel)) }
            }
            state.error?.let { Text(stringResource(it.resource)) }
            state.result?.let { result ->
                Text(stringResource(if (result.account is MinifluxStep.Success) R.string.miniflux_connected else R.string.miniflux_connection_failed))
                StepText(stringResource(R.string.miniflux_account), result.account) { "${it.username} (ID ${it.id})" }
                StepText(stringResource(R.string.miniflux_feeds), result.feeds) { it.toString() }
                StepText(stringResource(R.string.miniflux_categories), result.categories) { it.toString() }
                StepText(stringResource(R.string.miniflux_entries), result.entries) { it.toString() }
                StepText(stringResource(R.string.miniflux_version), result.version) { it }
                Text(stringResource(R.string.miniflux_capability_note))
                val caps = result.capabilities
                CapabilityText("changed_after", caps.changedAfter)
                CapabilityText("read/unread", caps.readUnread)
                CapabilityText("starred", caps.starredState)
                CapabilityText("fetch-content", caps.originalContent)
            }
        }
    }
}

@Composable
private fun <T> StepText(
    label: String,
    step: MinifluxStep<T>,
    format: (T) -> String,
) {
    val value =
        when (step) {
            is MinifluxStep.Success -> format(step.value)
            is MinifluxStep.Failed -> stringResource(step.error.resource)
            MinifluxStep.NotChecked -> stringResource(R.string.miniflux_unknown)
        }
    Text("$label: $value")
}

@Composable
private fun CapabilityText(
    label: String,
    support: MinifluxSupport,
) {
    val value =
        stringResource(
            when (support) {
                MinifluxSupport.VERSION_EXPECTED -> R.string.miniflux_version_expected
                MinifluxSupport.VERSION_TOO_OLD -> R.string.miniflux_version_too_old
                MinifluxSupport.UNKNOWN -> R.string.miniflux_unknown
            },
        )
    Text("$label: $value")
}

private val MinifluxError.resource: Int
    get() =
        when (this) {
            MinifluxError.MISSING_URL -> R.string.miniflux_missing_url
            MinifluxError.INVALID_URL -> R.string.miniflux_invalid_url
            MinifluxError.MISSING_TOKEN -> R.string.miniflux_missing_token
            MinifluxError.INVALID_TOKEN -> R.string.miniflux_invalid_token
            MinifluxError.CREDENTIAL_UNAVAILABLE -> R.string.miniflux_credential_error
            MinifluxError.CONNECTION -> R.string.miniflux_network_error
            MinifluxError.TIMEOUT -> R.string.miniflux_timeout
            MinifluxError.TLS -> R.string.miniflux_tls_error
            MinifluxError.UNAUTHORIZED -> R.string.miniflux_unauthorized
            MinifluxError.FORBIDDEN -> R.string.miniflux_forbidden
            MinifluxError.NOT_FOUND -> R.string.miniflux_not_found
            MinifluxError.SERVER -> R.string.miniflux_server_error
            MinifluxError.HTTP -> R.string.miniflux_http_error
            MinifluxError.REDIRECT -> R.string.miniflux_redirect_error
            MinifluxError.INVALID_RESPONSE -> R.string.miniflux_json_error
            MinifluxError.RESPONSE_TOO_LARGE -> R.string.miniflux_response_large
        }
