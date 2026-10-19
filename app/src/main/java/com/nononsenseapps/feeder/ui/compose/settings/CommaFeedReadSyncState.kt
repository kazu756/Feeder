package com.nononsenseapps.feeder.ui.compose.settings

import com.nononsenseapps.feeder.commafeed.ReadSyncResult

sealed interface CommaFeedReadSyncState {
    data object Idle : CommaFeedReadSyncState

    data object Running : CommaFeedReadSyncState

    data class Success(
        val result: ReadSyncResult,
    ) : CommaFeedReadSyncState

    data class Failed(
        val error: CommaFeedReadSyncError,
    ) : CommaFeedReadSyncState
}

enum class CommaFeedReadSyncError {
    INVALID_URL,
    AUTHENTICATION,
    NETWORK,
    FETCH_HTTP,
    MALFORMED_RESPONSE,
    PROCESSING,
}
