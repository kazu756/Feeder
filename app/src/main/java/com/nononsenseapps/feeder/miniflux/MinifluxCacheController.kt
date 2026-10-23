package com.nononsenseapps.feeder.miniflux

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    ) {
        if (!active.compareAndSet(false, true)) return
        mutableState.update {
            it.copy(busy = true, failed = false, completed = false, remaining = null, readResult = null, cacheResult = null, errorType = null, readStates = readStates, stage = "account", count = 0)
        }
        job =
            scope
                .launch(Dispatchers.IO) {
                    try {
                        withTimeout(if (readStates) manualTimeoutMillis else MINIFLUX_CACHE_TIMEOUT_MS + 5000) {
                            val base = normalizedMinifluxUrl(baseUrl).toString()
                            val token = credentials.loadToken(base) ?: throw MinifluxFailure(MinifluxError.MISSING_TOKEN)
                            if (readStates) {
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
                        mutableState.update { it.copy(failed = true, errorType = "TIMEOUT") }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        mutableState.update { it.copy(failed = true, errorType = (e as? MinifluxFailure)?.error?.name ?: "LOCAL_ERROR") }
                    } finally {
                        if (!readStates) {
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
