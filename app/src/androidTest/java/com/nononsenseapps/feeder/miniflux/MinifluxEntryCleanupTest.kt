package com.nononsenseapps.feeder.miniflux

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nononsenseapps.feeder.blob.blobFile
import com.nononsenseapps.feeder.db.room.AppDatabase
import com.nononsenseapps.feeder.db.room.Feed
import com.nononsenseapps.feeder.db.room.FeedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class MinifluxEntryCleanupTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val suffix = UUID.randomUUID().toString()
    private val root = File(app.cacheDir, "entry_cleanup_$suffix").apply { mkdirs() }
    private val context =
        object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir(): File = root

            override fun getNoBackupFilesDir(): File = File(root, "credentials").apply { mkdirs() }

            override fun getDatabasePath(name: String): File = File(root, "db/$name").also { it.parentFile?.mkdirs() }
        }
    private var store = MinifluxCacheStore(context, suffix)
    private val server = MockWebServer().apply { start() }
    private val api =
        MinifluxApiClient(
            OkHttpClient
                .Builder()
                .retryOnConnectionFailure(false)
                .readTimeout(300, TimeUnit.MILLISECONDS)
                .build(),
        )
    private val time = "2026-10-23T00:00:00Z"
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val feed = MinifluxFeed(900, "Feed", "https://example.com/rss", MinifluxCategory(99, "News"))

    @Volatile private var rows = emptyList<MinifluxEntry>()

    @Volatile private var readIds = emptyList<Long>()

    @Volatile private var unreadIds = emptyList<Long>()

    @Volatile private var replies = emptyMap<Long, MinifluxEntry>()

    @Volatile private var feedPresent = true

    @Volatile private var failure = ""

    @Volatile private var individualCode = 404

    @Volatile private var missingBody = """{"error_message":"resource not found"}"""

    @Volatile private var jsonError = true

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    if (request.method != "GET") return MockResponse().setResponseCode(405)
                    val url = request.requestUrl!!
                    return when (url.encodedPath) {
                        "/v1/me" -> MockResponse().setBody("""{"id":42,"username":"fixture"}""")
                        "/v1/feeds" -> MockResponse().setBody(Json.encodeToString(if (feedPresent) listOf(feed) else emptyList()))
                        "/v1/entries/ids" -> {
                            if (failure == "unsupported") return MockResponse().setResponseCode(404)
                            val offset = url.queryParameter("offset")!!.toInt()
                            if (failure == "ids" && url.queryParameter("status") == "unread" && offset > 0) return MockResponse().setResponseCode(503)
                            val ids = if (url.queryParameter("status") == "read") readIds else unreadIds
                            MockResponse().setBody(Json.encodeToString(MinifluxEntryIds(ids.size.toLong(), ids.sortedDescending().drop(offset).take(10000))))
                        }
                        "/v1/entries" -> {
                            val cursor = url.queryParameter("before_entry_id")?.toLong()
                            if (failure == "removed" && cursor != null) return MockResponse().setResponseCode(503)
                            val filtered = rows.filter { cursor == null || it.id < cursor }.sortedByDescending { it.id }
                            MockResponse().setBody(Json.encodeToString(MinifluxEntries(filtered.size.toLong(), filtered.take(100))))
                        }
                        else -> {
                            val id = url.encodedPath.substringAfterLast('/').toLongOrNull() ?: return MockResponse().setResponseCode(404)
                            replies[id]?.let { return MockResponse().setBody(Json.encodeToString(it)) }
                            if (failure == "stall") return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                            if (failure == "parse") return MockResponse().setBody("not-json")
                            MockResponse().setResponseCode(individualCode).setBody(missingBody).apply { if (jsonError) setHeader("Content-Type", "application/json") }
                        }
                    }
                }
            }
    }

    private fun entry(
        id: Long,
        status: String = "read",
        starred: Boolean = false,
    ) = MinifluxEntry(id, feed.id, "Entry $id", status, starred, time, time, "https://example.com/$id", content = "<p>$id</p>")

    private suspend fun prepare(count: Int = 2): MinifluxProfile {
        val profile = store.prepare(server.url("/").toString(), MinifluxAccount(42, "fixture"))
        store.saveCategories(profile, listOf(feed.category!!))
        store.saveFeed(profile, feed, feed.category!!)
        repeat(count) { store.saveEntry(profile, entry(it + 1L)) }
        return store.complete(profile, time)
    }

    private suspend fun dry(profile: MinifluxProfile) = MinifluxCacheSync(api, store).deletionDryRun(profile, "fixture-token")

    private suspend fun plan(profile: MinifluxProfile) = assertNotNull(dry(profile).entryCleanupPlan(profile))

    private fun individuals() = requests.filter { it.requestUrl!!.encodedPath.matches(Regex("/v1/entries/[0-9]+")) }

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

    @Test fun removedClassificationAndPendingStarredParentProtection() =
        runBlocking<Unit> {
            val profile = prepare(3)
            rows = listOf(entry(1, "removed"), entry(2, "removed", true), entry(3, "removed"))
            MinifluxReadMutationStore(store).setStatus(profile.id, 3, "unread")
            val before = fingerprint(profile)
            val result = dry(profile)
            assertEquals(3, result.removedEntries)
            assertEquals(1, result.pending)
            assertEquals(1, result.starred)
            assertEquals(1, result.entryCleanupPlan(profile)!!.entries.size)
            assertEquals(before, fingerprint(profile))
            feedPresent = false
            assertNull(dry(profile).entryCleanupPlan(profile))
        }

    @Test fun readUnreadAndRemovedUnionOnlyChecksAbsentId() =
        runBlocking<Unit> {
            val profile = prepare(4)
            readIds = listOf(1)
            unreadIds = listOf(2)
            rows = listOf(entry(3, "removed"))
            val result = dry(profile)
            assertTrue(result.entryEnumerationComplete)
            assertEquals(1, result.removedEntries)
            assertEquals(1, result.confirmedMissingEntries)
            assertEquals(listOf("/v1/entries/4"), individuals().map { it.requestUrl!!.encodedPath })
            assertEquals(setOf(MinifluxDeletionReason.REMOTE_STATUS_REMOVED, MinifluxDeletionReason.REMOTE_ENTRY_NOT_FOUND), result.entries.map { it.reason }.toSet())
        }

    @Test fun potentialMissingWith200IsNotMissing() =
        runBlocking<Unit> {
            val profile = prepare(1)
            replies = mapOf(1L to entry(1))
            assertEquals(0, dry(profile).confirmedMissingEntries)
            assertNull(dry(profile).entryCleanupPlan(profile))
        }

    @Test fun badHttpTimeoutParseAndGeneric404NeverConfirmMissing() =
        runBlocking<Unit> {
            val profile = prepare(1)
            val before = fingerprint(profile)
            for (code in listOf(401, 403, 429, 500)) {
                individualCode = code
                val result = dry(profile)
                assertEquals(0, result.confirmedMissingEntries)
                assertEquals(1, result.unverified)
                assertFalse(result.entryEnumerationComplete)
            }
            individualCode = 404
            for (type in listOf("stall", "parse")) {
                failure = type
                assertEquals(0, dry(profile).confirmedMissingEntries)
            }
            failure = ""
            jsonError = false
            assertEquals(0, dry(profile).confirmedMissingEntries)
            jsonError = true
            missingBody = """{"error_message":"proxy not found"}"""
            assertEquals(0, dry(profile).confirmedMissingEntries)
            assertEquals(before, fingerprint(profile))
        }

    @Test fun readAndUnreadPaginationBothExceedTenThousand() =
        runBlocking<Unit> {
            readIds = (1L..10001L).toList()
            unreadIds = (10002L..20002L).toList()
            val ids = enumerateMinifluxIds(api, server.url("/"), "fixture-token")
            assertEquals(20002, ids.size)
            assertTrue(10001L in ids && 20002L in ids)
            for (status in listOf("read", "unread")) {
                assertEquals(
                    listOf("0", "10000", "10001"),
                    requests
                        .filter {
                            it.requestUrl!!.queryParameter("status") == status
                        }.map { it.requestUrl!!.queryParameter("offset") },
                )
            }
        }

    @Test fun incompleteIdEnumerationDisables404ButCompleteRemovedRemainsVisible() =
        runBlocking<Unit> {
            val profile = prepare(2)
            rows = listOf(entry(1, "removed"))
            unreadIds = (100L..10100L).toList()
            failure = "ids"
            val result = dry(profile)
            assertFalse(result.entryEnumerationComplete)
            assertEquals(1, result.removedEntries)
            assertEquals(0, result.confirmedMissingEntries)
            assertTrue(individuals().isEmpty())
        }

    @Test fun unsupportedIdsUsesRemovedOnlyAndRemovedPagingFailureDiscardsPartial() =
        runBlocking<Unit> {
            val profile = prepare(2)
            rows = (1L..251L).map { entry(it, "removed") }
            failure = "unsupported"
            var result = dry(profile)
            assertTrue(result.complete)
            assertEquals(2, result.removedEntries)
            assertEquals(4, result.pages)
            assertFalse(result.entryEnumerationComplete)
            assertEquals(2, result.entryCleanupPlan(profile)!!.entries.size)
            failure = "removed"
            result = dry(profile)
            assertFalse(result.complete)
            assertNull(result.entryCleanupPlan(profile))
        }

    @Test fun individualConfirmationIsBoundedToFifty() =
        runBlocking<Unit> {
            val profile = prepare(111)
            val result = dry(profile)
            assertEquals(50, individuals().size)
            assertEquals(50, result.confirmedMissingEntries)
            assertEquals(61, result.unverified)
            assertEquals(50, result.entryCleanupPlan(profile)!!.entries.size)
        }

    @Test fun sixThousandLocalAndTenThousandRemoteIdsOnlyNeedTwoIndividualGets() =
        runBlocking<Unit> {
            val profile = prepare(1)
            val dao = store.database(profile.id).cacheDao()
            val identity = dao.entryIdentity(profile.id, 1)!!
            val item = dao.item(identity.localFeedItemId)!!
            store.database(profile.id).withTransaction {
                for (id in 2L..6000L) {
                    val local = dao.insertItem(item.copy(id = 0, guid = "fixture:$id"))
                    dao.insertEntryIdentity(identity.copy(remoteEntryId = id, localFeedItemId = local, blobReady = false))
                }
            }
            readIds = (1L..5998L).toList() + (6001L..10003L).toList()
            val result = dry(profile)
            assertEquals(10001, readIds.size)
            assertEquals(2, individuals().size)
            assertEquals(2, result.confirmedMissingEntries)
            assertEquals(0, result.unverified)
            assertTrue(requests.all { it.method == "GET" })
        }

    @Test fun cleanupRemovedAndMissingPreservesParentOtherEntryOutboxAndCheckpoint() =
        runBlocking<Unit> {
            val profile = prepare(3)
            rows = listOf(entry(1, "removed"))
            readIds = listOf(3)
            replies = mapOf(1L to entry(1, "removed"))
            MinifluxReadMutationStore(store).setStatus(profile.id, 3, "unread")
            val dao = store.database(profile.id).cacheDao()
            val pending = dao.pending(profile.id)
            val other = dao.entryIdentity(profile.id, 3)
            val beforeProfile = dao.profile()
            val result = MinifluxCacheSync(api, store).cleanupEntries(plan(profile), "fixture-token") {}
            assertTrue(result.complete)
            assertEquals(2, result.entries)
            assertEquals(2, result.blobs)
            assertEquals(0, result.feeds)
            assertEquals(1, dao.feedCount())
            assertEquals(1, dao.entryCount())
            assertNull(dao.entryIdentity(profile.id, 1))
            assertNull(dao.entryIdentity(profile.id, 2))
            assertEquals(other, dao.entryIdentity(profile.id, 3))
            assertEquals(pending, dao.pending(profile.id))
            assertEquals(beforeProfile, dao.profile())
            rows = emptyList()
            assertEquals(0, dry(profile).removedEntries)
            assertEquals(
                4,
                store
                    .database(profile.id)
                    .openHelper.readableDatabase.version,
            )
            assertTrue(requests.all { it.method == "GET" })
        }

    @Test fun lastEntryDeletionKeepsEmptyParentAndLaterRecreationUsesNewLocalId() =
        runBlocking<Unit> {
            val profile = prepare(1)
            val old =
                store
                    .database(profile.id)
                    .cacheDao()
                    .entryIdentity(profile.id, 1)!!
                    .localFeedItemId
            assertEquals(1, MinifluxCacheSync(api, store).cleanupEntries(plan(profile), "fixture-token") {}.entries)
            assertEquals(1, store.summary(profile).feeds)
            store.saveEntry(profile, entry(1).copy(changedAt = "2026-10-24T00:00:00Z"))
            val new =
                store
                    .database(profile.id)
                    .cacheDao()
                    .entryIdentity(profile.id, 1)!!
                    .localFeedItemId
            assertTrue(new != old)
            assertEquals("<p>1</p>", store.readBody(profile.id, new))
        }

    @Test fun revalidationSkipsRemovedResurrection404ResurrectionAndNewRemoteStar() =
        runBlocking<Unit> {
            val profile = prepare(2)
            rows = listOf(entry(1, "removed"))
            val target = plan(profile)
            replies = mapOf(1L to entry(1, "read"), 2L to entry(2))
            val before = fingerprint(profile)
            assertEquals(2, MinifluxCacheSync(api, store).cleanupEntries(target, "fixture-token") {}.skipped)
            assertEquals(before, fingerprint(profile))
            replies = mapOf(1L to entry(1, "removed", true), 2L to entry(2))
            assertEquals(2, MinifluxCacheSync(api, store).cleanupEntries(target, "fixture-token") {}.skipped)
            assertEquals(before, fingerprint(profile))
        }

    @Test fun newPendingLocalStarAndMismatchedIdentitySkip() =
        runBlocking<Unit> {
            val profile = prepare(3)
            val target = plan(profile)
            MinifluxReadMutationStore(store).setStatus(profile.id, 1, "unread")
            store.saveEntry(profile, entry(2, starred = true))
            val wrong = target.copy(entries = target.entries.map { if (it.remoteEntryId == 3L) it.copy(localEntryId = 99999) else it })
            val before = fingerprint(profile)
            assertEquals(3, MinifluxCacheSync(api, store).cleanupEntries(wrong, "fixture-token") {}.skipped)
            assertEquals(before, fingerprint(profile))
        }

    @Test fun databaseFailureNeverDeletesBlobAndBlobFailureNeverRestoresRow() =
        runBlocking<Unit> {
            val profile = prepare(1)
            val target = plan(profile)
            val before = fingerprint(profile)
            val db = store.database(profile.id).openHelper.writableDatabase
            db.execSQL("CREATE TRIGGER fixture_fail BEFORE DELETE ON feed_items BEGIN SELECT RAISE(ABORT, 'fixture'); END")
            assertEquals(1, MinifluxCacheSync(api, store).cleanupEntries(target, "fixture-token") {}.failed)
            assertEquals(before, fingerprint(profile))
            db.execSQL("DROP TRIGGER fixture_fail")
            val result = MinifluxEntryCleanup(api, store, deleteBlob = { false }).run(target, "fixture-token")
            assertEquals(1, result.entries)
            assertEquals(1, result.blobsFailed)
            assertEquals(0, store.summary(profile).entries)
            assertTrue(blobFile(target.entries.single().localEntryId, store.articleDirectory(profile.id)).exists())
            assertTrue(store.articles(profile.id, 0).isEmpty())
            assertEquals(0, dry(profile).confirmedMissingEntries)
        }

    @Test fun cancellationAfterOneCommitAndStoreRecreationKeepUnprocessedEntry() =
        runBlocking<Unit> {
            val profile = prepare(2)
            val target = plan(profile)
            var result = MinifluxCleanupResult()
            val worker =
                async(Dispatchers.IO) {
                    val job = currentCoroutineContext()[Job]!!
                    MinifluxCacheSync(api, store).cleanupEntries(target, "fixture-token") { current ->
                        result = current
                        if (current.entries == 1) job.cancel()
                    }
                }
            worker.join()
            assertTrue(worker.isCancelled)
            assertEquals(1, result.entries)
            store.close()
            store = MinifluxCacheStore(context, suffix)
            assertEquals(1, store.summary(profile).entries)
            assertEquals(1, store.summary(profile).feeds)
        }

    @Test fun dryRunCancelPublishesNoPartialConfirmation() =
        runBlocking<Unit> {
            val profile = prepare(1)
            val before = fingerprint(profile)
            failure = "stall"
            val slow = MinifluxApiClient(OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build())
            val worker = async(Dispatchers.IO) { MinifluxCacheSync(slow, store).deletionDryRun(profile, "fixture-token") }
            withTimeout(3000) { while (individuals().isEmpty()) delay(10) }
            worker.cancelAndJoin()
            assertTrue(worker.isCancelled)
            assertEquals(before, fingerprint(profile))
        }

    @Test fun controllerConfirmationCancelFailureAndSuccessInvalidateDryRunAndReleaseBusy() =
        runBlocking<Unit> {
            val profile = prepare(1)
            val credentials = MinifluxCredentialStore(context, "entry_$suffix", suffix)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                credentials.saveToken(profile.baseUrl, "fixture-token")
                val mutations = MinifluxReadMutationStore(store)
                val controller = MinifluxCacheController(store, credentials, MinifluxCacheSync(api, store), scope, MinifluxReadSync(api, store, mutations), mutations)

                suspend fun idle() {
                    withTimeout(5000) { while (controller.state.value.busy) delay(10) }
                }
                controller.syncCache(profile.baseUrl, deletionDryRun = true)
                idle()
                controller.requestEntryCleanup(profile.baseUrl)
                assertTrue(
                    controller.state.value.cleanupConfirmation!!
                        .entryCleanup,
                )
                val before = fingerprint(profile)
                controller.dismissCleanup()
                controller.confirmCleanup(profile.baseUrl)
                assertEquals(before, fingerprint(profile))
                controller.requestEntryCleanup(profile.baseUrl)
                individualCode = 429
                controller.confirmCleanup(profile.baseUrl)
                idle()
                assertFalse(
                    controller.state.value.cleanupResult!!
                        .complete,
                )
                assertEquals(before, fingerprint(profile))
                assertNull(controller.state.value.deletionResult)
                individualCode = 404
                controller.syncCache(profile.baseUrl, deletionDryRun = true)
                idle()
                controller.requestEntryCleanup(profile.baseUrl)
                controller.confirmCleanup(profile.baseUrl)
                idle()
                assertEquals(
                    1,
                    controller.state.value.cleanupResult!!
                        .entries,
                )
                assertTrue(controller.state.value.entryCleanup)
                assertNull(controller.state.value.deletionResult)
                controller.confirmCleanup(profile.baseUrl)
                assertFalse(controller.state.value.busy)
            } finally {
                scope.cancel()
                credentials.clearToken()
            }
        }

    @Test fun normalRssDatabaseAndSchemaRemainUnchanged() =
        runBlocking<Unit> {
            val normal = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
            try {
                val id = normal.feedDao().insertFeed(Feed(title = "Local", url = URL("https://example.com/rss")))
                val item = normal.feedItemDao().insertFeedItem(FeedItem(guid = "local", feedId = id, readTime = Instant.EPOCH))
                val profile = prepare(1)
                assertEquals(1, MinifluxCacheSync(api, store).cleanupEntries(plan(profile), "fixture-token") {}.entries)
                assertEquals("Local", normal.feedDao().getFeed(id)!!.title)
                assertEquals(Instant.EPOCH, normal.feedItemDao().loadFeedItem(item)!!.readTime)
                assertEquals(40, normal.openHelper.readableDatabase.version)
            } finally {
                normal.close()
            }
        }

    @Test fun entryCleanupSharesMutexAndParentDisappearanceSkips() =
        runBlocking<Unit> {
            val profile = prepare(1)
            val target = plan(profile)
            requests.clear()
            store.operationMutex.lock()
            val job = async(Dispatchers.IO) { MinifluxCacheSync(api, store).cleanupEntries(target, "fixture-token") {} }
            delay(100)
            assertTrue(requests.isEmpty())
            feedPresent = false
            store.operationMutex.unlock()
            assertEquals(1, withTimeout(5000) { job.await() }.skipped)
            assertEquals(1, store.summary(profile).entries)
            assertEquals(1, store.summary(profile).feeds)
        }

    @Test fun entryControllerCancellationAndTimeoutPreserveRowsAndReleaseBusy() =
        runBlocking<Unit> {
            val profile = prepare(1)
            val before = fingerprint(profile)
            val credentials = MinifluxCredentialStore(context, "entry_cancel_$suffix", suffix)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val slow = MinifluxApiClient(OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build())
            try {
                credentials.saveToken(profile.baseUrl, "fixture-token")
                val mutations = MinifluxReadMutationStore(store)
                val controller = MinifluxCacheController(store, credentials, MinifluxCacheSync(slow, store, timeoutMillis = 1500), scope, MinifluxReadSync(slow, store, mutations), mutations)

                suspend fun idle() {
                    withTimeout(5000) { while (controller.state.value.busy) delay(10) }
                }
                for (cancel in listOf(true, false)) {
                    failure = ""
                    controller.syncCache(profile.baseUrl, deletionDryRun = true)
                    idle()
                    controller.requestEntryCleanup(profile.baseUrl)
                    assertNotNull(controller.state.value.cleanupConfirmation)
                    requests.clear()
                    failure = "stall"
                    controller.confirmCleanup(profile.baseUrl)
                    if (cancel) {
                        withTimeout(3000) { while (individuals().isEmpty()) delay(10) }
                        controller.cancel()
                    }
                    idle()
                    assertEquals(
                        if (cancel) "CANCELLED" else "TIMEOUT",
                        controller.state.value.cleanupResult!!
                            .errorType,
                    )
                    assertEquals(before, fingerprint(profile))
                    assertNull(controller.state.value.deletionResult)
                }
            } finally {
                scope.cancel()
                credentials.clearToken()
            }
        }

    @After fun cleanupFixture() {
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
