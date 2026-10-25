package com.nononsenseapps.feeder.archmodel

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ArticleKeyTest {
    @Test fun collidingNumericIdsHaveDifferentStableKeys() {
        assertNotEquals(ArticleKey(123).stableKey, ArticleKey(123, "a".repeat(64)).stableKey)
        assertNotEquals(ArticleKey(123, "a".repeat(64)), ArticleKey(123, "b".repeat(64)))
    }

    @Test fun identityDoesNotDependOnModeOrRemoteId() {
        assertEquals(ArticleKey(123, "a".repeat(64)), ArticleKey(123, "a".repeat(64)))
        assertEquals("rss:123", ArticleKey(123).stableKey)
    }

    @Test fun invalidProfileCannotSelectAnArbitraryDatabasePath() {
        assertFailsWith<IllegalArgumentException> { ArticleKey(1, "../rss") }
        assertFailsWith<IllegalArgumentException> { ArticleKey(1, "") }
    }
}
