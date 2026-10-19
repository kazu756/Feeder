package com.nononsenseapps.feeder.commafeed

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommaFeedArticleMatcherTest {
    private fun local(
        id: Long = 1,
        feed: String = "https://example.com/feed",
        guid: String = "guid",
        url: String = "https://example.com/article",
        read: Boolean = false,
    ) = LocalArticle(id, 1, feed, "Feed", guid, url, "Article", read)

    private fun remote(
        id: String = "1",
        feed: String = "https://example.com/feed",
        guid: String = "guid",
        url: String = "https://example.com/article",
        read: Boolean = false,
    ) = RemoteArticle(id, "1", feed, "Feed", guid, url, "Article", null, null, read, true)

    @Test
    fun exactGuidMatchesAndObservesReadMismatchWithoutMutation() {
        val local = local(read = false)
        val remote = remote(read = true)
        val result = CommaFeedArticleMatcher.compare(listOf(local), listOf(remote))
        assertEquals(1, result.matchedGuid)
        assertEquals(1, result.readDifferent)
        assertEquals(0, result.readSame)
        assertFalse(local.read)
        assertTrue(remote.read)
        assertEquals(100.0, result.localMatchRate)
        assertEquals(100.0, result.remoteMatchRate)
    }

    @Test
    fun uniqueArticleUrlIsFallback() {
        val result = CommaFeedArticleMatcher.compare(listOf(local(guid = "local")), listOf(remote(guid = "remote")))
        assertEquals(1, result.matchedUrl)
        assertEquals(0, result.matchedGuid)
        assertEquals(1, result.readSame)
    }

    @Test
    fun feedUrlMustMatchExactly() {
        for (feed in listOf("http://example.com/feed", "https://example.com/feed/", "https://other.com/feed")) {
            val result = CommaFeedArticleMatcher.compare(listOf(local()), listOf(remote(feed = feed)))
            assertEquals(0, result.matchedTotal)
            assertEquals(1, result.unmatchedLocal)
            assertEquals(1, result.unmatchedRemote)
        }
    }

    @Test
    fun duplicateLocalGuidIsAmbiguous() {
        val result =
            CommaFeedArticleMatcher.compare(
                listOf(local(1, url = "a"), local(2, url = "b")),
                listOf(remote(url = "c")),
            )
        assertEquals(0, result.matchedTotal)
        assertEquals(2, result.ambiguousLocal)
    }

    @Test
    fun duplicateRemoteGuidIsAmbiguous() {
        val result =
            CommaFeedArticleMatcher.compare(
                listOf(local(url = "a")),
                listOf(remote("1", url = "b"), remote("2", url = "c")),
            )
        assertEquals(0, result.matchedTotal)
        assertEquals(1, result.ambiguousLocal)
        assertEquals(2, result.ambiguousRemote)
    }

    @Test
    fun duplicateArticleUrlAndRemoteIdAreAmbiguous() {
        val byUrl =
            CommaFeedArticleMatcher.compare(
                listOf(local(guid = "a")),
                listOf(remote("1", guid = "b"), remote("2", guid = "c")),
            )
        assertEquals(0, byUrl.matchedTotal)
        assertEquals(1, byUrl.ambiguousLocal)
        val byId =
            CommaFeedArticleMatcher.compare(
                listOf(local()),
                listOf(remote("1"), remote("1")),
            )
        assertEquals(0, byId.matchedTotal)
        assertEquals(1, byId.ambiguousLocal)
    }

    @Test
    fun competingGuidAndUrlEvidenceCannotMapOneArticleTwice() {
        val result =
            CommaFeedArticleMatcher.compare(
                listOf(local()),
                listOf(remote("1", url = "other"), remote("2", guid = "other")),
            )
        assertEquals(0, result.matchedTotal)
        assertEquals(1, result.ambiguousLocal)
    }

    @Test
    fun oneRemoteCannotMapTwoLocals() {
        val result =
            CommaFeedArticleMatcher.compare(
                listOf(local(1, guid = "a"), local(2, guid = "b")),
                listOf(remote(guid = "a")),
            )
        assertEquals(0, result.matchedTotal)
        assertEquals(2, result.ambiguousLocal)
    }

    @Test
    fun unmatchedAndRatesAreCounted() {
        val result =
            CommaFeedArticleMatcher.compare(
                listOf(local(), local(2, guid = "different", url = "different")),
                listOf(remote(), remote("2", guid = "remote", url = "remote")),
            )
        assertEquals(1, result.matchedTotal)
        assertEquals(1, result.unmatchedLocal)
        assertEquals(1, result.unmatchedRemote)
        assertEquals(50.0, result.localMatchRate)
        assertEquals(50.0, result.remoteMatchRate)
    }
}
