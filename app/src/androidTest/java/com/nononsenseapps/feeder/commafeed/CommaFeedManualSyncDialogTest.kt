package com.nononsenseapps.feeder.commafeed

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.ui.compose.feed.CommaFeedManualSyncDialog
import com.nononsenseapps.feeder.ui.compose.settings.CommaFeedReadSyncState
import org.junit.Rule
import org.junit.Test

class CommaFeedManualSyncDialogTest {
    @get:Rule
    val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun partialFailureDistinguishesRemoteAndLocalWrites() {
        val result = ReadSyncResult(3, 3, 3, 0, 1, 0, 0, 0, 0, 0, 1, 1, false)
        show(CommaFeedReadSyncState.Success(result))
        compose.onNodeWithText(context.getString(R.string.commafeed_manual_partial, 1, 2)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.commafeed_sync_remote_write_error)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.commafeed_sync_local_write_error)).assertIsDisplayed()
    }

    @Test
    fun noChangesArePresentedAsSuccess() {
        show(CommaFeedReadSyncState.Success(ReadSyncResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false)))
        compose.onNodeWithText(context.getString(R.string.commafeed_manual_unchanged)).assertIsDisplayed()
    }

    private fun show(state: CommaFeedReadSyncState) {
        compose.setContent {
            MaterialTheme {
                CommaFeedManualSyncDialog(false, state, false, { _, _ -> }, {}, {})
            }
        }
    }
}
