package com.nononsenseapps.feeder.miniflux

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class MinifluxSettingsState(
    val baseUrl: String = "",
    val saved: Boolean = false,
    val busy: Boolean = false,
    val error: MinifluxError? = null,
    val result: MinifluxDiagnosticResult? = null,
)

class MinifluxSettingsController(
    context: Context,
    private val credentials: MinifluxCredentialStore,
    private val diagnostic: MinifluxDiagnosticService,
    private val scope: CoroutineScope,
    preferencesName: String = "miniflux_connection",
) {
    private val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val mutableState =
        MutableStateFlow(
            MinifluxSettingsState(
                baseUrl = prefs.getString("base_url", "") ?: "",
                saved = credentials.hasSavedToken(),
            ),
        )
    val state = mutableState.asStateFlow()
    private val active = AtomicBoolean(false)
    private var job: Job? = null

    fun save(
        baseUrl: String,
        token: String,
    ) = perform {
        val base = normalizedMinifluxUrl(baseUrl).toString()
        credentials.saveToken(base, token)
        if (!prefs.edit().putString("base_url", base).commit()) throw MinifluxFailure(MinifluxError.CREDENTIAL_UNAVAILABLE)
        mutableState.update { it.copy(baseUrl = base, saved = true) }
    }

    fun clear() =
        perform {
            credentials.clearToken()
            mutableState.update { it.copy(saved = false) }
        }

    fun check(baseUrl: String) =
        perform {
            val base = normalizedMinifluxUrl(baseUrl).toString()
            val token = credentials.loadToken(base) ?: throw MinifluxFailure(MinifluxError.MISSING_TOKEN)
            val result = diagnostic.run(base, token)
            mutableState.update { it.copy(result = result) }
        }

    fun cancel() {
        job?.cancel()
    }

    private fun perform(block: suspend () -> Unit) {
        if (!active.compareAndSet(false, true)) return
        mutableState.update { it.copy(busy = true, error = null, result = null) }
        job =
            scope
                .launch(Dispatchers.IO) {
                    try {
                        block()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: MinifluxFailure) {
                        mutableState.update { it.copy(error = e.error) }
                    }
                }.also { operation ->
                    // Also runs when the scope is cancelled before the coroutine starts.
                    operation.invokeOnCompletion {
                        mutableState.update { it.copy(busy = false, saved = credentials.hasSavedToken()) }
                        active.set(false)
                    }
                }
    }
}
