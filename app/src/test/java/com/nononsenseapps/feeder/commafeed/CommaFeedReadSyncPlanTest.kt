package com.nononsenseapps.feeder.commafeed

import org.junit.Test
import kotlin.test.assertEquals

class CommaFeedReadSyncPlanTest {
    private fun local(
        id: Long,
        read: Boolean,
        guid: String = "guid$id",
    ) = LocalArticle(id, 1, "https://feed.example/rss", "Feed", guid, null, "Article", read)

    private fun remote(
        id: String,
        read: Boolean,
        guid: String = "guid$id",
        markable: Boolean = true,
    ) = RemoteArticle(id, "1", "https://feed.example/rss", "Feed", guid, null, "Article", null, null, read, markable)

    @Test
    fun readWinsFourStateCases() {
        val diagnostic =
            CommaFeedArticleMatcher.compare(
                listOf(local(1, false), local(2, true), local(3, false), local(4, true)),
                listOf(remote("1", false), remote("2", false), remote("3", true), remote("4", true)),
            )
        val plan = planReadSync(diagnostic)
        assertEquals(listOf(3L), plan.localIds)
        assertEquals(listOf("2"), plan.remoteIds)
        assertEquals(2, plan.alreadySame)
    }

    @Test
    fun unmatchedAndAmbiguousArticlesAreNeverWritten() {
        val diagnostic =
            CommaFeedArticleMatcher.compare(
                listOf(local(1, true), local(2, true, "duplicate"), local(3, true, "duplicate")),
                listOf(remote("9", false), remote("2", false, "duplicate")),
            )
        val plan = planReadSync(diagnostic)
        assertEquals(emptyList(), plan.localIds)
        assertEquals(emptyList(), plan.remoteIds)
        assertEquals(2, diagnostic.ambiguousLocal)
        assertEquals(1, diagnostic.unmatchedLocal)
        assertEquals(1, diagnostic.unmatchedRemote)
    }

    @Test
    fun unmarkableRemoteIsCountedAsFailedWithoutSendingWrite() {
        val diagnostic = CommaFeedArticleMatcher.compare(listOf(local(1, true)), listOf(remote("1", false, markable = false)))
        val plan = planReadSync(diagnostic)
        assertEquals(emptyList(), plan.remoteIds)
        assertEquals(1, plan.unmarkableRemote)
    }
}
