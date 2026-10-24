package com.nononsenseapps.feeder.miniflux

import androidx.room.withTransaction
import com.nononsenseapps.feeder.archmodel.BackendMode
import com.nononsenseapps.feeder.archmodel.BackendModeStore
import com.nononsenseapps.feeder.archmodel.BackendSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class BackendModeError { PROFILE_MISMATCH, BASELINE_REQUIRED, CACHE_REQUIRED, CACHE_UNAVAILABLE, STORAGE, BUSY, TIMEOUT, CANCELLED }

internal class BackendModeFailure(
    val error: BackendModeError,
) : Exception(error.name)

data class BackendModeChange(
    val mode: BackendMode,
    val readPending: Int = 0,
    val starPending: Int = 0,
)

/** Manual preference change only. No sync sender, cleanup, scheduler or AppDatabase dependency. */
class BackendModeSwitchService(
    private val modes: BackendModeStore,
    private val credentials: MinifluxCredentialStore,
    private val diagnostic: MinifluxDiagnosticService,
    private val cache: MinifluxCacheStore,
    private val timeoutMillis: Long = 30_000,
) {
    private val switching = Mutex()

    suspend fun switch(
        target: BackendMode,
        baseUrl: String,
    ): BackendModeChange =
        withContext(Dispatchers.IO) {
            if (!switching.tryLock()) throw BackendModeFailure(BackendModeError.BUSY)
            try {
                if (target == modes.selection.value.mode) return@withContext BackendModeChange(target)
                withTimeout(timeoutMillis) {
                    if (target == BackendMode.LOCAL_RSS) {
                        persist(BackendSelection())
                        BackendModeChange(target)
                    } else {
                        cache.operationMutex.withLock {
                            val base = normalizedMinifluxUrl(baseUrl).toString()
                            val token = credentials.loadToken(base) ?: throw MinifluxFailure(MinifluxError.MISSING_TOKEN)
                            val result = diagnostic.run(base, token)
                            val account =
                                when (val account = result.account) {
                                    is MinifluxStep.Success -> account.value
                                    is MinifluxStep.Failed -> throw MinifluxFailure(account.error)
                                    MinifluxStep.NotChecked -> throw MinifluxFailure(MinifluxError.INVALID_RESPONSE)
                                }
                            // Version and optional IDs capability are not requirements for a cache reader.
                            for (step in listOf(result.feeds, result.categories, result.entries)) {
                                if (step is MinifluxStep.Failed) throw MinifluxFailure(step.error)
                                if (step !is MinifluxStep.Success) throw MinifluxFailure(MinifluxError.INVALID_RESPONSE)
                            }
                            if (account.id <= 0) throw MinifluxFailure(MinifluxError.INVALID_RESPONSE)
                            val profile = cache.selectedProfile(existingOnly = true) ?: throw BackendModeFailure(BackendModeError.CACHE_REQUIRED)
                            if (profile.baseUrl != base || profile.userId != account.id || profile.id != minifluxProfileId(base, account.id)) {
                                throw BackendModeFailure(BackendModeError.PROFILE_MISMATCH)
                            }
                            val change =
                                try {
                                    val db = cache.database(profile.id)
                                    db.withTransaction {
                                        val dao = db.cacheDao()
                                        val current = dao.profile() ?: throw BackendModeFailure(BackendModeError.CACHE_REQUIRED)
                                        if (current != profile) throw BackendModeFailure(BackendModeError.PROFILE_MISMATCH)
                                        if (current.lastSuccessfulSync == null || current.bootstrapChangedAt != null || current.changedCheckpoint == null) {
                                            throw BackendModeFailure(BackendModeError.BASELINE_REQUIRED)
                                        }
                                        if (dao.feedCount() == 0 || dao.entryCount() == 0) throw BackendModeFailure(BackendModeError.CACHE_REQUIRED)
                                        val sample = cache.articles(profile.id, 0).firstOrNull() ?: throw BackendModeFailure(BackendModeError.CACHE_REQUIRED)
                                        cache.readBody(profile.id, sample.item.id)
                                        BackendModeChange(target, dao.pending(profile.id).size, dao.starPending(profile.id).size)
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: BackendModeFailure) {
                                    throw e
                                } catch (_: Exception) {
                                    throw BackendModeFailure(BackendModeError.CACHE_UNAVAILABLE)
                                }
                            // Credential edits do not take operationMutex. Reject a stale validation.
                            if (credentials.loadToken(base) != token || cache.selectedProfile(existingOnly = true)?.id != profile.id) {
                                throw BackendModeFailure(BackendModeError.PROFILE_MISMATCH)
                            }
                            persist(BackendSelection(target, profile.id))
                            change
                        }
                    }
                }
            } finally {
                switching.unlock()
            }
        }

    private suspend fun persist(value: BackendSelection) {
        currentCoroutineContext().ensureActive()
        try {
            modes.commit(value)
        } catch (_: Exception) {
            throw BackendModeFailure(BackendModeError.STORAGE)
        }
    }
}
