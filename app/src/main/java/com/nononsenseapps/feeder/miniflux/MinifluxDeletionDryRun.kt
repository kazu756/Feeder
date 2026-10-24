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
    val remoteFeedId: Long = 0,
    val parentRemotePresent: Boolean = false,
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
    val profileId: String? = null,
    val entryEnumerationComplete: Boolean = false,
    val entryScanError: String? = null,
    val individualGets: Int = 0,
)

data class MinifluxReconciliationTrace(
    val phase: String,
    val requestsStarted: Int,
    val pages: Int,
    val elapsedMillis: Long,
    val lastRequestPhase: String? = null,
    val lastRequest: MinifluxReconciliationRequest? = null,
    val readIdsTotal: Long? = null,
    val readIdsPages: Int = 0,
    val unreadIdsTotal: Long? = null,
    val unreadIdsPages: Int = 0,
    val mode: String = "legacy",
    val individualGets: Int = 0,
)

/** Read-only observations, never an authorization to remove anything. No persistent result. */
internal class MinifluxDeletionDryRun(
    private val api: MinifluxApiClient,
    private val store: MinifluxCacheStore,
) {
    suspend fun run(
        profile: MinifluxProfile,
        token: String,
        progress: (MinifluxReconciliationTrace) -> Unit = {},
    ): MinifluxDeletionResult {
        var pages = 0
        val diagnostics = MinifluxReconciliationDiagnostics(progress)

        fun trace(phase: String) = diagnostics.phase(phase, pages)
        trace("worker_entered")
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
                        (dao.pending(profile.id).map { it.remoteEntryId } + dao.starPending(profile.id).map { it.remoteEntryId }).toSet(),
                    )
                } ?: return MinifluxDeletionResult(false, "BASELINE_REQUIRED")
            trace("snapshot_complete")
            val base = normalizedMinifluxUrl(profile.baseUrl)
            diagnostics.start(MinifluxReconciliationRequest("me"), "me_request")
            if (api.me(base, token).id != profile.userId) throw MinifluxFailure(MinifluxError.FORBIDDEN)
            diagnostics.end(count = 1)
            diagnostics.start(MinifluxReconciliationRequest("version"), "version_request")
            val build =
                try {
                    api.version(base, token).also { diagnostics.end(count = 1) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: MinifluxFailure) {
                    if (e.error in setOf(MinifluxError.UNAUTHORIZED, MinifluxError.FORBIDDEN)) throw e
                    null
                }
            // The audited 2.3.2+ releases/known builds also postdate removal of removed status.
            // The shared build evidence is read-only here; no change to the star sender/gate.
            val idsMode = minifluxStarCapability(build).support == MinifluxStarSupport.SUPPORTED
            diagnostics.phase("mode_selected", pages, if (idsMode) "ids_tombstone" else "legacy")
            diagnostics.start(MinifluxReconciliationRequest("feeds"), "feeds_request")
            val remoteFeeds = api.feeds(base, token)
            diagnostics.end(count = remoteFeeds.size)
            require(remoteFeeds.all { it.id > 0 } && remoteFeeds.map { it.id }.distinct().size == remoteFeeds.size)
            val remoteFeedIds = remoteFeeds.map { it.id }.toSet()
            val localIds = snapshot.entries.map { it.remoteEntryId }.toSet()
            val observed = mutableMapOf<Long, Pair<String, Boolean>>()
            val allObserved = mutableSetOf<Long>()
            val remaining = localIds.toSortedSet()
            // Only cached identities affect candidates. Aim each descending page at the next
            // unresolved local ID, skipping unrelated history/new entries and gaps between IDs.
            // Crossing an ID is NOT a deletion proof: the IDs enumeration + individual GET below
            // remain mandatory for confirmed missing. Legacy removed status is still observed here.
            while (!idsMode && remaining.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val highest = remaining.last()
                val cursor = if (highest == Long.MAX_VALUE) null else highest + 1
                val limit = minOf(100, remaining.size)
                diagnostics.start(MinifluxReconciliationRequest("entries", limit = limit, cursor = cursor, page = pages + 1), "entries_request")
                val page = api.cacheEntries(base, token, beforeId = cursor, limit = limit)
                diagnostics.end(page.total, page.entries.size)
                pages++
                require(page.entries.size <= limit && page.total >= page.entries.size)
                if (page.entries.isEmpty()) {
                    require(page.total == 0L)
                    break
                }
                var previous = cursor ?: Long.MAX_VALUE
                for (entry in page.entries) {
                    require(entry.id > 0 && entry.id < previous && entry.feedId > 0)
                    require(entry.status in setOf("read", "unread", "removed"))
                    previous = entry.id
                    require(allObserved.add(entry.id))
                    if (entry.id in localIds) {
                        require(snapshot.entriesById.getValue(entry.id).remoteFeedId == entry.feedId)
                        observed[entry.id] = entry.status to entry.starred
                    }
                }
                val lowest = page.entries.last().id
                while (remaining.isNotEmpty() && remaining.last() >= lowest) remaining.remove(remaining.last())
            }
            var enumerationComplete = false
            var entryScanError: String? = null
            var individualGets = 0
            val confirmedMissing = mutableSetOf<Long>()
            var remoteStarred = emptySet<Long>()
            var knownIds: Set<Long> = allObserved
            try {
                val known =
                    enumerateMinifluxIds(
                        api,
                        base,
                        token,
                        onRequest = { status, offset, page -> diagnostics.start(MinifluxReconciliationRequest("ids", status, 10000, offset, page = page), "ids_request") },
                        onPage = { diagnostics.end(it.total, it.ids.size) },
                    ) + allObserved
                knownIds = known
                if (idsMode) {
                    remoteStarred =
                        enumerateMinifluxIdSet(
                            api,
                            base,
                            token,
                            status = null,
                            starred = true,
                            onRequest = { offset, page -> diagnostics.start(MinifluxReconciliationRequest("starred_ids", limit = 10000, offset = offset, page = page), "starred_ids_request") },
                            onPage = { diagnostics.end(it.total, it.ids.size) },
                        )
                }
                enumerationComplete = true
                val potential = snapshot.entries.filter { it.remoteFeedId in remoteFeedIds && it.remoteEntryId !in known }
                for (identity in potential.take(MINIFLUX_ENTRY_CONFIRM_LIMIT)) {
                    currentCoroutineContext().ensureActive()
                    individualGets++
                    diagnostics.start(MinifluxReconciliationRequest("individual_get", page = individualGets), "confirmation_request")
                    val entry = api.deletionEntry(base, token, identity.remoteEntryId)
                    diagnostics.end(count = if (entry == null) 0 else 1)
                    if (entry == null) {
                        confirmedMissing.add(identity.remoteEntryId)
                    } else {
                        require(entry.id == identity.remoteEntryId && entry.feedId == identity.remoteFeedId && entry.status in setOf("read", "unread", "removed"))
                        observed[entry.id] = entry.status to entry.starred
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (idsMode) throw e
                confirmedMissing.clear()
                enumerationComplete = false
                entryScanError = (e as? MinifluxFailure)?.error?.name ?: "INVALID_RESPONSE"
            }
            // Recheck feeds: a changing inventory is incomplete, never a deletion proof.
            diagnostics.start(MinifluxReconciliationRequest("feeds", page = 2), "feeds_recheck_request")
            val endingFeeds = api.feeds(base, token)
            diagnostics.end(count = endingFeeds.size)
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
                        entries.count { it.remoteStarred || observed[it.remoteEntryId]?.second == true || it.remoteEntryId in remoteStarred },
                        entries.count { bytes(it) != null },
                    )
                }
            val details = mutableListOf<MinifluxDeletionEntry>()
            var removed = 0
            var unknown = 0
            var pending = 0
            var starred = 0
            var feedEntries = 0

            fun reason(identity: MinifluxEntryIdentity): MinifluxDeletionReason? =
                when {
                    identity.remoteFeedId in missingIds -> MinifluxDeletionReason.REMOTE_FEED_MISSING
                    observed[identity.remoteEntryId]?.first == "removed" -> MinifluxDeletionReason.REMOTE_STATUS_REMOVED
                    identity.remoteEntryId in confirmedMissing -> MinifluxDeletionReason.REMOTE_ENTRY_NOT_FOUND
                    observed[identity.remoteEntryId] == null && identity.remoteEntryId !in knownIds -> MinifluxDeletionReason.UNVERIFIED
                    else -> null
                }
            for (identity in snapshot.entries.sortedBy { if (reason(it) in setOf(MinifluxDeletionReason.REMOTE_STATUS_REMOVED, MinifluxDeletionReason.REMOTE_ENTRY_NOT_FOUND)) 0 else 1 }) {
                currentCoroutineContext().ensureActive()
                val remote = observed[identity.remoteEntryId]
                val reason = reason(identity) ?: continue
                when (reason) {
                    MinifluxDeletionReason.REMOTE_STATUS_REMOVED -> removed++
                    MinifluxDeletionReason.REMOTE_FEED_MISSING -> feedEntries++
                    MinifluxDeletionReason.REMOTE_ENTRY_NOT_FOUND -> Unit
                    else -> unknown++
                }
                val protectPending = identity.remoteEntryId in snapshot.pending
                val protectStarred = identity.remoteStarred || remote?.second == true || identity.remoteEntryId in remoteStarred || snapshot.items[identity.localFeedItemId]?.bookmarked == true
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
                                MinifluxDeletionReason.REMOTE_ENTRY_NOT_FOUND -> MinifluxDeletionConfidence.CONFIRMED
                                else -> MinifluxDeletionConfidence.UNKNOWN
                            },
                            remote?.first,
                            protectPending,
                            protectStarred,
                            bytes(identity),
                            identity.remoteFeedId,
                            identity.remoteFeedId in remoteFeedIds,
                        )
                }
            }
            currentCoroutineContext().ensureActive()
            trace("complete")
            return MinifluxDeletionResult(
                true,
                missingFeeds = missing.size,
                removedEntries = removed,
                confirmedMissingEntries = confirmedMissing.size,
                unverified = unknown,
                pending = pending,
                starred = starred,
                feedEntries = feedEntries,
                pages = pages,
                feeds = feedDetails,
                entries = details,
                profileId = profile.id,
                entryEnumerationComplete = enumerationComplete,
                entryScanError = entryScanError,
                individualGets = individualGets,
            )
        } catch (e: CancellationException) {
            trace("cancelled")
            throw e
        } catch (e: Exception) {
            trace("failed")
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
