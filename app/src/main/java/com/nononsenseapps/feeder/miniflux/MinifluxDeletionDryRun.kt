package com.nononsenseapps.feeder.miniflux

import androidx.room.withTransaction
import com.nononsenseapps.feeder.blob.blobFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class MinifluxDeletionReason { REMOTE_STATUS_REMOVED, REMOTE_ENTRY_NOT_FOUND, REMOTE_FEED_MISSING, UNVERIFIED }

enum class MinifluxDeletionConfidence { CONFIRMED, HIGH, INFORMATIONAL, UNKNOWN }

data class MinifluxDeletionEntry(
    val remoteEntryId: Long,
    val localEntryId: Long,
    val title: String,
    val feedTitle: String,
    val reason: MinifluxDeletionReason,
    val confidence: MinifluxDeletionConfidence,
    val remoteStatus: String?,
    val protectedByPending: Boolean,
    val protectedByStarred: Boolean,
    val blobBytes: Long?,
)

data class MinifluxDeletionFeed(
    val remoteFeedId: Long,
    val localFeedId: Long,
    val title: String,
    val feedUrl: String,
    val category: String,
    val entries: Int,
    val pending: Int,
    val starred: Int,
    val blobs: Int,
    val reason: MinifluxDeletionReason = MinifluxDeletionReason.REMOTE_FEED_MISSING,
    val confidence: MinifluxDeletionConfidence = MinifluxDeletionConfidence.HIGH,
)

data class MinifluxDeletionResult(
    val complete: Boolean,
    val errorType: String? = null,
    val missingFeeds: Int = 0,
    val removedEntries: Int = 0,
    val confirmedMissingEntries: Int = 0,
    val unverified: Int = 0,
    val pending: Int = 0,
    val starred: Int = 0,
    val feedEntries: Int = 0,
    val pages: Int = 0,
    val feeds: List<MinifluxDeletionFeed> = emptyList(),
    val entries: List<MinifluxDeletionEntry> = emptyList(),
)

