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

internal data class MinifluxEntryCleanupPlan(
    val profile: MinifluxProfile,
    val entries: List<MinifluxDeletionEntry>,
) {
    val removed get() = entries.count { it.reason == MinifluxDeletionReason.REMOTE_STATUS_REMOVED }
    val missing get() = entries.size - removed
    val blobs get() = entries.count { it.blobBytes != null }
}

internal fun MinifluxDeletionResult.entryCleanupPlan(profile: MinifluxProfile): MinifluxEntryCleanupPlan? {
    if (!complete ||
        errorType != null ||
        profileId != profile.id ||
        profile.lastSuccessfulSync == null ||
        profile.bootstrapChangedAt != null ||
        entryScanError in setOf("UNAUTHORIZED", "FORBIDDEN")
    ) {
        return null
    }
    val eligible =
        entries
            .filter {
                it.remoteEntryId > 0 &&
                    it.localEntryId > 0 &&
                    it.remoteFeedId > 0 &&
                    it.parentRemotePresent &&
                    !it.protectedByPending &&
                    !it.protectedByStarred &&
                    it.confidence in setOf(MinifluxDeletionConfidence.HIGH, MinifluxDeletionConfidence.CONFIRMED) &&
                    (it.reason == MinifluxDeletionReason.REMOTE_STATUS_REMOVED || (entryEnumerationComplete && it.reason == MinifluxDeletionReason.REMOTE_ENTRY_NOT_FOUND))
            }.take(MINIFLUX_ENTRY_CONFIRM_LIMIT)
    return if (eligible.isEmpty()) null else MinifluxEntryCleanupPlan(profile, eligible.toList())
}

/** GET-only remote revalidation; transaction deletes only this Entry, never its Feed/outbox. */
internal class MinifluxEntryCleanup(
    private val api: MinifluxApiClient,
    private val store: MinifluxCacheStore,
    private val deleteBlob: (File) -> Boolean = { it.delete() },
) {
    suspend fun run(
        plan: MinifluxEntryCleanupPlan,
        token: String,
        progress: (MinifluxCleanupResult) -> Unit = {},
    ): MinifluxCleanupResult {
        var result = MinifluxCleanupResult()
        val caller = currentCoroutineContext()
        try {
            require(
                plan.entries.size in 1..MINIFLUX_ENTRY_CONFIRM_LIMIT &&
                    plan.entries
                        .map { it.remoteEntryId }
                        .distinct()
                        .size == plan.entries.size,
            )
            val profile = plan.profile
            val database = store.database(profile.id)
            val dao = database.cacheDao()
            val current = dao.profile()
            require(
                store.selectedProfile()?.id == profile.id &&
                    current?.id == profile.id &&
                    current.baseUrl == profile.baseUrl &&
                    current.userId == profile.userId &&
                    current.lastSuccessfulSync != null &&
                    current.bootstrapChangedAt == null,
            )
            val base = normalizedMinifluxUrl(profile.baseUrl)
            if (api.me(base, token).id != profile.userId) throw MinifluxFailure(MinifluxError.FORBIDDEN)

            fun validated(feeds: List<MinifluxFeed>): Set<Long> {
                require(feeds.all { it.id > 0 } && feeds.map { it.id }.distinct().size == feeds.size)
                return feeds.map { it.id }.toSet()
            }
            val parents = validated(api.feeds(base, token))
            val known = if (plan.missing > 0) enumerateMinifluxIds(api, base, token) else emptySet()
            require(parents == validated(api.feeds(base, token)))
            for (entry in plan.entries.toList()) {
                caller.ensureActive()
                if (!entry.parentRemotePresent ||
                    entry.remoteFeedId !in parents ||
                    entry.protectedByPending ||
                    entry.protectedByStarred ||
                    entry.confidence !in setOf(MinifluxDeletionConfidence.HIGH, MinifluxDeletionConfidence.CONFIRMED) ||
                    entry.reason !in setOf(MinifluxDeletionReason.REMOTE_STATUS_REMOVED, MinifluxDeletionReason.REMOTE_ENTRY_NOT_FOUND) ||
                    (entry.reason == MinifluxDeletionReason.REMOTE_ENTRY_NOT_FOUND && entry.remoteEntryId in known)
                ) {
                    result = result.copy(skipped = result.skipped + 1)
                    progress(result)
                    continue
                }
                val remote = api.deletionEntry(base, token, entry.remoteEntryId)
                if (remote != null) require(remote.id == entry.remoteEntryId && remote.feedId == entry.remoteFeedId && remote.status in setOf("read", "unread", "removed"))
                val stillEligible =
                    when (entry.reason) {
                        MinifluxDeletionReason.REMOTE_STATUS_REMOVED -> remote?.status == "removed" && !remote.starred
                        MinifluxDeletionReason.REMOTE_ENTRY_NOT_FOUND -> remote == null
                        else -> false
                    }
                if (!stillEligible) {
                    result = result.copy(skipped = result.skipped + 1)
                    progress(result)
                    continue
                }
                withContext(NonCancellable) {
                    try {
                        val committed =
                            database.withTransaction {
                                caller.ensureActive()
                                val latest = dao.profile()
                                require(latest?.id == profile.id && latest.lastSuccessfulSync != null && latest.bootstrapChangedAt == null)
                                val identity = dao.entryIdentity(profile.id, entry.remoteEntryId) ?: return@withTransaction null
                                val item = dao.item(entry.localEntryId) ?: return@withTransaction null
                                val feed = dao.feedIdentity(profile.id, entry.remoteFeedId) ?: return@withTransaction null
                                if (identity.localFeedItemId != entry.localEntryId ||
                                    identity.remoteFeedId != entry.remoteFeedId ||
                                    item.feedId != feed.localFeedId ||
                                    dao.feed(feed.localFeedId) == null ||
                                    identity.remoteStarred ||
                                    item.bookmarked ||
                                    dao.pending(profile.id, entry.remoteEntryId) != null
                                ) {
                                    return@withTransaction null
                                }
                                val file = blobFile(entry.localEntryId, store.articleDirectory(profile.id))
                                if (file.isFile != (entry.blobBytes != null)) return@withTransaction null
                                check(dao.cleanupEntryIdentity(profile.id, entry.remoteEntryId, entry.localEntryId) == 1)
                                check(dao.cleanupItem(entry.localEntryId) == 1)
                                caller.ensureActive()
                                file to file.isFile
                            }
                        if (committed == null) {
                            result = result.copy(skipped = result.skipped + 1)
                        } else {
                            result = result.copy(entries = result.entries + 1)
                            if (committed.second) {
                                val deleted =
                                    caller.isActive &&
                                        try {
                                            deleteBlob(committed.first)
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
