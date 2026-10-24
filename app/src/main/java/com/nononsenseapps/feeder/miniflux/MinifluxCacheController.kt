package com.nononsenseapps.feeder.miniflux

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

data class MinifluxCacheState(
    val busy: Boolean = false,
    val summary: MinifluxCacheSummary? = null,
    val stage: String = "",
    val count: Int = 0,
    val failed: Boolean = false,
    val completed: Boolean = false,
    val remaining: Int? = null,
    val readStates: Boolean = false,
    val readResult: MinifluxReadSyncResult? = null,
    val errorType: String? = null,
    val cacheResult: MinifluxCacheSyncResult? = null,
    val deletionResult: MinifluxDeletionResult? = null,
    val cleanupConfirmation: MinifluxCleanupConfirmation? = null,
    val cleanupResult: MinifluxCleanupResult? = null,
    val entryCleanup: Boolean = false,
    val starResult: MinifluxStarSyncResult? = null,
    val unifiedResult: MinifluxUnifiedSyncResult? = null,
)

data class MinifluxCleanupConfirmation(
    val feeds: Int,
    val entries: Int,
    val blobs: Int,
    val entryCleanup: Boolean = false,
    val removed: Int = 0,
    val missing: Int = 0,
    val pendingExcluded: Int = 0,
    val starredExcluded: Int = 0,
)

