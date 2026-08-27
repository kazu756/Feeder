package com.nononsenseapps.feeder.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArticleUrlUtilsTest {
    @Test
    fun `valid http and https article urls are retained`() {
        assertEquals("https://example.com/article", validHttpArticleUrlOrNull("https://example.com/article"))
        assertEquals("http://example.com/article", validHttpArticleUrlOrNull("http://example.com/article"))
    }

    @Test
    fun `missing invalid and non-http article urls are rejected`() {
        assertNull(validHttpArticleUrlOrNull(null))
        assertNull(validHttpArticleUrlOrNull(""))
        assertNull(validHttpArticleUrlOrNull("not a url"))
        assertNull(validHttpArticleUrlOrNull("ftp://example.com/article"))
    }

    @Test
    fun `article base url prefers a valid article url`() {
        assertEquals(
            "https://example.com/articles/story",
            articleBaseUrl("https://example.com/articles/story", "https://example.com/feed.xml"),
        )
    }

    @Test
    fun `article base url falls back to feed url`() {
        assertEquals("https://example.com/feed.xml", articleBaseUrl("invalid", "https://example.com/feed.xml"))
        assertEquals("", articleBaseUrl(null, null))
    }
}
