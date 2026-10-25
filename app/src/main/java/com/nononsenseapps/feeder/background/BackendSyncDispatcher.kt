package com.nononsenseapps.feeder.background

import com.nononsenseapps.feeder.archmodel.BackendMode
import com.nononsenseapps.feeder.archmodel.BackendSelection
import com.nononsenseapps.feeder.miniflux.MinifluxSyncRunner
import com.nononsenseapps.feeder.util.logDebug

/** Job IDs/cadence remain generic; execution is routed once from the captured job-start selection. */
class BackendSyncDispatcher(
    private val coordinator: BackendSyncCoordinator,
    private val miniflux: MinifluxSyncRunner,
) {
    suspend fun sync(
        snapshot: BackendSelection,
        rss: suspend () -> Unit,
    ) {
        if (snapshot.mode == BackendMode.LOCAL_RSS) {
            coordinator.withRun(snapshot) { rss() }
        } else {
            // Includes waiting for an older RSS run. The existing M4d core keeps its own 120s/step limits.
            val result = miniflux.sync(snapshot)
            logDebug(LOG_TAG, "Miniflux background result=${result.overall} error=${result.errorType ?: "none"}")
        }
    }

    companion object {
        private const val LOG_TAG = "FEEDER_BACKEND_SYNC"
    }
}
