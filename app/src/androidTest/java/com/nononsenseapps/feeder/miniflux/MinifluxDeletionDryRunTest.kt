package com.nononsenseapps.feeder.miniflux

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nononsenseapps.feeder.db.room.AppDatabase
import com.nononsenseapps.feeder.db.room.CommaFeedArticleMapping
import com.nononsenseapps.feeder.db.room.Feed
import com.nononsenseapps.feeder.db.room.FeedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class MinifluxDeletionDryRunTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val suffix = UUID.randomUUID().toString()
    private val root = File(app.cacheDir, "deletion_$suffix").apply { mkdirs() }
    private val context =
        object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir(): File = root

            override fun getNoBackupFilesDir(): File = File(root, "credentials").apply { mkdirs() }

            override fun getDatabasePath(name: String): File = File(root, "db/$name").also { it.parentFile?.mkdirs() }
        }
    private val store = MinifluxCacheStore(context, suffix)
    private val server = MockWebServer().apply { start() }
    private val api =
        MinifluxApiClient(
            OkHttpClient
                .Builder()
                .readTimeout(300, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build(),
        )
    private val sync = MinifluxCacheSync(api, store)
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val time = "2026-10-23T00:00:00Z"
    private val feed = MinifluxFeed(5000, "Feed", "https://example.com/rss")

    @Volatile private var remoteFeeds = listOf(feed)

    @Volatile private var rows = emptyList<MinifluxEntry>()

    @Volatile private var failure = ""

    @Volatile private var modernIds = false

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    if (request.method != "GET") return MockResponse().setResponseCode(405)
                    val url = request.requestUrl!!
                    if (failure == "auth") return MockResponse().setResponseCode(401)
                    return when (url.encodedPath) {
                        "/v1/version" -> if (modernIds) MockResponse().setBody("""{"version":"2.3.x-dev","commit":"bdd8087d"}""") else MockResponse().setResponseCode(404)
                        "/v1/entries/ids" -> {
                            if (!modernIds) return MockResponse().setResponseCode(404)
                            if (failure == "ids" && url.queryParameter("offset")!!.toInt() > 0) return MockResponse().setResponseCode(503)
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
                                Json.encodeToString(
                                    MinifluxEntryIds(
                                        ids.size.toLong(),
                                        ids.drop(url.queryParameter("offset")!!.toInt()).take(
                                            if (failure ==
                                                "ids"
                                            ) {
                                                3000
                                            } else {
                                                url.queryParameter("limit")!!.toInt()
                                            },
                                        ),
                                    ),
                                ),
                            )
                        }
                        "/v1/me" -> MockResponse().setBody("""{"id":42,"username":"fixture"}""")
                        "/v1/feeds" ->
                            when (failure) {
                                "feeds" -> MockResponse().setResponseCode(503)
                                "json" -> MockResponse().setBody("[")
                                "duplicate" -> MockResponse().setBody(Json.encodeToString(listOf(feed, feed)))
                                else -> MockResponse().setBody(Json.encodeToString(remoteFeeds))
                            }
                        "/v1/entries" -> {
                            if (failure == "stall") return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                            val cursor = if (failure == "cursor") null else url.queryParameter("before_entry_id")?.toLong()
                            if (failure == "page2" && cursor != null && cursor < 252) return MockResponse().setResponseCode(503)
                            val filtered = rows.filter { cursor == null || it.id < cursor }.sortedByDescending { it.id }
                            MockResponse().setBody(Json.encodeToString(MinifluxEntries(filtered.size.toLong(), filtered.take(url.queryParameter("limit")!!.toInt()))))
                        }
                        else -> MockResponse().setResponseCode(404).apply { if (modernIds) setHeader("Content-Type", "application/json").setBody("""{"error_message":"resource not found"}""") }
                    }
                }
            }
    }

    private fun entry(
        id: Long,
        status: String = "read",
        starred: Boolean = false,
    ) = MinifluxEntry(id, feed.id, "Entry $id", status, starred, time, time, "https://example.com/$id", content = "<p>body $id</p>")

    private suspend fun prepare(
        count: Int = 1,
        baseline: Boolean = true,
    ): MinifluxProfile {
        val profile = store.prepare(server.url("/").toString(), MinifluxAccount(42, "fixture"))
        val category = MinifluxCategory(99, "News")
        store.saveCategories(profile, listOf(category))
        store.saveFeed(profile, feed, category)
        repeat(count) { store.saveEntry(profile, entry(it + 1L)) }
        return if (baseline) store.complete(profile, time) else profile
    }

    private suspend fun fingerprint(profile: MinifluxProfile): List<Any?> {
        val dao = store.database(profile.id).cacheDao()
        return listOf(
            dao.profile(),
            dao.reconciliationFeeds(),
            dao.reconciliationLocalFeeds(),
            dao.reconciliationEntries(),
            dao.reconciliationItems(),
            dao.pending(
                profile.id,
            ),
            store
                .articleDirectory(profile.id)
                .listFiles()
                ?.sortedBy {
                    it.name
                }?.map { Triple(it.name, it.lastModified(), it.readBytes().toList()) },
        )
    }

    private suspend fun runUnchanged(profile: MinifluxProfile): MinifluxDeletionResult {
        val before = fingerprint(profile)
        val result = sync.deletionDryRun(profile, "fixture-token")
        assertEquals(before, fingerprint(profile))
        assertTrue(requests.all { it.method == "GET" })
        assertTrue(requests.none { it.path!!.matches(Regex("/v1/entries/\\d+")) })
        assertEquals(
            4,
            store
                .database(profile.id)
                .openHelper.readableDatabase.version,
        )
        return result
    }

    @Test fun matchingReadAndUnreadAreNotCandidates() =
        runBlocking {
            val profile = prepare(2)
            rows = listOf(entry(1), entry(2, "unread"))
            val result = runUnchanged(profile)
            assertTrue(result.complete)
            assertEquals(0, result.entries.size)
            assertEquals(0, result.missingFeeds)
        }

    @Test fun normalRssAndCommaFeedDatabaseRemainUnchanged() =
        runBlocking<Unit> {
            val local = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
            try {
                val feedId = local.feedDao().insertFeed(Feed(title = "Local", url = URL("https://example.com/rss")))
                val itemId = local.feedItemDao().insertFeedItem(FeedItem(guid = "local", feedId = feedId, readTime = Instant.EPOCH))
                val mapping = CommaFeedArticleMapping("old-server", itemId, "remote", feedId, "feed", "GUID", 1, true, true, 1)
                local.commaFeedArticleMappingDao().upsert(mapping)
                val profile = prepare()
                remoteFeeds = emptyList()
                assertTrue(runUnchanged(profile).complete)
                assertEquals("Local", local.feedDao().getFeed(feedId)!!.title)
                assertEquals(Instant.EPOCH, local.feedItemDao().loadFeedItem(itemId)!!.readTime)
                assertEquals(mapping, local.commaFeedArticleMappingDao().forServer("old-server").single())
                assertEquals(40, local.openHelper.readableDatabase.version)
            } finally {
                local.close()
            }
        }

    @Test fun emptyCompleteFeedInventoryAggregatesPendingStarredAndBlobs() =
        runBlocking {
            val profile = prepare(2)
            store.saveEntry(profile, entry(2, starred = true))
            MinifluxReadMutationStore(store).setStatus(profile.id, 1, "unread")
            remoteFeeds = emptyList()
            val result = runUnchanged(profile)
            assertTrue(result.complete)
            assertEquals(1, result.missingFeeds)
            assertEquals(2, result.feedEntries)
            assertEquals(2, result.feeds.single().entries)
            assertEquals(1, result.feeds.single().pending)
            assertEquals(1, result.feeds.single().starred)
            assertEquals(2, result.feeds.single().blobs)
        }

    @Test fun removedProtectsPendingAndStarredWithoutTouchingGeneration() =
        runBlocking {
            val profile = prepare(2)
            MinifluxReadMutationStore(store).setStatus(profile.id, 1, "unread")
            rows = listOf(entry(1, "removed"), entry(2, "removed", true))
            val result = runUnchanged(profile)
            assertEquals(2, result.removedEntries)
            assertEquals(1, result.pending)
            assertEquals(1, result.starred)
            assertTrue(result.entries.all { it.confidence == MinifluxDeletionConfidence.CONFIRMED && it.reason == MinifluxDeletionReason.REMOTE_STATUS_REMOVED })
        }

    @Test fun absentEntryIsOnlyUnknownNeverPhysicalMissing() =
        runBlocking {
            val result = runUnchanged(prepare())
            assertTrue(result.complete)
            assertEquals(1, result.unverified)
            assertEquals(0, result.confirmedMissingEntries)
            assertEquals(MinifluxDeletionConfidence.UNKNOWN, result.entries.single().confidence)
        }

    @Test fun failuresDiscardEveryCandidate() =
        runBlocking {
            val profile = prepare()
            for (type in listOf("feeds", "json", "duplicate", "auth", "stall")) {
                failure = type
                val result = runUnchanged(profile)
                assertFalse(result.complete, type)
                assertEquals(0, result.missingFeeds)
                assertTrue(result.entries.isEmpty())
            }
        }

    @Test fun paginationAndPageTwoFailure() =
        runBlocking {
            val profile = prepare(251)
            rows = (1L..251L).map { entry(it, "removed") }
            var result = runUnchanged(profile)
            assertTrue(result.complete)
            assertEquals(3, result.pages)
            assertEquals(251, result.removedEntries)
            failure = "page2"
            result = runUnchanged(profile)
            assertFalse(result.complete)
            assertEquals(0, result.removedEntries)
        }

    @Test fun ignoredCursorAndUnknownStatusAreIncomplete() =
        runBlocking {
            val profile = prepare()
            rows = (1L..101L).map { entry(it, "removed") }
            failure = "cursor"
            assertFalse(runUnchanged(profile).complete)
            failure = ""
            rows = listOf(entry(1, "unexpected"))
            assertFalse(runUnchanged(profile).complete)
        }

    @Test fun incompleteBaselineMakesNoRequests() =
        runBlocking {
            val result = runUnchanged(prepare(baseline = false))
            assertEquals("BASELINE_REQUIRED", result.errorType)
            assertTrue(requests.isEmpty())
        }

    @Test fun cancellationChangesNothing() =
        runBlocking {
            val profile = prepare()
            val before = fingerprint(profile)
            failure = "stall"
            val slow = MinifluxApiClient(OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build())
            val work = async(Dispatchers.IO) { MinifluxCacheSync(slow, store).deletionDryRun(profile, "fixture-token") }
            withTimeout(3000) { while (requests.none { it.requestUrl!!.encodedPath == "/v1/entries" }) delay(10) }
            work.cancelAndJoin()
            assertTrue(work.isCancelled)
            assertEquals(before, fingerprint(profile))
        }

    @Test fun sixThousandLocalEntriesAndTenThousandRemoteLimitDoNotCauseIndividualGets() =
        runBlocking {
            val profile = prepare()
            val dao = store.database(profile.id).cacheDao()
            val identity = dao.entryIdentity(profile.id, 1)!!
            val item = dao.item(identity.localFeedItemId)!!
            store.database(profile.id).withTransaction {
                for (id in 2L..6000L) {
                    val local = dao.insertItem(item.copy(id = 0, guid = "fixture:$id"))
                    dao.insertEntryIdentity(identity.copy(remoteEntryId = id, localFeedItemId = local, blobReady = false))
                }
            }
            rows = (1L..10001L).map { entry(it) }
            val result = runUnchanged(profile)
            assertTrue(result.complete)
            assertEquals(0, result.unverified)
            assertEquals(60, result.pages)
            assertEquals(65, requests.size)
            assertTrue(result.entries.isEmpty())
        }

    @Test fun requestBreakdownForSixThousandSevenHundredLocalEntries() =
        runBlocking {
            val profile = prepare()
            val db = store.database(profile.id)
            val dao = db.cacheDao()
            val identity = dao.entryIdentity(profile.id, 1)!!
            val item = dao.item(identity.localFeedItemId)!!
            db.withTransaction {
                for (id in 2L..6700L) {
                    val local = dao.insertItem(item.copy(id = 0, guid = "fixture:$id"))
                    dao.insertEntryIdentity(identity.copy(remoteEntryId = id, localFeedItemId = local, blobReady = false))
                }
            }
            modernIds = true
            rows = (1L..6700L).map { entry(it, if (it <= 6100) "read" else "unread") }
            val events = mutableListOf<MinifluxReconciliationTrace>()
            val result = MinifluxDeletionDryRun(api, store).run(profile, "fixture-token", events::add)
            assertTrue(result.complete)
            assertTrue(result.entryEnumerationComplete)
            assertEquals(0, result.pages)
            assertEquals(1, requests.count { it.requestUrl!!.queryParameter("status") == "read" })
            assertEquals(1, requests.count { it.requestUrl!!.queryParameter("status") == "unread" })
            assertEquals(1, requests.count { it.requestUrl!!.queryParameter("starred") == "true" })
            assertTrue(requests.none { it.requestUrl!!.encodedPath == "/v1/entries" })
            assertEquals(6100L, events.last().readIdsTotal)
            assertEquals(600L, events.last().unreadIdsTotal)
            assertEquals(0, result.individualGets)
            assertTrue(requests.all { it.method == "GET" })
            for (missing in listOf(2, 60)) {
                requests.clear()
                rows = rows.filter { it.id <= 6700 - missing }
                val absent = MinifluxDeletionDryRun(api, store).run(profile, "fixture-token")
                assertTrue(absent.complete)
                assertEquals(minOf(50, missing), absent.individualGets)
                assertEquals(minOf(50, missing), absent.confirmedMissingEntries)
                assertEquals(maxOf(0, missing - 50), absent.unverified)
                assertEquals(0, absent.pages)
            }
            rows = (1L..6700L).map { entry(it, if (it <= 6100) "read" else "unread", starred = it == 1L) }
            remoteFeeds = emptyList()
            MinifluxReadMutationStore(store).setStatus(profile.id, 3, "read")
            MinifluxStarMutationStore(store).setStarred(profile.id, 2, true)
            val protected = MinifluxDeletionDryRun(api, store).run(profile, "fixture-token")
            assertEquals(1, protected.missingFeeds)
            assertEquals(0, protected.individualGets)
            assertTrue(protected.entries.first { it.remoteEntryId == 1L }.protectedByStarred)
            assertTrue(protected.entries.first { it.remoteEntryId == 2L }.protectedByPending)
            assertTrue(protected.entries.first { it.remoteEntryId == 3L }.protectedByPending)
            failure = "ids"
            requests.clear()
            val failed = MinifluxDeletionDryRun(api, store).run(profile, "fixture-token")
            assertFalse(failed.complete)
            assertEquals("SERVER", failed.errorType)
            assertEquals(0, failed.individualGets)
            assertTrue(requests.none { it.requestUrl!!.encodedPath == "/v1/entries" })
        }

    @Test fun controllerReleasesBusyOnSuccessFailureTimeoutAndCancel() =
        runBlocking {
            val profile = prepare()
            val credentials = MinifluxCredentialStore(context, "deletion_$suffix", suffix)
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
            try {
                credentials.saveToken(profile.baseUrl, "fixture-token")
                val mutations = MinifluxReadMutationStore(store)
                val controller = MinifluxCacheController(store, credentials, sync, scope, MinifluxReadSync(api, store, mutations), mutations)

                suspend fun idle() {
                    withTimeout(5000) { while (controller.state.value.busy) delay(10) }
                }
                controller.syncCache(profile.baseUrl, deletionDryRun = true)
                idle()
                assertTrue(
                    controller.state.value.deletionResult!!
                        .complete,
                )
                failure = "auth"
                controller.syncCache(profile.baseUrl, deletionDryRun = true)
                idle()
                assertFalse(
                    controller.state.value.deletionResult!!
                        .complete,
                )
                failure = "stall"
                controller.syncCache(profile.baseUrl, deletionDryRun = true)
                idle()
                assertFalse(
                    controller.state.value.deletionResult!!
                        .complete,
                )
                controller.syncCache(profile.baseUrl, deletionDryRun = true)
                controller.cancel()
                idle()
                assertEquals(null, controller.state.value.deletionResult)
                val bounded = MinifluxCacheController(store, credentials, MinifluxCacheSync(api, store, timeoutMillis = 50), scope, MinifluxReadSync(api, store, mutations), mutations)
                bounded.syncCache(profile.baseUrl, deletionDryRun = true)
                withTimeout(5000) { while (bounded.state.value.busy) delay(10) }
                assertEquals(
                    "TIMEOUT",
                    bounded.state.value.deletionResult!!
                        .errorType,
                )
            } finally {
                scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                credentials.clearToken()
            }
        }

    @After fun cleanup() {
        store.close()
        server.shutdown()
        root.deleteRecursively()
        app
            .getSharedPreferences(suffix, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