class MinifluxCacheController(
    val store: MinifluxCacheStore,
    private val credentials: MinifluxCredentialStore,
    private val sync: MinifluxCacheSync,
    private val scope: CoroutineScope,
    private val readSync: MinifluxReadSync,
    val readMutations: MinifluxReadMutationStore,
    private val manualTimeoutMillis: Long = 65_000,
    val starMutations: MinifluxStarMutationStore = MinifluxStarMutationStore(store),
    private val starSync: MinifluxStarSync? = null,
    private val unifiedSync: MinifluxUnifiedSync? = null,
) {
    private val mutableState = MutableStateFlow(MinifluxCacheState())
    val state = mutableState.asStateFlow()
    private val active = AtomicBoolean(false)
    private var job: Job? = null
    private var cleanupPlan: MinifluxCleanupPlan? = null
    private var entryCleanupPlan: MinifluxEntryCleanupPlan? = null

    fun requestEntryCleanup(baseUrl: String) {
        if (active.get()) return
        val state = mutableState.value
        val profile = state.summary?.profile ?: return
        if (profile.baseUrl != runCatching { normalizedMinifluxUrl(baseUrl).toString() }.getOrNull()) return
        val result = state.deletionResult ?: return
        val plan = result.entryCleanupPlan(profile) ?: return
        cleanupPlan = null
        entryCleanupPlan = plan
        val candidates = result.entries.filter { it.reason in setOf(MinifluxDeletionReason.REMOTE_STATUS_REMOVED, MinifluxDeletionReason.REMOTE_ENTRY_NOT_FOUND) && it.parentRemotePresent }
        mutableState.update {
            it.copy(
                cleanupConfirmation =
                    MinifluxCleanupConfirmation(
                        0,
                        plan.entries.size,
                        plan.blobs,
                        true,
                        plan.removed,
                        plan.missing,
                        candidates.count {
                            it.protectedByPending
                        },
                        candidates.count { it.protectedByStarred },
                    ),
            )
        }
    }

    fun requestCleanup(baseUrl: String) {
        if (active.get()) return
        val state = mutableState.value
        val profile = state.summary?.profile ?: return
        if (profile.baseUrl != runCatching { normalizedMinifluxUrl(baseUrl).toString() }.getOrNull()) return
        val plan = state.deletionResult?.cleanupPlan(profile) ?: return
        cleanupPlan = plan
        entryCleanupPlan = null
        mutableState.update { it.copy(cleanupConfirmation = MinifluxCleanupConfirmation(plan.feeds.size, plan.entries, plan.blobs)) }
    }

    fun dismissCleanup() {
        cleanupPlan = null
        entryCleanupPlan = null
        mutableState.update { it.copy(cleanupConfirmation = null) }
    }

    fun confirmCleanup(baseUrl: String) {
        val plan = cleanupPlan
        val entryPlan = entryCleanupPlan
        val profile = plan?.profile ?: entryPlan?.profile ?: return
        if (profile.baseUrl != runCatching { normalizedMinifluxUrl(baseUrl).toString() }.getOrNull()) {
            dismissCleanup()
            return
        }
        if (!active.compareAndSet(false, true)) return
        dismissCleanup()
        mutableState.update {
            it.copy(
                busy = true,
                failed = false,
                completed = false,
                deletionResult = null,
                cleanupResult = MinifluxCleanupResult(),
                entryCleanup = entryPlan != null,
                readResult = null,
                cacheResult = null,
                remaining = null,
                errorType = null,
                stage = "cleanup",
                count = 0,
            )
        }
        job =
            scope
                .launch(Dispatchers.IO) {
                    try {
                        withTimeout(MINIFLUX_CACHE_TIMEOUT_MS + 5000) {
                            val token = credentials.loadToken(profile.baseUrl) ?: throw MinifluxFailure(MinifluxError.MISSING_TOKEN)
                            val progress: (MinifluxCleanupResult) -> Unit = { result ->
                                mutableState.update { it.copy(cleanupResult = result, count = result.entries + result.feeds + result.skipped + result.failed) }
                            }
                            val result = if (entryPlan != null) sync.cleanupEntries(entryPlan, token, progress) else sync.cleanup(checkNotNull(plan), token, progress)
                            mutableState.update { it.copy(cleanupResult = result) }
                        }
                    } catch (e: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        mutableState.update { it.copy(cleanupResult = (it.cleanupResult ?: MinifluxCleanupResult()).copy(complete = false, errorType = "TIMEOUT")) }
                    } catch (e: CancellationException) {
                        mutableState.update { it.copy(cleanupResult = (it.cleanupResult ?: MinifluxCleanupResult()).copy(complete = false, errorType = "CANCELLED")) }
                        throw e
                    } catch (e: Exception) {
                        mutableState.update {
                            it.copy(
                                cleanupResult =
                                    (it.cleanupResult ?: MinifluxCleanupResult()).copy(
                                        complete = false,
                                        errorType =
                                            (e as? MinifluxFailure)?.error?.name ?: "LOCAL_ERROR",
                                    ),
                            )
                        }
                    } finally {
                        // Keep committed counts visible even after cancellation; never reuse the dry-run.
                        withContext(NonCancellable) {
                            try {
                                withTimeout(2000) {
                                    store.selectedProfile()?.let { profile -> mutableState.update { it.copy(summary = store.summary(profile)) } }
                                }
                            } catch (_: Exception) {
                                // Retain the last known summary.
                            }
                        }
                    }
                }.also { operation ->
                    operation.invokeOnCompletion { cause ->
                        mutableState.update { state ->
                            state.copy(
                                busy = false,
                                cleanupResult =
                                    if (cause != null &&
                                        state.cleanupResult?.errorType == null
                                    ) {
                                        state.cleanupResult?.copy(complete = false, errorType = "CANCELLED")
                                    } else {
                                        state.cleanupResult
                                    },
                            )
                        }
                        active.set(false)
                    }
                }
    }

    init {
        scope.launch(Dispatchers.IO) {
            try {
                store.selectedProfile()?.let { profile ->
                    mutableState.update { it.copy(summary = store.summary(profile)) }
                }
            } catch (
                e: CancellationException,
            ) {
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(failed = true) }
            }
        }
    }

    fun syncAll(baseUrl: String) = syncCache(baseUrl, unified = true)

    fun syncCache(
        baseUrl: String,
        readStates: Boolean = false,
        deletionDryRun: Boolean = false,
        starStates: Boolean = false,
        unified: Boolean = false,
    ) {
        if (!active.compareAndSet(false, true)) return
        dismissCleanup()
        mutableState.update {
            it.copy(
                busy = true,
                failed = false,
                completed = false,
                remaining = null,
                readResult = null,
                starResult = null,
                unifiedResult = if (unified) MinifluxUnifiedSyncResult() else null,
                cacheResult = null,
                deletionResult = null,
                cleanupResult = null,
                entryCleanup = false,
                errorType = null,
                readStates = readStates,
                stage = if (deletionDryRun) "dry-run" else "account",
                count = 0,
            )
        }
        job =
            scope
                .launch(Dispatchers.IO) {
                    try {
                        withTimeout(
                            if (unified) {
                                125_000
                            } else if (readStates || starStates) {
                                manualTimeoutMillis
                            } else {
                                MINIFLUX_CACHE_TIMEOUT_MS + 5000
                            },
                        ) {
                            val base = normalizedMinifluxUrl(baseUrl).toString()
                            val token = credentials.loadToken(base) ?: throw MinifluxFailure(MinifluxError.MISSING_TOKEN)
                            if (unified) {
                                checkNotNull(unifiedSync).sync(base, token) { result ->
                                    mutableState.update {
                                        it.copy(unifiedResult = result, stage = result.stage, summary = result.summary ?: it.summary, deletionResult = result.reconciliation.value)
                                    }
                                }
                            } else if (starStates) {
                                val profile = store.selectedProfile() ?: throw MinifluxFailure(MinifluxError.INVALID_RESPONSE)
                                if (profile.baseUrl != base) throw MinifluxFailure(MinifluxError.FORBIDDEN)
                                val result = checkNotNull(starSync).sync(profile, token) { result -> mutableState.update { it.copy(starResult = result) } }
                                mutableState.update { it.copy(starResult = result) }
                            } else if (deletionDryRun) {
                                val profile = store.selectedProfile() ?: throw MinifluxFailure(MinifluxError.INVALID_RESPONSE)
                                if (profile.baseUrl != base) throw MinifluxFailure(MinifluxError.FORBIDDEN)
                                val result = sync.deletionDryRun(profile, token)
                                mutableState.update { it.copy(deletionResult = result) }
                            } else if (readStates) {
                                val profile = store.selectedProfile() ?: throw MinifluxFailure(MinifluxError.INVALID_RESPONSE)
                                if (profile.baseUrl != base) throw MinifluxFailure(MinifluxError.FORBIDDEN)
                                val result = readSync.syncWithResult(profile, token)
                                // A finished run may intentionally leave newer/unconfirmed pending rows.
                                mutableState.update { it.copy(remaining = result.pending, readResult = result, completed = true) }
                            } else {
                                val summary = sync.sync(base, token) { stage, count -> mutableState.update { it.copy(stage = stage, count = count) } }
                                mutableState.update { it.copy(summary = summary, cacheResult = summary.syncResult, completed = true) }
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        mutableState.update {
                            if (unified) {
                                it.copy(unifiedResult = (it.unifiedResult ?: MinifluxUnifiedSyncResult()).interrupted("TIMEOUT"))
                            } else if (deletionDryRun) {
                                it.copy(deletionResult = MinifluxDeletionResult(false, "TIMEOUT"))
                            } else {
                                it.copy(failed = true, errorType = "TIMEOUT")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val error = (e as? MinifluxFailure)?.error?.name ?: "LOCAL_ERROR"
                        mutableState.update {
                            if (unified) {
                                it.copy(unifiedResult = (it.unifiedResult ?: MinifluxUnifiedSyncResult()).interrupted(error))
                            } else if (deletionDryRun) {
                                it.copy(deletionResult = MinifluxDeletionResult(false, error))
                            } else {
                                it.copy(failed = true, errorType = error)
                            }
                        }
                    } finally {
                        if (!readStates && !deletionDryRun && !starStates && !unified) {
                            // Partial cache remains available; it is never marked complete on failure/cancel.
                            try {
                                withTimeout(2000) {
                                    store.selectedProfile()?.let { profile ->
                                        mutableState.update { it.copy(summary = store.summary(profile)) }
                                    }
                                }
                            } catch (_: TimeoutCancellationException) {
                                currentCoroutineContext().ensureActive()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // Keep last known counts.
                            }
                        }
                    }
                }.also { operation ->
                    operation.invokeOnCompletion { cause ->
                        mutableState.update {
                            it.copy(
                                busy = false,
                                unifiedResult =
                                    if (unified &&
                                        cause is CancellationException
                                    ) {
                                        (it.unifiedResult ?: MinifluxUnifiedSyncResult()).interrupted("CANCELLED", cancelled = true)
                                    } else {
                                        it.unifiedResult
                                    },
                            )
                        }
                        active.set(false)
                    }
                }
    }

    fun cancel() {
        job?.cancel()
    }
}
