package com.nononsenseapps.feeder.background

import com.nononsenseapps.feeder.archmodel.BackendMode
import com.nononsenseapps.feeder.archmodel.BackendSelection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One immutable backend/profile per run, including nested RSS/Sync Chain entry points. */
internal class SyncBackendRun(
    val selection: BackendSelection,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SyncBackendRun>
}

class BackendSyncCoordinator(
    private val selection: () -> BackendSelection,
) {
    private val pipelineMutex = Mutex()

    suspend fun currentSelection(): BackendSelection = currentCoroutineContext()[SyncBackendRun]?.selection ?: selection()

    suspend fun allowsLocalRss(): Boolean = currentSelection().mode == BackendMode.LOCAL_RSS

    suspend fun <T> withCurrentRun(action: suspend (BackendSelection) -> T): T = withRun(currentSelection(), action)

    suspend fun <T> withRun(
        snapshot: BackendSelection,
        action: suspend (BackendSelection) -> T,
    ): T {
        val active = currentCoroutineContext()[SyncBackendRun]
        if (active != null) return action(active.selection)
        return pipelineMutex.withLock {
            withContext(SyncBackendRun(snapshot)) { action(snapshot) }
        }
    }
}
