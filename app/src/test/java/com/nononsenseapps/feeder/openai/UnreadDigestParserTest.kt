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
        assertEquals(
            listOf(
                UnreadDigestContent.ArticleLink(UnreadDigestArticleLink(1, 101L, "First original title")),
                UnreadDigestContent.Markdown("One"),
                UnreadDigestContent.ArticleLink(UnreadDigestArticleLink(50, 150L, "Fiftieth original title")),
                UnreadDigestContent.Markdown("Fifty"),
            ),
            parsed.content,
        )
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
        assertEquals(1, parsed.content.filterIsInstance<UnreadDigestContent.ArticleLink>().size)
    }

    @Test
    fun markerFreeDigestRemainsVisibleWithNoLinks() {
        val markdown = "# Themes\nA complete digest without markers."

        val parsed = parseUnreadDigestResponse(markdown, prompt)

        assertEquals(markdown, parsed.markdown)
        assertTrue(parsed.articleLinks.isEmpty())
        assertEquals(listOf(UnreadDigestContent.Markdown(markdown)), parsed.content)
    }

    @Test
    fun keepsEachValidatedLinkImmediatelyAfterItsArticleBlock() {
        val markdown =
            """
            ### 1. First
            概要：First overview
            読む理由：First reason
            [[ARTICLE:1]]

            ### 2. Fiftieth
            概要：Fiftieth overview
            読む理由：Fiftieth reason
            [[ARTICLE:50]]
            """.trimIndent()

        val parsed = parseUnreadDigestResponse(markdown, prompt)

        assertEquals(4, parsed.content.size)
        assertTrue((parsed.content[0] as UnreadDigestContent.Markdown).value.contains("First reason"))
        assertEquals(101L, (parsed.content[1] as UnreadDigestContent.ArticleLink).value.feedItemId)
        assertTrue((parsed.content[2] as UnreadDigestContent.Markdown).value.contains("Fiftieth reason"))
        assertEquals(150L, (parsed.content[3] as UnreadDigestContent.ArticleLink).value.feedItemId)
    }
}
