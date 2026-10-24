package com.nononsenseapps.feeder.miniflux

import androidx.room.withTransaction
import com.nononsenseapps.feeder.util.logDebug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

private const val LOG_TAG = "FEEDER_UNIFIED_SYNC"

enum class MinifluxUnifiedOverall { RUNNING, SUCCESS, PARTIAL_SUCCESS, NOTHING_TO_DO, FAILED, CANCELLED }

enum class MinifluxUnifiedStepState { NOT_RUN, RUNNING, SUCCESS, PARTIAL, SKIPPED, FAILED, CANCELLED }

data class MinifluxUnifiedStep<T>(
    val state: MinifluxUnifiedStepState = MinifluxUnifiedStepState.NOT_RUN,
    val value: T? = null,
    val errorType: String? = null,
)

data class MinifluxUnifiedSyncResult(
    val read: MinifluxUnifiedStep<MinifluxReadSyncResult> = MinifluxUnifiedStep(),
    val star: MinifluxUnifiedStep<MinifluxStarSyncResult> = MinifluxUnifiedStep(),
    val incremental: MinifluxUnifiedStep<MinifluxCacheSyncResult> = MinifluxUnifiedStep(),
    val reconciliation: MinifluxUnifiedStep<MinifluxDeletionResult> = MinifluxUnifiedStep(),
    val overall: MinifluxUnifiedOverall = MinifluxUnifiedOverall.RUNNING,
    val stage: String = "account",
    val readPending: Int = 0,
    val starPending: Int = 0,
    val summary: MinifluxCacheSummary? = null,
    val errorType: String? = null,
    val timeoutSource: String? = null,
    val reconciliationTrace: MinifluxReconciliationTrace? = null,
)

