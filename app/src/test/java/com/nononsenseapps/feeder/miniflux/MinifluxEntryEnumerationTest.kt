package com.nononsenseapps.feeder.miniflux

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MinifluxEntryEnumerationTest {
    private val server = MockWebServer().apply { start() }
    private val api = MinifluxApiClient()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var total = 0
    private var failure = ""
    private var chunk = 10000

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val url = request.requestUrl!!
                    val offset = url.queryParameter("offset")!!.toInt()
                    if (failure == "server" && offset > 0) return MockResponse().setResponseCode(503)
                    val ids =
                        (total downTo 1)
                            .map { it.toLong() }
                            .drop(offset)
                            .take(chunk)
                            .toMutableList()
                    if (offset > 0 && failure == "duplicate" && ids.isNotEmpty()) ids[0] = (total - offset + 1).toLong()
                    if (offset > 0 && failure == "empty") ids.clear()
                    val count = total.toLong() + if (offset > 0 && failure == "total") 1 else 0
                    return MockResponse().setBody(Json.encodeToString(MinifluxEntryIds(count, ids)))
                }
            }
    }

    @After fun close() = server.shutdown()

    @Test fun readAndUnreadUseTotalAndTenThousandLimitWithoutEmptyTailRequest() =
        runBlocking<Unit> {
            for (status in listOf("read", "unread")) {
                for (count in listOf(0, 6104, 10000, 10001, 23400)) {
                    total = count
                    requests.clear()
                    val ids = enumerateMinifluxIdSet(api, server.url("/"), "fixture-token", status)
                    assertEquals((1L..count.toLong()).toSet(), ids)
                    val pages = maxOf(1, (count + 9999) / 10000)
                    assertEquals(pages, requests.size)
                    assertEquals((0 until pages).map { (it * 10000).toString() }, requests.map { it.requestUrl!!.queryParameter("offset") })
                    assertTrue(
                        requests.all {
                            it.method == "GET" &&
                                it.requestUrl!!.encodedPath == "/v1/entries/ids" &&
                                it.requestUrl!!.queryParameter("limit") == "10000" &&
                                it.requestUrl!!.queryParameter("status") == status
                        },
                    )
                }
            }
        }

    @Test fun shortNonemptyPagesContinueUntilTotal() =
        runBlocking<Unit> {
            total = 23400
            chunk = 7000
            val ids = enumerateMinifluxIdSet(api, server.url("/"), "fixture-token", "read")
            assertEquals(total, ids.size)
            assertEquals(listOf("0", "7000", "14000", "21000"), requests.map { it.requestUrl!!.queryParameter("offset") })
        }

    @Test fun middlePageFailureNeverReturnsPartialSet() =
        runBlocking<Unit> {
            total = 10001
            for (type in listOf("server", "duplicate", "empty", "total")) {
                failure = type
                assertFailsWith<Exception> { enumerateMinifluxIdSet(api, server.url("/"), "fixture-token", "unread") }
            }
        }

    @Test fun crossStatusDuplicatesAreRejected() =
        runBlocking<Unit> {
            total = 2
            assertFailsWith<IllegalArgumentException> { enumerateMinifluxIds(api, server.url("/"), "fixture-token") }
        }
}
