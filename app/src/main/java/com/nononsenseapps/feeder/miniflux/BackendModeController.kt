package com.nononsenseapps.feeder.miniflux

import com.nononsenseapps.feeder.archmodel.BackendMode
import com.nononsenseapps.feeder.archmodel.BackendModeStore
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
import java.util.concurrent.atomic.AtomicBoolean

data class BackendModeSwitchState(
    val confirmation: BackendMode? = null,
    val busy: Boolean = false,
    val result: BackendModeChange? = null,
    val error: BackendModeError? = null,
    val connectionError: MinifluxError? = null,
)

class BackendModeController(
    modes: BackendModeStore,
    private val service: BackendModeSwitchService,
    private val scope: CoroutineScope,
) {
    val selection = modes.selection
    private val mutableState = MutableStateFlow(BackendModeSwitchState())
    val state = mutableState.asStateFlow()
    private val active = AtomicBoolean(false)
    private var baseUrl = ""
    private var job: Job? = null

    fun request(
        target: BackendMode,
        baseUrl: String,
    ) {
        if (active.get() || state.value.confirmation != null || target == selection.value.mode) return
        this.baseUrl = baseUrl
        mutableState.value = BackendModeSwitchState(confirmation = target)
    }

    fun dismiss() {
        if (!active.get()) mutableState.value = BackendModeSwitchState()
    }

    fun confirm() {
        val target = state.value.confirmation ?: return
        if (!active.compareAndSet(false, true)) return
        val requestedBase = baseUrl
        mutableState.value = BackendModeSwitchState(busy = true)
        job =
            scope
                .launch(Dispatchers.IO) {
                    try {
                        val result = service.switch(target, requestedBase)
                        mutableState.update { it.copy(result = result) }
                    } catch (e: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        mutableState.update { it.copy(error = BackendModeError.TIMEOUT) }
                    } catch (e: CancellationException) {
                        mutableState.update { it.copy(error = BackendModeError.CANCELLED) }
                        throw e
                    } catch (e: MinifluxFailure) {
                        mutableState.update { it.copy(connectionError = e.error) }
                    } catch (e: BackendModeFailure) {
                        mutableState.update { it.copy(error = e.error) }
                    } catch (_: Exception) {
                        mutableState.update { it.copy(error = BackendModeError.CACHE_UNAVAILABLE) }
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
