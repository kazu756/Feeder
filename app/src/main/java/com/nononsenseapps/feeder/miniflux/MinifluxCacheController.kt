package com.nononsenseapps.feeder.miniflux

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class MinifluxCacheState(
    val busy: Boolean = false,
    val summary: MinifluxCacheSummary? = null,
    val stage: String = "",
    val count: Int = 0,
    val failed: Boolean = false,
    val completed: Boolean = false,
)

class MinifluxCacheController(
    val store: MinifluxCacheStore,
    private val credentials: MinifluxCredentialStore,
    private val sync: MinifluxCacheSync,
    private val scope: CoroutineScope,
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

    fun syncCache(baseUrl: String) {
        if (!active.compareAndSet(false, true)) return
        mutableState.update { it.copy(busy = true, failed = false, completed = false, stage = "account", count = 0) }
        job =
            scope
                .launch(Dispatchers.IO) {
                    try {
                        val base = normalizedMinifluxUrl(baseUrl).toString()
                        val token = credentials.loadToken(base) ?: throw MinifluxFailure(MinifluxError.MISSING_TOKEN)
                        val summary = sync.sync(base, token) { stage, count -> mutableState.update { it.copy(stage = stage, count = count) } }
                        mutableState.update { it.copy(summary = summary, completed = true) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        mutableState.update { it.copy(failed = true) }
                    } finally {
                        // Partial cache remains available; it is never marked complete on failure/cancel.
                        try {
                            store.selectedProfile()?.let { profile ->
                                mutableState.update { it.copy(summary = store.summary(profile)) }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Keep last known counts.
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
