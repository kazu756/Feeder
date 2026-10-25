package com.nononsenseapps.feeder.ui.compose.feedarticle

import com.nononsenseapps.feeder.archmodel.Article
import com.nononsenseapps.feeder.archmodel.ArticleKey
import com.nononsenseapps.feeder.db.room.FeedItemWithFeed
import com.nononsenseapps.feeder.model.translationCacheIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ReaderArticleLoaderTest {
    private val article = Article(FeedItemWithFeed(id = 123, plainTitle = "Title", link = "https://example.com/a"))
    private val mini = ArticleKey(123, "a".repeat(64))

    @Test fun sameNumericIdUsesOnlyItsPinnedSourceAndSharedPlainTextConversion() =
        runBlocking<Unit> {
            val loader = ReaderArticleLoader({ "<h1>Miniflux</h1><p>body <a href='/'>link</a></p>" }) { _, _, _, _ -> "<p>RSS body</p>" }
            assertEquals("RSS body", loader.load(ArticleKey(123), article, false)!!.plainText)
            assertEquals("Miniflux body link", loader.load(mini, article, false)!!.plainText)
            assertEquals("Title", loader.load(mini, article, false)!!.article.title)
        }

    @Test fun missingMinifluxBodyNeverTouchesLocalEvenForSummaryAndFullText() =
        runBlocking<Unit> {
            val loader = ReaderArticleLoader({ null }) { _, _, _, _ -> error("Local fallback") }
            assertNull(loader.load(mini, article, false))
            assertNull(loader.load(mini, article, true, summary = true))
            assertNull(loader.load(mini, article, false, cachedOnly = true))
        }

    @Test fun profileIsPassedUnchangedForEveryRead() =
        runBlocking<Unit> {
            val loader = ReaderArticleLoader({ "<p>${it.profileId}</p>" }) { _, _, _, _ -> error("Local") }
            val other = ArticleKey(123, "b".repeat(64))
            assertEquals(mini.profileId, loader.load(mini, article, false)!!.plainText)
            assertEquals(other.profileId, loader.load(other, article, false)!!.plainText)
        }

    @Test fun absentOrMismatchedRowCannotStartAFeature() =
        runBlocking<Unit> {
            val loader = ReaderArticleLoader({ error("Unexpected load") }) { _, _, _, _ -> error("Unexpected load") }
            assertNull(loader.load(mini, Article(null), false))
            assertNull(loader.load(ArticleKey(124, mini.profileId), article, false))
        }

    @Test fun loaderPropagatesCancellation() =
        runBlocking<Unit> {
            val loader = ReaderArticleLoader({ throw CancellationException() }) { _, _, _, _ -> error("Local") }
            assertFailsWith<CancellationException> { loader.load(mini, article, false) }
        }

    @Test fun cacheNamespacesPreserveRssAndSeparateBackendAndProfiles() {
        assertEquals("123", translationCacheIdentity(ArticleKey(123)))
        assertNotEquals(translationCacheIdentity(ArticleKey(123)), translationCacheIdentity(mini))
        assertNotEquals(translationCacheIdentity(mini), translationCacheIdentity(ArticleKey(123, "b".repeat(64))))
    }
}
