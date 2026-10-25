package com.nononsenseapps.feeder.miniflux

import com.nononsenseapps.feeder.archmodel.BackendMode
import com.nononsenseapps.feeder.archmodel.BackendSelection
import com.nononsenseapps.feeder.background.BackendSyncCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** UI independent normal sync entry. Diagnostics may still call UnifiedSync directly in either mode. */
class MinifluxSyncRunner(
    private val coordinator: BackendSyncCoordinator,
    private val cache: MinifluxCacheStore,
    private val credentials: MinifluxCredentialStore,
    private val unified: MinifluxUnifiedSync,
    private val timeoutMillis: Long = 125_000,
) {
    suspend fun sync(snapshot: BackendSelection): MinifluxUnifiedSyncResult {
        if (unified.busy.value) return failure("ALREADY_RUNNING")
        return try {
            withTimeout(timeoutMillis) {
                coordinator.withRun(snapshot) { selected ->
                    check(selected.mode == BackendMode.MINIFLUX)
                    val id = checkNotNull(selected.minifluxProfileId)
                    val profile = checkNotNull(cache.existingDatabase(id).cacheDao().profile())
                    check(profile.id == id && minifluxProfileId(profile.baseUrl, profile.userId) == id)
                    val token = credentials.loadToken(profile.baseUrl) ?: throw MinifluxFailure(MinifluxError.MISSING_TOKEN)
                    unified.syncPinned(profile.baseUrl, token, id)
                }
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            failure("TIMEOUT")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure((e as? MinifluxFailure)?.error?.name ?: "LOCAL_ERROR")
        }
    }

    private fun failure(code: String) = MinifluxUnifiedSyncResult(overall = MinifluxUnifiedOverall.FAILED, errorType = code)
}
