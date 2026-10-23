package com.nononsenseapps.feeder.miniflux

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.ui.compose.settings.MinifluxDiagnosticSection
import com.nononsenseapps.feeder.ui.compose.utils.WithAllPreviewProviders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(AndroidJUnit4::class)
class MinifluxSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val suffix = UUID.randomUUID().toString()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store = MinifluxCredentialStore(context, "feeder_miniflux_ui_$suffix", "miniflux_ui_$suffix")
    private val controller by lazy {
        MinifluxSettingsController(context, store, MinifluxDiagnosticService(MinifluxApiClient()), scope, "miniflux_ui_$suffix")
    }

    @After fun cleanup() {
        scope.cancel()
        store.clearToken()
        context
            .getSharedPreferences("miniflux_ui_$suffix", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test fun savedTokenIsNeverRepopulatedAndCorruptCredentialStaysRecoverable() {
        val secret = "secret-ui-$suffix"
        store.saveToken("https://example.com", secret)
        compose.setContent { WithAllPreviewProviders { Column(Modifier.verticalScroll(rememberScrollState())) { MinifluxDiagnosticSection(controller) } } }
        val label = context.getString(R.string.miniflux_api_token)
        assertEquals(
            "",
            compose
                .onNodeWithText(label)
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text,
        )
        compose.onNodeWithText(secret).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.miniflux_base_url)).performTextInput("https://example.com")
        compose.onNodeWithText(label).performTextInput("replacement-$suffix")
        compose.onNodeWithText(context.getString(R.string.miniflux_save_token)).performScrollTo().performClick()
        compose.waitUntil(10000) { !controller.state.value.busy && controller.state.value.saved }
        assertEquals(
            "",
            compose
                .onNodeWithText(label)
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text,
        )
        assertFalse(
            controller.state.value
                .toString()
                .contains(secret),
        )
        assertFalse(
            controller.state.value
                .toString()
                .contains("replacement-$suffix"),
        )
        File(context.noBackupFilesDir, "miniflux_ui_$suffix").writeBytes(byteArrayOf(1))
        compose.onNodeWithText(context.getString(R.string.miniflux_check)).performScrollTo().performClick()
        compose.waitUntil(10000) { controller.state.value.error == MinifluxError.CREDENTIAL_UNAVAILABLE }
        compose.onNodeWithText(context.getString(R.string.miniflux_delete_token)).performScrollTo().performClick()
        compose.waitUntil(10000) { !controller.state.value.busy && !controller.state.value.saved }
    }

    @Test fun cancelledScopeDoesNotLeaveDiagnosticBusy() {
        scope.cancel()
        controller.check("https://example.com")
        compose.waitUntil(10000) { !controller.state.value.busy }
    }
}
