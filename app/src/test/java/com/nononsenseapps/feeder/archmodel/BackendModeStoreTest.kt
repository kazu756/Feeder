package com.nononsenseapps.feeder.archmodel

import android.content.SharedPreferences
import com.nononsenseapps.feeder.util.logDebug
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackendModeStoreTest {
    private val values = mutableMapOf<String, String?>()
    private val staged = mutableMapOf<String, String?>()
    private val prefs = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    private var writesSucceed = true

    @Before fun setup() {
        mockkStatic("com.nononsenseapps.feeder.util.LoggingKt")
        justRun { logDebug(any(), any(), any()) }
        every { prefs.getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
        every { prefs.edit() } answers {
            staged.clear()
            editor
        }
        every { editor.putString(any(), any()) } answers {
            staged[firstArg()] = secondArg()
            editor
        }
        every { editor.remove(any()) } answers {
            staged[firstArg()] = null
            editor
        }
        every { editor.commit() } answers {
            if (writesSucceed) values.putAll(staged)
            writesSucceed
        }
    }

    @After fun cleanup() {
        unmockkStatic("com.nononsenseapps.feeder.util.LoggingKt")
    }

    @Test fun newAndExistingPreferencesDefaultToLocalWithoutWriting() {
        assertEquals(BackendSelection(), BackendModeStore(prefs).selection.value)
        values["existing_setting"] = "retained"
        assertEquals(BackendSelection(), BackendModeStore(prefs).selection.value)
        assertEquals<Map<String, String?>>(mapOf("existing_setting" to "retained"), values)
    }

    @Test fun roundTripRetainsModeAndProfileOfflineThenReturnsToLocal() {
        val store = BackendModeStore(prefs)
        val selected = BackendSelection(BackendMode.MINIFLUX, "a".repeat(64))
        store.commit(selected)
        assertEquals(selected, store.selection.value)
        assertEquals(selected, BackendModeStore(prefs).selection.value)
        store.commit(BackendSelection())
        assertEquals(BackendSelection(), BackendModeStore(prefs).selection.value)
    }

    @Test fun unknownValueOrMalformedBindingFallsBackToLocal() {
        for (raw in listOf("UNKNOWN", "miniflux", "", "MINIFLUX")) {
            values[BackendModeStore.MODE_KEY] = raw
            values[BackendModeStore.PROFILE_KEY] = "invalid"
            assertEquals(BackendSelection(), BackendModeStore(prefs).selection.value)
        }
    }

    @Test fun corruptPreferenceTypeFallsBackToLocal() {
        every { prefs.getString(BackendModeStore.MODE_KEY, null) } throws ClassCastException()
        assertEquals(BackendSelection(), BackendModeStore(prefs).selection.value)
    }

    @Test fun failedPersistenceDoesNotPublishNewMode() {
        val store = BackendModeStore(prefs)
        writesSucceed = false
        assertFailsWith<IllegalStateException> { store.commit(BackendSelection(BackendMode.MINIFLUX, "a".repeat(64))) }
        assertEquals(BackendSelection(), store.selection.value)
        assertEquals(BackendSelection(), BackendModeStore(prefs).selection.value)
    }

    @Test fun minifluxCannotBeSavedWithoutValidatedProfileBinding() {
        val store = BackendModeStore(prefs)
        assertFailsWith<IllegalArgumentException> { store.commit(BackendSelection(BackendMode.MINIFLUX)) }
        assertEquals(BackendSelection(), store.selection.value)
    }
}