/** Read-only observations, never an authorization to remove anything. No persistent result. */
internal class MinifluxDeletionDryRun(
    private val api: MinifluxApiClient,
    private val store: MinifluxCacheStore,
) {
    suspend fun run(
        profile: MinifluxProfile,
        token: String,
    ): MinifluxDeletionResult {
        try {
            val database = store.database(profile.id)
            val dao = database.cacheDao()
            // Coherent local snapshot; release the read transaction before any network operation.
            val snapshot =
                database.withTransaction {
                    val current = dao.profile()
                    if (current?.id != profile.id || current.lastSuccessfulSync == null || current.bootstrapChangedAt != null) {
                        return@withTransaction null
                    }
                    Snapshot(
                        dao.reconciliationFeeds(),
                        dao.reconciliationLocalFeeds().associateBy {
                            it.id
                        },
                        dao.reconciliationEntries(),
                        dao.reconciliationItems().associateBy { it.id },
                        dao.pending(profile.id).map { it.remoteEntryId }.toSet(),
                    )
                } ?: return MinifluxDeletionResult(false, "BASELINE_REQUIRED")
            val base = normalizedMinifluxUrl(profile.baseUrl)
            if (api.me(base, token).id != profile.userId) throw MinifluxFailure(MinifluxError.FORBIDDEN)
            val remoteFeeds = api.feeds(base, token)
            require(remoteFeeds.all { it.id > 0 } && remoteFeeds.map { it.id }.distinct().size == remoteFeeds.size)
            val remoteFeedIds = remoteFeeds.map { it.id }.toSet()
            val localIds = snapshot.entries.map { it.remoteEntryId }.toSet()
            val observed = mutableMapOf<Long, Pair<String, Boolean>>()
            var cursor: Long? = null
            var pages = 0
            // Unfiltered listing works with legacy removed and newer physical-delete servers.
            // Absence is ALWAYS unknown, including concurrent changes and server-side filtering.
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = api.cacheEntries(base, token, beforeId = cursor)
                pages++
                require(page.entries.size <= 100 && page.total >= page.entries.size)
                if (page.entries.isEmpty()) {
                    require(page.total == 0L)
                    break
                }
                var previous = cursor ?: Long.MAX_VALUE
                for (entry in page.entries) {
                    require(entry.id > 0 && entry.id < previous && entry.feedId > 0)
                    require(entry.status in setOf("read", "unread", "removed"))
                    previous = entry.id
                    if (entry.id in localIds) {
                        require(snapshot.entriesById.getValue(entry.id).remoteFeedId == entry.feedId)
                        observed[entry.id] = entry.status to entry.starred
                    }
                }
                cursor = page.entries.last().id
            }
            // Recheck feeds: a changing inventory is incomplete, never a deletion proof.
            val endingFeeds = api.feeds(base, token)
            require(endingFeeds.all { it.id > 0 } && endingFeeds.map { it.id }.distinct().size == endingFeeds.size)
            require(endingFeeds.map { it.id }.toSet() == remoteFeedIds)
            val missing = snapshot.feeds.filter { it.remoteFeedId !in remoteFeedIds }
            val missingIds = missing.map { it.remoteFeedId }.toSet()

            fun bytes(identity: MinifluxEntryIdentity): Long? = blobFile(identity.localFeedItemId, store.articleDirectory(profile.id)).let { if (it.isFile) it.length() else null }
            val feedDetails =
                missing.take(50).map { identity ->
                    val feed = snapshot.localFeeds.getValue(identity.localFeedId)
                    val entries = snapshot.entries.filter { it.remoteFeedId == identity.remoteFeedId }
                    MinifluxDeletionFeed(
                        identity.remoteFeedId,
                        identity.localFeedId,
                        feed.title.replace(token, "[redacted]"),
                        feed.url.toString().replace(token, "[redacted]"),
                        feed.tag.replace(token, "[redacted]"),
                        entries.size,
                        entries.count {
                            it.remoteEntryId in
                                snapshot.pending
                        },
                        entries.count { it.remoteStarred || observed[it.remoteEntryId]?.second == true },
                        entries.count { bytes(it) != null },
                    )
                }
            val details = mutableListOf<MinifluxDeletionEntry>()
            var removed = 0
            var unknown = 0
            var pending = 0
            var starred = 0
            var feedEntries = 0
            for (identity in snapshot.entries) {
                currentCoroutineContext().ensureActive()
                val remote = observed[identity.remoteEntryId]
                val reason =
                    when {
                        remote?.first == "removed" -> MinifluxDeletionReason.REMOTE_STATUS_REMOVED
                        identity.remoteFeedId in missingIds -> MinifluxDeletionReason.REMOTE_FEED_MISSING
                        remote == null -> MinifluxDeletionReason.UNVERIFIED
                        else -> continue
                    }
                when (reason) {
                    MinifluxDeletionReason.REMOTE_STATUS_REMOVED -> removed++
                    MinifluxDeletionReason.REMOTE_FEED_MISSING -> feedEntries++
                    else -> unknown++
                }
                val protectPending = identity.remoteEntryId in snapshot.pending
                val protectStarred = identity.remoteStarred || remote?.second == true
                if (protectPending) pending++
                if (protectStarred) starred++
                if (details.size < 50) {
                    val item = snapshot.items.getValue(identity.localFeedItemId)
                    val feed = snapshot.localFeeds.getValue(checkNotNull(item.feedId))
                    details +=
                        MinifluxDeletionEntry(
                            identity.remoteEntryId,
                            identity.localFeedItemId,
                            item.plainTitle.replace(token, "[redacted]"),
                            feed.title.replace(token, "[redacted]"),
                            reason,
                            when (reason) {
                                MinifluxDeletionReason.REMOTE_STATUS_REMOVED -> MinifluxDeletionConfidence.CONFIRMED
                                MinifluxDeletionReason.REMOTE_FEED_MISSING -> MinifluxDeletionConfidence.HIGH
                                else -> MinifluxDeletionConfidence.UNKNOWN
                            },
                            remote?.first,
                            protectPending,
                            protectStarred,
                            bytes(identity),
                        )
                }
            }
            currentCoroutineContext().ensureActive()
            return MinifluxDeletionResult(
                true,
                missingFeeds = missing.size,
                removedEntries = removed,
                unverified = unknown,
                pending = pending,
                starred = starred,
                feedEntries = feedEntries,
                pages = pages,
                feeds = feedDetails,
                entries = details,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Discard every partial observation. Never expose response/exception text or tokens.
            return MinifluxDeletionResult(false, (e as? MinifluxFailure)?.error?.name ?: "INVALID_RESPONSE")
        }
    }

    private data class Snapshot(
        val feeds: List<MinifluxFeedIdentity>,
        val localFeeds: Map<Long, com.nononsenseapps.feeder.db.room.Feed>,
        val entries: List<MinifluxEntryIdentity>,
        val items: Map<Long, com.nononsenseapps.feeder.db.room.FeedItem>,
        val pending: Set<Long>,
    ) {
        val entriesById = entries.associateBy { it.remoteEntryId }
    }
}
