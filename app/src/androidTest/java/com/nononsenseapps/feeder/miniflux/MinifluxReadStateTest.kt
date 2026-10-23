package com.nononsenseapps.feeder.miniflux

import android.content.Context
import android.content.ContextWrapper
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class MinifluxReadStateTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val suffix = UUID.randomUUID().toString()
    private val root = File(app.cacheDir, "miniflux_m3_$suffix").apply { mkdirs() }
    private val context =
        object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir(): File = root

            override fun getNoBackupFilesDir(): File = File(root, "credentials").apply { mkdirs() }

            override fun getDatabasePath(name: String): File = File(root, "databases/$name").also { it.parentFile?.mkdirs() }
        }
    private val cache = MinifluxCacheStore(context, suffix)
    private val mutations = MinifluxReadMutationStore(cache)
    private val credentials = MinifluxCredentialStore(context, "feeder_m3_fix_$suffix", suffix)
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer().apply { start() }
    private val api =
        MinifluxApiClient(
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .readTimeout(300, TimeUnit.MILLISECONDS)
                .build(),
        )
    private val entry = MinifluxEntry(9000, 5000, "Title", "unread", false, "2026-10-23T00:00:00Z", null, "https://example.com/a", content = "<p>Body</p>")

    @After fun cleanup() {
        controllerScope.cancel()
        credentials.clearToken()
        cache.close()
        server.shutdown()
        root.deleteRecursively()
        app
            .getSharedPreferences(suffix, 0)
            .edit()
            .clear()
            .commit()
    }

    private suspend fun prepare(): MinifluxProfile {
        val profile = cache.prepare(server.url("/").toString(), MinifluxAccount(42, "test"))
        val category = MinifluxCategory(99, "News")
        cache.saveCategories(profile, listOf(category))
        cache.saveFeed(profile, MinifluxFeed(5000, "Feed", "https://example.com/rss", category), category)
        cache.saveEntry(profile, entry)
        return profile
    }

    private fun remote(
        status: String,
        id: Long = 9000,
    ): MockResponse = MockResponse().setBody("""{"id":$id,"feed_id":5000,"title":"Title","status":"$status","starred":false,"published_at":"2026-10-23T00:00:00Z","url":"https://example.com/a"}""")

    private fun account() = server.enqueue(MockResponse().setBody("""{"id":42,"username":"test"}"""))

    private fun sync() = MinifluxReadSync(api, cache, mutations)

    private suspend fun boundedSync(profile: MinifluxProfile): Int = withTimeout(5000) { MinifluxReadSync(api, cache, mutations).sync(profile, "test-token") }

    @Test fun optimisticEditsCoalesceSurviveReopenAndProtectAgainstGetImports() =
        runBlocking<Unit> {
            val profile = prepare()
            val dao = cache.database(profile.id).cacheDao()
            mutations.setStatus(profile.id, 9000, "read")
            assertEquals("read", cache.articles(profile.id, 0).single().effectiveStatus)
            assertNotNull(
                cache
                    .articles(profile.id, 0)
                    .single()
                    .item.readTime,
            )
            mutations.setStatus(profile.id, 9000, "unread")
            assertEquals("unread", dao.pending(profile.id, 9000)!!.desiredStatus)
            mutations.setStatus(profile.id, 9000, "read")
            assertEquals(3L, dao.pending(profile.id, 9000)!!.generation)
            assertEquals(1, dao.pending(profile.id).size)
            cache.saveEntry(profile, entry)
            assertEquals(
                "unread",
                cache
                    .articles(profile.id, 0)
                    .single()
                    .identity.remoteStatus,
            )
            assertEquals("read", cache.articles(profile.id, 0).single().effectiveStatus)
            assertEquals(3L, dao.pending(profile.id, 9000)!!.generation)
            cache.close()
            assertEquals("read", cache.articles(profile.id, 0).single().effectiveStatus)
            assertEquals(
                3L,
                cache
                    .database(profile.id)
                    .cacheDao()
                    .pending(profile.id, 9000)!!
                    .generation,
            )
            assertEquals(0, server.requestCount)
        }

    @Test fun matchingConfirmationAcknowledgesAndGenerationsNeverRestart() =
        runBlocking<Unit> {
            val profile = prepare()
            mutations.setStatus(profile.id, 9000, "read")
            account()
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(remote("read"))
            assertEquals(0, boundedSync(profile))
            assertNull(cache.database(profile.id).cacheDao().pending(profile.id, 9000))
            assertEquals("read", cache.articles(profile.id, 0).single().effectiveStatus)
            mutations.setStatus(profile.id, 9000, "unread")
            assertEquals(
                2L,
                cache
                    .database(profile.id)
                    .cacheDao()
                    .pending(profile.id, 9000)!!
                    .generation,
            )
            assertNull(
                cache
                    .articles(profile.id, 0)
                    .single()
                    .item.readTime,
            )
        }

    @Test fun successWithoutConfirmationMismatchMalformedAndMissingRemainPending() =
        runBlocking<Unit> {
            val profile = prepare()
            mutations.setStatus(profile.id, 9000, "read")
            for ((response, expected) in listOf(
                remote("unread") to "CONFIRMATION_MISMATCH",
                MockResponse().setBody("not-json") to "INVALID_RESPONSE",
                MockResponse().setResponseCode(404) to "NOT_FOUND",
                remote("read", 9999) to "INVALID_RESPONSE",
            )) {
                account()
                server.enqueue(MockResponse().setResponseCode(204))
                server.enqueue(response)
                assertEquals(1, boundedSync(profile))
                assertEquals(
                    expected,
                    cache
                        .database(profile.id)
                        .cacheDao()
                        .pending(profile.id, 9000)!!
                        .lastErrorType,
                )
            }
            assertEquals(
                4,
                cache
                    .database(profile.id)
                    .cacheDao()
                    .pending(profile.id, 9000)!!
                    .attemptCount,
            )
            assertEquals("read", cache.articles(profile.id, 0).single().effectiveStatus)
        }

    @Test fun authenticationAndServerErrorsNeverDiscardPending() =
        runBlocking<Unit> {
            val profile = prepare()
            mutations.setStatus(profile.id, 9000, "read")
            for ((code, expected) in listOf(401 to "UNAUTHORIZED", 403 to "FORBIDDEN", 404 to "NOT_FOUND", 503 to "SERVER")) {
                account()
                server.enqueue(MockResponse().setResponseCode(code))
                assertEquals(1, boundedSync(profile))
                assertEquals(
                    expected,
                    cache
                        .database(profile.id)
                        .cacheDao()
                        .pending(profile.id, 9000)!!
                        .lastErrorType,
                )
            }
            assertEquals(
                4,
                cache
                    .database(profile.id)
                    .cacheDao()
                    .pending(profile.id, 9000)!!
                    .attemptCount,
            )
        }

    @Test fun inFlightOldGenerationCannotAcknowledgeNewDesiredState() =
        runBlocking<Unit> {
            val profile = prepare()
            mutations.setStatus(profile.id, 9000, "read")
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when {
                            request.path == "/v1/me" -> MockResponse().setBody("""{"id":42,"username":"test"}""")
                            request.method == "PUT" -> {
                                runBlocking { mutations.setStatus(profile.id, 9000, "unread") }
                                MockResponse().setResponseCode(204)
                            }
                            else -> remote("read")
                        }
                }
            assertEquals(1, boundedSync(profile))
            val row = cache.database(profile.id).cacheDao().pending(profile.id, 9000)!!
            assertEquals(2L, row.generation)
            assertEquals("unread", row.desiredStatus)
            assertEquals("unread", cache.articles(profile.id, 0).single().effectiveStatus)
            assertNull(row.lastErrorType)
        }

    @Test fun batchesAreBoundedSeparatedAndContainOnlyStatusMutation() =
        runBlocking<Unit> {
            val profile = prepare()
            for (id in 9000L..9051L) {
                cache.saveEntry(profile, entry.copy(id = id))
                mutations.setStatus(profile.id, id, if (id == 9051L) "unread" else "read")
            }
            val bodies = mutableListOf<String>()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when {
                            request.path == "/v1/me" -> MockResponse().setBody("""{"id":42,"username":"test"}""")
                            request.method == "PUT" -> {
                                assertEquals("/v1/entries", request.path)
                                bodies.add(request.body.readUtf8())
                                MockResponse().setResponseCode(204)
                            }
                            else -> {
                                assertEquals("GET", request.method)
                                val id = request.path!!.substringAfterLast('/').toLong()
                                remote(if (id == 9051L) "unread" else "read", id)
                            }
                        }
                }
            assertEquals(0, boundedSync(profile))
            assertEquals(3, bodies.size)
            val json = kotlinx.serialization.json.Json
            for (body in bodies) {
                val payload = json.decodeFromString<MinifluxReadUpdate>(body)
                assertTrue(payload.entryIds.size <= 50)
                assertEquals(setOf("entry_ids", "status"), (json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject).keys)
                assertTrue(payload.entryIds.all { (it == 9051L) == (payload.status == "unread") })
            }
        }

    @Test fun remoteChangesWithoutPendingAndWrongAccountProtection() =
        runBlocking<Unit> {
            val profile = prepare()
            assertEquals(0, boundedSync(profile))
            assertEquals(0, server.requestCount)
            mutations.observeRemote(profile.id, entry.copy(status = "read"))
            assertEquals("read", cache.articles(profile.id, 0).single().effectiveStatus)
            assertEquals(
                java.time.Instant.EPOCH,
                cache
                    .articles(profile.id, 0)
                    .single()
                    .item.readTime,
            )
            mutations.setStatus(profile.id, 9000, "unread")
            server.enqueue(MockResponse().setBody("""{"id":43,"username":"other"}"""))
            assertFails { boundedSync(profile) }
            assertEquals(1, server.requestCount) // wrong account only; no PUT or unrelated refresh
            assertEquals("unread", cache.articles(profile.id, 0).single().effectiveStatus)
        }

    @Test fun localTransactionRollsBackIfOutboxCannotBeWritten() =
        runBlocking<Unit> {
            val profile = prepare()
            val db = cache.database(profile.id)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_outbox BEFORE INSERT ON miniflux_read_outbox BEGIN SELECT RAISE(ABORT, 'test failure'); END")
            assertFails { mutations.setStatus(profile.id, 9000, "read") }
            assertEquals("unread", cache.articles(profile.id, 0).single().effectiveStatus)
            assertNull(
                cache
                    .articles(profile.id, 0)
                    .single()
                    .item.readTime,
            )
            assertEquals(
                0L,
                cache
                    .articles(profile.id, 0)
                    .single()
                    .identity.readGeneration,
            )
            assertTrue(db.cacheDao().pending(profile.id).isEmpty())
        }

    @Test fun connectionDnsTimeoutAndTlsFailuresKeepLocalDesiredState() =
        runBlocking<Unit> {
            val profile = prepare()
            mutations.setStatus(profile.id, 9000, "read")
            for ((failure, expected) in listOf(
                java.net.ConnectException() to "CONNECTION",
                java.net.UnknownHostException() to "DNS",
                java.net.SocketTimeoutException() to "TIMEOUT",
                javax.net.ssl.SSLException("test") to "TLS",
            )) {
                val failing =
                    MinifluxApiClient(
                        OkHttpClient
                            .Builder()
                            .addInterceptor { chain ->
                                if (chain.request().method == "PUT") throw failure
                                chain.proceed(chain.request())
                            }.build(),
                    )
                account()
                if (expected != "TLS") server.enqueue(remote("unread"))
                assertEquals(1, MinifluxReadSync(failing, cache, mutations).sync(profile, "test-token"))
                assertEquals(
                    expected,
                    cache
                        .database(profile.id)
                        .cacheDao()
                        .pending(profile.id, 9000)!!
                        .lastErrorType,
                )
                assertEquals("read", cache.articles(profile.id, 0).single().effectiveStatus)
            }
        }

    @Test fun realCacheSyncPreservesPendingAndGeneration() =
        runBlocking<Unit> {
            val profile = prepare()
            mutations.setStatus(profile.id, 9000, "read")
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        assertEquals("GET", request.method)
                        return when (request.requestUrl!!.encodedPath) {
                            "/v1/me" -> MockResponse().setBody("""{"id":42,"username":"test"}""")
                            "/v1/categories" -> MockResponse().setBody("""[{"id":99,"title":"News"}]""")
                            "/v1/feeds" -> MockResponse().setBody("""[{"id":5000,"title":"Feed","feed_url":"https://example.com/rss","category":{"id":99,"title":"News"}}]""")
                            else -> {
                                val url = request.requestUrl!!
                                if (url.queryParameter("status") == "unread" && url.queryParameter("before_entry_id") == null) {
                                    MockResponse().setBody(
                                        """{"total":1,"entries":[{"id":9000,"feed_id":5000,"title":"Title","status":"unread","starred":false,"published_at":"2026-10-23T00:00:00Z","url":"https://example.com/a","content":"<p>Body updated</p>"}]}""",
                                    )
                                } else {
                                    MockResponse().setBody("""{"total":0,"entries":[]}""")
                                }
                            }
                        }
                    }
                }
            MinifluxCacheSync(api, cache).sync(profile.baseUrl, "test-token") { _, _ -> }
            val article = cache.articles(profile.id, 0).single()
            assertEquals("read", article.effectiveStatus)
            assertEquals("unread", article.identity.remoteStatus)
            assertEquals(1L, article.pending!!.generation)
            assertEquals(1L, article.identity.readGeneration)
            assertEquals("<p>Body updated</p>", cache.readBody(profile.id, article.item.id))
        }

    @Test fun uncertainPutResponseCanBeConfirmedWithoutDiscardingNewerWork() =
        runBlocking<Unit> {
            val profile = prepare()
            mutations.setStatus(profile.id, 9000, "read")
            val failing =
                MinifluxApiClient(
                    OkHttpClient
                        .Builder()
                        .addInterceptor { chain ->
                            if (chain.request().method == "PUT") throw java.net.SocketTimeoutException()
                            chain.proceed(chain.request())
                        }.build(),
                )
            account()
            server.enqueue(remote("read"))
            assertEquals(0, MinifluxReadSync(failing, cache, mutations).sync(profile, "test-token"))
            assertEquals("read", cache.articles(profile.id, 0).single().effectiveStatus)
            assertNull(cache.articles(profile.id, 0).single().pending)
        }

    @Test fun ackReturnsWithoutRequestingUnrelatedCacheEvenWhenThatEndpointWouldStall() =
        runBlocking<Unit> {
            val profile = prepare()
            // A non-pending cached article must never extend this one-shot outbox operation.
            cache.saveEntry(profile, entry.copy(id = 9001))
            mutations.setStatus(profile.id, 9000, "read")
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when {
                            request.path == "/v1/me" -> MockResponse().setBody("""{"id":42,"username":"test"}""")
                            request.method == "PUT" -> MockResponse().setResponseCode(204)
                            request.path == "/v1/entries/9000" -> remote("read")
                            else -> MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                        }
                }
            val result = withTimeout(2000) { sync().syncWithResult(profile, "test-token") }
            assertEquals(MinifluxReadSyncResult(1, 1, 0, 0), result)
            assertEquals(3, server.requestCount)
            assertTrue(
                cache
                    .database(profile.id)
                    .cacheDao()
                    .pending(profile.id)
                    .isEmpty(),
            )
        }

    @Test fun newerEntryAndGenerationStayForNextRunRatherThanBeingChased() =
        runBlocking<Unit> {
            val profile = prepare()
            cache.saveEntry(profile, entry.copy(id = 9001))
            mutations.setStatus(profile.id, 9000, "read")
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when {
                            request.path == "/v1/me" -> MockResponse().setBody("""{"id":42,"username":"test"}""")
                            request.method == "PUT" -> {
                                runBlocking {
                                    mutations.setStatus(profile.id, 9000, "unread")
                                    mutations.setStatus(profile.id, 9001, "read")
                                }
                                MockResponse().setResponseCode(204)
                            }
                            else -> remote("read")
                        }
                }
            val result = withTimeout(2000) { sync().syncWithResult(profile, "test-token") }
            assertEquals(MinifluxReadSyncResult(1, 0, 2, 0), result)
            assertEquals(3, server.requestCount)
            assertEquals(
                2L,
                cache
                    .database(profile.id)
                    .cacheDao()
                    .pending(profile.id, 9000)!!
                    .generation,
            )
            assertEquals(
                "unread",
                cache
                    .database(profile.id)
                    .cacheDao()
                    .pending(profile.id, 9000)!!
                    .desiredStatus,
            )
        }

    @Test fun alreadyDesiredRemoteAndConfirmationFailureBothFinishOneAttempt() =
        runBlocking<Unit> {
            val profile = prepare()
            mutations.setStatus(profile.id, 9000, "read")
            account()
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(503))
            val failed = withTimeout(2000) { sync().syncWithResult(profile, "test-token") }
            assertEquals(MinifluxReadSyncResult(1, 0, 1, 1), failed)
            // Remote already read, pending read survived: the next invocation confirms and returns.
            account()
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(remote("read"))
            assertEquals(MinifluxReadSyncResult(1, 1, 0, 0), withTimeout(2000) { sync().syncWithResult(profile, "test-token") })
            assertEquals(6, server.requestCount)
        }

    @Test fun entireManualRunHasDeadlineAndCancellationPreservesPending() =
        runBlocking<Unit> {
            val profile = prepare()
            mutations.setStatus(profile.id, 9000, "read")
            val slowApi = MinifluxApiClient(OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build())
            account()
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(2000) { MinifluxReadSync(slowApi, cache, mutations, 500).sync(profile, "test-token") }
            }
            assertNotNull(cache.database(profile.id).cacheDao().pending(profile.id, 9000))
            account()
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val worker = async(Dispatchers.IO) { MinifluxReadSync(slowApi, cache, mutations).sync(profile, "test-token") }
            withTimeout(2000) { while (server.requestCount < 5) delay(10) }
            worker.cancelAndJoin()
            assertTrue(worker.isCancelled)
            assertNotNull(cache.database(profile.id).cacheDao().pending(profile.id, 9000))
        }

    private suspend fun awaitIdle(controller: MinifluxCacheController) {
        withTimeout(5000) { while (controller.state.value.busy) delay(10) }
    }

    @Test fun controllerClearsBusyOnSuccessPartialAuthAndUnexpectedFailure() =
        runBlocking<Unit> {
            val profile = prepare()
            credentials.saveToken(profile.baseUrl, "test-token")
            mutations.setStatus(profile.id, 9000, "read")
            val controller = MinifluxCacheController(cache, credentials, MinifluxCacheSync(api, cache), controllerScope, sync(), mutations)
            account()
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(remote("read"))
            controller.syncCache(profile.baseUrl, readStates = true)
            awaitIdle(controller)
            assertTrue(controller.state.value.completed)
            assertEquals(MinifluxReadSyncResult(1, 1, 0, 0), controller.state.value.readResult)
            mutations.setStatus(profile.id, 9000, "unread")
            account()
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(remote("read"))
            controller.syncCache(profile.baseUrl, readStates = true)
            awaitIdle(controller)
            assertTrue(controller.state.value.completed)
            assertEquals(MinifluxReadSyncResult(1, 0, 1, 1), controller.state.value.readResult)
            server.enqueue(MockResponse().setResponseCode(401))
            controller.syncCache(profile.baseUrl, readStates = true)
            awaitIdle(controller)
            assertTrue(controller.state.value.failed)
            assertEquals("UNAUTHORIZED", controller.state.value.errorType)
            cache
                .database(profile.id)
                .openHelper.writableDatabase
                .execSQL("CREATE TRIGGER reject_attempt BEFORE UPDATE ON miniflux_read_outbox BEGIN SELECT RAISE(ABORT, 'test failure'); END")
            account()
            controller.syncCache(profile.baseUrl, readStates = true)
            awaitIdle(controller)
            assertTrue(controller.state.value.failed)
            assertEquals("LOCAL_ERROR", controller.state.value.errorType)
            assertNotNull(cache.database(profile.id).cacheDao().pending(profile.id, 9000))
        }

    @Test fun controllerDeadlineAndExternalCancellationClearBusyWithoutDroppingPending() =
        runBlocking<Unit> {
            val profile = prepare()
            credentials.saveToken(profile.baseUrl, "test-token")
            mutations.setStatus(profile.id, 9000, "read")
            val slowApi = MinifluxApiClient(OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build())
            val controller = MinifluxCacheController(cache, credentials, MinifluxCacheSync(slowApi, cache), controllerScope, MinifluxReadSync(slowApi, cache, mutations), mutations, 500)
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            controller.syncCache(profile.baseUrl, readStates = true)
            awaitIdle(controller)
            assertTrue(controller.state.value.failed)
            assertEquals("TIMEOUT", controller.state.value.errorType)
            assertNotNull(cache.database(profile.id).cacheDao().pending(profile.id, 9000))
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            controller.syncCache(profile.baseUrl, readStates = true)
            controller.cancel()
            awaitIdle(controller)
            assertEquals(false, controller.state.value.failed)
            assertNotNull(cache.database(profile.id).cacheDao().pending(profile.id, 9000))
        }
}
