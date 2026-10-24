package com.nononsenseapps.feeder.miniflux

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class MinifluxStarMutationStore(
    private val cache: MinifluxCacheStore,
) {
    suspend fun toggleStarred(
        profileId: String,
        remoteId: Long,
    ) = mutate(profileId, remoteId, null)

    suspend fun setStarred(
        profileId: String,
        remoteId: Long,
        desired: Boolean,
    ) = mutate(profileId, remoteId, desired)

    private suspend fun mutate(
        profileId: String,
        remoteId: Long,
        requested: Boolean?,
    ) {
        val db = cache.database(profileId)
        db.withTransaction {
            val dao = db.cacheDao()
            val identity = checkNotNull(dao.entryIdentity(profileId, remoteId))
            val item = checkNotNull(dao.item(identity.localFeedItemId))
            val previous = dao.starPending(profileId, remoteId)
            val desired = requested ?: !(previous?.desiredStarred ?: identity.remoteStarred)
            val generation = Math.addExact(identity.starGeneration, 1)
            val now = System.currentTimeMillis()
            item.bookmarked = desired
            dao.updateItem(item)
            dao.updateEntryIdentity(identity.copy(starGeneration = generation))
            dao.saveStarPending(MinifluxStarOutbox(profileId, remoteId, desired, generation, previous?.createdAt ?: now, now))
        }
    }

    suspend fun observeRemote(
        profileId: String,
        remote: MinifluxEntry,
        snapshot: MinifluxStarOutbox? = null,
    ): Boolean {
        val db = cache.database(profileId)
        return db.withTransaction {
            val dao = db.cacheDao()
            val identity = checkNotNull(dao.entryIdentity(profileId, remote.id))
            if (identity.remoteFeedId != remote.feedId || remote.status !in setOf("read", "unread")) throw MinifluxFailure(MinifluxError.INVALID_RESPONSE)
            dao.updateEntryIdentity(identity.copy(remoteStarred = remote.starred))
            val ack = snapshot != null && snapshot.desiredStarred == remote.starred && dao.acknowledgeStar(profileId, remote.id, snapshot.generation) == 1
            val item = checkNotNull(dao.item(identity.localFeedItemId))
            item.bookmarked = dao.starPending(profileId, remote.id)?.desiredStarred ?: remote.starred
            dao.updateItem(item)
            ack
        }
    }

    internal suspend fun record(
        snapshot: MinifluxStarOutbox,
        error: String?,
        attempt: Boolean = false,
    ) {
        val db = cache.database(snapshot.profileId)
        db.withTransaction {
            val dao = db.cacheDao()
            val current = dao.starPending(snapshot.profileId, snapshot.remoteEntryId)
            if (current?.generation == snapshot.generation) {
                val now = System.currentTimeMillis()
                dao.saveStarPending(
                    current.copy(
                        lastAttemptAt = if (attempt) now else current.lastAttemptAt,
                        attemptCount = current.attemptCount + if (attempt) 1 else 0,
                        lastErrorType = error,
                        lastErrorAt =
                            if (error ==
                                null
                            ) {
                                null
                            } else {
                                now
                            },
                    ),
                )
            }
        }
    }
}

data class MinifluxStarSyncResult(
    val requested: Int,
    val confirmed: Int,
    val failed: Int,
    val pending: Int,
    val skipped: Int,
    val writeEnabled: Boolean,
    val capability: MinifluxStarCapability = minifluxStarCapability(null),
)

class MinifluxStarSync(
    private val api: MinifluxApiClient,
    private val cache: MinifluxCacheStore,
    private val mutations: MinifluxStarMutationStore,
    private val timeoutMillis: Long = 60_000,
) {
    suspend fun sync(
        profile: MinifluxProfile,
        token: String,
        progress: (MinifluxStarSyncResult) -> Unit = {},
    ): MinifluxStarSyncResult =
        withTimeout(timeoutMillis) {
            cache.operationMutex.withLock {
                val dao = cache.database(profile.id).cacheDao()
                val initial = dao.starPending(profile.id)
                val snapshots = initial.take(20)
                val base = normalizedMinifluxUrl(profile.baseUrl)
                if (api.me(base, token).id != profile.userId) throw MinifluxFailure(MinifluxError.FORBIDDEN)
                val capability =
                    try {
                        minifluxStarCapability(api.version(base, token))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: MinifluxFailure) {
                        minifluxStarCapability(null)
                    }
                if (capability.support != MinifluxStarSupport.SUPPORTED) {
                    return@withLock MinifluxStarSyncResult(0, 0, 0, initial.size, initial.size, false, capability).also(progress)
                }
                var confirmed = 0
                var failed = 0
                var processed = 0
                for (snapshot in snapshots) {
                    currentCoroutineContext().ensureActive()
                    processed++
                    try {
                        val identity = checkNotNull(dao.entryIdentity(profile.id, snapshot.remoteEntryId))
                        val before = api.entry(base, token, snapshot.remoteEntryId)
                        if (before.id != snapshot.remoteEntryId ||
                            before.feedId != identity.remoteFeedId ||
                            before.status !in setOf("read", "unread")
                        ) {
                            throw MinifluxFailure(MinifluxError.INVALID_RESPONSE)
                        }
                        mutations.record(snapshot, null, attempt = true)
                        val putError =
                            try {
                                api.updateStarred(base, token, listOf(snapshot.remoteEntryId), snapshot.desiredStarred)
                                null
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: MinifluxFailure) {
                                e.error
                            }
                        if (putError != null && putError !in setOf(MinifluxError.CONNECTION, MinifluxError.DNS, MinifluxError.TIMEOUT)) throw MinifluxFailure(putError)
                        val remote = api.entry(base, token, snapshot.remoteEntryId)
                        if (remote.id != snapshot.remoteEntryId) throw MinifluxFailure(MinifluxError.INVALID_RESPONSE)
                        if (mutations.observeRemote(profile.id, remote, snapshot)) {
                            confirmed++
                        } else if (remote.starred != snapshot.desiredStarred) {
                            failed++
                            mutations.record(snapshot, "CONFIRMATION_MISMATCH")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: MinifluxFailure) {
                        failed++
                        mutations.record(snapshot, e.error.name)
                        if (e.error in setOf(MinifluxError.UNAUTHORIZED, MinifluxError.FORBIDDEN, MinifluxError.HTTP)) {
                            progress(MinifluxStarSyncResult(snapshots.size, confirmed, failed, dao.starPending(profile.id).size, initial.size - processed, true, capability))
                            break
                        }
                    }
                    progress(MinifluxStarSyncResult(snapshots.size, confirmed, failed, dao.starPending(profile.id).size, initial.size - processed, true, capability))
                }
                MinifluxStarSyncResult(snapshots.size, confirmed, failed, dao.starPending(profile.id).size, initial.size - processed, true, capability)
            }
        }
}
