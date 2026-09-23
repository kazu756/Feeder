package com.nononsenseapps.feeder.localtranslation

import com.nononsenseapps.feeder.model.hasEnoughTextForLanguageDetection
import org.jsoup.Jsoup
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HtmlTranslationTargetsTest {
    @Test
    fun shortHeadingsAreTranslatedWithoutLoweringLanguageDetectionThreshold() {
        assertEquals(listOf("News"), targets("<h2>News</h2>"))
        assertFalse(hasEnoughTextForLanguageDetection("News"))
    }

    @Test
    fun shortListItemsAndLinkLabelsAreTranslated() {
        assertEquals(
            listOf("Buy", "Read more", "I"),
            targets("<ul><li>Buy</li><li><a href='https://example.com'>Read more</a></li><li>I</li></ul>"),
        )
    }

    @Test
    fun urlOnlyNodesAreExcludedButSentencesContainingUrlsAreTranslated() {
        val urls =
            listOf(
                "https://example.com/page",
                "HTTP://example.com",
                "ftp://example.com",
                "www.example.com",
                "example.com",
                "//example.com",
                "mailto:reader@example.com",
            )
        urls.forEach { url -> assertEquals(emptyList(), targets("<p> $url </p>"), url) }
        assertEquals(listOf("Visit https://example.com"), targets("<p>Visit https://example.com</p>"))
    }

    @Test
    fun protectedElementsAndTheirDescendantsAreExcluded() {
        assertEquals(
            listOf("Normal article text is translated."),
            targets(
                """
                <pre>Do not translate this text <span>or this</span></pre>
                <p><code>Do not translate this code</code></p>
                <script>const message = 'Do not translate this script';</script>
                <style>.example { font-family: 'Do not translate this style'; }</style>
                <p>Normal article text is translated.</p>
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun translateNoProtectsDescendantsIncludingExplicitYes() {
        assertEquals(
            listOf("Translate me"),
            targets("<div translate='NO'><span>Protected text</span><b translate='yes'>Still protected</b></div><p>Translate me</p>"),
        )
    }

    @Test
    fun whitespaceNumbersSymbolsAndEmojiAreExcluded() {
        assertEquals(emptyList(), targets("<p> \n </p><p>123.45 / 2026-09-23</p><p>!? + − © ™</p><p>😀 🎉 ❤️ 1️⃣ ℹ️</p>"))
    }

    @Test
    fun ordinaryBodyAndInlineTextRemainSeparateTargets() {
        assertEquals(
            listOf("This is ", "not", " recommended for everyday use."),
            targets("<p>This is <strong>not</strong> recommended for everyday use.</p>"),
        )
    }

    @Test
    fun replacingTargetsPreservesStructureAttributesAndProtectedContent() {
        val document =
            Jsoup.parseBodyFragment(
                "<h2 id='title'> News </h2><ul><li><a href='https://example.com'>Read</a>" +
                    "<img src='image.png' alt='Original caption'></li></ul><pre><code>Keep this code</code></pre>" +
                    "<span translate='no'>Keep this label</span><p>https://example.com</p>",
            )
        val original = document.clone()
        val translations = listOf("ニュース", "読む")
        val selected = collectTranslatableTextNodes(document.body()).map(HtmlTextNodeTranslation::from)
        assertEquals(translations.size, selected.size)
        selected.zip(translations).forEach { (target, translated) ->
            target.textNode.text(target.leadingWhitespace + translated + target.trailingWhitespace)
        }

        assertEquals(
            original.allElements.map { it.tagName() to it.attributes().toString() },
            document.allElements.map { it.tagName() to it.attributes().toString() },
        )
        val headingText =
            document
                .selectFirst("h2")!!
                .textNodes()
                .single()
                .wholeText
        assertEquals(" ニュース ", headingText)
        assertEquals("読む", document.selectFirst("a")!!.text())
        assertEquals(original.select("pre, span, p").outerHtml(), document.select("pre, span, p").outerHtml())
    }

    private fun targets(html: String): List<String> = collectTranslatableTextNodes(Jsoup.parseBodyFragment(html).body()).map { it.wholeText }
}
