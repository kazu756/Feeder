package com.nononsenseapps.feeder.localtranslation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BergamotHtmlTranslationTest {
    private data class Request(
        val texts: List<String>,
        val html: Boolean,
    )

    @Test
    fun negationAcrossStrongIsSentAsOneHtmlRequest() =
        runTest {
            val requests = mutableListOf<Request>()
            val result =
                translateBergamotHtml("<p>This is <strong>not</strong> recommended.</p>") { texts, html ->
                    requests += Request(texts, html)
                    listOf("これは推奨<strong>されません</strong>。")
                }
            assertEquals(listOf(Request(listOf("This is <strong>not</strong> recommended."), true)), requests)
            assertEquals("これは推奨されません。", Jsoup.parseBodyFragment(result).selectFirst("p")!!.text())
        }

    @Test
    fun headingsListItemsAndParagraphsAreIndependentAndNeverTranslatedTwice() =
        runTest {
            val requests = mutableListOf<Request>()
            translateBergamotHtml(
                "<h2>Key findings</h2><ul><li>This is <em>very</em> important.</li></ul>" +
                    "<p>First <span>paragraph</span> here.</p><p>Second paragraph.</p>",
            ) { texts, html ->
                requests += Request(texts, html)
                texts
            }
            assertEquals(4, requests.size, requests.toString())
            assertTrue(requests.all { it.html && it.texts.size == 1 })
            assertEquals("Key findings", requests[0].texts.single())
            assertEquals("This is <em>very</em> important.", requests[1].texts.single())
            assertEquals(4, requests.flatMap { it.texts }.distinct().size)
        }

    @Test
    fun inlineHierarchyAndAllAttributesSurvive() =
        runTest {
            val source =
                "<p id='outer'><a href='https://example.com/article?x=1&amp;y=2' class='link' style='color:red' " +
                    "data-value='keep' aria-label='Original label'>Read more</a><br>" +
                    "<strong><em><span><b><i><small><sup><sub>Important</sub></sup></small></i></b></span></em></strong>" +
                    "<img src='photo.jpg' srcset='photo.jpg 1x, large.jpg 2x' alt='Keep caption'></p>"
            val result =
                translateBergamotHtml(source) { texts, html ->
                    assertTrue(html)
                    texts.map { it.replace("Read more", "続きを読む").replace("Important", "重要") }
                }
            val original = Jsoup.parseBodyFragment(source)
            val translated = Jsoup.parseBodyFragment(result)
            assertEquals(
                original.allElements.map { it.tagName() to it.attributes().toString() },
                translated.allElements.map { it.tagName() to it.attributes().toString() },
            )
            assertEquals("続きを読む", translated.selectFirst("a")!!.text())
            assertEquals("重要", translated.selectFirst("strong > em > span > b > i > small > sup > sub")!!.text())
            assertEquals("https://example.com/article?x=1&y=2", translated.selectFirst("a")!!.attr("href"))
        }

    @Test
    fun protectedContentUsesPlainTextFallbackWithoutSendingProtectedText() =
        runTest {
            val requests = mutableListOf<Request>()
            val source =
                "<p>Run <code>git status</code> first.</p><p>Keep <span translate='no'><b>Brand name</b></span> safe.</p>" +
                    "<pre>Untouched pre</pre><script>untouchedScript()</script><style>.untouched{color:red}</style>"
            val result =
                translateBergamotHtml(source) { texts, html ->
                    requests += Request(texts, html)
                    texts.map { "訳:$it" }
                }
            assertEquals(listOf(Request(listOf("Run", "first.", "Keep", "safe."), false)), requests)
            val original = Jsoup.parseBodyFragment(source)
            val translated = Jsoup.parseBodyFragment(result)
            assertEquals(original.select("code, [translate], pre, script, style").outerHtml(), translated.select("code, [translate], pre, script, style").outerHtml())
        }

    @Test
    fun nonLanguageNodesRemainUntouchedInsideBlocks() =
        runTest {
            val result =
                translateBergamotHtml("<p>Read <span>https://example.com</span><span>123</span><span>😀</span><span> ! </span></p>") { texts, html ->
                    assertFalse(html)
                    assertEquals(listOf("Read"), texts)
                    listOf("読む")
                }
            assertEquals(listOf("https://example.com", "123", "😀", " ! "), Jsoup.parseBodyFragment(result).select("span").map { it.wholeText() })
        }

    @Test
    fun isolatedTextAndUnsupportedStructuresUseAPath() =
        runTest {
            val requests = mutableListOf<Request>()
            translateBergamotHtml("Orphan <p>Safe paragraph</p><table><tr><td>Cell</td></tr></table><div translate='no'><p>Protected</p></div>") { texts, html ->
                requests += Request(texts, html)
                texts
            }
            assertEquals(listOf(Request(listOf("Safe paragraph"), true), Request(listOf("Orphan", "Cell"), false)), requests)
        }

    @Test
    fun nestedBlocksDoNotOverlapWithTheirParents() =
        runTest {
            val requests = mutableListOf<Request>()
            translateBergamotHtml("<blockquote>Intro<p>Quote</p></blockquote><ul><li>Parent<ul><li>Child</li></ul></li></ul>") { texts, html ->
                requests += Request(texts, html)
                texts
            }
            assertEquals(listOf(Request(listOf("Quote"), true), Request(listOf("Child"), true), Request(listOf("Intro", "Parent"), false)), requests)
        }

    @Test
    fun corruptHtmlFallsBackOnlyForTheFailedBlock() =
        runTest {
            val badResults =
                listOf(
                    "",
                    "<strong>broken",
                    "Lost tag",
                    "<em>Changed tag</em>",
                    "<strong id='injected'>Changed attributes</strong>",
                    "<strong>Duplicate</strong><strong>Duplicate</strong>",
                    "<strong><span>Changed parent</span></strong>",
                    "<!-- unexpected --><strong>Text</strong>",
                )
            for (bad in badResults) {
                val requests = mutableListOf<Request>()
                val result =
                    translateBergamotHtml("<p>Good</p><p><strong>Bad</strong></p>") { texts, html ->
                        requests += Request(texts, html)
                        when {
                            !html -> listOf("復旧")
                            texts.single() == "Good" -> listOf("良好")
                            else -> listOf(bad)
                        }
                    }
                assertEquals(Request(listOf("Bad"), false), requests.last(), bad)
                assertEquals(3, requests.size, bad)
                assertEquals(listOf("良好", "復旧"), Jsoup.parseBodyFragment(result).select("p").map { it.text() }, bad)
            }
        }

    @Test
    fun htmlExceptionAndMissingResponseUseFallback() =
        runTest {
            for (throws in listOf(false, true)) {
                val result =
                    translateBergamotHtml("<p>Hello</p>") { _, html ->
                        if (html) {
                            if (throws) error("Alignment failed")
                            emptyList()
                        } else {
                            listOf("こんにちは")
                        }
                    }
                assertEquals("こんにちは", Jsoup.parseBodyFragment(result).text())
            }
        }

    @Test
    fun cancellationIsRethrownWithoutFallback() =
        runTest {
            var calls = 0
            assertFailsWith<CancellationException> {
                translateBergamotHtml("<p>Hello</p>") { _, _ ->
                    calls++
                    throw CancellationException("Cancelled")
                }
            }
            assertEquals(1, calls)
        }

    @Test
    fun failedPlainTextFallbackStillReportsAnError() =
        runTest {
            assertFailsWith<IllegalStateException> {
                translateBergamotHtml("Orphan") { _, _ -> emptyList() }
            }
        }

    @Test
    fun oversizedBlockFallsBackWithoutTruncatingText() =
        runTest {
            val text = "Long text ".repeat(MAX_TRANSLATION_BLOCK_HTML_LENGTH / 10 + 1)
            translateBergamotHtml("<p>$text</p>") { texts, html ->
                assertFalse(html)
                assertEquals(listOf(text.trim()), texts)
                texts
            }
        }
}
