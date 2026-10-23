package com.nononsenseapps.feeder.miniflux

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nononsenseapps.feeder.R
import java.time.Instant

@Composable
fun MinifluxCacheSection(
    controller: MinifluxCacheController,
    baseUrl: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf(false) }
    if (preview) state.summary?.let { MinifluxCachePreview(controller.store, it.profile, onClose = { preview = false }, mutations = controller.readMutations) }
    Column(modifier) {
        Text(stringResource(R.string.miniflux_cache_scope))
        Button(enabled = enabled && !state.busy, onClick = { controller.syncCache(baseUrl) }) {
            Text(stringResource(R.string.miniflux_cache_sync))
        }
        Button(enabled = enabled && !state.busy && state.summary != null, onClick = { controller.syncCache(baseUrl, readStates = true) }) {
            Text(stringResource(R.string.miniflux_read_sync))
        }
        state.remaining?.let { Text(stringResource(R.string.miniflux_read_remaining, it)) }
        state.readResult?.let { result ->
            Text(stringResource(R.string.miniflux_read_result, result.requested, result.confirmed, result.pending, result.failed))
        }
        state.errorType?.let { Text(stringResource(R.string.miniflux_read_error, it)) }
        if (state.busy) {
            LinearProgressIndicator()
            Text("${state.stage}: ${state.count}")
            TextButton(onClick = controller::cancel) { Text(stringResource(android.R.string.cancel)) }
        }
        if (state.failed) Text(stringResource(R.string.miniflux_cache_failed, state.stage, state.count / 100 + 1))
        if (state.completed) Text(stringResource(if (state.readStates) R.string.miniflux_read_complete else R.string.miniflux_cache_complete))
        state.summary?.let { summary ->
            TextButton(enabled = !state.busy, onClick = { preview = true }) { Text(stringResource(R.string.miniflux_cache_preview)) }
            Text("${summary.profile.baseUrl} · ${summary.profile.username} (ID ${summary.profile.userId})")
            Text(stringResource(R.string.miniflux_cache_counts, summary.feeds, summary.categories, summary.entries, summary.blobs))
            Text(stringResource(R.string.miniflux_cache_last, summary.profile.lastSuccessfulSync?.let { Instant.ofEpochMilli(it).toString() } ?: "—"))
        }
    }
}
