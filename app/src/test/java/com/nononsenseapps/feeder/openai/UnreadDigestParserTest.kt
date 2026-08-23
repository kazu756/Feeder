package com.nononsenseapps.feeder.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnreadDigestParserTest {
    private val prompt =
        UnreadDigestPrompt(
            system = "system",
            user = "user",
            articleReferences =
                listOf(
                    UnreadDigestArticleReference(1, 101L, "First original title"),
                    UnreadDigestArticleReference(50, 150L, "Fiftieth original title"),
                ),
        )

    @Test
    fun resolvesValidIndexesToOriginalFeedItemsAndTitles() {
        val parsed = parseUnreadDigestResponse("[[ARTICLE:1]]\nOne\n[[ARTICLE:50]]\nFifty", prompt)

        assertEquals(
            listOf(
                UnreadDigestArticleLink(1, 101L, "First original title"),
                UnreadDigestArticleLink(50, 150L, "Fiftieth original title"),
            ),
            parsed.articleLinks,
        )
        assertEquals("One\n\nFifty", parsed.markdown)
    }

    @Test
    fun ignoresUnknownBrokenAndDuplicateMarkersWithoutLosingDigest() {
        val markdown = "Intro [[ARTICLE:999]] unknown [[ARTICLE:nope]] broken [[ARTICLE:1]] first [[ARTICLE:1]] duplicate End"
        val parsed = parseUnreadDigestResponse(markdown, prompt)

        assertEquals(listOf(UnreadDigestArticleLink(1, 101L, "First original title")), parsed.articleLinks)
        assertTrue(parsed.markdown.contains("Intro"))
        assertTrue(parsed.markdown.contains("unknown"))
        assertTrue(parsed.markdown.contains("[[ARTICLE:nope]]"))
        assertTrue(parsed.markdown.contains("End"))
    }

    @Test
    fun markerFreeDigestRemainsVisibleWithNoLinks() {
        val markdown = "# Themes\nA complete digest without markers."

        val parsed = parseUnreadDigestResponse(markdown, prompt)

        assertEquals(markdown, parsed.markdown)
        assertTrue(parsed.articleLinks.isEmpty())
    }
}
