package com.nononsenseapps.feeder.archmodel

import kotlin.test.Test
import kotlin.test.assertEquals

class FeedItemStoreOrderTest {
    @Test
    fun databaseOrderIsRestoredToHistoryOrder() {
        val items = listOf(TestItem(3L), TestItem(1L), TestItem(5L), TestItem(2L), TestItem(4L))

        assertEquals(
            listOf(5L, 1L, 4L, 2L, 3L),
            orderByArticleIds(listOf(5L, 1L, 4L, 2L, 3L), items, TestItem::id).map(TestItem::id),
        )
    }

    @Test
    fun deletedArticlesAreIgnored() {
        val items = listOf(TestItem(1L), TestItem(3L), TestItem(4L))

        assertEquals(
            listOf(1L, 3L, 4L),
            orderByArticleIds(listOf(1L, 2L, 3L, 4L, 5L), items, TestItem::id).map(TestItem::id),
        )
    }

    @Test
    fun emptyItemsRemainEmpty() {
        assertEquals(emptyList(), orderByArticleIds(listOf(1L), emptyList<TestItem>(), TestItem::id))
    }

    @Test
    fun oneItemIsReturned() {
        assertEquals(
            listOf(TestItem(1L)),
            orderByArticleIds(listOf(1L), listOf(TestItem(1L)), TestItem::id),
        )
    }

    @Test
    fun fiveItemsAreReturnedInHistoryOrder() {
        val history = listOf(5L, 4L, 3L, 2L, 1L)
        val items = history.reversed().map(::TestItem)

        assertEquals(history, orderByArticleIds(history, items, TestItem::id).map(TestItem::id))
    }

    private data class TestItem(
        val id: Long,
    )
}
