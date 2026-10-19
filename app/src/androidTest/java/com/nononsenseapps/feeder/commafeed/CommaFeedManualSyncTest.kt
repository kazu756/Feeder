package com.nononsenseapps.feeder.commafeed

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nononsenseapps.feeder.ApplicationCoroutineScope
import com.nononsenseapps.feeder.archmodel.FeedItemStore
import com.nononsenseapps.feeder.db.room.FeedItemDao
import com.nononsenseapps.feeder.ui.TestDatabaseRule
import com.nononsenseapps.feeder.ui.compose.settings.CommaFeedReadSyncError
import com.nononsenseapps.feeder.ui.compose.settings.CommaFeedReadSyncState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.singleton
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CommaFeedManualSyncTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @get:Rule
    val testDb = TestDatabaseRule(app)
    private val testScope = ApplicationCoroutineScope()
    private val server = MockWebServer()
    private val credentials = CommaFeedCredentialStore(app, "feeder_commafeed_manual_test", "commafeed_manual_test")
    private val prefs = app.getSharedPreferences("commafeed_manual_test", 0)
    private lateinit var manual: CommaFeedManualSync
    private val account get() = "${server.url("/")}\nuser"

    @Before
    fun setup() {
        credentials.clearPassword()
        prefs
            .edit()
            .clear()
            .putString("base_url", server.url("/").toString())
            .putString("username", "user")
            .commit()
        val di =
            DI {
                bind<Application>() with instance(app)
                bind<ApplicationCoroutineScope>() with instance(testScope)
                bind<CommaFeedCredentialStore>() with instance(credentials)
                bind<CommaFeedRestClient>() with singleton { CommaFeedRestClient(OkHttpClient()) }
                bind<FeedItemDao>() with instance(testDb.db.feedItemDao())
                bind<FeedItemStore>() with singleton { FeedItemStore(di) }
                bind<CommaFeedReadSyncService>() with singleton { CommaFeedReadSyncService(di) }
            }
        manual = CommaFeedManualSync(di, "commafeed_manual_test")
    }

    @After
    fun cleanup() {
        testScope.cancel()
        credentials.clearPassword()
        prefs.edit().clear().commit()
        server.shutdown()
    }

    @Test
    fun absentPasswordPromptsWithoutNetworkRequest() {
        manual.requestSync()
        awaitIdle()
        assertTrue(manual.prompt.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun savedPasswordStartsWithoutDialogAndNoChangesSucceed() {
        credentials.savePassword("test-secret", account)
        enqueueEmpty()
        manual.requestSync()
        awaitIdle()
        assertFalse(manual.prompt.value)
        val result = assertIs<CommaFeedReadSyncState.Success>(manual.syncState.value).result
        assertEquals(0, result.localMarkedRead + result.remoteMarkedRead)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun optInSavesAndOptOutDoesNotSave() {
        enqueueEmpty()
        manual.submitPassword("once-only", false)
        awaitIdle()
        assertFalse(credentials.hasSavedPassword())
        enqueueEmpty()
        manual.submitPassword("saved-secret", true)
        awaitIdle()
        assertTrue(manual.saved.value)
        assertEquals("saved-secret", credentials.loadPassword(account))
        assertFalse(prefs.all.toString().contains("saved-secret"))
        assertFalse(
            manual.syncState.value
                .toString()
                .contains("saved-secret"),
        )
    }

    @Test
    fun settingsAndMainShareConcurrentExecutionGuard() {
        server.enqueue(MockResponse().setBody(emptyPage).setBodyDelay(400, TimeUnit.MILLISECONDS))
        manual.runSync(server.url("/").toString(), "user", "test-secret", false)
        manual.requestSync()
        manual.runDiagnostic(server.url("/").toString(), "user", "test-secret", false)
        awaitIdle()
        assertEquals(1, server.requestCount)
        assertIs<CommaFeedReadSyncState.Success>(manual.syncState.value)
    }

    @Test
    fun authenticationInvalidatesSavedCredentialButNetworkDoesNot() {
        credentials.savePassword("test-secret", account)
        server.enqueue(MockResponse().setResponseCode(401))
        manual.requestSync()
        awaitIdle()
        assertEquals(CommaFeedReadSyncError.AUTHENTICATION, assertIs<CommaFeedReadSyncState.Failed>(manual.syncState.value).error)
        assertFalse(manual.saved.value)
        manual.requestSync()
        awaitIdle()
        assertTrue(manual.prompt.value)
        credentials.savePassword("test-secret", account)
        server.shutdown()
        manual.requestSync()
        awaitIdle()
        assertEquals(CommaFeedReadSyncError.NETWORK, assertIs<CommaFeedReadSyncState.Failed>(manual.syncState.value).error)
        assertTrue(manual.saved.value)
    }

    @Test
    fun corruptCredentialShowsReentryErrorWithoutRequest() {
        credentials.savePassword("test-secret", account)
        File(app.noBackupFilesDir, "commafeed_manual_test").writeBytes(byteArrayOf(1))
        manual.requestSync()
        awaitIdle()
        assertEquals(CommaFeedReadSyncError.CREDENTIAL_UNAVAILABLE, assertIs<CommaFeedReadSyncState.Failed>(manual.syncState.value).error)
        assertFalse(manual.saved.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun missingConfigurationInvalidUrlAndEmptyPasswordDoNotStartSync() {
        prefs.edit().putString("base_url", "").commit()
        manual.requestSync()
        awaitIdle()
        assertEquals(CommaFeedReadSyncError.MISSING_CONFIGURATION, assertIs<CommaFeedReadSyncState.Failed>(manual.syncState.value).error)
        prefs.edit().putString("base_url", "ftp://example.com").commit()
        manual.requestSync()
        awaitIdle()
        assertEquals(CommaFeedReadSyncError.INVALID_URL, assertIs<CommaFeedReadSyncState.Failed>(manual.syncState.value).error)
        prefs.edit().putString("base_url", server.url("/").toString()).commit()
        manual.submitPassword("", false)
        awaitIdle()
        assertEquals(CommaFeedReadSyncError.EMPTY_PASSWORD, assertIs<CommaFeedReadSyncState.Failed>(manual.syncState.value).error)
        assertEquals(0, server.requestCount)
    }

    private fun enqueueEmpty() {
        server.enqueue(MockResponse().setBody(emptyPage))
    }

    private fun awaitIdle() {
        runBlocking { withTimeout(15000) { manual.busy.first { !it } } }
    }

    private val emptyPage = """{"offset":0,"limit":500,"hasMore":false,"entries":[]}"""
}
