package com.nononsenseapps.feeder.miniflux

import androidx.room.withTransaction
import com.nononsenseapps.feeder.blob.blobFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

internal data class MinifluxCleanupPlan(
    val profile: MinifluxProfile,
    val feeds: List<MinifluxDeletionFeed>,
) {
    val entries get() = feeds.sumOf { it.entries }
    val blobs get() = feeds.sumOf { it.blobs }
}

internal fun MinifluxDeletionResult.cleanupPlan(profile: MinifluxProfile): MinifluxCleanupPlan? {
    if (!complete || errorType != null || profileId != profile.id || profile.lastSuccessfulSync == null || profile.bootstrapChangedAt != null) return null
    val eligible =
        feeds.filter {
            it.reason == MinifluxDeletionReason.REMOTE_FEED_MISSING &&
                it.confidence == MinifluxDeletionConfidence.HIGH &&
                it.pending == 0 &&
                it.starred == 0 &&
                it.remoteFeedId > 0 &&
                it.localFeedId > 0 &&
                it.entries >= 0 &&
                it.blobs >= 0
        }
    if (eligible.isEmpty() || eligible.map { it.remoteFeedId }.distinct().size != eligible.size) return null
    return MinifluxCleanupPlan(profile, eligible.toList())
}

data class MinifluxCleanupResult(
    val complete: Boolean = false,
    val feeds: Int = 0,
    val entries: Int = 0,
    val blobs: Int = 0,
    val blobsFailed: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val errorType: String? = null,
)

/** Only called after explicit confirmation. No outbox delete or remote mutation API. */
internal class MinifluxFeedCleanup(
    private val api: MinifluxApiClient,
    private val store: MinifluxCacheStore,
    private val deleteBlob: (File) -> Boolean = { it.delete() },
) {
    suspend fun run(
        plan: MinifluxCleanupPlan,
        token: String,
        progress: (MinifluxCleanupResult) -> Unit = {},
    ): MinifluxCleanupResult {
        var result = MinifluxCleanupResult()
        val caller = currentCoroutineContext()
        try {
            val profile = plan.profile
            val base = normalizedMinifluxUrl(profile.baseUrl)
            val database = store.database(profile.id)
            val dao = database.cacheDao()
            val current = dao.profile()
            require(store.selectedProfile()?.id == profile.id)
            require(current?.id == profile.id && current.baseUrl == profile.baseUrl && current.userId == profile.userId && current.lastSuccessfulSync != null && current.bootstrapChangedAt == null)
            if (api.me(base, token).id != profile.userId) throw MinifluxFailure(MinifluxError.FORBIDDEN)

            // Two complete inventories must agree before the first local deletion.
            fun validated(feeds: List<MinifluxFeed>): Set<Long> {
                require(feeds.all { it.id > 0 } && feeds.map { it.id }.distinct().size == feeds.size)
                return feeds.map { it.id }.toSet()
            }
            val remote = validated(api.feeds(base, token))
            require(remote == validated(api.feeds(base, token)))
            val targets = plan.feeds.toList()
            for (target in targets) {
                caller.ensureActive()
                if (target.reason != MinifluxDeletionReason.REMOTE_FEED_MISSING ||
                    target.confidence != MinifluxDeletionConfidence.HIGH ||
                    target.pending != 0 ||
                    target.starred != 0 ||
                    target.remoteFeedId in remote
                ) {
                    result = result.copy(skipped = result.skipped + 1)
                    progress(result)
                    continue
                }
                // Preserve accounting across the commit boundary. Cancellation is explicitly
                // checked inside the transaction; after commit only blob/accounting work remains.
                withContext(NonCancellable) {
                    try {
                        val committed =
                            database.withTransaction {
                                caller.ensureActive()
                                val latest = dao.profile()
                                require(latest?.id == profile.id && latest.lastSuccessfulSync != null && latest.bootstrapChangedAt == null)
                                val identity = dao.feedIdentity(profile.id, target.remoteFeedId)
                                if (identity?.localFeedId != target.localFeedId || dao.feed(target.localFeedId) == null) return@withTransaction null
                                val entries = dao.cleanupEntries(profile.id, target.remoteFeedId)
                                val pending = dao.cleanupPendingCount(profile.id, target.remoteFeedId)
                                val items = dao.cleanupItems(target.localFeedId)
                                // Unexpected unmapped rows must never be swept up by Feed cascade.
                                if (items.map { it.id }.toSet() != entries.map { it.localFeedItemId }.toSet() ||
                                    entries.size != target.entries ||
                                    pending != 0 ||
                                    entries.any { it.remoteStarred } ||
                                    items.any { it.bookmarked }
                                ) {
                                    return@withTransaction null
                                }
                                val files = entries.map { blobFile(it.localFeedItemId, store.articleDirectory(profile.id)) }.filter { it.isFile }
                                if (files.size != target.blobs) return@withTransaction null
                                caller.ensureActive()
                                // Entry identity -> Feed identity -> Feed. FeedItem uses CASCADE.
                                // Outbox NO ACTION is an additional guard; no outbox row is deleted.
                                dao.cleanupEntryIdentities(profile.id, target.remoteFeedId)
                                check(dao.cleanupFeedIdentity(profile.id, target.remoteFeedId, target.localFeedId) == 1)
                                check(dao.cleanupFeed(target.localFeedId) == 1)
                                caller.ensureActive()
                                entries.size to files
                            }
                        if (committed == null) {
                            result = result.copy(skipped = result.skipped + 1)
                        } else {
                            result = result.copy(feeds = result.feeds + 1, entries = result.entries + committed.first)
                            // DB is already authoritative. A failed/unprocessed file remains an
                            // orphan at its profile-scoped numeric path for future orphan cleanup.
                            for (file in committed.second) {
                                val deleted =
                                    caller.isActive &&
                                        try {
                                            deleteBlob(file)
                                        } catch (_: Exception) {
                                            false
                                        }
                                result = if (deleted) result.copy(blobs = result.blobs + 1) else result.copy(blobsFailed = result.blobsFailed + 1)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        result = result.copy(failed = result.failed + 1)
                    }
                    progress(result)
                }
            }
            caller.ensureActive()
            return result.copy(complete = true).also(progress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return result.copy(errorType = (e as? MinifluxFailure)?.error?.name ?: "INVALID_RESPONSE").also(progress)
        }
    }
}
