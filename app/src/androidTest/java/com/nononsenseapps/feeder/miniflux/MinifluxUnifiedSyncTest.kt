package com.nononsenseapps.feeder.miniflux

import android.content.Context
import android.content.ContextWrapper
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.singleton
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class MinifluxUnifiedSyncTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val suffix = UUID.randomUUID().toString()
    private val root = File(app.cacheDir, "unified_$suffix").apply { mkdirs() }
    private val context =
        object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir(): File = root

            override fun getNoBackupFilesDir(): File = File(root, "credentials").apply { mkdirs() }

            override fun getDatabasePath(name: String): File = File(root, "db/$name").also { it.parentFile?.mkdirs() }
        }
    private var cache = MinifluxCacheStore(context, suffix)
    private val api =
        MinifluxApiClient(
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .retryOnConnectionFailure(false)
                .readTimeout(250, TimeUnit.MILLISECONDS)
                .build(),
        )
    private val server = MockWebServer().apply { start() }
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val putBodies = CopyOnWriteArrayList<String>()
    private val t = Instant.parse("2026-10-23T00:00:00Z")
    private val category = MinifluxCategory(99, "News")
    private val feed = MinifluxFeed(5000, "Feed", "https://example.com/rss", category)

    @Volatile private var rows = listOf(entry())

    @Volatile private var commit = "bcb2cf2aa29c3d1289fe889fb5fae2c255953bd9"

    @Volatile private var versionText = "2.3.x-dev"

    @Volatile private var versionCode = 200

    @Volatile private var feedPresent = true

    @Volatile private var putCode = 204

    @Volatile private var readPutCode = 204

    @Volatile private var starPutCode = 204

    @Volatile private var accountCode = 200

    @Volatile private var currentStage = "account"

    @Volatile private var failCache = false

    @Volatile private var failReconciliation = false

    @Volatile private var stallCache = false

    @Volatile private var stallAllLists = false

    @Volatile private var stallReconciliation = false

    @Volatile private var reconciliationDelayMillis = 0L
    private val reconciliationLists =
        java.util.concurrent.atomic
            .AtomicInteger()

    @Volatile private var getCode = 200

    @Volatile private var confirmCode = 200

    @Volatile private var applyPut = true

    @Volatile private var stallPut = false

    @Volatile private var stallConfirm = false

    @Volatile private var onPut: (() -> Unit)? = null
    private var getCount = 0

    private fun entry(
        id: Long = 9000,
        starred: Boolean = false,
    ) = MinifluxEntry(id, 5000, "Title", "unread", starred, t.toString(), t.plusSeconds(1).toString(), "https://example.com/$id", content = "<p>Body</p>")

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val url = request.requestUrl!!
                    if (request.method == "PUT") {
                        if (url.encodedPath != "/v1/entries") return MockResponse().setResponseCode(405)
                        val body = request.body.readUtf8()
                        putBodies += body
                        val responseCode = if (body.contains("starred")) starPutCode else readPutCode
                        if (applyPut && responseCode == 204) {
                            if (body.contains("starred")) {
                                val update = Json.decodeFromString<MinifluxStarUpdate>(body)
                                rows = rows.map { if (it.id in update.entryIds) it.copy(starred = update.starred) else it }
                            } else {
                                val update = Json.decodeFromString<MinifluxReadUpdate>(body)
                                rows = rows.map { if (it.id in update.entryIds) it.copy(status = update.status) else it }
                            }
                        }
                        onPut?.invoke()
                        if (stallPut) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        return MockResponse().setResponseCode(responseCode)
                    }
                    if (request.method != "GET") return MockResponse().setResponseCode(405)
                    return when (url.encodedPath) {
                        "/v1/me" -> MockResponse().setResponseCode(accountCode).setBody("""{"id":42,"username":"fixture"}""")
                        "/v1/version" -> MockResponse().setResponseCode(versionCode).setBody(Json.encodeToString(MinifluxVersion(versionText, commit, "2099-01-01")))
                        "/v1/entries/ids" -> {
                            if (currentStage == "reconciliation") reconciliationLists.incrementAndGet()
                            if (currentStage == "reconciliation" && stallReconciliation) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                            val ids =
                                rows
                                    .filter {
                                        (
                                            url.queryParameter(
                                                "status",
                                            ) == null ||
                                                it.status == url.queryParameter("status")
                                        ) &&
                                            (url.queryParameter("starred") == null || it.starred)
                                    }.map { it.id }
                                    .sortedDescending()
                            MockResponse().setBody(
                                Json.encodeToString(MinifluxEntryIds(ids.size.toLong(), ids.drop(url.queryParameter("offset")!!.toInt()).take(url.queryParameter("limit")!!.toInt()))),
                            )
                        }
                        "/v1/feeds" ->
                            if (currentStage == "reconciliation" &&
                                failReconciliation
                            ) {
                                MockResponse().setResponseCode(500)
                            } else {
                                MockResponse().setBody(Json.encodeToString(if (feedPresent) listOf(feed) else emptyList()))
                            }
                        "/v1/categories" -> MockResponse().setBody(Json.encodeToString(listOf(category)))
                        "/v1/entries" -> {
                            if (currentStage == "reconciliation") reconciliationLists.incrementAndGet()
                            if (currentStage == "reconciliation" && stallReconciliation) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                            if (stallAllLists) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                            if (currentStage == "cache" && stallCache) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                            if (currentStage == "cache" && failCache) return MockResponse().setResponseCode(500)
                            val cursor = url.queryParameter("before_entry_id")?.toLong()
                            val lower = url.queryParameter("changed_after")?.toLong()
                            val upper = url.queryParameter("changed_before")?.toLong()
                            val filtered =
                                rows
                                    .filter {
                                        (cursor == null || it.id < cursor) &&
                                            (url.queryParameter("status") == null || it.status == url.queryParameter("status")) &&
                                            (url.queryParameter("starred") == null || it.starred == url.queryParameter("starred").toBoolean()) &&
                                            (lower == null || Instant.parse(it.changedAt).epochSecond > lower) &&
                                            (upper == null || Instant.parse(it.changedAt).epochSecond < upper)
                                    }.let { values -> if (url.queryParameter("order") == "changed_at") values.sortedByDescending { it.changedAt } else values.sortedByDescending { it.id } }
                            MockResponse()
                                .setBody(Json.encodeToString(MinifluxEntries(filtered.size.toLong(), filtered.take(url.queryParameter("limit")?.toInt() ?: 100))))
                                .setHeadersDelay(if (currentStage == "reconciliation") reconciliationDelayMillis else 0, TimeUnit.MILLISECONDS)
                        }
                        else -> {
                            val id = url.encodedPath.substringAfterLast('/').toLongOrNull() ?: return MockResponse().setResponseCode(404)
                            getCount++
                            val code = if (getCount > 1) confirmCode else getCode
                            if (getCount > 1 && stallConfirm) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                            if (code != 200) return MockResponse().setResponseCode(code)
                            val row = rows.firstOrNull { it.id == id } ?: return MockResponse().setResponseCode(404)
                            MockResponse().setBody(Json.encodeToString(row))
                        }
                    }
                }
            }
    }

    private fun mutations() = MinifluxStarMutationStore(cache)

    private fun sync(timeout: Long = 5000) = MinifluxStarSync(api, cache, mutations(), timeout)

    private suspend fun prepare(count: Int = 1): MinifluxProfile {
        val profile = cache.prepare(server.url("/").toString(), MinifluxAccount(42, "fixture"))
        cache.saveCategories(profile, listOf(category))
        cache.saveFeed(profile, feed, category)
        repeat(count) { cache.saveEntry(profile, entry(9000L + it)) }
        return cache.complete(profile, t.toString())
    }

    private fun coordinator(
        client: MinifluxApiClient = api,
        timeout: Long = 20000,
        cacheTimeout: Long = 10000,
        reconciliationTimeout: Long = 10000,
    ) = MinifluxUnifiedSync(
        client,
        cache,
        MinifluxReadSync(client, cache, MinifluxReadMutationStore(cache)),
        MinifluxStarSync(client, cache, mutations()),
        MinifluxCacheSync(client, cache),
        timeoutMillis = timeout,
        mutationTimeoutMillis = 10000,
        cacheTimeoutMillis = cacheTimeout,
        reconciliationTimeoutMillis = reconciliationTimeout,
    )

    private suspend fun runUnified(profile: MinifluxProfile): MinifluxUnifiedSyncResult = coordinator().sync(profile.baseUrl, "fixture-token") { currentStage = it.stage }

    private suspend fun pending(
        profile: MinifluxProfile,
        read: Boolean = true,
        star: Boolean = true,
    ) {
        if (read) MinifluxReadMutationStore(cache).setStatus(profile.id, 9000, "read")
        if (star) mutations().setStarred(profile.id, 9000, true)
    }

    private fun assertMutationScope() {
        requests.filter { it.method != "GET" }.forEach {
            assertEquals("PUT", it.method)
            assertEquals("/v1/entries", it.requestUrl!!.encodedPath)
        }
        putBodies.forEach { assertTrue(it.contains("status") xor it.contains("starred")) }
        assertEquals(0, requests.count { it.path!!.contains("bookmark") || it.method == "DELETE" || it.method == "POST" })
    }

    @Test fun readOnlyStarOnlyBothAndNothingCompleteWithoutDeadlock() =
        runBlocking<Unit> {
            val profile = prepare()
            for ((read, star) in listOf(true to false, false to true, true to true, false to false)) {
                pending(profile, read, star)
                val result = withTimeout(15000) { runUnified(profile) }
                assertEquals(if (read || star) MinifluxUnifiedOverall.SUCCESS else MinifluxUnifiedOverall.NOTHING_TO_DO, result.overall)
                assertEquals(if (read) 1 else 0, result.read.value!!.confirmed)
                assertEquals(if (star) 1 else 0, result.star.value!!.confirmed)
                assertEquals(MinifluxUnifiedStepState.SUCCESS, result.incremental.state)
                assertEquals(MinifluxUnifiedStepState.SUCCESS, result.reconciliation.state)
                assertEquals(0, result.readPending)
                assertEquals(0, result.starPending)
            }
            assertMutationScope()
            assertEquals(
                4,
                cache
                    .database(profile.id)
                    .openHelper.writableDatabase.version,
            )
        }

    @Test fun independentFailuresContinueOtherFieldAndGetStagesWithoutRetry() =
        runBlocking<Unit> {
            val profile = prepare()
            for ((readCode, starCode) in listOf(204 to 500, 500 to 204, 429 to 204)) {
                readPutCode = readCode
                starPutCode = starCode
                pending(profile)
                val before = putBodies.size
                val result = runUnified(profile)
                assertEquals(MinifluxUnifiedOverall.PARTIAL_SUCCESS, result.overall)
                assertEquals(if (readCode == 204) 0 else 1, result.readPending)
                assertEquals(if (starCode == 204) 0 else 1, result.starPending)
                assertEquals(2, putBodies.size - before)
                assertEquals(MinifluxUnifiedStepState.SUCCESS, result.incremental.state)
                assertEquals(MinifluxUnifiedStepState.SUCCESS, result.reconciliation.state)
            }
            assertMutationScope()
        }

    @Test fun incrementalFailureKeepsAcksButNeverAdvancesCheckpoint() =
        runBlocking<Unit> {
            val profile = prepare()
            pending(profile)
            val dao = cache.database(profile.id).cacheDao()
            val checkpoint = dao.profile()!!.changedCheckpoint
            failCache = true
            val result = runUnified(profile)
            assertEquals(MinifluxUnifiedOverall.PARTIAL_SUCCESS, result.overall)
            assertEquals(1, result.read.value!!.confirmed)
            assertEquals(1, result.star.value!!.confirmed)
            assertEquals(0, result.readPending)
            assertEquals(0, result.starPending)
            assertEquals(checkpoint, dao.profile()!!.changedCheckpoint)
            assertEquals(MinifluxUnifiedStepState.FAILED, result.incremental.state)
        }

    @Test fun reconciliationFailureDoesNotRollBackIncrementalCheckpoint() =
        runBlocking<Unit> {
            val profile = prepare()
            pending(profile)
            failReconciliation = true
            val result = runUnified(profile)
            assertEquals(MinifluxUnifiedOverall.PARTIAL_SUCCESS, result.overall)
            assertEquals(MinifluxUnifiedStepState.SUCCESS, result.incremental.state)
            assertEquals(MinifluxUnifiedStepState.FAILED, result.reconciliation.state)
            assertEquals("SERVER", result.reconciliation.errorType)
            assertEquals(null, result.timeoutSource)
            assertEquals(
                t.plusSeconds(1).toString(),
                cache
                    .database(profile.id)
                    .cacheDao()
                    .profile()!!
                    .changedCheckpoint,
            )
        }

    @Test fun unknownStarCapabilitySkipsOnlyStarAndProtectsOptimisticState() =
        runBlocking<Unit> {
            val profile = prepare()
            pending(profile)
            commit = "unknown"
            val result = runUnified(profile)
            assertEquals(MinifluxUnifiedOverall.PARTIAL_SUCCESS, result.overall)
            assertEquals(1, result.read.value!!.confirmed)
            assertEquals(MinifluxUnifiedStepState.SKIPPED, result.star.state)
            assertEquals(1, result.starPending)
            assertTrue(cache.articles(profile.id, 0).single().effectiveStarred)
            assertFalse(
                cache
                    .articles(profile.id, 0)
                    .single()
                    .identity.remoteStarred,
            )
            assertEquals(1, putBodies.size)
            assertMutationScope()
        }

    @Test fun snapshotsDoNotChaseNewEntriesOrNewGenerationsDuringSending() =
        runBlocking<Unit> {
            val profile = prepare(2)
            rows = listOf(entry(), entry(9001))
            pending(profile)
            var injected = false
            onPut = {
                if (!injected) {
                    injected = true
                    runBlocking {
                        val reads = MinifluxReadMutationStore(cache)
                        reads.setStatus(profile.id, 9000, "unread")
                        reads.setStatus(profile.id, 9001, "read")
                        mutations().setStarred(profile.id, 9000, false)
                        mutations().setStarred(profile.id, 9001, true)
                    }
                }
            }
            val result = runUnified(profile)
            assertEquals(MinifluxUnifiedOverall.PARTIAL_SUCCESS, result.overall)
            assertEquals(2, putBodies.size)
            assertTrue(putBodies.none { it.contains("9001") })
            val dao = cache.database(profile.id).cacheDao()
            assertEquals(2L, dao.pending(profile.id, 9000)!!.generation)
            assertEquals(2L, dao.starPending(profile.id, 9000)!!.generation)
            assertEquals("unread", cache.articles(profile.id, 0).first { it.identity.remoteEntryId == 9000L }.effectiveStatus)
            assertFalse(cache.articles(profile.id, 0).first { it.identity.remoteEntryId == 9000L }.effectiveStarred)
            cache.close()
            cache = MinifluxCacheStore(context, suffix)
            assertEquals(
                2,
                cache
                    .database(profile.id)
                    .cacheDao()
                    .pending(profile.id)
                    .size,
            )
            assertEquals(
                2,
                cache
                    .database(profile.id)
                    .cacheDao()
                    .starPending(profile.id)
                    .size,
            )
        }

    @Test fun deletionCandidatesNeverExecuteFeedOrEntryCleanup() =
        runBlocking<Unit> {
            val profile = prepare()
            rows = listOf(entry().copy(status = "removed"))
            val removed = runUnified(profile)
            assertEquals(1, removed.reconciliation.value!!.removedEntries)
            assertEquals(1, cache.summary(profile).entries)
            rows = emptyList()
            feedPresent = false
            val missing = runUnified(profile)
            assertEquals(1, missing.reconciliation.value!!.missingFeeds)
            assertEquals(1, cache.summary(profile).feeds)
            assertEquals(1, cache.summary(profile).entries)
            assertMutationScope()
        }

    @Test fun authenticationAndOfflineFinishEarlyWithPendingIntact() =
        runBlocking<Unit> {
            val profile = prepare()
            pending(profile)
            accountCode = 401
            val result = runUnified(profile)
            assertEquals(MinifluxUnifiedOverall.FAILED, result.overall)
            assertEquals(1, requests.size)
            assertEquals(1, result.readPending)
            assertEquals(1, result.starPending)
            accountCode = 200
            val offline = MinifluxApiClient(OkHttpClient.Builder().addInterceptor { throw java.net.ConnectException("fixture offline") }.build())
            val failed = coordinator(offline).sync(profile.baseUrl, "fixture-token")
            assertEquals(MinifluxUnifiedOverall.FAILED, failed.overall)
            assertEquals("CONNECTION", failed.errorType)
            assertEquals(1, failed.readPending)
            assertEquals(1, failed.starPending)
            assertEquals(
                t.toString(),
                cache
                    .database(profile.id)
                    .cacheDao()
                    .profile()!!
                    .changedCheckpoint,
            )
        }

    @Test fun initialAndPartialBaselinePreservePendingAndUseInitialCachePath() =
        runBlocking<Unit> {
            val profile = prepare()
            val dao = cache.database(profile.id).cacheDao()
            dao.saveProfile(profile.copy(lastSuccessfulSync = null, changedCheckpoint = null))
            pending(profile)
            readPutCode = 500
            starPutCode = 500
            val result = runUnified(profile)
            assertEquals("initial", result.incremental.value!!.mode)
            assertEquals(1, result.readPending)
            assertEquals(1, result.starPending)
            val article = cache.articles(profile.id, 0).single()
            assertEquals("read", article.effectiveStatus)
            assertTrue(article.effectiveStarred)
        }

    @Test fun fiveHundredPendingPerFieldOnlySendsFiftyReadsAndTwentyStars() =
        runBlocking<Unit> {
            val profile = prepare()
            pending(profile)
            val db = cache.database(profile.id)
            val dao = db.cacheDao()
            db.withTransaction {
                val identity = dao.entryIdentity(profile.id, 9000)!!
                val item = dao.item(identity.localFeedItemId)!!
                for (id in 9001L..9499L) {
                    val local = dao.insertItem(item.copy(id = 0, guid = "miniflux:${profile.id}:$id"))
                    dao.insertEntryIdentity(identity.copy(remoteEntryId = id, localFeedItemId = local, blobReady = false))
                    dao.savePending(MinifluxReadOutbox(profile.id, id, "read", 1, id, id))
                    dao.saveStarPending(MinifluxStarOutbox(profile.id, id, true, 1, id, id))
                }
            }
            rows = (9000L..9499L).map { entry(it) }
            val result = runUnified(profile)
            assertEquals(50, result.read.value!!.requested)
            assertEquals(50, result.read.value!!.confirmed)
            assertEquals(20, result.star.value!!.confirmed)
            assertEquals(450, result.readPending)
            assertEquals(480, result.starPending)
            assertEquals(21, putBodies.size) // One 50-ID read batch, twenty single-ID starred PUTs.
            assertEquals(MinifluxUnifiedOverall.PARTIAL_SUCCESS, result.overall)
            assertMutationScope()
        }

    @Test fun controllerDoubleTapCancellationAndDeadlineAlwaysReleaseBusy() =
        runBlocking<Unit> {
            val profile = prepare()
            val credentials = MinifluxCredentialStore(context, "unified_$suffix", suffix)
            credentials.saveToken(profile.baseUrl, "fixture-token")
            val slow =
                MinifluxApiClient(
                    OkHttpClient
                        .Builder()
                        .readTimeout(20, TimeUnit.SECONDS)
                        .retryOnConnectionFailure(false)
                        .build(),
                )
            try {
                for (cancel in listOf(true, false)) {
                    pending(profile)
                    stallAllLists = true
                    requests.clear()
                    putBodies.clear()
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    try {
                        val controller =
                            MinifluxCacheController(
                                cache,
                                credentials,
                                MinifluxCacheSync(slow, cache),
                                scope,
                                MinifluxReadSync(slow, cache, MinifluxReadMutationStore(cache)),
                                MinifluxReadMutationStore(cache),
                                unifiedSync = coordinator(slow, if (cancel) 15000 else 3000),
                            )
                        controller.syncAll(profile.baseUrl)
                        controller.syncAll(profile.baseUrl)
                        withTimeout(10000) { while (requests.none { it.method == "GET" && it.requestUrl!!.encodedPath == "/v1/entries" }) delay(10) }
                        if (cancel) controller.cancel()
                        withTimeout(6000) { while (controller.state.value.busy) delay(10) }
                        val result = controller.state.value.unifiedResult!!
                        assertEquals(if (cancel) MinifluxUnifiedOverall.CANCELLED else MinifluxUnifiedOverall.PARTIAL_SUCCESS, result.overall)
                        assertEquals(if (cancel) "CANCELLED" else "TIMEOUT", result.errorType)
                        assertEquals(2, putBodies.size)
                        assertEquals(0, result.readPending)
                        assertEquals(0, result.starPending)
                        assertEquals(
                            t.toString(),
                            cache
                                .database(profile.id)
                                .cacheDao()
                                .profile()!!
                                .changedCheckpoint,
                        )
                    } finally {
                        scope.cancel()
                    }
                }
            } finally {
                credentials.clearToken()
            }
        }

    @Test fun lockContentionIsBoundedAndPublicWorkersRemainUsable() =
        runBlocking<Unit> {
            val profile = prepare()
            pending(profile)
            cache.operationMutex.lock()
            try {
                val result = coordinator(timeout = 500).sync(profile.baseUrl, "fixture-token")
                assertEquals("TIMEOUT", result.errorType)
                assertEquals(MinifluxUnifiedOverall.FAILED, result.overall)
                assertEquals(0, requests.size)
            } finally {
                cache.operationMutex.unlock()
            }
            assertEquals(1, MinifluxReadSync(api, cache, MinifluxReadMutationStore(cache)).syncWithResult(profile, "fixture-token").confirmed)
            assertEquals(1, sync().sync(profile, "fixture-token").confirmed)
            assertNotNull(MinifluxCacheSync(api, cache).sync(profile.baseUrl, "fixture-token") { _, _ -> })
            assertTrue(MinifluxCacheSync(api, cache).deletionDryRun(profile, "fixture-token").complete)
        }

    @Test fun credentialsFailureAndCancelledScopeDoNotStickProcessing() =
        runBlocking<Unit> {
            val profile = prepare()
            val credentials = MinifluxCredentialStore(context, "unified_$suffix", suffix)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val controller =
                MinifluxCacheController(
                    cache,
                    credentials,
                    MinifluxCacheSync(api, cache),
                    scope,
                    MinifluxReadSync(api, cache, MinifluxReadMutationStore(cache)),
                    MinifluxReadMutationStore(cache),
                    unifiedSync = coordinator(),
                )
            controller.syncAll(profile.baseUrl)
            withTimeout(3000) { while (controller.state.value.busy) delay(10) }
            assertEquals(
                "MISSING_TOKEN",
                controller.state.value.unifiedResult!!
                    .errorType,
            )
            assertEquals(
                MinifluxUnifiedOverall.FAILED,
                controller.state.value.unifiedResult!!
                    .overall,
            )
            scope.cancel()
            controller.syncAll(profile.baseUrl)
            withTimeout(3000) { while (controller.state.value.busy) delay(10) }
            assertEquals(
                MinifluxUnifiedOverall.CANCELLED,
                controller.state.value.unifiedResult!!
                    .overall,
            )
            assertEquals(0, requests.size)
        }

    @Test fun unrelatedHistoryTimesOutLegacyWalkButUnifiedAndIndividualFinishWithinSameBudget() =
        runBlocking<Unit> {
            val profile = prepare()
            // Large fixture serialization must not hit the separate 250 ms HTTP-failure fixture.
            val client =
                MinifluxApiClient(
                    OkHttpClient
                        .Builder()
                        .readTimeout(5, TimeUnit.SECONDS)
                        .retryOnConnectionFailure(false)
                        .build(),
                )
            rows = (1L..30000L).map { if (it == 9000L) entry(it).copy(title = "Updated remotely") else entry(it).copy(status = "read", changedAt = t.minusSeconds(60).toString()) }
            reconciliationDelayMillis = 20
            currentStage = "reconciliation"
            // Reproduce the former unfiltered whole-history walk under a scaled test deadline.
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(3000) {
                    var cursor: Long? = null
                    while (true) {
                        val page = client.cacheEntries(server.url("/"), "fixture-token", beforeId = cursor)
                        if (page.entries.isEmpty()) break
                        cursor = page.entries.last().id
                    }
                }
            }
            requests.clear()
            val result = coordinator(client, reconciliationTimeout = 3000).sync(profile.baseUrl, "fixture-token") { currentStage = it.stage }
            assertEquals(MinifluxUnifiedStepState.SUCCESS, result.incremental.state)
            assertEquals(1, result.incremental.value!!.updated)
            assertEquals(MinifluxUnifiedStepState.SUCCESS, result.reconciliation.state)
            assertEquals("complete", result.reconciliationTrace!!.phase)
            assertTrue(result.reconciliationTrace!!.requestsStarted > 0)
            assertEquals(0, result.reconciliation.value!!.pages)
            assertEquals(0, result.reconciliation.value!!.unverified)
            assertEquals(null, result.timeoutSource)
            requests.clear()
            val individual = withTimeout(3000) { MinifluxCacheSync(client, cache).deletionDryRun(profile, "fixture-token") }
            assertTrue(individual.complete)
            assertTrue(individual.entryEnumerationComplete)
            assertEquals(0, individual.pages)
            assertTrue(requests.none { it.requestUrl!!.encodedPath == "/v1/entries" })
            assertTrue(requests.filter { it.requestUrl!!.encodedPath == "/v1/entries/ids" }.all { it.requestUrl!!.queryParameter("limit") == "10000" })
            assertMutationScope()
        }

    @Test fun sparseLocalIdsSkipUnrelatedGapsWithoutLosingRemovedOrStarredProtection() =
        runBlocking<Unit> {
            val profile = prepare()
            cache.saveEntry(profile, entry(2_000_000))
            rows = (1L..30000L).map { entry(it).copy(status = "read", changedAt = t.minusSeconds(60).toString()) } + entry(2_000_000).copy(status = "removed", starred = true)
            currentStage = "reconciliation"
            val result = MinifluxCacheSync(api, cache).deletionDryRun(profile, "fixture-token")
            assertTrue(result.complete)
            assertEquals(0, result.pages)
            assertEquals(1, result.removedEntries)
            assertTrue(result.entries.single().protectedByStarred)
            assertEquals(2, cache.summary(profile).entries)
            assertMutationScope()
        }

    @Test fun reconciliationStepHttpAndOverallTimeoutsAreDistinguishedAndReleaseBusy() =
        runBlocking<Unit> {
            val profile = prepare()
            stallReconciliation = true
            val slow =
                MinifluxApiClient(
                    OkHttpClient
                        .Builder()
                        .readTimeout(20, TimeUnit.SECONDS)
                        .retryOnConnectionFailure(false)
                        .build(),
                )
            for ((client, overall, step, expected) in listOf(
                listOf(slow, 20000L, 500L, "STEP:reconciliation"),
                listOf(api, 20000L, 10000L, "HTTP:reconciliation"),
                listOf(slow, 1500L, 10000L, "OVERALL"),
            )) {
                val credentials = MinifluxCredentialStore(context, "recon_$suffix", suffix)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    credentials.saveToken(profile.baseUrl, "fixture-token")
                    val controller =
                        MinifluxCacheController(
                            cache,
                            credentials,
                            MinifluxCacheSync(api, cache),
                            scope,
                            MinifluxReadSync(api, cache, MinifluxReadMutationStore(cache)),
                            MinifluxReadMutationStore(cache),
                            unifiedSync = coordinator(client as MinifluxApiClient, overall as Long, reconciliationTimeout = step as Long),
                        )
                    controller.syncAll(profile.baseUrl)
                    withTimeout(8000) {
                        while (controller.state.value.busy) {
                            currentStage = controller.state.value.stage
                            delay(10)
                        }
                    }
                    val result = controller.state.value.unifiedResult!!
                    assertEquals(MinifluxUnifiedOverall.PARTIAL_SUCCESS, result.overall)
                    assertEquals("TIMEOUT", result.reconciliation.errorType)
                    assertEquals(expected as String, result.timeoutSource)
                    assertTrue(result.reconciliationTrace!!.requestsStarted > 0)
                    assertEquals("ids_request", result.reconciliationTrace!!.lastRequestPhase)
                    assertTrue(cache.operationMutex.tryLock())
                    cache.operationMutex.unlock()
                } finally {
                    scope.cancel()
                    credentials.clearToken()
                }
            }
        }

    @Test fun reconciliationHoldingSharedLockExcludesBothPublicDryRunAndAnotherUnifiedRun() =
        runBlocking<Unit> {
            val profile = prepare()
            stallReconciliation = true
            val slow = MinifluxApiClient(OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build())
            val trace =
                java.util.concurrent.atomic
                    .AtomicReference<MinifluxReconciliationTrace?>()
            val owner =
                async(Dispatchers.IO) {
                    coordinator(slow).sync(profile.baseUrl, "fixture-token") {
                        currentStage = it.stage
                        trace.set(it.reconciliationTrace)
                    }
                }
            withTimeout(5000) { while (trace.get()?.lastRequestPhase != "ids_request" || reconciliationLists.get() == 0) delay(10) }
            val count = requests.size
            val individual = async(Dispatchers.IO) { MinifluxCacheSync(api, cache).deletionDryRun(profile, "fixture-token") }
            val blocked = coordinator(timeout = 300).sync(profile.baseUrl, "fixture-token")
            assertEquals("OVERALL", blocked.timeoutSource)
            assertEquals(count, requests.size)
            assertFalse(individual.isCompleted)
            stallReconciliation = false
            owner.cancelAndJoin()
            assertTrue(withTimeout(5000) { individual.await() }.complete)
            assertMutationScope()
        }

    @Test fun sharedUnifiedEntryRejectsDuplicateMainAndSettingsRefresh() =
        runBlocking<Unit> {
            val profile = prepare()
            stallReconciliation = true
            val slow = MinifluxApiClient(OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build())
            val unified = coordinator(slow)
            val owner = async(Dispatchers.IO) { unified.sync(profile.baseUrl, "fixture-token") { currentStage = it.stage } }
            withTimeout(5000) { while (reconciliationLists.get() == 0) delay(10) }
            assertTrue(unified.busy.value)
            val count = requests.size
            assertEquals("ALREADY_RUNNING", unified.syncPinned(profile.baseUrl, "fixture-token", profile.id).errorType)
            assertEquals(count, requests.size)
            owner.cancelAndJoin()
            assertFalse(unified.busy.value)
        }

    @Test fun pinnedMainRefreshRejectsChangedAccountBeforePreparingOrSendingPending() =
        runBlocking<Unit> {
            val profile = prepare()
            MinifluxReadMutationStore(cache).setStatus(profile.id, 9000, "read")
            val before = cache.database(profile.id).cacheDao().profile()
            val result = coordinator().syncPinned(profile.baseUrl, "fixture-token", "a".repeat(64))
            assertEquals(MinifluxUnifiedOverall.FAILED, result.overall)
            assertEquals(before, cache.database(profile.id).cacheDao().profile())
            assertEquals(
                1,
                cache
                    .database(profile.id)
                    .cacheDao()
                    .pending(profile.id)
                    .size,
            )
            assertTrue(requests.all { it.method == "GET" && it.requestUrl!!.encodedPath == "/v1/me" })
        }

    @Test fun mainRefreshUsesExistingUnifiedEntryAndRetainsCacheAndPendingOnFailure() =
        runBlocking<Unit> {
            val profile = prepare()
            val prefs = app.getSharedPreferences("main_refresh_$suffix", 0)
            val modes =
                com.nononsenseapps.feeder.archmodel
                    .BackendModeStore(prefs)
            modes.commit(
                com.nononsenseapps.feeder.archmodel
                    .BackendSelection(com.nononsenseapps.feeder.archmodel.BackendMode.MINIFLUX, profile.id),
            )
            val credentials = MinifluxCredentialStore(context, "main_refresh_$suffix", "main_refresh_$suffix")
            val fixtureScope = com.nononsenseapps.feeder.ApplicationCoroutineScope()
            val unified = coordinator()
            val source = MinifluxMainSource(cache, app as android.app.Application)
            val testDi =
                org.kodein.di.DI {
                    extend((app as com.nononsenseapps.feeder.FeederApplication).di)
                    bind<android.content.SharedPreferences>(overrides = true) with instance(prefs)
                    bind<com.nononsenseapps.feeder.archmodel.BackendModeStore>(overrides = true) with instance(modes)
                    bind<MinifluxCacheStore>(overrides = true) with instance(cache)
                    bind<MinifluxCredentialStore>(overrides = true) with instance(credentials)
                    bind<MinifluxUnifiedSync>(overrides = true) with instance(unified)
                    bind<MinifluxMainSource>(overrides = true) with instance(source)
                    bind<com.nononsenseapps.feeder.ApplicationCoroutineScope>(overrides = true) with instance(fixtureScope)
                    bind<com.nononsenseapps.feeder.archmodel.SettingsStore>(overrides = true) with
                        singleton {
                            com.nononsenseapps.feeder.archmodel
                                .SettingsStore(di)
                                .also {
                                    it.setAddedFeederNews(true)
                                    it.setFeedListFilterRead(false)
                                    it.setFeedListFilterRecentlyRead(false)
                                }
                        }
                }
            val repository =
                com.nononsenseapps.feeder.archmodel
                    .Repository(testDi)
            try {
                credentials.saveToken(profile.baseUrl, "fixture-token")
                rows = listOf(entry().copy(title = "Main updated", status = "read", starred = true))
                repository.refreshMinifluxMain()
                withTimeout(10000) { while (requests.isEmpty() || unified.busy.value) delay(10) }
                val local =
                    cache
                        .database(profile.id)
                        .cacheDao()
                        .entryIdentity(profile.id, 9000)!!
                        .localFeedItemId
                val key =
                    com.nononsenseapps.feeder.archmodel
                        .ArticleKey(local, profile.id)
                assertEquals("Main updated", source.article(key).first()!!.title)
                assertTrue(source.article(key).first()!!.bookmarked)
                assertEquals(0, repository.getCurrentFeedListVisibleItemCount().first())
                MinifluxReadMutationStore(cache).setStatus(profile.id, 9000, "unread")
                mutations().setStarred(profile.id, 9000, false)
                val before = cache.database(profile.id).cacheDao().profile()
                accountCode = 401
                val count = requests.size
                repository.refreshMinifluxMain()
                withTimeout(10000) { while (requests.size == count || unified.busy.value) delay(10) }
                assertEquals("Main updated", source.article(key).first()!!.title)
                assertEquals(1, repository.getCurrentFeedListVisibleItemCount().first())
                assertEquals(
                    1,
                    cache
                        .database(profile.id)
                        .cacheDao()
                        .pending(profile.id)
                        .size,
                )
                assertEquals(
                    1,
                    cache
                        .database(profile.id)
                        .cacheDao()
                        .starPending(profile.id)
                        .size,
                )
                assertEquals(before, cache.database(profile.id).cacheDao().profile())
            } finally {
                fixtureScope.cancel()
                credentials.clearToken()
                prefs.edit().clear().commit()
            }
        }

    @After fun cleanupFixture() {
        cache.close()
        server.shutdown()
        root.deleteRecursively()
        app
            .getSharedPreferences(suffix, 0)
            .edit()
            .clear()
            .commit()
    }
}
