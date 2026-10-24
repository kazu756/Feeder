package com.nononsenseapps.feeder.miniflux

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
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
    state.cleanupConfirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = controller::dismissCleanup,
            title = { Text(stringResource(if (confirmation.entryCleanup) R.string.miniflux_entry_cleanup_title else R.string.miniflux_cleanup_title)) },
            text = {
                Text(
                    if (confirmation.entryCleanup) {
                        stringResource(
                            R.string.miniflux_entry_cleanup_confirm,
                            confirmation.removed,
                            confirmation.missing,
                            confirmation.pendingExcluded,
                            confirmation.starredExcluded,
                            confirmation.blobs,
                        )
                    } else {
                        stringResource(R.string.miniflux_cleanup_confirm, confirmation.feeds, confirmation.entries, confirmation.blobs)
                    },
                )
            },
            confirmButton = {
                TextButton(enabled = !state.busy && !preview, onClick = { controller.confirmCleanup(baseUrl) }) {
                    Text(stringResource(if (confirmation.entryCleanup) R.string.miniflux_entry_cleanup_delete else R.string.miniflux_cleanup_delete))
                }
            },
            dismissButton = { TextButton(onClick = controller::dismissCleanup) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
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
        Text(stringResource(R.string.miniflux_deletion_warning))
        Button(enabled = enabled && !state.busy && state.summary != null, onClick = { controller.syncCache(baseUrl, deletionDryRun = true) }) {
            Text(stringResource(R.string.miniflux_deletion_check))
        }
        state.deletionResult?.let { result ->
            val entryPlan = state.summary?.profile?.let { result.entryCleanupPlan(it) }
            Button(enabled = enabled && !state.busy && !preview && entryPlan != null, onClick = { controller.requestEntryCleanup(baseUrl) }) {
                Text(stringResource(R.string.miniflux_entry_cleanup_title))
            }
            if (entryPlan == null && result.complete) Text(stringResource(R.string.miniflux_entry_cleanup_none))
            result.entryScanError?.let { Text(stringResource(R.string.miniflux_entry_scan_incomplete, it)) }
            Text(stringResource(R.string.miniflux_entry_scan_limit, result.individualGets))
            val eligible = state.summary?.profile?.let { result.cleanupPlan(it) }
            if (eligible != null) {
                Button(enabled = enabled && !state.busy && !preview, onClick = { controller.requestCleanup(baseUrl) }) {
                    Text(stringResource(R.string.miniflux_cleanup_title))
                }
            }
            if (!result.complete) {
                Text(stringResource(R.string.miniflux_deletion_incomplete, result.errorType ?: "UNKNOWN"))
            } else {
                Text(
                    stringResource(
                        R.string.miniflux_deletion_result,
                        result.missingFeeds,
                        result.removedEntries,
                        result.confirmedMissingEntries,
                        result.unverified,
                        result.pending,
                        result.starred,
                        result.feedEntries,
                    ),
                )
                Text(
                    stringResource(
                        R.string.miniflux_deletion_limit,
                        result.feeds.size,
                        result.missingFeeds,
                        result.entries.size,
                        result.removedEntries + result.confirmedMissingEntries + result.feedEntries + result.unverified,
                    ),
                )
                for (feed in result.feeds) {
                    Text(
                        "${feed.title} · Feed ${feed.remoteFeedId} / local ${feed.localFeedId}\n${feed.feedUrl}\n${feed.category}\n${feed.reason} · ${feed.confidence}\nEntries ${feed.entries} · Pending ${feed.pending} · Starred ${feed.starred} · Blobs ${feed.blobs}",
                    )
                }
                for (entry in result.entries) {
                    Text(
                        "${entry.title} · Entry ${entry.remoteEntryId}\n${entry.feedTitle}\n${entry.reason} · ${entry.confidence} · ${entry.remoteStatus ?: "?"}\nPending ${entry.protectedByPending} · Starred ${entry.protectedByStarred} · Blob ${entry.blobBytes ?: 0} B",
                    )
                }
            }
            Text(stringResource(R.string.miniflux_deletion_warning))
        }
        state.readResult?.let { result ->
            Text(stringResource(R.string.miniflux_read_result, result.requested, result.confirmed, result.pending, result.failed))
        }
        state.cleanupResult?.let { result ->
            if (state.entryCleanup) Text(stringResource(R.string.miniflux_entry_cleanup_title))
            Text(stringResource(if (result.complete) R.string.miniflux_cleanup_complete else R.string.miniflux_cleanup_incomplete))
            Text(
                stringResource(
                    if (state.entryCleanup) R.string.miniflux_entry_cleanup_result else R.string.miniflux_cleanup_result,
                    result.feeds,
                    result.entries,
                    result.blobs,
                    result.blobsFailed,
                    result.skipped,
                    result.failed,
                ),
            )
            result.errorType?.let { Text(it) }
        }
        state.errorType?.let { Text(stringResource(R.string.miniflux_read_error, it)) }
        state.cacheResult?.let { result ->
            val mode =
                stringResource(
                    when (result.mode) {
                        "initial" -> R.string.miniflux_cache_initial
                        "bootstrap" -> R.string.miniflux_cache_bootstrap
                        else -> R.string.miniflux_cache_incremental
                    },
                )
            Text(
                stringResource(
                    R.string.miniflux_cache_delta_result,
                    mode,
                    result.fetched,
                    result.inserted,
                    result.updated,
                    result.unchanged,
                    result.deduplicated,
                    result.pendingProtected,
                    result.blobsUpdated,
                    result.pages,
                ),
            )
        }
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
