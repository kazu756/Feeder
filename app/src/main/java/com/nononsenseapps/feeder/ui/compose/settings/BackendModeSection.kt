package com.nononsenseapps.feeder.ui.compose.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.BackendMode
import com.nononsenseapps.feeder.miniflux.BackendModeController
import com.nononsenseapps.feeder.miniflux.BackendModeError

@Composable
fun BackendModeSection(
    controller: BackendModeController,
    baseUrl: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val selection by controller.selection.collectAsStateWithLifecycle()
    val state by controller.state.collectAsStateWithLifecycle()
    Column(modifier) {
        Text(stringResource(R.string.backend_title))
        Text(stringResource(R.string.backend_current, backendLabel(selection.mode)))
        Text(stringResource(R.string.backend_local_description))
        Text(stringResource(R.string.backend_foundation_note))
        Column(Modifier.selectableGroup()) {
            BackendMode.entries.forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .testTag("backend_${mode.name}")
                        .selectable(
                            selected = selection.mode == mode,
                            enabled = enabled && !state.busy && state.confirmation == null,
                            role = Role.RadioButton,
                            onClick = { controller.request(mode, baseUrl) },
                        ).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selection.mode == mode, onClick = null)
                    Text(backendLabel(mode))
                }
            }
        }
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.backend_checking))
            TextButton(onClick = controller::cancel) { Text(stringResource(android.R.string.cancel)) }
        }
        state.connectionError?.let { Text(stringResource(it.resource)) }
        state.error?.let {
            Text(
                stringResource(
                    when (it) {
                        BackendModeError.PROFILE_MISMATCH -> R.string.backend_profile_mismatch
                        BackendModeError.BASELINE_REQUIRED -> R.string.backend_baseline_required
                        BackendModeError.CACHE_REQUIRED -> R.string.backend_cache_required
                        BackendModeError.CACHE_UNAVAILABLE -> R.string.backend_cache_unavailable
                        BackendModeError.STORAGE -> R.string.backend_storage_error
                        BackendModeError.BUSY -> R.string.backend_busy
                        BackendModeError.TIMEOUT -> R.string.miniflux_timeout
                        BackendModeError.CANCELLED -> R.string.backend_cancelled
                    },
                ),
            )
        }
        state.result?.let {
            Text(stringResource(R.string.backend_changed, backendLabel(it.mode)))
            if (it.readPending > 0 || it.starPending > 0) Text(stringResource(R.string.backend_pending, it.readPending, it.starPending))
        }
        state.confirmation?.let { target ->
            AlertDialog(
                onDismissRequest = controller::dismiss,
                title = { Text(stringResource(R.string.backend_confirm_title)) },
                text = { Text(stringResource(if (target == BackendMode.MINIFLUX) R.string.backend_confirm_miniflux else R.string.backend_confirm_local)) },
                confirmButton = {
                    TextButton(enabled = enabled, modifier = Modifier.testTag("backend_confirm"), onClick = controller::confirm) {
                        Text(stringResource(if (target == BackendMode.MINIFLUX) R.string.backend_use_miniflux else R.string.backend_use_local))
                    }
                },
                dismissButton = { TextButton(onClick = controller::dismiss) { Text(stringResource(android.R.string.cancel)) } },
            )
        }
    }
}

@Composable
private fun backendLabel(mode: BackendMode): String = stringResource(if (mode == BackendMode.LOCAL_RSS) R.string.backend_local else R.string.backend_miniflux)
