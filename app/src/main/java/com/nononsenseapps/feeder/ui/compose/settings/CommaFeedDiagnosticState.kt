package com.nononsenseapps.feeder.ui.compose.settings

import com.nononsenseapps.feeder.commafeed.DiagnosticResult

sealed interface CommaFeedDiagnosticState {
    data object Idle : CommaFeedDiagnosticState

    data object Running : CommaFeedDiagnosticState

    data class Success(
        val result: DiagnosticResult,
    ) : CommaFeedDiagnosticState

    data class Failed(
        val error: CommaFeedDiagnosticError,
    ) : CommaFeedDiagnosticState
}

enum class CommaFeedDiagnosticError {
    CONNECTION_REFUSED,
    TIMEOUT,
    AUTHENTICATION,
    HTTP,
    INVALID_RESPONSE,
    PAGINATION,
    INVALID_URL,
    PARTIAL_FAILURE,
}
