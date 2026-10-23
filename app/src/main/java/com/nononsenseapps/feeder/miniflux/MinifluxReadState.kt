package com.nononsenseapps.feeder.miniflux

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.time.Instant
import kotlin.coroutines.coroutineContext

/** The sole optimistic mutation entry point. No network is needed for a local operation. */
class MinifluxReadMutationStore(
    private val cache: MinifluxCacheStore,
) {
    suspend fun setStatus(
        profileId: String,
        remoteId: Long,
        desired: String,
    ) {
        require(desired in setOf("read", "unread"))
        mutate(profileId, remoteId, desired)
    }

    suspend fun toggleStatus(
        profileId: String,
        remoteId: Long,
    ) = mutate(profileId, remoteId, null)

    private suspend fun mutate(
        profileId: String,
        remoteId: Long,
        requested: String?,
    ) {
        val db = cache.database(profileId)
        db.withTransaction {
            val dao = db.cacheDao()
            val identity = checkNotNull(dao.entryIdentity(profileId, remoteId))
            val item = checkNotNull(dao.item(identity.localFeedItemId))
            val previous = dao.pending(profileId, remoteId)
            val effective = previous?.desiredStatus ?: identity.remoteStatus
            val desired = requested ?: if (effective == "read") "unread" else "read"
            val now = System.currentTimeMillis()
            // Persist the counter beyond acknowledgement, preventing generation reuse (ABA).
            val generation = Math.addExact(identity.readGeneration, 1)
            item.readTime = if (desired == "read") item.readTime ?: Instant.ofEpochMilli(now) else null
            dao.updateItem(item)
            dao.updateEntryIdentity(identity.copy(readGeneration = generation))
            dao.savePending(MinifluxReadOutbox(profileId, remoteId, desired, generation, previous?.createdAt ?: now, now))
        }
    }

    suspend fun observeRemote(
        profileId: String,
        entry: MinifluxEntry,
        snapshot: MinifluxReadOutbox? = null,
    ): Boolean {
        val db = cache.database(profileId)
        return db.withTransaction {
            val dao = db.cacheDao()
            val identity = checkNotNull(dao.entryIdentity(profileId, entry.id))
            if (entry.feedId != identity.remoteFeedId || entry.status !in setOf("read", "unread")) throw MinifluxFailure(MinifluxError.INVALID_RESPONSE)
            dao.updateEntryIdentity(identity.copy(remoteStatus = entry.status))
            val confirmed =
                snapshot != null &&
                    entry.status == snapshot.desiredStatus &&
                    dao.acknowledge(profileId, entry.id, snapshot.generation) == 1
            val effective = dao.pending(profileId, entry.id)?.desiredStatus ?: entry.status
            val item = checkNotNull(dao.item(identity.localFeedItemId))
            item.readTime = if (effective == "read") item.readTime ?: Instant.EPOCH else null
            dao.updateItem(item)
            confirmed
        }
    }

    suspend fun attempt(snapshot: MinifluxReadOutbox) = updateAttempt(snapshot, null, true)

    suspend fun failed(
        snapshot: MinifluxReadOutbox,
        error: String,
    ) = updateAttempt(snapshot, error, false)

    private suspend fun updateAttempt(
        snapshot: MinifluxReadOutbox,
        error: String?,
        increment: Boolean,
    ) {
        val db = cache.database(snapshot.profileId)
        db.withTransaction {
            val dao = db.cacheDao()
            val current = dao.pending(snapshot.profileId, snapshot.remoteEntryId)
            if (current?.generation == snapshot.generation) {
                val now = System.currentTimeMillis()
                dao.savePending(
                    current.copy(
                        lastAttemptAt = if (increment) now else current.lastAttemptAt,
                        attemptCount = current.attemptCount + if (increment) 1 else 0,
                        lastErrorType = error,
                        lastErrorAt = if (error == null) null else now,
                    ),
                )
            }
        }
    }
}

/** Manual only. A finite snapshot prevents rapid edits from keeping a sync alive indefinitely. */
data class MinifluxReadSyncResult(
    val requested: Int,
    val confirmed: Int,
    val pending: Int,
    val failed: Int,
)

class MinifluxReadSync(
    private val api: MinifluxApiClient,
    private val cache: MinifluxCacheStore,
    private val mutations: MinifluxReadMutationStore,
    private val manualTimeoutMillis: Long = 60_000,
) {
    private val mutex = Mutex()

    suspend fun sync(
        profile: MinifluxProfile,
        token: String,
    ): Int = syncWithResult(profile, token).pending

    suspend fun syncWithResult(
        profile: MinifluxProfile,
        token: String,
    ): MinifluxReadSyncResult =
        withTimeout(manualTimeoutMillis) {
            mutex.withLock {
                val base = normalizedMinifluxUrl(profile.baseUrl)
                val dao = cache.database(profile.id).cacheDao()
                val snapshots = dao.pending(profile.id)
                var confirmed = 0
                var failed = 0
                if (snapshots.isEmpty()) return@withLock MinifluxReadSyncResult(0, 0, 0, 0)
                // Never send an old profile's entry IDs using another account's token.
                try {
                    if (api.me(base, token).id != profile.userId) throw MinifluxFailure(MinifluxError.FORBIDDEN)
                } catch (e: MinifluxFailure) {
                    snapshots.forEach {
                        mutations.attempt(it)
                        mutations.failed(it, e.error.name)
                    }
                    throw e
                }
                batches@ for ((status, rows) in snapshots.groupBy { it.desiredStatus }) {
                    for (batch in rows.chunked(50)) {
                        coroutineContext.ensureActive()
                        batch.forEach { mutations.attempt(it) }
                        val putError =
                            try {
                                api.updateReadStatus(base, token, batch.map { it.remoteEntryId }, status)
                                null
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: MinifluxFailure) {
                                e.error
                            }
                        for (row in batch) {
                            if (putError != null && putError !in setOf(MinifluxError.CONNECTION, MinifluxError.DNS, MinifluxError.TIMEOUT)) {
                                mutations.failed(row, putError.name)
                                failed++
                                continue
                            }
                            try {
                                val remote = api.entry(base, token, row.remoteEntryId)
                                if (remote.id != row.remoteEntryId) throw MinifluxFailure(MinifluxError.INVALID_RESPONSE)
                                val ack = mutations.observeRemote(profile.id, remote, row)
                                if (ack) confirmed++
                                if (!ack && remote.status != row.desiredStatus) {
                                    mutations.failed(row, putError?.name ?: "CONFIRMATION_MISMATCH")
                                    failed++
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: MinifluxFailure) {
                                mutations.failed(row, e.error.name)
                                failed++
                            }
                        }
                        // Authentication errors stop this run without discarding any pending rows.
                        if (putError in setOf(MinifluxError.UNAUTHORIZED, MinifluxError.FORBIDDEN)) break@batches
                    }
                }
                // This button sends only its initial snapshot. Content/remote refresh is M2's GET button.
                MinifluxReadSyncResult(snapshots.size, confirmed, dao.pending(profile.id).size, failed)
            }
        }
}
