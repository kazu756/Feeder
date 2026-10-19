package com.nononsenseapps.feeder.commafeed

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nononsenseapps.feeder.archmodel.FeedItemStore
import com.nononsenseapps.feeder.db.room.Feed
import com.nononsenseapps.feeder.db.room.FeedItem
import com.nononsenseapps.feeder.db.room.FeedItemDao
import com.nononsenseapps.feeder.ui.TestDatabaseRule
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.singleton
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CommaFeedReadSyncServiceTest {
    @get:Rule
    val testDb = TestDatabaseRule(ApplicationProvider.getApplicationContext())

    private val server = MockWebServer()
    private var feedId = 0L
    private var sharedInterceptorCalled = false

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun validBaseUrlProducesReadOnlyDirectionPostAndVerifiesIt() {
        runBlocking {
            val localId = insertLocal(read = true)
            server.enqueue(MockResponse().setBody(page(read = false)))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse().setBody(page(read = true)))

            val result = service().run("http://10.0.2.2:8082", "user", "test-password")
            val dao = testDb.db.feedItemDao()

            assertEquals(1, result.remoteMarkedRead)
            assertEquals(0, result.remoteFailedWrites)
            assertEquals(0, result.localMarkedRead)
            assertNotNull(dao.loadFeedItem(localId)?.readTime)
            val fetch = server.takeRequest()
            val mark = server.takeRequest()
            val verify = server.takeRequest()
            assertEquals("GET", fetch.method)
            assertEquals("/rest/category/entries", fetch.requestUrl?.encodedPath)
            assertEquals("POST", mark.method)
            assertEquals("http", mark.requestUrl?.scheme)
            assertEquals("10.0.2.2", mark.requestUrl?.host)
            assertEquals(8082, mark.requestUrl?.port)
            assertEquals("/rest/entry/markMultiple", mark.requestUrl?.encodedPath)
            assertTrue(mark.getHeader("Content-Type")?.startsWith("application/json") == true)
            val requests = JSONObject(mark.body.readUtf8()).getJSONArray("requests")
            assertEquals(1, requests.length())
            assertEquals("remote-1", requests.getJSONObject(0).getString("id"))
            assertTrue(requests.getJSONObject(0).getBoolean("read"))
            assertFalse(requests.toString().contains("\"read\":false"))
            assertEquals("GET", verify.method)
            assertFalse(result.toString().contains("test-password"))
            assertFalse(result.toString().contains("Authorization"))
            assertFalse(sharedInterceptorCalled)
        }
    }

    @Test
    fun writeAuthenticationFailureIsReportedWithoutChangingReadWins() {
        runBlocking {
            val localId = insertLocal(read = true)
            server.enqueue(MockResponse().setBody(page(read = false)))
            server.enqueue(MockResponse().setResponseCode(403))
            val result = service().run("http://10.0.2.2:8082", "user", "test-password")
            assertTrue(result.authenticationFailed)
            assertEquals(1, result.remoteFailedWrites)
            assertNotNull(
                testDb.db
                    .feedItemDao()
                    .loadFeedItem(localId)
                    ?.readTime,
            )
        }
    }

    @Test
    fun remoteReadMarksOnlyLocalAndNeverPosts() {
        runBlocking {
            val localId = insertLocal(read = false)
            server.enqueue(MockResponse().setBody(page(read = true)))

            val result = service().run("http://10.0.2.2:8082", "user", "test-password")
            val dao = testDb.db.feedItemDao()

            assertEquals(1, result.localMarkedRead)
            assertEquals(0, result.remoteMarkedRead)
            assertNotNull(dao.loadFeedItem(localId)?.readTime)
            assertEquals(1, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
        }
    }

    @Test
    fun failedRemoteWriteDoesNotUndoLocalRead() {
        runBlocking {
            val localToRemote = insertLocal(read = true, guid = "one")
            val remoteToLocal = insertLocal(read = false, guid = "two")
            server.enqueue(MockResponse().setBody(page(read = false, secondRead = true)))
            server.enqueue(MockResponse().setResponseCode(500))

            val result = service().run("http://10.0.2.2:8082", "user", "test-password")
            val dao = testDb.db.feedItemDao()

            assertEquals(1, result.remoteFailedWrites)
            assertEquals(0, result.remoteMarkedRead)
            assertEquals(1, result.localMarkedRead)
            assertNotNull(dao.loadFeedItem(localToRemote)?.readTime)
            assertNotNull(dao.loadFeedItem(remoteToLocal)?.readTime)
            assertEquals(2, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
            assertEquals("POST", server.takeRequest().method)
        }
    }

    private suspend fun insertLocal(
        read: Boolean,
        guid: String = "one",
    ): Long {
        val dao = testDb.db.feedItemDao()
        if (feedId == 0L) feedId = testDb.db.feedDao().insertFeed(Feed(title = "Feed", url = URL("https://feed.example/rss")))
        val id = dao.insertFeedItem(FeedItem(guid = guid, plainTitle = "Article", feedId = feedId))
        if (read) dao.markAsReadAndNotified(id)
        return id
    }

    private fun service(): CommaFeedReadSyncService {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", server.port))
        val httpClient =
            OkHttpClient
                .Builder()
                .proxy(proxy)
                .addInterceptor {
                    sharedInterceptorCalled = true
                    it.proceed(it.request())
                }.addNetworkInterceptor {
                    sharedInterceptorCalled = true
                    it.proceed(it.request())
                }.build()
        val client = CommaFeedRestClient(httpClient)
        val di =
            DI {
                bind<CommaFeedRestClient>() with instance(client)
                bind<FeedItemDao>() with instance(testDb.db.feedItemDao())
                bind<FeedItemStore>() with singleton { FeedItemStore(di) }
            }
        return CommaFeedReadSyncService(di)
    }

    private fun page(
        read: Boolean,
        secondRead: Boolean? = null,
    ): String {
        val first = """{"id":"remote-1","feedId":"feed","feedUrl":"https://feed.example/rss","guid":"one","read":$read,"markable":true}"""
        val second =
            secondRead?.let {
                """,{"id":"remote-2","feedId":"feed","feedUrl":"https://feed.example/rss","guid":"two","read":$it,"markable":true}"""
            } ?: ""
        return """{"offset":0,"limit":500,"hasMore":false,"entries":[$first$second]}"""
    }
}
