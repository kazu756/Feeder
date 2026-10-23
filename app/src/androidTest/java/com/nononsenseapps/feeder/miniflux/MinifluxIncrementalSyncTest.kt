package com.nononsenseapps.feeder.miniflux

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class MinifluxIncrementalSyncTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val suffix = UUID.randomUUID().toString()
    private val root = File(app.cacheDir, "miniflux_delta_$suffix").apply { mkdirs() }
    private val context =
        object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir(): File = root

            override fun getNoBackupFilesDir(): File = File(root, "credentials").apply { mkdirs() }

            override fun getDatabasePath(name: String): File = File(root, "databases/$name").also { it.parentFile?.mkdirs() }
        }
    private val store = MinifluxCacheStore(context, suffix)
    private val server = MockWebServer().apply { start() }
    private val api =
        MinifluxApiClient(
            OkHttpClient
                .Builder()
                .readTimeout(250, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build(),
        )
    private val sync = MinifluxCacheSync(api, store)
    private val t = Instant.parse("2026-10-23T00:00:00Z")
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile private var rows = emptyList<MinifluxEntry>()

    @Volatile private var failureCode = 0

    @Volatile private var failFirstPage = false

    @Volatile private var feedsPresent = true

    @Volatile private var malformed = false

    @Volatile private var stall = false

    @Volatile private var onPage: ((RecordedRequest) -> Unit)? = null
    private var allowReadPut = false

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    val url = request.requestUrl!!
                    val json = Json
                    if (request.method == "PUT" && allowReadPut) {
                        val update = json.decodeFromString<MinifluxReadUpdate>(request.body.readUtf8())
                        rows = rows.map { if (it.id in update.entryIds) it.copy(status = update.status) else it }
                        return MockResponse().setResponseCode(204)
                    }
                    if (request.method != "GET") return MockResponse().setResponseCode(405)
                    val body =
                        when (url.encodedPath) {
                            "/v1/me" -> """{"id":42,"username":"test"}"""
                            "/v1/categories" -> """[{"id":99,"title":"News"}]"""
                            "/v1/feeds" -> if (feedsPresent) """[{"id":5000,"title":"Feed","feed_url":"https://example.com/rss","category":{"id":99,"title":"News"}}]""" else "[]"
                            "/v1/entries" -> {
                                if (url.queryParameter("order") != "changed_at") {
                                    onPage?.invoke(request)
                                    if ((failFirstPage || url.queryParameter("before_entry_id") != null) && failureCode != 0) return MockResponse().setResponseCode(failureCode)
                                    if (malformed) return MockResponse().setBody("not-json")
                                    if (stall) return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                                }
                                val after = url.queryParameter("changed_after")?.toLong()?.let(Instant::ofEpochSecond)
                                val before = url.queryParameter("changed_before")?.toLong()?.let(Instant::ofEpochSecond)
                                val cursor = url.queryParameter("before_entry_id")?.toLong()
                                val status = url.queryParameter("status")
                                val starred = url.queryParameter("starred")
                                val published = url.queryParameter("published_after")?.toLong()?.let(Instant::ofEpochSecond)
                                val filtered =
                                    rows.filter {
                                        (after == null || Instant.parse(it.changedAt) > after) &&
                                            (before == null || Instant.parse(it.changedAt) < before) &&
                                            (cursor == null || it.id < cursor) &&
                                            (status == null || it.status == status) &&
                                            (starred == null || it.starred == starred.toBoolean()) &&
                                            (published == null || Instant.parse(it.publishedAt) > published)
                                    }
                                val sorted = if (url.queryParameter("order") == "changed_at") filtered.sortedByDescending { Instant.parse(it.changedAt) } else filtered.sortedByDescending { it.id }
                                json.encodeToString(MinifluxEntries(filtered.size.toLong(), sorted.take(url.queryParameter("limit")!!.toInt())))
                            }
                            else -> {
                                val id = url.encodedPath.substringAfterLast('/').toLongOrNull()
                                val entry = rows.firstOrNull { it.id == id } ?: return MockResponse().setResponseCode(404)
                                json.encodeToString(entry)
                            }
                        }
                    return MockResponse().setBody(body)
                }
            }
    }

    @After fun cleanup() {
        store.close()
        server.shutdown()
        root.deleteRecursively()
        app
            .getSharedPreferences(suffix, 0)
            .edit()
            .clear()
            .commit()
    }

    private fun entry(
        id: Long = 9000,
        changed: Instant = t,
    ): MinifluxEntry = MinifluxEntry(id, 5000, "Title $id", "unread", false, t.toString(), changed.toString(), "https://example.com/$id", "Author", "<p>Body $id</p>")

    private suspend fun prepare(checkpoint: Instant? = t): MinifluxProfile {
        val profile = store.prepare(server.url("/").toString(), MinifluxAccount(42, "test"))
        val category = MinifluxCategory(99, "News")
        store.saveCategories(profile, listOf(category))
        store.saveFeed(profile, MinifluxFeed(5000, "Feed", "https://example.com/rss", category), category)
        if (checkpoint != null) store.database(profile.id).cacheDao().saveProfile(profile.copy(changedCheckpoint = checkpoint.toString(), lastSuccessfulSync = 123))
        return store.selectedProfile()!!
    }

    private suspend fun runSync(): MinifluxCacheSummary = withTimeout(10000) { sync.sync(server.url("/").toString(), "fixture-token") { _, _ -> } }

    @Test fun initialBaselineThenIncrementalPreservesIdsAndAvoidsRewritingUnchangedBody() =
        runBlocking<Unit> {
            rows = listOf(entry())
            val first = runSync()
            assertEquals("initial", first.syncResult!!.mode)
            assertEquals(t.toString(), first.profile.changedCheckpoint)
            val identity = store.database(first.profile.id).cacheDao().entryIdentity(first.profile.id, 9000)!!
            val blob =
                com.nononsenseapps.feeder.blob
                    .blobFile(identity.localFeedItemId, store.articleDirectory(first.profile.id))
            blob.setLastModified(123456789)
            requests.clear()
            val again = runSync()
            assertEquals("incremental", again.syncResult!!.mode)
            assertEquals(1, again.entries)
            assertEquals(0, again.syncResult!!.blobsUpdated)
            assertEquals(123456789L, blob.lastModified())
            assertTrue(
                requests.filter { it.requestUrl!!.encodedPath == "/v1/entries" && it.requestUrl!!.queryParameter("order") == "id" }.all {
                    it.requestUrl!!.queryParameter("changed_after") ==
                        t.minusSeconds(2).epochSecond.toString()
                },
            )
            assertEquals(
                identity.localFeedItemId,
                store
                    .articles(first.profile.id, 0)
                    .single()
                    .item.id,
            )
            assertTrue(requests.all { it.method == "GET" && it.requestUrl!!.queryParameter("offset") == null })
        }

    @Test fun boundaryEntriesNewRowsContentStatusAndPendingAllUseExistingMapper() =
        runBlocking<Unit> {
            val profile = prepare()
            store.saveEntry(profile, entry())
            val original = store.articles(profile.id, 0).single()
            val mutations = MinifluxReadMutationStore(store)
            mutations.setStatus(profile.id, 9000, "read")
            rows = listOf(entry().copy(content = "<p>Changed content</p>", starred = true), entry(9001, t.plusSeconds(1)).copy(status = "read"), entry(9002, t.plusSeconds(2)))
            val result = runSync()
            assertEquals(3, result.entries)
            assertEquals(2, result.syncResult!!.inserted)
            assertEquals(1, result.syncResult!!.pendingProtected)
            val article = store.articles(profile.id, 0).first { it.identity.remoteEntryId == 9000L }
            assertEquals(original.item.id, article.item.id)
            assertEquals("read", article.effectiveStatus)
            assertEquals("unread", article.identity.remoteStatus)
            assertEquals(1L, article.pending!!.generation)
            assertTrue(article.identity.remoteStarred)
            assertEquals("<p>Changed content</p>", store.readBody(profile.id, article.item.id))
            assertNotNull(
                store
                    .articles(profile.id, 0)
                    .first { it.identity.remoteEntryId == 9001L }
                    .item.readTime,
            )
            assertEquals(t.plusSeconds(2).toString(), result.profile.changedCheckpoint)
            allowReadPut = true
            assertEquals(0, MinifluxReadSync(api, store, mutations).sync(result.profile, "fixture-token"))
            assertNull(store.articles(profile.id, 0).first { it.identity.remoteEntryId == 9000L }.pending)
        }

    @Test fun hundredItemPagesFailWithoutAdvancingAndRetryIdempotently() =
        runBlocking<Unit> {
            val profile = prepare()
            rows = (1L..250L).map { entry(it, t.plusSeconds(1)) }
            failureCode = 503
            assertFails { runSync() }
            assertEquals(t.toString(), store.selectedProfile()!!.changedCheckpoint)
            assertEquals(100, store.summary(profile).entries)
            val id =
                store
                    .database(profile.id)
                    .cacheDao()
                    .entryIdentity(profile.id, 250)!!
                    .localFeedItemId
            failureCode = 0
            val completed = runSync()
            assertEquals(250, completed.entries)
            assertEquals(4, completed.syncResult!!.pages) // 100 + 100 + 50 + empty
            assertEquals(
                id,
                store
                    .database(profile.id)
                    .cacheDao()
                    .entryIdentity(profile.id, 250)!!
                    .localFeedItemId,
            )
            assertEquals(t.plusSeconds(1).toString(), completed.profile.changedCheckpoint)
        }

    @Test fun concurrentHigherIdsAndChangesToAlreadyTraversedIdsAreRecoveredNextRun() =
        runBlocking<Unit> {
            prepare()
            rows = (1L..200L).map { entry(it, t.plusSeconds(1)) }
            var injected = false
            onPage = { request ->
                if (!injected && request.requestUrl!!.queryParameter("before_entry_id") != null) {
                    injected = true
                    rows =
                        rows.map { if (it.id == 200L) it.copy(changedAt = t.plusSeconds(5).toString(), status = "read") else it } + entry(201, t.plusSeconds(5)) +
                        entry(202, t.plusSeconds(1).plusNanos(500_000_000))
                }
            }
            val first = runSync()
            assertEquals(t.plusSeconds(1).toString(), first.profile.changedCheckpoint)
            assertEquals(200, first.entries)
            onPage = null
            val next = runSync()
            assertEquals(202, next.entries)
            assertEquals(t.plusSeconds(5).toString(), next.profile.changedCheckpoint)
            val observed = store.database(next.profile.id).cacheDao().entryIdentity(next.profile.id, 200)!!
            assertEquals("read", observed.remoteStatus)
        }

    @Test fun emptyDeltaAndMissingRemoteRowsNeverJumpToDeviceTimeOrDeleteCache() =
        runBlocking<Unit> {
            val profile = prepare()
            store.saveEntry(profile, entry())
            rows = emptyList()
            feedsPresent = false
            val result = runSync()
            assertEquals(t.toString(), result.profile.changedCheckpoint)
            assertEquals(0, result.syncResult!!.fetched)
            assertEquals(1, result.entries)
            assertEquals(1, result.feeds)
        }

    @Test fun authenticationServerErrorsAndMissingBootstrapTimesNeverAdvance() =
        runBlocking<Unit> {
            val profile = prepare()
            store.saveEntry(profile, entry())
            rows = listOf(entry(changed = t.plusSeconds(1)))
            failFirstPage = true
            for (code in listOf(401, 503)) {
                failureCode = code
                assertFails { runSync() }
                assertEquals(t.toString(), store.selectedProfile()!!.changedCheckpoint)
                assertEquals(123L, store.selectedProfile()!!.lastSuccessfulSync)
            }
            failFirstPage = false
            failureCode = 0
            val dao = store.database(profile.id).cacheDao()
            dao.saveProfile(store.selectedProfile()!!.copy(changedCheckpoint = null))
            val mapped = dao.entryIdentity(profile.id, 9000)!!
            dao.updateEntryIdentity(mapped.copy(changedAt = null))
            assertFails { runSync() }
            assertNull(store.selectedProfile()!!.changedCheckpoint)
            assertEquals(1, store.summary(profile).entries)
        }

    @Test fun failedBootstrapRetainsItsOriginalLowerBoundAcrossProcessRecreation() =
        runBlocking<Unit> {
            val profile = prepare(null)
            store.saveEntry(profile, entry(250, t.minusSeconds(100)))
            store.complete(profile)
            rows = (1L..250L).map { entry(it, if (it == 1L) t.minusSeconds(50) else t) }
            failureCode = 503
            assertFails { runSync() }
            assertNull(store.selectedProfile()!!.changedCheckpoint)
            assertEquals(t.minusSeconds(100).toString(), store.selectedProfile()!!.bootstrapChangedAt)
            // The oldest cached time has now changed to T; recomputing it would skip Entry 1.
            store.close()
            failureCode = 0
            val completed = runSync()
            assertEquals(250, completed.entries)
            assertNotNull(store.database(profile.id).cacheDao().entryIdentity(profile.id, 1))
            assertEquals(t.toString(), completed.profile.changedCheckpoint)
            assertNull(completed.profile.bootstrapChangedAt)
        }

    @Test fun legacyBootstrapUsesOldestCachedTimeAndPreservesCheckpointAcrossReopen() =
        runBlocking<Unit> {
            val profile = prepare(null)
            store.saveEntry(profile, entry(9000, t.minusSeconds(100)))
            store.saveEntry(profile, entry(9001, t))
            store.complete(profile)
            rows = listOf(entry(9000, t.minusSeconds(50)), entry(9001, t))
            val result = runSync()
            assertEquals("bootstrap", result.syncResult!!.mode)
            assertTrue(requests.any { it.requestUrl!!.queryParameter("changed_after") == t.minusSeconds(102).epochSecond.toString() })
            store.close()
            assertEquals(t.toString(), store.selectedProfile()!!.changedCheckpoint)
            assertEquals("incremental", runSync().syncResult!!.mode)
        }

    @Test fun blobFailureMalformedResponseAndHttpFailureDoNotAdvanceCheckpoint() =
        runBlocking<Unit> {
            val profile = prepare()
            rows = listOf(entry(changed = t.plusSeconds(1)))
            val staging =
                File(store.articleDirectory(profile.id).parentFile, "staging").apply {
                    parentFile!!.mkdirs()
                    writeText("block")
                }
            assertFails { runSync() }
            assertEquals(t.toString(), store.selectedProfile()!!.changedCheckpoint)
            staging.delete()
            malformed = true
            assertFails { runSync() }
            assertEquals(t.toString(), store.selectedProfile()!!.changedCheckpoint)
            malformed = false
            val repaired = runSync()
            assertEquals(1, repaired.blobs)
            assertEquals(t.plusSeconds(1).toString(), repaired.profile.changedCheckpoint)
        }

    @Test fun controllerReleasesBusyAfterCacheSuccessFailureTimeoutAndCancel() =
        runBlocking<Unit> {
            val profile = prepare()
            rows = listOf(entry(changed = t.plusSeconds(1)))
            val credentials = MinifluxCredentialStore(context, "feeder_delta_$suffix", suffix)
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
            try {
                credentials.saveToken(profile.baseUrl, "fixture-token")
                val mutations = MinifluxReadMutationStore(store)
                val controller = MinifluxCacheController(store, credentials, sync, scope, MinifluxReadSync(api, store, mutations), mutations)

                suspend fun idle() {
                    withTimeout(5000) { while (controller.state.value.busy) delay(10) }
                }
                controller.syncCache(profile.baseUrl)
                idle()
                assertTrue(controller.state.value.completed)
                val checkpoint = store.selectedProfile()!!.changedCheckpoint
                failureCode = 401
                failFirstPage = true
                controller.syncCache(profile.baseUrl)
                idle()
                assertTrue(controller.state.value.failed)
                assertEquals(checkpoint, store.selectedProfile()!!.changedCheckpoint)
                failFirstPage = false
                failureCode = 0
                stall = true
                controller.syncCache(profile.baseUrl)
                idle()
                assertTrue(controller.state.value.failed)
                assertEquals("TIMEOUT", controller.state.value.errorType)
                controller.syncCache(profile.baseUrl)
                controller.cancel()
                idle()
                assertEquals(checkpoint, store.selectedProfile()!!.changedCheckpoint)
            } finally {
                scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                credentials.clearToken()
            }
        }

    @Test fun cancellationAndTimeoutPreserveCheckpointAndCache() =
        runBlocking<Unit> {
            val profile = prepare()
            store.saveEntry(profile, entry())
            rows = listOf(entry(changed = t.plusSeconds(1)))
            stall = true
            assertFails { runSync() }
            assertEquals(t.toString(), store.selectedProfile()!!.changedCheckpoint)
            val slow = MinifluxApiClient(OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build())
            val worker = async(Dispatchers.IO) { MinifluxCacheSync(slow, store).sync(profile.baseUrl, "fixture-token") { _, _ -> } }
            withTimeout(2000) { while (requests.count { it.requestUrl!!.queryParameter("changed_after") != null } < 2) delay(10) }
            worker.cancelAndJoin()
            assertTrue(worker.isCancelled)
            assertEquals(t.toString(), store.selectedProfile()!!.changedCheckpoint)
            assertEquals(1, store.summary(profile).entries)
        }
}
