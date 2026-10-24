package com.nononsenseapps.feeder.archmodel

import android.content.SharedPreferences
import com.nononsenseapps.feeder.util.logDebug
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BackendMode { LOCAL_RSS, MINIFLUX }

data class BackendSelection(
    val mode: BackendMode = BackendMode.LOCAL_RSS,
    val minifluxProfileId: String? = null,
)

/** One application-scoped source of truth. Preferences contain no credential. */
class BackendModeStore(
    private val preferences: SharedPreferences,
) {
    private val mutableSelection = MutableStateFlow(readSelection())
    val selection = mutableSelection.asStateFlow()

    private fun readSelection(): BackendSelection =
        try {
            val raw = preferences.getString(MODE_KEY, null)
            val mode = BackendMode.entries.firstOrNull { it.name == raw }
            val profile = preferences.getString(PROFILE_KEY, null)
            if (raw != null && (mode == null || mode == BackendMode.MINIFLUX && !validProfile(profile))) {
                logDebug(LOG_TAG, "Invalid backend preference; using LOCAL_RSS")
                BackendSelection()
            } else {
                BackendSelection(mode ?: BackendMode.LOCAL_RSS, if (mode == BackendMode.MINIFLUX) profile else null)
            }
        } catch (_: ClassCastException) {
            logDebug(LOG_TAG, "Invalid backend preference type; using LOCAL_RSS")
            BackendSelection()
        }

    internal fun commit(value: BackendSelection) {
        require(value.mode != BackendMode.MINIFLUX || validProfile(value.minifluxProfileId))
        val editor = preferences.edit().putString(MODE_KEY, value.mode.name)
        if (value.mode == BackendMode.MINIFLUX) editor.putString(PROFILE_KEY, value.minifluxProfileId) else editor.remove(PROFILE_KEY)
        if (!editor.commit()) {
            // SharedPreferences updates its in-memory map even if the disk write fails.
            val old = mutableSelection.value
            val rollback = preferences.edit().putString(MODE_KEY, old.mode.name)
            if (old.mode == BackendMode.MINIFLUX) rollback.putString(PROFILE_KEY, old.minifluxProfileId) else rollback.remove(PROFILE_KEY)
            rollback.commit()
            error("Backend preference write failed")
        }
        mutableSelection.value = value
    }

    companion object {
        internal const val MODE_KEY = "backend_mode"
        internal const val PROFILE_KEY = "backend_miniflux_profile"
        private const val LOG_TAG = "FEEDER_BACKEND_MODE"

        private fun validProfile(value: String?) = value != null && Regex("[a-f0-9]{64}").matches(value)
    }
}
