package com.nononsenseapps.feeder.archmodel

import android.content.SharedPreferences
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.bind
import org.kodein.di.instance
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentlyViewedStoreTest : DIAware {
    @MockK(relaxed = true)
    private lateinit var sharedPreferences: SharedPreferences

    override val di =
        DI.lazy {
            bind<SharedPreferences>() with instance(sharedPreferences)
        }

    @BeforeTest
    fun setup() {
        MockKAnnotations.init(this, relaxed = true)
        every { sharedPreferences.getString(PREF_RECENTLY_VIEWED_ARTICLE_IDS, null) } returns null
    }

    @Test
    fun emptyPlusArticle() {
        assertEquals(listOf(1L), updateRecentlyViewedArticleIds(emptyList(), 1L))
    }

    @Test
    fun newerArticleMovesToFront() {
        assertEquals(listOf(2L, 1L), updateRecentlyViewedArticleIds(listOf(1L), 2L))
    }

    @Test
    fun sixthArticleDropsOldest() {
        assertEquals(
            listOf(6L, 1L, 2L, 3L, 4L),
            updateRecentlyViewedArticleIds(listOf(1L, 2L, 3L, 4L, 5L), 6L),
        )
    }

    @Test
    fun reopenedArticleMovesToFront() {
        assertEquals(
            listOf(3L, 1L, 2L, 4L, 5L),
            updateRecentlyViewedArticleIds(listOf(1L, 2L, 3L, 4L, 5L), 3L),
        )
    }

    @Test
    fun consecutiveDuplicateIsStoredOnce() {
        assertEquals(listOf(1L), updateRecentlyViewedArticleIds(listOf(1L), 1L))
    }

    @Test
    fun resultNeverExceedsFiveArticles() {
        assertEquals(
            MAX_RECENTLY_VIEWED_ARTICLES,
            updateRecentlyViewedArticleIds((1L..20L).toList(), 21L).size,
        )
    }

    @Test
    fun encodeAndDecodeRoundTrip() {
        val ids = listOf(5L, 4L, 3L, 2L, 1L)
        assertEquals(ids, decodeRecentlyViewedArticleIds(encodeRecentlyViewedArticleIds(ids)))
    }

    @Test
    fun emptyValueDecodesToEmptyList() {
        assertEquals(emptyList(), decodeRecentlyViewedArticleIds(null))
        assertEquals(emptyList(), decodeRecentlyViewedArticleIds(""))
    }

    @Test
    fun malformedAndInvalidIdsAreIgnored() {
        assertEquals(
            listOf(5L, 4L, 3L),
            decodeRecentlyViewedArticleIds("5,broken,-1,0,4,,3,5"),
        )
    }

    @Test
    fun storeRestoresPersistedIds() {
        every { sharedPreferences.getString(PREF_RECENTLY_VIEWED_ARTICLE_IDS, null) } returns "5,4,3,2,1"

        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), RecentlyViewedStore(di).articleIds.value)
    }

    @Test
    fun storePersistsUpdatedIds() {
        val store = RecentlyViewedStore(di)

        store.recordArticle(2L)
        store.recordArticle(1L)

        assertEquals(listOf(1L, 2L), store.articleIds.value)
        verify {
            sharedPreferences.edit().putString(PREF_RECENTLY_VIEWED_ARTICLE_IDS, "1,2").apply()
        }
    }
}
