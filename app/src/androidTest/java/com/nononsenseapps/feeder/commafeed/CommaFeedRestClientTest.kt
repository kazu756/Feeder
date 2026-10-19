package com.nononsenseapps.feeder.commafeed

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CommaFeedRestClientTest {
    private val server = MockWebServer()
    private val client = CommaFeedRestClient(OkHttpClient())

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getOnlyWithBasicAuthAndCorrectPagination() =
        runBlocking {
            server.enqueue(MockResponse().setBody(page(0, 500, true, "1")))
            server.enqueue(MockResponse().setBody(page(1, 500, false, "2")))
            val result = client.fetch(server.url("/").toString().removeSuffix("/"), "user", "pass")
            assertEquals(2, result.size)
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("GET", first.method)
            assertEquals("GET", second.method)
            assertEquals("/rest/category/entries", first.requestUrl?.encodedPath)
            assertEquals("all", first.requestUrl?.queryParameter("id"))
            assertEquals("all", first.requestUrl?.queryParameter("readType"))
            assertEquals("0", first.requestUrl?.queryParameter("offset"))
            assertEquals("500", first.requestUrl?.queryParameter("limit"))
            assertEquals("1", second.requestUrl?.queryParameter("offset"))
            assertEquals(Credentials.basic("user", "pass"), first.getHeader("Authorization"))
        }

    @Test
    fun rejectsAuthenticationFailure() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(401))
            assertFailsWith<CommaFeedFailure.Authentication> {
                client.fetch(server.url("/").toString(), "user", "pass")
            }
            assertEquals("GET", server.takeRequest().method)
        }

    @Test
    fun rejectsMalformedAndInconsistentPages() {
        runBlocking {
            server.enqueue(MockResponse().setBody("not json"))
            assertFailsWith<CommaFeedFailure.InvalidResponse> {
                client.fetch(server.url("/").toString(), "user", "pass")
            }
            server.enqueue(MockResponse().setBody(page(8, 500, false, "1")))
            assertFailsWith<CommaFeedFailure.Pagination> {
                client.fetch(server.url("/").toString(), "user", "pass")
            }
        }
    }

    @Test
    fun baseUrlIsNormalizedWithoutCredentials() {
        val base = server.url("/").toString()
        assertEquals(client.normalizedBaseUrl(base), client.normalizedBaseUrl(base.removeSuffix("/")))
        assertTrue(client.normalizedBaseUrl(base).toString().endsWith("/"))
        assertEquals("http://10.0.2.2:8082/", client.normalizedBaseUrl("http://10.0.2.2:8082").toString())
        assertEquals(client.normalizedBaseUrl("http://10.0.2.2:8082"), client.normalizedBaseUrl("http://10.0.2.2:8082/"))
        assertEquals("http://192.168.1.10:8082/", client.normalizedBaseUrl("http://192.168.1.10:8082").toString())
        assertEquals(client.normalizedBaseUrl("http://192.168.1.10:8082"), client.normalizedBaseUrl("http://192.168.1.10:8082/"))
        assertEquals("https://example.com/", client.normalizedBaseUrl("https://example.com").toString())
        assertEquals(client.normalizedBaseUrl("https://example.com"), client.normalizedBaseUrl("https://example.com/"))
        assertEquals("https://commafeed.example.com/", client.normalizedBaseUrl("https://commafeed.example.com").toString())
        assertEquals(client.normalizedBaseUrl("https://commafeed.example.com"), client.normalizedBaseUrl("https://commafeed.example.com/"))
        listOf(
            "10.0.2.2:8082",
            "ftp://10.0.2.2:8082",
            "http://",
            "http://user:pass@10.0.2.2:8082",
            "http://10.0.2.2:8082/rest/test",
            "http://10.0.2.2:8082/?a=1",
            "http://10.0.2.2:8082/#fragment",
        ).forEach { assertFailsWith<InvalidCommaFeedBaseUrl> { client.normalizedBaseUrl(it) } }
    }

    private fun page(
        offset: Int,
        limit: Int,
        hasMore: Boolean,
        id: String,
    ) =
        """{"offset":$offset,"limit":$limit,"hasMore":$hasMore,"entries":[{"id":"$id","guid":"guid$id","feedId":"feed","feedUrl":"https://feed.example/rss","feedName":"Feed","url":"https://feed.example/$id","title":"Article","read":false,"markable":true}]}"""
}
