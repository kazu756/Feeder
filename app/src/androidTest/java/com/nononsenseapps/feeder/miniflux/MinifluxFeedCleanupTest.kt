package com.nononsenseapps.feeder.miniflux

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
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
class MinifluxFeedCleanupTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val suffix = UUID.randomUUID().toString()
    private val root = File(app.cacheDir, "cleanup_$suffix").apply { mkdirs() }
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
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val time = "2026-10-23T00:00:00Z"

    @Volatile private var remote = emptyList<MinifluxFeed>()

    @Volatile private var failure = ""

    @Volatile private var onFeeds: (() -> Unit)? = null

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    if (request.method != "GET") return MockResponse().setResponseCode(405)
                    if (failure == "auth") return MockResponse().setResponseCode(401)
                    return when (request.requestUrl!!.encodedPath) {
                        "/v1/me" -> MockResponse().setBody("""{"id":42,"username":"fixture"}""")
                        "/v1/feeds" -> {
                            onFeeds?.invoke()
                            when (failure) {
                                "network" -> MockResponse().setResponseCode(503)
                                "partial" -> MockResponse().setBody("[")
                                "duplicate" -> MockResponse().setBody(Json.encodeToString(listOf(feed(1), feed(1))))
                                "stall" -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                                else -> MockResponse().setBody(Json.encodeToString(remote))
                            }
                        }
                        "/v1/entries" -> MockResponse().setBody("""{"total":0,"entries":[]}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
    }

    private fun feed(id: Long) = MinifluxFeed(id, "Feed $id", "https://example.com/$id/rss", MinifluxCategory(99, "News"))

    private fun entry(
        id: Long,
        feedId: Long = 1,
        starred: Boolean = false,
    ) = MinifluxEntry(id, feedId, "Entry $id", "read", starred, time, time, "https://example.com/$id", content = "<p>$id</p>")

    private suspend fun prepare(count: Int = 2): MinifluxProfile {
        val profile = store.prepare(server.url("/").toString(), MinifluxAccount(42, "fixture"))
        store.saveCategories(profile, listOf(MinifluxCategory(99, "News")))
        store.saveFeed(profile, feed(1), MinifluxCategory(99, "News"))
        repeat(count) { store.saveEntry(profile, entry(it + 1L)) }
        return store.complete(profile, time)
    }

    private suspend fun dry(profile: MinifluxProfile) = MinifluxCacheSync(api, store).deletionDryRun(profile, "fixture-token")

    private suspend fun plan(profile: MinifluxProfile) = assertNotNull(dry(profile).cleanupPlan(profile))

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

    private fun assertGetOnly() = assertTrue(requests.all { it.method == "GET" })

    @Test fun eligibilityRejectsWrongReasonConfidencePendingStarredAndIncomplete() =
        runBlocking<Unit> {
            val profile = prepare()
            val result = dry(profile)
            assertEquals(1, result.cleanupPlan(profile)!!.feeds.size)
            val candidate = result.feeds.single()
            for (feed in listOf(
                candidate.copy(pending = 1),
                candidate.copy(starred = 1),
                candidate.copy(confidence = MinifluxDeletionConfidence.UNKNOWN),
                candidate.copy(reason = MinifluxDeletionReason.REMOTE_STATUS_REMOVED),
            )) {
                assertNull(result.copy(feeds = listOf(feed)).cleanupPlan(profile))
            }
            assertNull(result.copy(complete = false).cleanupPlan(profile))
            assertNull(result.copy(profileId = "other").cleanupPlan(profile))
            assertNull(result.cleanupPlan(profile.copy(lastSuccessfulSync = null)))
        }

    @Test fun deletesOnlyApprovedFeedIdentitiesItemsAndBlobsAfterCommit() =
        runBlocking<Unit> {
            val profile = prepare(3)
            store.saveFeed(profile, feed(2), MinifluxCategory(99, "News"))
            store.saveEntry(profile, entry(20, 2))
            remote = listOf(feed(2))
            val dao = store.database(profile.id).cacheDao()
            val other = dao.entryIdentity(profile.id, 20)!!
            MinifluxReadMutationStore(store).setStatus(profile.id, 20, "unread")
            val pending = dao.pending(profile.id)
            val generations = dao.entryIdentity(profile.id, 20)!!.readGeneration
            val beforeProfile = dao.profile()
            val target = plan(profile)
            val result = MinifluxCacheSync(api, store).cleanup(target, "fixture-token") {}
            assertTrue(result.complete)
            assertEquals(1, result.feeds)
            assertEquals(3, result.entries)
            assertEquals(3, result.blobs)
            assertEquals(0, result.blobsFailed)
            assertEquals(1, dao.feedCount())
            assertEquals(1, dao.entryCount())
            assertNull(dao.feedIdentity(profile.id, 1))
            assertNull(dao.feed(target.feeds.single().localFeedId))
            assertEquals(pending, dao.pending(profile.id))
            assertEquals(generations, dao.entryIdentity(profile.id, 20)!!.readGeneration)
            assertEquals(beforeProfile, dao.profile())
            assertTrue(blobFile(other.localFeedItemId, store.articleDirectory(profile.id)).exists())
            assertEquals(0, dry(profile).missingFeeds)
            assertEquals(
                3,
                store
                    .database(profile.id)
                    .openHelper.readableDatabase.version,
            )
            assertGetOnly()
        }

    @Test fun emptyFeedCanBeCleaned() =
        runBlocking<Unit> {
            val profile = prepare(0)
            val result = MinifluxCacheSync(api, store).cleanup(plan(profile), "fixture-token") {}
            assertEquals(1, result.feeds)
            assertEquals(0, result.entries)
            assertEquals(0, result.blobs)
        }

    @Test fun pendingAddedAfterConfirmationProtectsFeedAndGeneration() =
        runBlocking<Unit> {
            val profile = prepare()
            val target = plan(profile)
            MinifluxReadMutationStore(store).setStatus(profile.id, 1, "unread")
            val before = fingerprint(profile)
            val result = MinifluxCacheSync(api, store).cleanup(target, "fixture-token") {}
            assertEquals(1, result.skipped)
            assertEquals(before, fingerprint(profile))
            assertNull(dry(profile).cleanupPlan(profile))
        }

    @Test fun starredAddedAfterConfirmationProtectsWholeFeed() =
        runBlocking<Unit> {
            val profile = prepare()
            val target = plan(profile)
            store.saveEntry(profile, entry(1, starred = true))
            val before = fingerprint(profile)
            assertEquals(1, MinifluxCacheSync(api, store).cleanup(target, "fixture-token") {}.skipped)
            assertEquals(before, fingerprint(profile))
            assertNull(dry(profile).cleanupPlan(profile))
        }

    @Test fun reappearingRemoteIdentityIsSkippedButSameUrlDifferentIdIsDistinct() =
        runBlocking<Unit> {
            val profile = prepare()
            val target = plan(profile)
            remote = listOf(feed(1))
            val before = fingerprint(profile)
            assertEquals(1, MinifluxCacheSync(api, store).cleanup(target, "fixture-token") {}.skipped)
            assertEquals(before, fingerprint(profile))
            remote = listOf(feed(99).copy(feedUrl = feed(1).feedUrl))
            assertEquals(1, MinifluxCacheSync(api, store).cleanup(target, "fixture-token") {}.feeds)
        }

    @Test fun remoteFailuresAndChangingInventoryNeverDelete() =
        runBlocking<Unit> {
            val profile = prepare()
            val target = plan(profile)
            val before = fingerprint(profile)
            for (type in listOf("network", "partial", "duplicate", "auth", "stall")) {
                failure = type
                val result = MinifluxCacheSync(api, store).cleanup(target, "fixture-token") {}
                assertFalse(result.complete)
                assertEquals(0, result.feeds)
                assertEquals(before, fingerprint(profile))
            }
            failure = ""
            var calls = 0
            onFeeds = { if (++calls == 2) remote = listOf(feed(1)) }
            assertFalse(MinifluxCacheSync(api, store).cleanup(target, "fixture-token") {}.complete)
            assertEquals(before, fingerprint(profile))
            assertGetOnly()
        }

    @Test fun staleLocalIdentityAndChangedCountsSkip() =
        runBlocking<Unit> {
            val profile = prepare()
            val target = plan(profile)
            val wrong = target.copy(feeds = listOf(target.feeds.single().copy(localFeedId = 99999)))
            assertEquals(1, MinifluxCacheSync(api, store).cleanup(wrong, "fixture-token") {}.skipped)
            store.saveEntry(profile, entry(3))
            val before = fingerprint(profile)
            assertEquals(1, MinifluxCacheSync(api, store).cleanup(target, "fixture-token") {}.skipped)
            assertEquals(before, fingerprint(profile))
        }

    @Test fun transactionFailureRollsBackAndNeverTouchesBlobs() =
        runBlocking<Unit> {
            val profile = prepare()
            val target = plan(profile)
            val before = fingerprint(profile)
            store
                .database(profile.id)
                .openHelper.writableDatabase
                .execSQL("CREATE TRIGGER fixture_fail BEFORE DELETE ON feeds BEGIN SELECT RAISE(ABORT, 'fixture'); END")
            val result = MinifluxCacheSync(api, store).cleanup(target, "fixture-token") {}
            assertEquals(1, result.failed)
            assertEquals(0, result.feeds)
            assertEquals(0, result.blobs)
            assertEquals(before, fingerprint(profile))
        }

    @Test fun blobFailureLeavesOrphanWithoutRestoringDbAndNextDryRunSyncReaderWork() =
        runBlocking<Unit> {
            val profile = prepare()
            val target = plan(profile)
            val result = MinifluxFeedCleanup(api, store, deleteBlob = { false }).run(target, "fixture-token")
            assertEquals(1, result.feeds)
            assertEquals(2, result.blobsFailed)
            assertEquals(0, store.summary(profile).entries)
            assertEquals(2, store.articleDirectory(profile.id).listFiles()!!.size)
            assertEquals(0, dry(profile).missingFeeds)
            assertTrue(store.articles(profile.id, 0).isEmpty())
            store.saveFeed(profile, feed(1), MinifluxCategory(99, "News"))
            store.saveEntry(profile, entry(10))
            assertEquals(
                "<p>10</p>",
                store.readBody(
                    profile.id,
                    store
                        .database(profile.id)
                        .cacheDao()
                        .entryIdentity(profile.id, 10)!!
                        .localFeedItemId,
                ),
            )
        }

    @Test fun cancelAfterFirstCommitAndReopenRetainsOnlyUnprocessedFeed() =
        runBlocking<Unit> {
            val profile = prepare(1)
            store.saveFeed(profile, feed(2), MinifluxCategory(99, "News"))
            store.saveEntry(profile, entry(20, 2))
            val target = plan(profile)
            var partial = MinifluxCleanupResult()
            val worker =
                async(Dispatchers.IO) {
                    val job = currentCoroutineContext()[Job]!!
                    MinifluxCacheSync(api, store).cleanup(target, "fixture-token") { result ->
                        partial = result
                        if (result.feeds == 1) job.cancel()
                    }
                }
            worker.join()
            assertTrue(worker.isCancelled)
            assertEquals(1, partial.feeds)
            store.close()
            store = MinifluxCacheStore(context, suffix)
            assertEquals(1, store.summary(profile).feeds)
            assertEquals(1, dry(profile).missingFeeds)
            assertEquals(1, store.summary(profile).entries)
        }

    @Test fun partialSuccessSkipsNewlyProtectedFeed() =
        runBlocking<Unit> {
            val profile = prepare(1)
            store.saveFeed(profile, feed(2), MinifluxCategory(99, "News"))
            store.saveEntry(profile, entry(20, 2))
            val target = plan(profile)
            store.saveEntry(profile, entry(20, 2, true))
            val result = MinifluxCacheSync(api, store).cleanup(target, "fixture-token") {}
            assertEquals(1, result.feeds)
            assertEquals(1, result.skipped)
            assertNotNull(store.database(profile.id).cacheDao().entryIdentity(profile.id, 20))
        }

    @Test fun cancellationBeforeRemoteValidationDoesNothing() =
        runBlocking<Unit> {
            val profile = prepare()
            val target = plan(profile)
            val before = fingerprint(profile)
            failure = "stall"
            requests.clear()
            val slow = MinifluxApiClient(OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build())
            val job = async(Dispatchers.IO) { MinifluxCacheSync(slow, store).cleanup(target, "fixture-token") {} }
            withTimeout(3000) { while (requests.none { it.requestUrl!!.encodedPath == "/v1/feeds" }) delay(10) }
            job.cancelAndJoin()
            assertEquals(before, fingerprint(profile))
        }

    @Test fun sharedMutexSerializesDryRunReadSyncAndCleanupWithoutDeadlock() =
        runBlocking<Unit> {
            val profile = prepare()
            val target = plan(profile)
            val firstSync = MinifluxCacheSync(api, store)
            val secondSync = MinifluxCacheSync(api, store)
            val mutations = MinifluxReadMutationStore(store)
            store.operationMutex.lock()
            requests.clear()
            val dry = async(Dispatchers.IO) { secondSync.deletionDryRun(profile, "fixture-token") }
            val read = async(Dispatchers.IO) { MinifluxReadSync(api, store, mutations).syncWithResult(profile, "fixture-token") }
            val cleanup = async(Dispatchers.IO) { firstSync.cleanup(target, "fixture-token") {} }
            delay(100)
            assertTrue(requests.isEmpty())
            store.operationMutex.unlock()
            withTimeout(5000) {
                dry.await()
                read.await()
                cleanup.await()
            }
            assertGetOnly()
        }

    @Test fun controllerRequiresOneTimeConfirmationAndReleasesBusyOnEveryOutcome() =
        runBlocking<Unit> {
            val profile = prepare()
            val credentials = MinifluxCredentialStore(context, "cleanup_$suffix", suffix)
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
                val before = fingerprint(profile)
                controller.requestCleanup(profile.baseUrl)
                assertEquals(
                    1,
                    controller.state.value.cleanupConfirmation!!
                        .feeds,
                )
                controller.dismissCleanup()
                controller.confirmCleanup(profile.baseUrl)
                assertEquals(before, fingerprint(profile))
                controller.requestCleanup(profile.baseUrl)
                failure = "auth"
                controller.confirmCleanup(profile.baseUrl)
                idle()
                assertFalse(
                    controller.state.value.cleanupResult!!
                        .complete,
                )
                assertNull(controller.state.value.deletionResult)
                assertEquals(before, fingerprint(profile))
                failure = ""
                controller.syncCache(profile.baseUrl, deletionDryRun = true)
                idle()
                controller.requestCleanup(profile.baseUrl)
                controller.confirmCleanup(profile.baseUrl)
                idle()
                assertEquals(
                    1,
                    controller.state.value.cleanupResult!!
                        .feeds,
                )
                assertNull(controller.state.value.deletionResult)
                assertEquals(
                    0,
                    controller.state.value.summary!!
                        .feeds,
                )
                controller.confirmCleanup(profile.baseUrl)
                assertFalse(controller.state.value.busy)
                assertGetOnly()
            } finally {
                scope.cancel()
                credentials.clearToken()
            }
        }

    @Test fun controllerCancellationAndWholeOperationTimeoutReleaseBusyWithoutDeletion() =
        runBlocking<Unit> {
            val profile = prepare()
            val before = fingerprint(profile)
            val credentials = MinifluxCredentialStore(context, "cleanup_cancel_$suffix", suffix)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val slow = MinifluxApiClient(OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build())
            try {
                credentials.saveToken(profile.baseUrl, "fixture-token")
                val mutations = MinifluxReadMutationStore(store)
                val controller = MinifluxCacheController(store, credentials, MinifluxCacheSync(slow, store, timeoutMillis = 1000), scope, MinifluxReadSync(slow, store, mutations), mutations)

                suspend fun idle() {
                    withTimeout(5000) { while (controller.state.value.busy) delay(10) }
                }
                for (cancel in listOf(true, false)) {
                    failure = ""
                    controller.syncCache(profile.baseUrl, deletionDryRun = true)
                    idle()
                    controller.requestCleanup(profile.baseUrl)
                    assertNotNull(controller.state.value.cleanupConfirmation)
                    requests.clear()
                    failure = "stall"
                    controller.confirmCleanup(profile.baseUrl)
                    if (cancel) {
                        withTimeout(3000) { while (requests.none { it.requestUrl!!.encodedPath == "/v1/feeds" }) delay(10) }
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

    @Test fun normalRssDatabaseUnchanged() =
        runBlocking<Unit> {
            val normal = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
            try {
                val id = normal.feedDao().insertFeed(Feed(title = "Local", url = URL("https://example.com/rss")))
                val item = normal.feedItemDao().insertFeedItem(FeedItem(guid = "local", feedId = id, readTime = Instant.EPOCH))
                val profile = prepare()
                assertEquals(1, MinifluxCacheSync(api, store).cleanup(plan(profile), "fixture-token") {}.feeds)
                assertEquals("Local", normal.feedDao().getFeed(id)!!.title)
                assertEquals(Instant.EPOCH, normal.feedItemDao().loadFeedItem(item)!!.readTime)
                assertEquals(40, normal.openHelper.readableDatabase.version)
            } finally {
                normal.close()
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
