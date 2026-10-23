package com.nononsenseapps.feeder.miniflux

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MinifluxApiClientTest {
    private val server = MockWebServer()
    private val token = "secret-miniflux-test-token"
    private val api = MinifluxApiClient()

    @Before fun start() {
        server.start()
    }

    @After fun stop() {
        server.shutdown()
    }

    @Test fun normalizesOnlyServerRoots() {
        listOf("http://192.168.1.10:8080", "https://rss.example.com", "http://[::1]:8080").forEach {
            assertEquals("$it/", normalizedMinifluxUrl(it).toString())
            assertEquals(normalizedMinifluxUrl(it), normalizedMinifluxUrl("$it/"))
        }
    }

    @Test fun rejectsInvalidOrAmbiguousUrlsWithoutEchoingInput() {
        listOf(
            "192.168.1.10:8080",
            "ftp://example.com",
            "https://user:pass@example.com",
            "https://example.com/v1/me",
            "https://example.com/#abc",
            "https://example.com/#",
            "https://example.com/?",
            "https://example.com/?token=$token",
            "https://example.com/%2f",
            "https://example.com/../",
            "http:///example.com",
            "https://example.com:99999",
            "https://example.com ",
            "https://@example.com",
        ).forEach {
            val error = assertFailsWith<MinifluxFailure> { normalizedMinifluxUrl(it) }
            assertEquals(MinifluxError.INVALID_URL, error.error)
            assertFalse(error.toString().contains(token))
            assertNull(error.cause)
        }
        assertEquals(MinifluxError.MISSING_URL, assertFailsWith<MinifluxFailure> { normalizedMinifluxUrl("") }.error)
    }

    private fun fixtures(
        failedPath: String? = null,
        echo: Boolean = false,
    ) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl?.encodedPath == failedPath) return MockResponse().setResponseCode(503).setBody(token)
                    val body =
                        when (request.requestUrl?.encodedPath) {
                            "/v1/me" -> """{"id":1,"username":"${if (echo) token else "reader"}"}"""
                            "/v1/feeds" -> """[{"id":2,"title":"Feed","feed_url":"https://example.com/rss","password":"ignored"}]"""
                            "/v1/categories" -> """[{"id":3,"title":"News"}]"""
                            "/v1/entries" ->
                                """
{"total":12,
"entries":[{"id":4,
"feed_id":2,
"title":"Article",
"status":"unread",
"starred":true,
"published_at":"2026-10-23T00:00:00Z",
"changed_at":"2026-10-23T00:00:01Z",
"url":"https://example.com/article",
"content":"ignored"}]}
"""
                            "/v1/version" -> """{"version":"2.3.2","commit":"ignored"}"""
                            else -> return MockResponse().setResponseCode(404)
                        }
                    return MockResponse().setBody(body)
                }
            }
    }

    @Test fun diagnosticUsesOnlyFiveGetRequestsAndParsesRemoteData() =
        runBlocking<Unit> {
            fixtures()
            val result = MinifluxDiagnosticService(api).run(server.url("/").toString(), token)
            assertEquals("reader", assertIs<MinifluxStep.Success<MinifluxAccount>>(result.account).value.username)
            assertEquals(MinifluxStep.Success(1), result.feeds)
            assertEquals(MinifluxStep.Success(1), result.categories)
            assertEquals(MinifluxStep.Success(1), result.entries)
            assertEquals(MinifluxStep.Success("2.3.2"), result.version)
            assertEquals(MinifluxSupport.VERSION_EXPECTED, result.capabilities.starredState)
            val requests = (1..5).map { server.takeRequest(2, TimeUnit.SECONDS)!! }
            assertEquals(listOf("/v1/me", "/v1/feeds", "/v1/categories", "/v1/entries", "/v1/version"), requests.map { it.requestUrl!!.encodedPath })
            requests.forEach {
                assertEquals("GET", it.method)
                assertEquals(token, it.getHeader("X-Auth-Token"))
                assertEquals(0L, it.bodySize)
                assertFalse(it.path!!.contains(token))
                assertNull(it.getHeader("Authorization"))
            }
            assertEquals("10", requests[3].requestUrl!!.queryParameter("limit"))
            assertEquals(5, server.requestCount)
        }

    @Test fun rejectsCrossOriginAndSameOriginRedirectsWithoutForwardingToken() =
        runBlocking<Unit> {
            MockWebServer().use { destination ->
                destination.start()
                for (location in listOf(destination.url("/v1/me"), server.url("/redirected"))) {
                    server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", location))
                    val error = assertFailsWith<MinifluxFailure> { api.me(server.url("/"), token) }
                    assertEquals(MinifluxError.REDIRECT, error.error)
                    server.takeRequest()
                }
                assertNull(destination.takeRequest(200, TimeUnit.MILLISECONDS))
                assertEquals(2, server.requestCount)
            }
        }

    @Test fun separatesHttpErrorsAndDiscardsSensitiveResponseBody() =
        runBlocking<Unit> {
            for ((code, expected) in listOf(
                401 to MinifluxError.UNAUTHORIZED,
                403 to MinifluxError.FORBIDDEN,
                404 to MinifluxError.NOT_FOUND,
                500 to MinifluxError.SERVER,
                429 to MinifluxError.HTTP,
            )) {
                server.enqueue(MockResponse().setResponseCode(code).setBody(token))
                val error = assertFailsWith<MinifluxFailure> { api.me(server.url("/"), token) }
                assertEquals(expected, error.error)
                assertFalse(error.stackTraceToString().contains(token))
                assertNull(error.cause)
            }
        }

    @Test fun malformedJsonIsSanitized() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setBody("{invalid:$token"))
            val error = assertFailsWith<MinifluxFailure> { api.me(server.url("/"), token) }
            assertEquals(MinifluxError.INVALID_RESPONSE, error.error)
            assertFalse(error.stackTraceToString().contains(token))
            assertNull(error.cause)
        }

    @Test fun partialFailuresPreserveAuthenticationAndOtherSteps() =
        runBlocking<Unit> {
            for (path in listOf("/v1/feeds", "/v1/categories", "/v1/entries", "/v1/version")) {
                fixtures(failedPath = path)
                val result = MinifluxDiagnosticService(api).run(server.url("/").toString(), token)
                assertIs<MinifluxStep.Success<MinifluxAccount>>(result.account)
                val steps = listOf(result.feeds, result.categories, result.entries, result.version)
                assertEquals(1, steps.count { it is MinifluxStep.Failed })
                assertEquals(3, steps.count { it is MinifluxStep.Success })
            }
        }

    @Test fun authenticationFailureStopsRemainingRequests() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setResponseCode(401))
            val result = MinifluxDiagnosticService(api).run(server.url("/").toString(), token)
            assertEquals(MinifluxStep.Failed(MinifluxError.UNAUTHORIZED), result.account)
            assertEquals(MinifluxStep.NotChecked, result.feeds)
            assertEquals(1, server.requestCount)
        }

    @Test fun timeoutIsDistinctAndCancellationPropagates() =
        runBlocking<Unit> {
            val quickApi = MinifluxApiClient(OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build())
            server.enqueue(MockResponse().setBody("{}").setHeadersDelay(500, TimeUnit.MILLISECONDS))
            val failure = assertFailsWith<MinifluxFailure> { quickApi.me(server.url("/"), token) }
            assertEquals(MinifluxError.TIMEOUT, failure.error)
            server.enqueue(MockResponse().setBody("{}").setHeadersDelay(500, TimeUnit.MILLISECONDS))
            val job = async { MinifluxDiagnosticService(api).run(server.url("/").toString(), token) }
            delay(50)
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
        }

    @Test fun responseEchoCannotRevealTokenInDiagnosticState() =
        runBlocking<Unit> {
            fixtures(echo = true)
            val result = MinifluxDiagnosticService(api).run(server.url("/").toString(), token)
            assertFalse(result.toString().contains(token))
        }

    @Test fun invalidTokenNeverReachesNetwork() =
        runBlocking<Unit> {
            for (value in listOf("", "a\nsecret", "a b", "日本語")) {
                assertFailsWith<MinifluxFailure> { api.me(server.url("/"), value) }
            }
            assertEquals(0, server.requestCount)
        }

    @Test fun unknownAndOldVersionsAreNotReportedAsVerified() {
        for (version in listOf(null, "development", "2.3.2-dev", "3.0.0", "2.3.999999999999999")) {
            assertEquals(MinifluxSupport.UNKNOWN, minifluxCapabilities(version).changedAfter)
        }
        assertEquals(MinifluxSupport.VERSION_TOO_OLD, minifluxCapabilities("2.0.48").changedAfter)
        assertEquals(MinifluxSupport.VERSION_TOO_OLD, minifluxCapabilities("2.3.1").starredState)
        assertEquals(MinifluxSupport.VERSION_EXPECTED, minifluxCapabilities("2.0.49").changedAfter)
        assertTrue(minifluxCapabilities("2.0.35").originalContent == MinifluxSupport.VERSION_TOO_OLD)
    }

    @Test fun transportFailuresAreSanitized() =
        runBlocking<Unit> {
            for ((transportError, expected) in listOf(
                javax.net.ssl.SSLHandshakeException(token) to MinifluxError.TLS,
                java.net.UnknownHostException(token) to MinifluxError.DNS,
                java.net.ConnectException(token) to MinifluxError.CONNECTION,
            )) {
                val call = io.mockk.mockk<okhttp3.Call>(relaxed = true)
                val client = io.mockk.mockk<OkHttpClient>()
                io.mockk.every { client.newCall(any()) } returns call
                io.mockk.every { call.enqueue(any()) } answers {
                    firstArg<okhttp3.Callback>().onFailure(call, transportError)
                }
                val failure = assertFailsWith<MinifluxFailure> { MinifluxApiClient(client).me(server.url("/"), token) }
                assertEquals(expected, failure.error)
                assertNull(failure.cause)
                assertFalse(failure.stackTraceToString().contains(token))
            }
        }
}
