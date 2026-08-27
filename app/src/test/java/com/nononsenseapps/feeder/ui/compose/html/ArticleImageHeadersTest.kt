package com.nononsenseapps.feeder.ui.compose.html

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ArticleImageHeadersTest {
    @Test
    fun `valid article url is used as referer`() {
        assertEquals("https://example.com/article", articleImageHeaders("https://example.com/article")["Referer"])
        assertEquals("http://example.com/article", articleImageHeaders("http://example.com/article")["Referer"])
    }

    @Test
    fun `invalid article url does not create a referer`() {
        assertNull(articleImageHeaders(null)["Referer"])
        assertNull(articleImageHeaders("")["Referer"])
        assertNull(articleImageHeaders("not a url")["Referer"])
        assertNull(articleImageHeaders("content://example/article")["Referer"])
    }

    @Test
    fun `referer is the article url rather than an image url`() {
        val articleUrl = "https://example.com/articles/story"
        val imageUrl = "https://cdn.example.com/image.jpg"

        assertEquals(articleUrl, articleImageHeaders(articleUrl)["Referer"])
        assertNotEquals(imageUrl, articleImageHeaders(articleUrl)["Referer"])
    }
}
