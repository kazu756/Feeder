package com.nononsenseapps.feeder.miniflux

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.BackendMode
import com.nononsenseapps.feeder.archmodel.BackendModeStore
import com.nononsenseapps.feeder.archmodel.BackendSelection
import com.nononsenseapps.feeder.ui.compose.settings.BackendModeSection
import com.nononsenseapps.feeder.ui.compose.utils.WithAllPreviewProviders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class BackendModeUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val suffix = UUID.randomUUID().toString()
    private val prefs = context.getSharedPreferences("backend_ui_$suffix", 0)
    private val modes = BackendModeStore(prefs)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = MinifluxCacheStore(context, "backend_ui_cache_$suffix")
    private val credentials = MinifluxCredentialStore(context, "backend_ui_$suffix", "backend_ui_$suffix")
    private val controller = BackendModeController(modes, BackendModeSwitchService(modes, credentials, MinifluxDiagnosticService(MinifluxApiClient()), cache), scope)

    private fun show() {
        compose.setContent { WithAllPreviewProviders { Column { BackendModeSection(controller, "https://example.org") } } }
    }

    @Test fun localDisplayConfirmationCancellationAndPreflightFailure() {
        show()
        compose.onNodeWithTag("backend_LOCAL_RSS").assertIsSelected()
        compose.onNodeWithTag("backend_MINIFLUX").performClick()
        compose.onNodeWithText(context.getString(R.string.backend_confirm_miniflux)).assertExists()
        compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        assertEquals(BackendSelection(), modes.selection.value)
        compose.onNodeWithTag("backend_MINIFLUX").performClick()
        compose.onNodeWithTag("backend_confirm").performClick()
        compose.waitUntil(5000) { !controller.state.value.busy && controller.state.value.connectionError != null }
        compose.onNodeWithText(context.getString(R.string.miniflux_missing_token)).assertExists()
        compose.onNodeWithTag("backend_LOCAL_RSS").assertIsSelected()
    }

    @Test fun offlineMinifluxDisplayAndReturnToLocal() {
        modes.commit(BackendSelection(BackendMode.MINIFLUX, "a".repeat(64)))
        show()
        compose.onNodeWithTag("backend_MINIFLUX").assertIsSelected()
        compose.onNodeWithTag("backend_LOCAL_RSS").performClick()
        compose.onNodeWithTag("backend_confirm").performClick()
        compose.waitUntil(5000) { !controller.state.value.busy && controller.state.value.result != null }
        compose.onNodeWithTag("backend_LOCAL_RSS").assertIsSelected()
        assertEquals(BackendSelection(), BackendModeStore(prefs).selection.value)
    }

    @After fun cleanup() {
        scope.cancel()
        cache.close()
        credentials.clearToken()
        prefs.edit().clear().commit()
        context
            .getSharedPreferences("backend_ui_cache_$suffix", 0)
            .edit()
            .clear()
            .commit()
    }
}
