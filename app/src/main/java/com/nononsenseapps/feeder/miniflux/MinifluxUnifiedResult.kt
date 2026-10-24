package com.nononsenseapps.feeder.miniflux

/** Last-resort Controller interruption must retain earlier commits and close every running step. */
internal fun MinifluxUnifiedSyncResult.interrupted(
    code: String,
    cancelled: Boolean = false,
): MinifluxUnifiedSyncResult {
    fun <T> stopped(step: MinifluxUnifiedStep<T>): MinifluxUnifiedStep<T> =
        if (step.state == MinifluxUnifiedStepState.RUNNING) {
            step.copy(state = if (cancelled) MinifluxUnifiedStepState.CANCELLED else MinifluxUnifiedStepState.FAILED, errorType = code)
        } else {
            step
        }
    val committed =
        listOf(read.state, star.state, incremental.state, reconciliation.state).any { it == MinifluxUnifiedStepState.SUCCESS } ||
            (read.value?.confirmed ?: 0) > 0 ||
            (star.value?.confirmed ?: 0) > 0
    return copy(
        read = stopped(read),
        star = stopped(star),
        incremental = stopped(incremental),
        reconciliation = stopped(reconciliation),
        overall =
            when {
                cancelled -> MinifluxUnifiedOverall.CANCELLED
                committed -> MinifluxUnifiedOverall.PARTIAL_SUCCESS
                else -> MinifluxUnifiedOverall.FAILED
            },
        errorType = code,
        timeoutSource = if (code == "TIMEOUT") "CONTROLLER" else timeoutSource,
    )
}
