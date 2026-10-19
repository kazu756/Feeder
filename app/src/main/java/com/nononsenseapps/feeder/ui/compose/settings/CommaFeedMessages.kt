package com.nononsenseapps.feeder.ui.compose.settings

import com.nononsenseapps.feeder.R

val CommaFeedReadSyncError.messageResource: Int
    get() =
        when (this) {
            CommaFeedReadSyncError.MISSING_CONFIGURATION -> R.string.commafeed_missing_configuration
            CommaFeedReadSyncError.CREDENTIAL_UNAVAILABLE -> R.string.commafeed_credential_unavailable
            CommaFeedReadSyncError.EMPTY_PASSWORD -> R.string.commafeed_empty_password
            CommaFeedReadSyncError.INVALID_URL -> R.string.commafeed_invalid_url
            CommaFeedReadSyncError.AUTHENTICATION -> R.string.commafeed_authentication_failed
            CommaFeedReadSyncError.NETWORK -> R.string.commafeed_sync_network_error
            CommaFeedReadSyncError.FETCH_HTTP -> R.string.commafeed_sync_fetch_error
            CommaFeedReadSyncError.MALFORMED_RESPONSE -> R.string.commafeed_invalid_response
            CommaFeedReadSyncError.PROCESSING -> R.string.commafeed_sync_processing_error
        }
