package com.nononsenseapps.feeder.miniflux

import com.nononsenseapps.feeder.util.logDebug

data class MinifluxReconciliationRequest(
    val endpoint: String,
    val status: String? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val cursor: Long? = null,
    val page: Int = 1,
    val total: Long? = null,
    val count: Int? = null,
    val elapsedMillis: Long = 0,
    val finished: Boolean = false,
)

/** Metadata only. Never receives a URL, token, title, body or exception message. */
internal class MinifluxReconciliationDiagnostics(
    private val progress: (MinifluxReconciliationTrace) -> Unit,
) {
    private val started = System.nanoTime()
    private var requestStarted = started
    private var value = MinifluxReconciliationTrace("worker_entered", 0, 0, 0)

    fun phase(
        phase: String,
        pages: Int = value.pages,
        mode: String = value.mode,
    ) {
        val pending = value.lastRequest?.takeUnless { it.finished }?.copy(elapsedMillis = (System.nanoTime() - requestStarted) / 1_000_000)
        value = value.copy(phase = phase, pages = pages, mode = mode, elapsedMillis = (System.nanoTime() - started) / 1_000_000, lastRequest = pending ?: value.lastRequest)
        logDebug(LOG_TAG, "phase=$phase elapsedMs=${value.elapsedMillis} requests=${value.requestsStarted} pages=$pages mode=$mode")
        progress(value)
    }

    fun start(
        request: MinifluxReconciliationRequest,
        phase: String,
    ) {
        requestStarted = System.nanoTime()
        value =
            value.copy(
                requestsStarted = value.requestsStarted + 1,
                lastRequestPhase = phase,
                lastRequest = request,
                individualGets =
                    value.individualGets + if (request.endpoint == "individual_get") 1 else 0,
            )
        logDebug(LOG_TAG, "request_start $request")
        phase(phase)
    }

    fun end(
        total: Long? = null,
        count: Int? = null,
    ) {
        val request = checkNotNull(value.lastRequest).copy(total = total, count = count, elapsedMillis = (System.nanoTime() - requestStarted) / 1_000_000, finished = true)
        value =
            value.copy(
                lastRequest = request,
                readIdsTotal = if (request.endpoint == "ids" && request.status == "read") total else value.readIdsTotal,
                readIdsPages = value.readIdsPages + if (request.endpoint == "ids" && request.status == "read") 1 else 0,
                unreadIdsTotal = if (request.endpoint == "ids" && request.status == "unread") total else value.unreadIdsTotal,
                unreadIdsPages = value.unreadIdsPages + if (request.endpoint == "ids" && request.status == "unread") 1 else 0,
            )
        logDebug(LOG_TAG, "request_end $request")
        phase(value.phase)
    }

    companion object {
        private const val LOG_TAG = "FEEDER_RECONCILE"
    }
}
