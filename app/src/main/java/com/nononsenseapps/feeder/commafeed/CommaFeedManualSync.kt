package com.nononsenseapps.feeder.commafeed

import android.app.Application
import com.nononsenseapps.feeder.ApplicationCoroutineScope
import com.nononsenseapps.feeder.ui.compose.settings.CommaFeedDiagnosticError
import com.nononsenseapps.feeder.ui.compose.settings.CommaFeedDiagnosticState
import com.nononsenseapps.feeder.ui.compose.settings.CommaFeedReadSyncError
import com.nononsenseapps.feeder.ui.compose.settings.CommaFeedReadSyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/** Shared, user-initiated execution for Settings and the article list. Never schedules sync. */
class CommaFeedManualSync(
    override val di: DI,
    preferencesName: String = "commafeed_diagnostic",
) : DIAware {
    private val context: Application by instance()
    private val scope: ApplicationCoroutineScope by instance()
    private val credentials: CommaFeedCredentialStore by instance()
    private val client: CommaFeedRestClient by instance()
    private val diagnostic: CommaFeedDiagnosticService by instance()
    private val sync: CommaFeedReadSyncService by instance()
    private val prefs by lazy { context.getSharedPreferences(preferencesName, 0) }
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableSaved = MutableStateFlow(credentials.hasSavedPassword())
    val saved = mutableSaved.asStateFlow()
    private val mutablePrompt = MutableStateFlow(false)
    val prompt = mutablePrompt.asStateFlow()
    private val mutableDiagnostic = MutableStateFlow<CommaFeedDiagnosticState>(CommaFeedDiagnosticState.Idle)
    val diagnosticState = mutableDiagnostic.asStateFlow()
    private val mutableSync = MutableStateFlow<CommaFeedReadSyncState>(CommaFeedReadSyncState.Idle)
    val syncState = mutableSync.asStateFlow()
    val baseUrl: String get() = prefs.getString("base_url", "") ?: ""
    val username: String get() = prefs.getString("username", "") ?: ""

    fun dismissPrompt() {
        mutablePrompt.value = false
    }

    fun clearPassword() {
        if (!mutableBusy.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                credentials.clearPassword()
                mutableSaved.value = credentials.hasSavedPassword()
            } finally {
                mutableBusy.value = false
            }
        }
    }

    fun requestSync() = run(baseUrl, username, "", false, false, true)

    fun submitPassword(
        password: String,
        save: Boolean,
    ) = run(baseUrl, username, password, save, false, false)

    fun runDiagnostic(
        baseUrl: String,
        username: String,
        password: String,
        save: Boolean,
    ) = run(baseUrl, username, password, save, true, false)

    fun runSync(
        baseUrl: String,
        username: String,
        password: String,
        save: Boolean,
    ) = run(baseUrl, username, password, save, false, false)

    private fun run(
        baseUrl: String,
        username: String,
        password: String,
        save: Boolean,
        readOnly: Boolean,
        allowPrompt: Boolean,
    ) {
        if (!mutableBusy.compareAndSet(false, true)) return
        mutablePrompt.value = false
        mutableSync.value = CommaFeedReadSyncState.Idle
        mutableDiagnostic.value = CommaFeedDiagnosticState.Idle
        scope.launch(Dispatchers.IO) {
            try {
                if (baseUrl.isBlank() || username.isBlank()) {
                    mutableSync.value = CommaFeedReadSyncState.Failed(CommaFeedReadSyncError.MISSING_CONFIGURATION)
                    return@launch
                }
                val origin = client.normalizedBaseUrl(baseUrl).toString()
                val user = username.trim()
                val account = "$origin\n$user"
                prefs
                    .edit()
                    .putString("base_url", origin)
                    .putString("username", user)
                    .apply()
                val secret =
                    if (password.isNotEmpty()) {
                        if (save) credentials.savePassword(password, account)
                        password
                    } else {
                        credentials.loadPassword(account)
                    }
                if (secret.isNullOrEmpty()) {
                    mutablePrompt.value = allowPrompt
                    if (!allowPrompt) mutableSync.value = CommaFeedReadSyncState.Failed(CommaFeedReadSyncError.EMPTY_PASSWORD)
                    return@launch
                }
                if (readOnly) {
                    mutableDiagnostic.value = CommaFeedDiagnosticState.Running
                    mutableDiagnostic.value = CommaFeedDiagnosticState.Success(diagnostic.run(origin, user, secret))
                } else {
                    mutableSync.value = CommaFeedReadSyncState.Running
                    val result = sync.run(origin, user, secret)
                    if (result.authenticationFailed) credentials.clearPassword()
                    mutableSync.value = CommaFeedReadSyncState.Success(result)
                }
            } catch (e: CancellationException) {
                mutableSync.value = CommaFeedReadSyncState.Idle
                mutableDiagnostic.value = CommaFeedDiagnosticState.Idle
                throw e
            } catch (e: Exception) {
                if (e is CommaFeedFailure.Authentication) credentials.clearPassword()
                if (readOnly && e !is CommaFeedCredentialUnavailable) {
                    mutableDiagnostic.value =
                        CommaFeedDiagnosticState.Failed(
                            when (e) {
                                is CommaFeedFailure.Authentication -> CommaFeedDiagnosticError.AUTHENTICATION
                                is CommaFeedFailure.Http -> CommaFeedDiagnosticError.HTTP
                                is CommaFeedFailure.InvalidResponse -> CommaFeedDiagnosticError.INVALID_RESPONSE
                                is CommaFeedFailure.Pagination -> CommaFeedDiagnosticError.PAGINATION
                                is SocketTimeoutException -> CommaFeedDiagnosticError.TIMEOUT
                                is ConnectException -> CommaFeedDiagnosticError.CONNECTION_REFUSED
                                is InvalidCommaFeedBaseUrl -> CommaFeedDiagnosticError.INVALID_URL
                                else -> CommaFeedDiagnosticError.PARTIAL_FAILURE
                            },
                        )
                } else {
                    mutableSync.value =
                        CommaFeedReadSyncState.Failed(
                            when (e) {
                                is CommaFeedCredentialUnavailable -> CommaFeedReadSyncError.CREDENTIAL_UNAVAILABLE
                                is InvalidCommaFeedBaseUrl -> CommaFeedReadSyncError.INVALID_URL
                                is CommaFeedFailure.Authentication -> CommaFeedReadSyncError.AUTHENTICATION
                                is CommaFeedFailure.InvalidResponse, is CommaFeedFailure.Pagination -> CommaFeedReadSyncError.MALFORMED_RESPONSE
                                is CommaFeedFailure.Http -> CommaFeedReadSyncError.FETCH_HTTP
                                is IOException -> CommaFeedReadSyncError.NETWORK
                                else -> CommaFeedReadSyncError.PROCESSING
                            },
                        )
                }
            } finally {
                mutableSaved.value = credentials.hasSavedPassword()
                mutableBusy.value = false
            }
        }
    }
}
