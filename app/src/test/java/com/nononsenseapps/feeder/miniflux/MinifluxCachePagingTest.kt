package com.nononsenseapps.feeder.miniflux

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MinifluxCachePagingTest {
    private val now = Instant.parse("2026-10-23T00:00:00Z")

    private fun entry(id: Long) = MinifluxEntry(id, 1, "Title", "unread", false, now.toString(), null, "https://example.com", content = "Body")

    @Test fun pagesInHundredsAndSupportsRetryWithoutCursorDrift() =
        runBlocking<Unit> {
            val ids = mutableListOf<Long>()
            val calls = mutableListOf<Long?>()
            collectCachePages(MinifluxCacheScope.UNREAD, now, fetch = { before, limit ->
                assertEquals(100, limit)
                calls += before
                val items = (250L downTo 1L).filter { before == null || it < before }.take(limit).map(::entry)
                MinifluxEntries(250, items)
            }, consume = { ids += it.id }, progress = {})
            assertEquals((250L downTo 1L).toList(), ids)
            assertEquals(listOf(null, 151L, 51L, 1L), calls)
        }

    @Test fun ignoredCursorAndWrongFiltersFailInsteadOfClaimingCompletion() =
        runBlocking<Unit> {
            assertFailsWith<IllegalArgumentException> {
                collectCachePages(MinifluxCacheScope.UNREAD, now, { _, _ -> MinifluxEntries(1, listOf(entry(1))) }, {}, {})
            }
            assertFailsWith<IllegalArgumentException> {
                collectCachePages(MinifluxCacheScope.STARRED, now, { _, _ -> MinifluxEntries(1, listOf(entry(1))) }, {}, {})
            }
        }

    @Test fun recentReadIsLimitedAndCancellationPropagates() =
        runBlocking<Unit> {
            var consumed = 0
            collectCachePages(MinifluxCacheScope.RECENT_READ, now, { before, limit ->
                MinifluxEntries(5000, ((before ?: 5001) - 1 downTo 1).take(limit).map { entry(it).copy(status = "read") })
            }, { consumed++ }, {})
            assertEquals(1000, consumed)
            assertFailsWith<CancellationException> {
                collectCachePages(MinifluxCacheScope.UNREAD, now, { _, _ -> throw CancellationException() }, {}, {})
            }
        }

    @Test fun deltaPagesKeepEqualTimestampsAcrossIdCursors() =
        runBlocking<Unit> {
            val ids = mutableListOf<Long>()
            collectChangedPages(now.minusSeconds(2).epochSecond, now.plusSeconds(1).epochSecond, fetch = { cursor ->
                MinifluxEntries(250, (250L downTo 1L).filter { cursor == null || it < cursor }.take(100).map { entry(it).copy(changedAt = now.toString()) })
            }, consume = { ids += it.id })
            assertEquals((250L downTo 1L).toList(), ids)
        }

    @Test fun deltaRejectsIgnoredBoundsMissingTimestampsAndIgnoredCursor() =
        runBlocking<Unit> {
            val after = now.minusSeconds(2).epochSecond
            val before = now.plusSeconds(1).epochSecond
            for (value in listOf(now.minusSeconds(2).toString(), now.plusSeconds(1).toString())) {
                assertFailsWith<IllegalArgumentException> {
                    collectChangedPages(after, before, { MinifluxEntries(1, listOf(entry(1).copy(changedAt = value))) }, {})
                }
            }
            assertFailsWith<IllegalStateException> {
                collectChangedPages(after, before, { MinifluxEntries(1, listOf(entry(1))) }, {})
            }
            assertFailsWith<IllegalArgumentException> {
                collectChangedPages(after, before, { MinifluxEntries(1, listOf(entry(1).copy(changedAt = now.toString()))) }, {})
            }
            assertFailsWith<CancellationException> {
                collectChangedPages(after, before, { throw CancellationException() }, {})
            }
        }
}
