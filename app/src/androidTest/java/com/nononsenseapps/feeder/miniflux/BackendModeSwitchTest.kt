package com.nononsenseapps.feeder.miniflux

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nononsenseapps.feeder.archmodel.BackendMode
import com.nononsenseapps.feeder.archmodel.BackendModeStore
import com.nononsenseapps.feeder.archmodel.BackendSelection
import com.nononsenseapps.feeder.db.room.AppDatabase
import com.nononsenseapps.feeder.db.room.Feed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class BackendModeSwitchTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val suffix = UUID.randomUUID().toString()
    private val root = File(app.cacheDir, "backend_$suffix").apply { mkdirs() }
    private val context =
        object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir() = root

            override fun getNoBackupFilesDir() = File(root, "credentials").apply { mkdirs() }

            override fun getDatabasePath(name: String) = File(root, "db/$name").also { it.parentFile?.mkdirs() }
        }
    private val prefs = context.getSharedPreferences("backend_$suffix", 0)
    private val modes = BackendModeStore(prefs)
    private val credentials = MinifluxCredentialStore(context, "backend_$suffix", "backend_$suffix")
    private val cache = MinifluxCacheStore(context, "selection_$suffix")
    private val server = MockWebServer().apply { start() }
    private val base get() = server.url("/").toString()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val api =
        MinifluxApiClient(
            OkHttpClient
                .Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build(),
        )
    private val service = BackendModeSwitchService(modes, credentials, MinifluxDiagnosticService(api), cache)
    private val category = MinifluxCategory(99, "Fixture")
    private val feed = MinifluxFeed(5000, "Fixture", "https://example.com/rss", category)
    private val time = "2026-10-24T00:00:00Z"
    private val entry = MinifluxEntry(9000, 5000, "Fixture", "unread", false, time, time, "https://example.com/9000", content = "<p>Body</p>")

    @Volatile private var account = 42L

    @Volatile private var status = 200

    @Volatile private var stall = false

    @Volatile private var onAccount: (() -> Unit)? = null

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    if (request.method != "GET") return MockResponse().setResponseCode(405)
                    return when (request.requestUrl!!.encodedPath) {
                        "/v1/me" ->
                            if (stall) {
                                MockResponse().setSocketPolicy(
                                    SocketPolicy.NO_RESPONSE,
                                )
                            } else {
                                onAccount?.invoke()
                                MockResponse().setResponseCode(status).setBody(Json.encodeToString(MinifluxAccount(account, "fixture")))
                            }
                        "/v1/feeds" -> MockResponse().setBody(Json.encodeToString(listOf(feed)))
                        "/v1/categories" -> MockResponse().setBody(Json.encodeToString(listOf(category)))
                        "/v1/entries" -> MockResponse().setBody(Json.encodeToString(MinifluxEntries(1, listOf(entry))))
                        "/v1/version" -> MockResponse().setBody("""{"version":"2.3.2","commit":"fixture"}""")
                        "/v1/entries/ids" -> MockResponse().setBody("""{"total":1,"entry_ids":[9000]}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
    }

    private suspend fun prepare(
        baseline: Boolean = true,
        withFeed: Boolean = true,
        withEntry: Boolean = true,
    ): MinifluxProfile {
        credentials.saveToken(base, "fixture-token")
        val profile = cache.prepare(base, MinifluxAccount(42, "fixture"))
        cache.saveCategories(profile, listOf(category))
        if (withFeed) cache.saveFeed(profile, feed, category)
        if (withEntry) cache.saveEntry(profile, entry)
        return if (baseline) cache.complete(profile, time) else profile
    }

    private suspend fun fingerprint(profile: MinifluxProfile): List<Any?> {
        val dao = cache.database(profile.id).cacheDao()
        return listOf(
            dao.profile(),
            dao.reconciliationFeeds(),
            dao.reconciliationLocalFeeds(),
            dao.reconciliationEntries(),
            dao.reconciliationItems(),
            dao.pending(
                profile.id,
            ),
            dao.starPending(profile.id),
            cache
                .articleDirectory(profile.id)
                .listFiles()
                ?.sortedBy {
                    it.name
                }?.map { Triple(it.name, it.lastModified(), it.readBytes().toList()) },
        )
    }

    @Test fun successfulRoundTripKeepsBothDatabasesPendingCheckpointsAndBodies() =
        runBlocking<Unit> {
            val profile = prepare()
            MinifluxReadMutationStore(cache).setStatus(profile.id, 9000, "read")
            MinifluxStarMutationStore(cache).setStarred(profile.id, 9000, true)
            val before = fingerprint(profile)
            val rss = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                val localId = rss.feedDao().insertFeed(Feed(title = "Local", url = URL("https://example.org/rss")))
                val localBefore = rss.feedDao().getFeed(localId)
                val changed = service.switch(BackendMode.MINIFLUX, base)
                assertEquals(1, changed.readPending)
                assertEquals(1, changed.starPending)
                assertEquals(BackendSelection(BackendMode.MINIFLUX, profile.id), modes.selection.value)
                assertEquals(modes.selection.value, BackendModeStore(prefs).selection.value)
                // Reopen does not construct a network service and works while the endpoint is offline.
                status = 503
                val count = requests.size
                assertEquals(BackendMode.MINIFLUX, BackendModeStore(prefs).selection.value.mode)
                assertEquals(count, requests.size)
                service.switch(BackendMode.LOCAL_RSS, "")
                assertEquals(count, requests.size)
                assertEquals(BackendSelection(), BackendModeStore(prefs).selection.value)
                assertEquals(before, fingerprint(profile))
                assertEquals(localBefore, rss.feedDao().getFeed(localId))
                assertEquals(40, rss.openHelper.readableDatabase.version)
                assertEquals(
                    4,
                    cache
                        .database(profile.id)
                        .openHelper.readableDatabase.version,
                )
                assertTrue(requests.all { it.method == "GET" })
            } finally {
                rss.close()
            }
        }

    @Test fun missingUrlTokenAndCorruptCredentialsCannotChangeMode() =
        runBlocking<Unit> {
            assertEquals(MinifluxError.MISSING_URL, assertFailsWith<MinifluxFailure> { service.switch(BackendMode.MINIFLUX, "") }.error)
            assertEquals(MinifluxError.MISSING_TOKEN, assertFailsWith<MinifluxFailure> { service.switch(BackendMode.MINIFLUX, base) }.error)
            credentials.saveToken(base, "fixture-token")
            File(context.noBackupFilesDir, "backend_$suffix").writeBytes(byteArrayOf(1))
            assertEquals(MinifluxError.CREDENTIAL_UNAVAILABLE, assertFailsWith<MinifluxFailure> { service.switch(BackendMode.MINIFLUX, base) }.error)
            assertEquals(BackendSelection(), modes.selection.value)
            assertTrue(requests.isEmpty())
        }

    @Test fun connectionAndUnauthorizedFailuresKeepOldMode() =
        runBlocking<Unit> {
            val profile = prepare()
            val before = fingerprint(profile)
            for ((code, error) in listOf(401 to MinifluxError.UNAUTHORIZED, 503 to MinifluxError.SERVER)) {
                status = code
                assertEquals(error, assertFailsWith<MinifluxFailure> { service.switch(BackendMode.MINIFLUX, base) }.error)
                assertEquals(BackendSelection(), modes.selection.value)
            }
            val target = base
            server.shutdown()
            assertEquals(MinifluxError.CONNECTION, assertFailsWith<MinifluxFailure> { service.switch(BackendMode.MINIFLUX, target) }.error)
            assertEquals(before, fingerprint(profile))
        }

    @Test fun missingEmptyIncompleteAndMismatchedCacheRejectsSwitch() =
        runBlocking<Unit> {
            credentials.saveToken(base, "fixture-token")
            assertEquals(BackendModeError.CACHE_REQUIRED, assertFailsWith<BackendModeFailure> { service.switch(BackendMode.MINIFLUX, base) }.error)
            var profile = prepare(baseline = false)
            assertEquals(BackendModeError.BASELINE_REQUIRED, assertFailsWith<BackendModeFailure> { service.switch(BackendMode.MINIFLUX, base) }.error)
            profile = cache.complete(profile, time)
            val dao = cache.database(profile.id).cacheDao()
            dao.saveProfile(profile.copy(bootstrapChangedAt = time))
            assertEquals(BackendModeError.BASELINE_REQUIRED, assertFailsWith<BackendModeFailure> { service.switch(BackendMode.MINIFLUX, base) }.error)
            dao.saveProfile(profile)
            account = 43
            assertEquals(BackendModeError.PROFILE_MISMATCH, assertFailsWith<BackendModeFailure> { service.switch(BackendMode.MINIFLUX, base) }.error)
            account = 42
            credentials.saveToken("https://other.example.org", "other-token")
            assertEquals(MinifluxError.CREDENTIAL_UNAVAILABLE, assertFailsWith<MinifluxFailure> { service.switch(BackendMode.MINIFLUX, base) }.error)
            assertEquals(BackendSelection(), modes.selection.value)
            assertEquals(profile, cache.selectedProfile())
        }

    @Test fun emptyCacheAndUnavailableBodyRejectSwitch() =
        runBlocking<Unit> {
            val profile = prepare(withFeed = false, withEntry = false)
            assertEquals(BackendModeError.CACHE_REQUIRED, assertFailsWith<BackendModeFailure> { service.switch(BackendMode.MINIFLUX, base) }.error)
            cache.saveFeed(profile, feed, category)
            assertEquals(BackendModeError.CACHE_REQUIRED, assertFailsWith<BackendModeFailure> { service.switch(BackendMode.MINIFLUX, base) }.error)
            cache.saveEntry(profile, entry)
            cache.articleDirectory(profile.id).listFiles()!!.forEach { it.delete() }
            assertEquals(BackendModeError.CACHE_UNAVAILABLE, assertFailsWith<BackendModeFailure> { service.switch(BackendMode.MINIFLUX, base) }.error)
            assertEquals(BackendSelection(), modes.selection.value)
        }

    @Test fun changedCredentialAndOtherServerCannotReuseSelectedProfile() =
        runBlocking<Unit> {
            val profile = prepare()
            val before = fingerprint(profile)
            onAccount = { credentials.saveToken(base, "replacement-token") }
            assertEquals(BackendModeError.PROFILE_MISMATCH, assertFailsWith<BackendModeFailure> { service.switch(BackendMode.MINIFLUX, base) }.error)
            onAccount = null
            val other =
                MockWebServer().apply {
                    dispatcher = server.dispatcher
                    start()
                }
            try {
                val otherBase = other.url("/").toString()
                credentials.saveToken(otherBase, "other-server-token")
                assertEquals(BackendModeError.PROFILE_MISMATCH, assertFailsWith<BackendModeFailure> { service.switch(BackendMode.MINIFLUX, otherBase) }.error)
                assertEquals(BackendSelection(), modes.selection.value)
                assertEquals(before, fingerprint(profile))
            } finally {
                other.shutdown()
            }
        }

    @Test fun unavailableDatabaseIsNotRecreatedByPreflight() =
        runBlocking<Unit> {
            val profile = prepare()
            cache.close()
            val file = context.getDatabasePath("miniflux_cache_${profile.id}.db")
            assertTrue(file.delete())
            assertEquals(BackendModeError.CACHE_REQUIRED, assertFailsWith<BackendModeFailure> { service.switch(BackendMode.MINIFLUX, base) }.error)
            assertFalse(file.exists())
            assertEquals(BackendSelection(), modes.selection.value)
        }

    @Test fun cancellationTimeoutAndConcurrentSwitchDoNotPersistOrLeaveBusy() =
        runBlocking<Unit> {
            prepare()
            stall = true
            val owner = async(Dispatchers.IO) { service.switch(BackendMode.MINIFLUX, base) }
            withTimeout(5000) { while (requests.isEmpty()) delay(10) }
            assertEquals(BackendModeError.BUSY, assertFailsWith<BackendModeFailure> { service.switch(BackendMode.LOCAL_RSS, "") }.error)
            owner.cancelAndJoin()
            assertEquals(BackendSelection(), modes.selection.value)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val cancelled = BackendModeController(modes, service, scope)
            val beforeCancel = requests.size
            cancelled.request(BackendMode.MINIFLUX, base)
            cancelled.confirm()
            withTimeout(5000) { while (requests.size == beforeCancel) delay(10) }
            cancelled.cancel()
            withTimeout(5000) { while (cancelled.state.value.busy) delay(10) }
            assertEquals(BackendModeError.CANCELLED, cancelled.state.value.error)
            assertEquals(BackendSelection(), modes.selection.value)
            val bounded = BackendModeSwitchService(modes, credentials, MinifluxDiagnosticService(api), cache, timeoutMillis = 250)
            val controller = BackendModeController(modes, bounded, scope)
            try {
                controller.request(BackendMode.MINIFLUX, base)
                controller.confirm()
                controller.confirm()
                withTimeout(5000) { while (controller.state.value.busy) delay(10) }
                assertEquals(BackendModeError.TIMEOUT, controller.state.value.error)
                assertEquals(BackendSelection(), modes.selection.value)
                assertTrue(cache.operationMutex.tryLock())
                cache.operationMutex.unlock()
                scope.cancel()
                controller.request(BackendMode.MINIFLUX, base)
                controller.confirm()
                withTimeout(5000) { while (controller.state.value.busy) delay(10) }
                assertFalse(controller.state.value.busy)
            } finally {
                scope.cancel()
            }
        }

    @After fun cleanup() {
        cache.close()
        server.shutdown()
        credentials.clearToken()
        prefs.edit().clear().commit()
        context
            .getSharedPreferences("selection_$suffix", 0)
            .edit()
            .clear()
            .commit()
        root.deleteRecursively()
    }
}
