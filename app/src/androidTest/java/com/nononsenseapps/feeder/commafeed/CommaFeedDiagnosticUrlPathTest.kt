package com.nononsenseapps.feeder.commafeed

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nononsenseapps.feeder.db.room.CommaFeedArticleMappingDao
import com.nononsenseapps.feeder.db.room.FeedDao
import com.nononsenseapps.feeder.db.room.FeedItemDao
import com.nononsenseapps.feeder.ui.TestDatabaseRule
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.instance
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(AndroidJUnit4::class)
class CommaFeedDiagnosticUrlPathTest {
    @get:Rule
    val testDb = TestDatabaseRule(ApplicationProvider.getApplicationContext())

    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun validInputReachesExpectedGetEndpoint() {
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"offset":0,"limit":500,"hasMore":false,"entries":[]}"""))
            val service = diagnosticService()

            service.run("http://10.0.2.2:8082", "user", "test-password")

            val request = server.takeRequest()
            val url = requireNotNull(request.requestUrl)
            assertEquals("GET", request.method)
            assertEquals("http", url.scheme)
            assertEquals("10.0.2.2", url.host)
            assertEquals(8082, url.port)
            assertEquals("/rest/category/entries", url.encodedPath)
            assertEquals("all", url.queryParameter("id"))
            assertEquals("all", url.queryParameter("readType"))
            assertEquals("desc", url.queryParameter("order"))
            assertEquals("0", url.queryParameter("offset"))
            assertEquals("500", url.queryParameter("limit"))
        }
    }

    @Test
    fun missingSchemeColonIsRejectedBeforeRequest() {
        runBlocking {
            val error =
                assertFailsWith<InvalidCommaFeedBaseUrl> {
                    diagnosticService().run("http//10.0.2.2:8082", "user", "test-password")
                }
            assertEquals("normalizedBaseUrl", error.stackTrace.first().methodName)
            assertEquals("CommaFeedRestClient.kt", error.stackTrace.first().fileName)
            assertEquals(0, server.requestCount)
        }
    }

    private fun diagnosticService(): CommaFeedDiagnosticService {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", server.port))
        val client = CommaFeedRestClient(OkHttpClient.Builder().proxy(proxy).build())
        val di =
            DI {
                bind<CommaFeedRestClient>() with instance(client)
                bind<FeedItemDao>() with instance(testDb.db.feedItemDao())
                bind<FeedDao>() with instance(testDb.db.feedDao())
                bind<CommaFeedArticleMappingDao>() with instance(testDb.db.commaFeedArticleMappingDao())
            }
        return CommaFeedDiagnosticService(di)
    }
}
