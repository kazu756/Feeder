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
import com.nononsenseapps.feeder.BuildConfig
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
    if (preview) {
        state.summary?.let {
            MinifluxCachePreview(
                controller.store,
                it.profile,
                onClose = { preview = false },
                mutations = controller.readMutations,
                starMutations = controller.starMutations,
            )
        }
    }
    Column(modifier) {
        Button(enabled = enabled && !state.busy, onClick = { controller.syncAll(baseUrl) }) {
            Text(stringResource(R.string.miniflux_unified_sync))
        }
        Text(stringResource(R.string.miniflux_unified_scope))
        state.unifiedResult?.let { UnifiedResult(it) }
        Text(stringResource(R.string.miniflux_cache_scope))
        Button(enabled = enabled && !state.busy, onClick = { controller.syncCache(baseUrl) }) {
            Text(stringResource(R.string.miniflux_cache_sync))
        }
        Button(enabled = enabled && !state.busy && state.summary != null, onClick = { controller.syncCache(baseUrl, readStates = true) }) {
            Text(stringResource(R.string.miniflux_read_sync))
        }
        state.remaining?.let { Text(stringResource(R.string.miniflux_read_remaining, it)) }
        Button(enabled = enabled && !state.busy && state.summary != null, onClick = { controller.syncCache(baseUrl, starStates = true) }) {
            Text(stringResource(R.string.miniflux_star_sync))
        }
        state.starResult?.let { result ->
            Text(stringResource(R.string.miniflux_star_capability, result.capability.support.name, result.capability.reason.name))
            Text(stringResource(R.string.miniflux_star_result, result.requested, result.confirmed, result.failed, result.pending, result.skipped))
            if (!result.writeEnabled) Text(stringResource(R.string.miniflux_star_disabled))
        }
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
            if (state.unifiedResult != null) {
                Text(
                    stringResource(
                        when (state.stage) {
                            "read" -> R.string.miniflux_unified_stage_read
                            "star" -> R.string.miniflux_unified_stage_star
                            "cache" -> R.string.miniflux_unified_stage_cache
                            "reconciliation" -> R.string.miniflux_unified_stage_deletion
                            else -> R.string.miniflux_unified_stage_account
                        },
                    ),
                )
            } else {
                Text("${state.stage}: ${state.count}")
            }
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

@Composable
private fun UnifiedResult(result: MinifluxUnifiedSyncResult) {
    Column {
        val overall =
            when (result.overall) {
                MinifluxUnifiedOverall.RUNNING -> R.string.miniflux_unified_running
                MinifluxUnifiedOverall.SUCCESS -> R.string.miniflux_unified_success
                MinifluxUnifiedOverall.PARTIAL_SUCCESS -> R.string.miniflux_unified_partial
                MinifluxUnifiedOverall.NOTHING_TO_DO -> R.string.miniflux_unified_nothing
                MinifluxUnifiedOverall.FAILED -> R.string.miniflux_unified_failed
                MinifluxUnifiedOverall.CANCELLED -> R.string.miniflux_unified_cancelled
            }
        Text(stringResource(overall))
        Text(stringResource(R.string.miniflux_unified_read, result.read.value?.requested ?: 0, result.read.value?.confirmed ?: 0, result.readPending) + " · " + unifiedStepText(result.read.state))
        Text(stringResource(R.string.miniflux_unified_star, result.star.value?.requested ?: 0, result.star.value?.confirmed ?: 0, result.starPending) + " · " + unifiedStepText(result.star.state))
        Text(
            stringResource(R.string.miniflux_unified_cache, result.incremental.value?.fetched ?: 0, result.incremental.value?.inserted ?: 0, result.incremental.value?.updated ?: 0) + " · " +
                unifiedStepText(result.incremental.state),
        )
        val deletion = result.reconciliation.value
        Text(
            stringResource(R.string.miniflux_unified_deletion, deletion?.missingFeeds ?: 0, (deletion?.removedEntries ?: 0) + (deletion?.confirmedMissingEntries ?: 0)) + " · " +
                unifiedStepText(result.reconciliation.state),
        )
        if (deletion?.feeds?.isNotEmpty() == true || deletion?.entries?.isNotEmpty() == true) Text(stringResource(R.string.miniflux_unified_candidates))
        val errors = listOfNotNull(result.errorType, result.read.errorType, result.star.errorType, result.incremental.errorType, result.reconciliation.errorType).distinct()
        if (errors.isNotEmpty()) Text(errors.joinToString(" · "))
        if (BuildConfig.DEBUG) {
            result.timeoutSource?.let { Text("Timeout source: $it") }
            result.reconciliationTrace?.let {
                Text("Reconciliation: ${it.phase} · ${it.elapsedMillis} ms · requests ${it.requestsStarted} · pages ${it.pages} · last ${it.lastRequestPhase ?: "—"}")
                Text(
                    "${it.mode} · read IDs ${it.readIdsTotal ?: "—"}/${it.readIdsPages} pages · unread IDs ${it.unreadIdsTotal ?: "—"}/${it.unreadIdsPages} pages · individual GET ${it.individualGets}",
                )
            }
        }
    }
}

@Composable
private fun unifiedStepText(state: MinifluxUnifiedStepState): String =
    stringResource(
        when (state) {
            MinifluxUnifiedStepState.NOT_RUN -> R.string.miniflux_unified_not_run
            MinifluxUnifiedStepState.RUNNING -> R.string.miniflux_unified_running
            MinifluxUnifiedStepState.SUCCESS -> R.string.miniflux_unified_success
            MinifluxUnifiedStepState.PARTIAL -> R.string.miniflux_unified_partial
            MinifluxUnifiedStepState.SKIPPED -> R.string.miniflux_unified_skipped
            MinifluxUnifiedStepState.FAILED -> R.string.miniflux_unified_failed
            MinifluxUnifiedStepState.CANCELLED -> R.string.miniflux_unified_cancelled
        },
    )
