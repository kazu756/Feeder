package com.nononsenseapps.feeder.miniflux

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class MinifluxStarStateTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val suffix = UUID.randomUUID().toString()
    private val root = File(app.cacheDir, "star_$suffix").apply { mkdirs() }
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
                        if (applyPut) {
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
                        return MockResponse().setResponseCode(putCode)
                    }
                    if (request.method != "GET") return MockResponse().setResponseCode(405)
                    return when (url.encodedPath) {
                        "/v1/me" -> MockResponse().setBody("""{"id":42,"username":"fixture"}""")
                        "/v1/version" -> MockResponse().setResponseCode(versionCode).setBody(Json.encodeToString(MinifluxVersion(versionText, commit, "2099-01-01")))
                        "/v1/entries/ids" -> {
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
                        "/v1/feeds" -> MockResponse().setBody(Json.encodeToString(if (feedPresent) listOf(feed) else emptyList()))
                        "/v1/categories" -> MockResponse().setBody(Json.encodeToString(listOf(category)))
                        "/v1/entries" -> {
                            val cursor = url.queryParameter("before_entry_id")?.toLong()
                            val lower = url.queryParameter("changed_after")?.toLong()
                            val upper = url.queryParameter("changed_before")?.toLong()
                            val filtered =
                                rows
                                    .filter {
                                        (cursor == null || it.id < cursor) &&
                                            (lower == null || Instant.parse(it.changedAt).epochSecond > lower) &&
                                            (upper == null || Instant.parse(it.changedAt).epochSecond < upper)
                                    }.sortedByDescending { it.id }
                            MockResponse().setBody(Json.encodeToString(MinifluxEntries(filtered.size.toLong(), filtered.take(url.queryParameter("limit")?.toInt() ?: 100))))
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

    @Test fun offlineOptimisticCoalescingReopenAndGenerationSafeAck() =
        runBlocking<Unit> {
            val profile = prepare()
            val mutations = mutations()
            mutations.setStarred(profile.id, 9000, true)
            val old = cache.database(profile.id).cacheDao().starPending(profile.id, 9000)!!
            mutations.setStarred(profile.id, 9000, false)
            mutations.setStarred(profile.id, 9000, true)
            assertEquals(
                3L,
                cache
                    .database(profile.id)
                    .cacheDao()
                    .starPending(profile.id, 9000)!!
                    .generation,
            )
            assertFalse(mutations.observeRemote(profile.id, entry(starred = true), old))
            assertTrue(cache.articles(profile.id, 0).single().effectiveStarred)
            assertTrue(requests.isEmpty())
            cache.close()
            cache = MinifluxCacheStore(context, suffix)
            assertEquals(
                3L,
                cache
                    .database(profile.id)
                    .cacheDao()
                    .starPending(profile.id, 9000)!!
                    .generation,
            )
            assertEquals(1, sync().sync(profile, "fixture-token").confirmed)
            assertNull(cache.database(profile.id).cacheDao().starPending(profile.id, 9000))
            mutations().setStarred(profile.id, 9000, false)
            assertEquals(
                4L,
                cache
                    .database(profile.id)
                    .cacheDao()
                    .starPending(profile.id, 9000)!!
                    .generation,
            )
        }

    @Test fun exactDesiredBodiesNeverContainStatusOrToggle() =
        runBlocking<Unit> {
            val profile = prepare()
            for (desired in listOf(true, false)) {
                mutations().setStarred(profile.id, 9000, desired)
                assertEquals(1, sync().sync(profile, "fixture-token").confirmed)
                val put = requests.last { it.method == "PUT" }
                assertEquals("/v1/entries", put.path)
                assertEquals("""{"entry_ids":[9000],"starred":$desired}""", putBodies.last())
                assertEquals(desired, rows.single().starred)
            }
            assertEquals(2, requests.count { it.method == "PUT" })
            assertTrue(requests.none { it.path!!.contains("bookmark") })
            assertTrue(cache.articles(profile.id, 0).single().pending == null)
        }

    @Test fun unknownCommitAndVersionAloneNeverPermitMutation() =
        runBlocking<Unit> {
            val profile = prepare()
            mutations().setStarred(profile.id, 9000, true)
            for (unknown in listOf("", "dev", "0000000000000000000000000000000000000000")) {
                commit = unknown
                val result = sync().sync(profile, "fixture-token")
                assertFalse(result.writeEnabled)
                assertEquals(1, result.pending)
            }
            assertTrue(requests.none { it.method != "GET" })
        }

    @Test fun blockedCapabilityAndGetDiagnosticDoNotChangeExistingPendingOrCheckpoint() =
        runBlocking<Unit> {
            val profile = prepare()
            mutations().setStarred(profile.id, 9000, true)
            MinifluxReadMutationStore(cache).setStatus(profile.id, 9000, "read")
            val dao = cache.database(profile.id).cacheDao()
            val originalStar = dao.starPending(profile.id, 9000)
            val originalRead = dao.pending(profile.id, 9000)
            val originalProfile = dao.profile()
            val originalIdentity = dao.entryIdentity(profile.id, 9000)
            for ((version, hash, code, expected) in listOf(
                listOf("2.3.x-dev", "unknown", "200", "UNKNOWN"),
                listOf("2.3.1", "", "200", "UNSUPPORTED"),
                listOf("2.3.x-dev", "9a77408", "200", "UNSUPPORTED"),
                listOf("2.3.x-dev", "51f2e0d", "503", "UNKNOWN"),
            )) {
                versionText = version
                commit = hash
                versionCode = code.toInt()
                val diagnostic = MinifluxDiagnosticService(api).run(profile.baseUrl, "fixture-token")
                assertEquals(expected, diagnostic.starCapability.support.name)
                assertEquals(MinifluxStep.Success(1), diagnostic.entryIds)
                val result = sync().sync(profile, "fixture-token")
                assertFalse(result.writeEnabled)
                assertEquals(expected, result.capability.support.name)
                assertEquals(1, result.pending)
                assertEquals(originalStar, dao.starPending(profile.id, 9000))
                assertEquals(originalRead, dao.pending(profile.id, 9000))
                assertEquals(originalProfile, dao.profile())
                assertEquals(originalIdentity, dao.entryIdentity(profile.id, 9000))
                assertTrue(
                    cache
                        .articles(profile.id, 0)
                        .single()
                        .item.bookmarked,
                )
            }
            assertEquals(
                4,
                cache
                    .database(profile.id)
                    .openHelper.writableDatabase.version,
            )
            assertTrue(requests.all { it.method == "GET" })
            assertEquals(0, requests.count { it.path!!.contains("bookmark") })
        }

    @Test fun stableAndKnownReleaseBuildCanSendPreviouslySavedPending() =
        runBlocking<Unit> {
            val profile = prepare()
            for ((version, hash) in listOf("2.3.2" to "", "2.3.3" to "", "2.3.4" to "", "2.3.x-dev" to "51f2e0d")) {
                versionText = version
                commit = hash
                mutations().setStarred(profile.id, 9000, true)
                val result = sync().sync(profile, "fixture-token")
                assertTrue(result.writeEnabled)
                assertEquals(1, result.confirmed)
                assertEquals(0, result.pending)
            }
            assertEquals(4, requests.count { it.method == "PUT" })
            assertEquals(0, requests.count { it.path!!.contains("bookmark") })
        }

    @Test fun mismatchedConfirmationAndGetFailureRetainDesired() =
        runBlocking<Unit> {
            val profile = prepare()
            mutations().setStarred(profile.id, 9000, true)
            applyPut = false
            assertEquals(1, sync().sync(profile, "fixture-token").failed)
            assertTrue(cache.articles(profile.id, 0).single().effectiveStarred)
            applyPut = true
            confirmCode = 500
            assertEquals(1, sync().sync(profile, "fixture-token").pending)
            assertNotNull(cache.database(profile.id).cacheDao().starPending(profile.id, 9000))
        }

    @Test fun putTimeoutAfterServerSuccessCanConfirmWithoutRetry() =
        runBlocking<Unit> {
            val profile = prepare()
            mutations().setStarred(profile.id, 9000, true)
            stallPut = true
            assertEquals(1, sync().sync(profile, "fixture-token").confirmed)
            assertEquals(1, requests.count { it.method == "PUT" })
        }

    @Test fun timeoutWithoutDesiredOrConfirmationKeepsPending() =
        runBlocking<Unit> {
            val profile = prepare()
            mutations().setStarred(profile.id, 9000, true)
            stallPut = true
            applyPut = false
            assertEquals(1, sync().sync(profile, "fixture-token").pending)
            stallPut = false
            applyPut = true
            getCount = 0
            stallConfirm = true
            assertEquals(1, sync().sync(profile, "fixture-token").pending)
        }

    @Test fun explicitFailuresNeverAckOrToggle() =
        runBlocking<Unit> {
            val profile = prepare()
            mutations().setStarred(profile.id, 9000, true)
            applyPut = false
            for (code in listOf(401, 403, 404, 429, 500)) {
                putCode = code
                assertEquals(1, sync().sync(profile, "fixture-token").pending)
            }
            assertTrue(cache.articles(profile.id, 0).single().effectiveStarred)
            assertTrue(requests.none { it.path!!.contains("bookmark") })
        }

    @Test fun removedAndMissingAreSkippedWithoutPut() =
        runBlocking<Unit> {
            val profile = prepare()
            mutations().setStarred(profile.id, 9000, true)
            rows = listOf(entry().copy(status = "removed"))
            assertEquals(1, sync().sync(profile, "fixture-token").pending)
            rows = emptyList()
            getCount = 0
            assertEquals(1, sync().sync(profile, "fixture-token").pending)
            assertTrue(requests.none { it.method == "PUT" })
        }

    @Test fun olderPutAckCannotDeleteNewGenerationAndSnapshotDoesNotChase() =
        runBlocking<Unit> {
            val profile = prepare(2)
            mutations().setStarred(profile.id, 9000, true)
            onPut = {
                runBlocking {
                    mutations().setStarred(profile.id, 9000, false)
                    mutations().setStarred(profile.id, 9001, true)
                }
            }
            val result = sync().sync(profile, "fixture-token")
            assertEquals(1, result.requested)
            assertEquals(0, result.confirmed)
            assertEquals(2, result.pending)
            assertFalse(cache.articles(profile.id, 0).first { it.identity.remoteEntryId == 9000L }.effectiveStarred)
            assertEquals(1, requests.count { it.method == "PUT" })
            onPut = null
            rows = rows + entry(9001)
            assertEquals(2, sync().sync(profile, "fixture-token").confirmed)
        }

    @Test fun readAndStarPendingRemainIndependentAcrossBothSenders() =
        runBlocking<Unit> {
            val profile = prepare()
            val reads = MinifluxReadMutationStore(cache)
            reads.setStatus(profile.id, 9000, "read")
            mutations().setStarred(profile.id, 9000, true)
            assertEquals(1, MinifluxReadSync(api, cache, reads).syncWithResult(profile, "fixture-token").confirmed)
            assertNotNull(cache.database(profile.id).cacheDao().starPending(profile.id, 9000))
            reads.setStatus(profile.id, 9000, "unread")
            val pending = cache.database(profile.id).cacheDao().pending(profile.id)
            assertEquals(1, sync().sync(profile, "fixture-token").confirmed)
            assertEquals(pending, cache.database(profile.id).cacheDao().pending(profile.id))
        }

    @Test fun remoteTrueFalseAndIncrementalImportRespectPending() =
        runBlocking<Unit> {
            val profile = prepare()
            cache.saveEntry(profile, entry(starred = true))
            assertTrue(cache.articles(profile.id, 0).single().effectiveStarred)
            cache.saveEntry(profile, entry(starred = false))
            assertFalse(cache.articles(profile.id, 0).single().effectiveStarred)
            mutations().setStarred(profile.id, 9000, true)
            MinifluxCacheSync(api, cache).sync(profile.baseUrl, "fixture-token") { _, _ -> }
            val article = cache.observeArticles(profile.id, 50).first().single()
            assertTrue(article.effectiveStarred)
            assertTrue(article.item.bookmarked)
            assertFalse(article.identity.remoteStarred)
            assertNotNull(article.starPending)
            assertEquals(1, sync().sync(profile, "fixture-token").confirmed)
        }

    @Test fun pendingUnstarProtectsFeedAndEntryCleanupIncludingStalePlans() =
        runBlocking<Unit> {
            val profile = prepare()
            val local =
                cache
                    .database(profile.id)
                    .cacheDao()
                    .entryIdentity(profile.id, 9000)!!
                    .localFeedItemId
            val candidate =
                MinifluxDeletionEntry(9000, local, "Title", "Feed", MinifluxDeletionReason.REMOTE_STATUS_REMOVED, MinifluxDeletionConfidence.CONFIRMED, "removed", false, false, 10L, 5000, true)
            val entryPlan = MinifluxEntryCleanupPlan(profile, listOf(candidate))
            val feedPlan =
                MinifluxCleanupPlan(
                    profile,
                    listOf(
                        MinifluxDeletionFeed(
                            5000,
                            cache
                                .database(profile.id)
                                .cacheDao()
                                .feedIdentity(profile.id, 5000)!!
                                .localFeedId,
                            "Feed",
                            feed.feedUrl,
                            "News",
                            1,
                            0,
                            0,
                            1,
                        ),
                    ),
                )
            mutations().setStarred(profile.id, 9000, false)
            rows = listOf(entry().copy(status = "removed"))
            val result = MinifluxCacheSync(api, cache).deletionDryRun(profile, "fixture-token")
            assertEquals(1, result.pending)
            assertNull(result.entryCleanupPlan(profile))
            assertEquals(1, MinifluxCacheSync(api, cache).cleanupEntries(entryPlan, "fixture-token") {}.skipped)
            assertEquals(1, cache.database(profile.id).cacheDao().cleanupPendingCount(profile.id, 5000))
            feedPresent = false
            assertEquals(1, MinifluxCacheSync(api, cache).cleanup(feedPlan, "fixture-token") {}.skipped)
            assertEquals(1, cache.summary(profile).entries)
        }

    @Test fun maximumTwentySnapshotMutationsPerRun() =
        runBlocking<Unit> {
            val profile = prepare(25)
            rows = (9000L..9024L).map { entry(it) }
            for (id in 9000L..9024L) mutations().setStarred(profile.id, id, true)
            val result = sync(15000).sync(profile, "fixture-token")
            assertEquals(20, result.requested)
            assertEquals(20, result.confirmed)
            assertEquals(5, result.pending)
            assertEquals(5, result.skipped)
            assertEquals(20, requests.count { it.method == "PUT" })
        }

    @Test fun cancelAndTimeoutKeepUnconfirmedPendingAndReleaseControllerBusy() =
        runBlocking<Unit> {
            val profile = prepare()
            mutations().setStarred(profile.id, 9000, true)
            val credentials = MinifluxCredentialStore(context, "star_$suffix", suffix)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                credentials.saveToken(profile.baseUrl, "fixture-token")
                val slow = MinifluxApiClient(OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build())
                val reads = MinifluxReadMutationStore(cache)
                val controller =
                    MinifluxCacheController(
                        cache,
                        credentials,
                        MinifluxCacheSync(api, cache),
                        scope,
                        MinifluxReadSync(api, cache, reads),
                        reads,
                        starSync = MinifluxStarSync(slow, cache, mutations(), 3000),
                    )
                for (cancel in listOf(true, false)) {
                    stallPut = true
                    requests.clear()
                    getCount = 0
                    controller.syncCache(profile.baseUrl, starStates = true)
                    withTimeout(10000) { while (requests.none { it.method == "PUT" }) delay(10) }
                    if (cancel) controller.cancel()
                    withTimeout(10000) { while (controller.state.value.busy) delay(10) }
                    assertNotNull(cache.database(profile.id).cacheDao().starPending(profile.id, 9000))
                    if (!cancel) assertEquals("TIMEOUT", controller.state.value.errorType)
                }
            } finally {
                scope.cancel()
                credentials.clearToken()
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
