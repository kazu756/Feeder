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
)

data class MinifluxCleanupConfirmation(
    val feeds: Int,
    val entries: Int,
    val blobs: Int,
)

class MinifluxCacheController(
    val store: MinifluxCacheStore,
    private val credentials: MinifluxCredentialStore,
    private val sync: MinifluxCacheSync,
    private val scope: CoroutineScope,
    private val readSync: MinifluxReadSync,
    val readMutations: MinifluxReadMutationStore,
    private val manualTimeoutMillis: Long = 65_000,
) {
    private val mutableState = MutableStateFlow(MinifluxCacheState())
    val state = mutableState.asStateFlow()
    private val active = AtomicBoolean(false)
    private var job: Job? = null
    private var cleanupPlan: MinifluxCleanupPlan? = null

    fun requestCleanup(baseUrl: String) {
        if (active.get()) return
        val state = mutableState.value
        val profile = state.summary?.profile ?: return
        if (profile.baseUrl != runCatching { normalizedMinifluxUrl(baseUrl).toString() }.getOrNull()) return
        val plan = state.deletionResult?.cleanupPlan(profile) ?: return
        cleanupPlan = plan
        mutableState.update { it.copy(cleanupConfirmation = MinifluxCleanupConfirmation(plan.feeds.size, plan.entries, plan.blobs)) }
    }

    fun dismissCleanup() {
        cleanupPlan = null
        mutableState.update { it.copy(cleanupConfirmation = null) }
    }

    fun confirmCleanup(baseUrl: String) {
        val plan = cleanupPlan ?: return
        if (plan.profile.baseUrl != runCatching { normalizedMinifluxUrl(baseUrl).toString() }.getOrNull()) {
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
                            val token = credentials.loadToken(plan.profile.baseUrl) ?: throw MinifluxFailure(MinifluxError.MISSING_TOKEN)
                            val result =
                                sync.cleanup(plan, token) { result ->
                                    mutableState.update { it.copy(cleanupResult = result, count = result.feeds + result.skipped + result.failed) }
                                }
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

    fun syncCache(
        baseUrl: String,
        readStates: Boolean = false,
        deletionDryRun: Boolean = false,
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
                cacheResult = null,
                deletionResult = null,
                cleanupResult = null,
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
                        withTimeout(if (readStates) manualTimeoutMillis else MINIFLUX_CACHE_TIMEOUT_MS + 5000) {
                            val base = normalizedMinifluxUrl(baseUrl).toString()
                            val token = credentials.loadToken(base) ?: throw MinifluxFailure(MinifluxError.MISSING_TOKEN)
                            if (deletionDryRun) {
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
                            if (deletionDryRun) it.copy(deletionResult = MinifluxDeletionResult(false, "TIMEOUT")) else it.copy(failed = true, errorType = "TIMEOUT")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val error = (e as? MinifluxFailure)?.error?.name ?: "LOCAL_ERROR"
                        mutableState.update {
                            if (deletionDryRun) it.copy(deletionResult = MinifluxDeletionResult(false, error)) else it.copy(failed = true, errorType = error)
                        }
                    } finally {
                        if (!readStates && !deletionDryRun) {
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
                    operation.invokeOnCompletion {
                        mutableState.update { it.copy(busy = false) }
                        active.set(false)
                    }
                }
    }

    fun cancel() {
        job?.cancel()
    }
}