/** Manual only. One lock, one pair of snapshots, independent commits, never cleanup. */
class MinifluxUnifiedSync(
    private val api: MinifluxApiClient,
    private val store: MinifluxCacheStore,
    private val reads: MinifluxReadSync,
    private val stars: MinifluxStarSync,
    private val cache: MinifluxCacheSync,
    private val timeoutMillis: Long = 120_000,
    private val mutationTimeoutMillis: Long = 30_000,
    private val cacheTimeoutMillis: Long = 45_000,
    private val reconciliationTimeoutMillis: Long = 30_000,
) {
    suspend fun sync(
        baseUrl: String,
        token: String,
        progress: (MinifluxUnifiedSyncResult) -> Unit = {},
    ): MinifluxUnifiedSyncResult {
        var result = MinifluxUnifiedSyncResult()
        var profile: MinifluxProfile? = null
        var stop = false
        val started = System.nanoTime()

        fun publish(value: MinifluxUnifiedSyncResult) {
            if (value.stage != result.stage || value.overall != result.overall) {
                logDebug(LOG_TAG, "stage=${value.stage} overall=${value.overall} elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
            }
            result = value
            progress(value)
        }

        fun error(e: Exception): String = (e as? MinifluxFailure)?.error?.name ?: if (e is TimeoutCancellationException) "TIMEOUT" else "LOCAL_ERROR"

        fun observeFailure(value: MinifluxError) {
            if (value in setOf(MinifluxError.UNAUTHORIZED, MinifluxError.FORBIDDEN)) throw MinifluxFailure(value)
            if (value in setOf(MinifluxError.CONNECTION, MinifluxError.DNS, MinifluxError.TLS)) stop = true
        }

        suspend fun stageFailure(e: Exception): String {
            currentCoroutineContext().ensureActive()
            val code = error(e)
            if (code == "TIMEOUT") result = result.copy(timeoutSource = if (e is TimeoutCancellationException) "STEP:${result.stage}" else "HTTP:${result.stage}")
            if (code in setOf("UNAUTHORIZED", "FORBIDDEN", "INVALID_TOKEN", "CONNECTION", "DNS", "TLS")) stop = true
            return code
        }

        fun finish(): MinifluxUnifiedSyncResult {
            result =
                result.copy(
                    read = if (result.read.state == MinifluxUnifiedStepState.RUNNING) result.read.copy(state = MinifluxUnifiedStepState.FAILED, errorType = result.errorType) else result.read,
                    star = if (result.star.state == MinifluxUnifiedStepState.RUNNING) result.star.copy(state = MinifluxUnifiedStepState.FAILED, errorType = result.errorType) else result.star,
                    incremental =
                        if (result.incremental.state ==
                            MinifluxUnifiedStepState.RUNNING
                        ) {
                            result.incremental.copy(state = MinifluxUnifiedStepState.FAILED, errorType = result.errorType)
                        } else {
                            result.incremental
                        },
                    reconciliation =
                        if (result.reconciliation.state ==
                            MinifluxUnifiedStepState.RUNNING
                        ) {
                            result.reconciliation.copy(state = MinifluxUnifiedStepState.FAILED, errorType = result.errorType)
                        } else {
                            result.reconciliation
                        },
                )
            val states = listOf(result.read.state, result.star.state, result.incremental.state, result.reconciliation.state)
            val bad = states.any { it != MinifluxUnifiedStepState.SUCCESS } || result.readPending > 0 || result.starPending > 0 || result.errorType != null
            val anySuccess = states.any { it == MinifluxUnifiedStepState.SUCCESS } || (result.read.value?.confirmed ?: 0) > 0 || (result.star.value?.confirmed ?: 0) > 0
            val changes = (result.read.value?.confirmed ?: 0) + (result.star.value?.confirmed ?: 0) + (result.incremental.value?.inserted ?: 0) + (result.incremental.value?.updated ?: 0)
            val deletion = result.reconciliation.value
            val candidates = (deletion?.missingFeeds ?: 0) + (deletion?.removedEntries ?: 0) + (deletion?.confirmedMissingEntries ?: 0)
            return result.copy(
                overall =
                    when {
                        bad && anySuccess -> MinifluxUnifiedOverall.PARTIAL_SUCCESS
                        bad -> MinifluxUnifiedOverall.FAILED
                        changes == 0 && candidates == 0 -> MinifluxUnifiedOverall.NOTHING_TO_DO
                        else -> MinifluxUnifiedOverall.SUCCESS
                    },
            )
        }
        try {
            withTimeout(timeoutMillis) {
                store.operationMutex.withLock {
                    logDebug(LOG_TAG, "lock_acquired elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
                    val base = normalizedMinifluxUrl(baseUrl)
                    validateMinifluxToken(token)
                    profile = store.selectedProfile()?.takeIf { it.baseUrl == base.toString() }
                    val account = api.me(base, token)
                    val active = store.prepare(base.toString(), account.copy(username = account.username.replace(token, "[redacted]")))
                    profile = active
                    val db = store.database(active.id)
                    val dao = db.cacheDao()
                    // Freeze BOTH fields before the first mutation, including when baseline is partial.
                    val (readSnapshot, starSnapshot) = db.withTransaction { dao.pending(active.id) to dao.starPending(active.id) }
                    publish(result.copy(readPending = readSnapshot.size, starPending = starSnapshot.size))
                    publish(result.copy(stage = "read", read = MinifluxUnifiedStep(MinifluxUnifiedStepState.RUNNING, MinifluxReadSyncResult(readSnapshot.take(50).size, 0, readSnapshot.size, 0))))
                    try {
                        val value =
                            withTimeout(mutationTimeoutMillis) {
                                reads.syncLocked(active, token, readSnapshot.take(50), progress = { value ->
                                    publish(result.copy(read = result.read.copy(value = value), readPending = value.pending))
                                }, onError = ::observeFailure)
                            }
                        publish(
                            result.copy(
                                read =
                                    MinifluxUnifiedStep(
                                        if (value.failed == 0 &&
                                            value.pending == 0
                                        ) {
                                            MinifluxUnifiedStepState.SUCCESS
                                        } else {
                                            MinifluxUnifiedStepState.PARTIAL
                                        },
                                        value,
                                    ),
                                readPending = value.pending,
                            ),
                        )
                    } catch (e: Exception) {
                        val code = stageFailure(e)
                        publish(result.copy(read = result.read.copy(state = MinifluxUnifiedStepState.FAILED, errorType = code)))
                    }
                    if (stop) return@withLock
                    publish(result.copy(stage = "star", star = MinifluxUnifiedStep(MinifluxUnifiedStepState.RUNNING)))
                    try {
                        val value =
                            if (starSnapshot.isEmpty()) {
                                MinifluxStarSyncResult(0, 0, 0, 0, 0, false)
                            } else {
                                withTimeout(mutationTimeoutMillis) {
                                    stars.syncLocked(active, token, starSnapshot.take(20), progress = { value ->
                                        publish(result.copy(star = result.star.copy(value = value), starPending = value.pending))
                                    }, onError = ::observeFailure)
                                }
                            }
                        val state =
                            when {
                                starSnapshot.isNotEmpty() && !value.writeEnabled -> MinifluxUnifiedStepState.SKIPPED
                                value.failed > 0 || value.pending > 0 -> MinifluxUnifiedStepState.PARTIAL
                                else -> MinifluxUnifiedStepState.SUCCESS
                            }
                        publish(
                            result.copy(
                                star =
                                    MinifluxUnifiedStep(
                                        state,
                                        value,
                                        if (state ==
                                            MinifluxUnifiedStepState.SKIPPED
                                        ) {
                                            value.capability.reason.name
                                        } else {
                                            null
                                        },
                                    ),
                                starPending = value.pending,
                            ),
                        )
                    } catch (e: Exception) {
                        val code = stageFailure(e)
                        publish(result.copy(star = result.star.copy(state = MinifluxUnifiedStepState.FAILED, errorType = code)))
                    }
                    if (stop) return@withLock
                    publish(result.copy(stage = "cache", incremental = MinifluxUnifiedStep(MinifluxUnifiedStepState.RUNNING)))
                    try {
                        val summary = withTimeout(cacheTimeoutMillis) { cache.syncLocked(base.toString(), token, { _, _ -> }, maximumPages = 50, expectedUserId = active.userId) }
                        publish(result.copy(summary = summary, incremental = MinifluxUnifiedStep(MinifluxUnifiedStepState.SUCCESS, summary.syncResult)))
                    } catch (e: Exception) {
                        val code = stageFailure(e)
                        publish(result.copy(incremental = MinifluxUnifiedStep(MinifluxUnifiedStepState.FAILED, errorType = code)))
                    }
                    if (stop) return@withLock
                    publish(result.copy(stage = "reconciliation", reconciliation = MinifluxUnifiedStep(MinifluxUnifiedStepState.RUNNING)))
                    try {
                        val value =
                            withTimeout(reconciliationTimeoutMillis) {
                                cache.deletionDryRunLocked(active, token) { trace -> publish(result.copy(reconciliationTrace = trace)) }
                            }
                        val timeout = value.errorType == "TIMEOUT" || value.entryScanError == "TIMEOUT"
                        val complete = value.complete && value.entryEnumerationComplete && value.entryScanError == null
                        publish(
                            result.copy(
                                timeoutSource = if (timeout) "HTTP:reconciliation" else result.timeoutSource,
                                reconciliation =
                                    MinifluxUnifiedStep(
                                        if (complete) MinifluxUnifiedStepState.SUCCESS else MinifluxUnifiedStepState.FAILED,
                                        value,
                                        value.errorType ?: value.entryScanError,
                                    ),
                            ),
                        )
                    } catch (e: Exception) {
                        val code = stageFailure(e)
                        publish(result.copy(reconciliation = MinifluxUnifiedStep(MinifluxUnifiedStepState.FAILED, errorType = code)))
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            publish(result.copy(errorType = "TIMEOUT", timeoutSource = "OVERALL"))
        } catch (e: CancellationException) {
            publish(
                result.copy(
                    overall = MinifluxUnifiedOverall.CANCELLED,
                    errorType = "CANCELLED",
                    read = if (result.read.state == MinifluxUnifiedStepState.RUNNING) result.read.copy(state = MinifluxUnifiedStepState.CANCELLED) else result.read,
                    star = if (result.star.state == MinifluxUnifiedStepState.RUNNING) result.star.copy(state = MinifluxUnifiedStepState.CANCELLED) else result.star,
                    incremental = if (result.incremental.state == MinifluxUnifiedStepState.RUNNING) result.incremental.copy(state = MinifluxUnifiedStepState.CANCELLED) else result.incremental,
                    reconciliation =
                        if (result.reconciliation.state ==
                            MinifluxUnifiedStepState.RUNNING
                        ) {
                            result.reconciliation.copy(state = MinifluxUnifiedStepState.CANCELLED)
                        } else {
                            result.reconciliation
                        },
                ),
            )
            throw e
        } catch (e: Exception) {
            publish(result.copy(errorType = error(e)))
        } finally {
            // Bounded metadata-only refresh, including after cancellation; no blob scan or network.
            withContext(NonCancellable) {
                try {
                    withTimeoutOrNull(1000) {
                        profile?.let {
                            val dao = store.database(it.id).cacheDao()
                            publish(result.copy(readPending = dao.pending(it.id).size, starPending = dao.starPending(it.id).size))
                        }
                    }
                } catch (_: Exception) {
                    // Retain last committed progress if storage is unavailable.
                }
            }
        }
        publish(finish())
        return result
    }
}
