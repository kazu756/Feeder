package com.nononsenseapps.feeder.ui.compose.feedarticle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ArticleReaderFallbackTest {
    @Test
    fun fullTextSuccessUsesFullText() =
        runTest {
            val result = resolve(loadFullText = { "full" }, loadRss = { "rss" })

            assertEquals(
                ReaderContentResult.Content("full", ReaderContentSource.FULL_TEXT),
                result,
            )
        }

    @Test
    fun nullFullTextFallsBackToRss() =
        runTest {
            val result = resolve(loadFullText = { null }, loadRss = { "rss" })

            assertEquals(ReaderContentResult.Content("rss", ReaderContentSource.RSS), result)
        }

    @Test
    fun blankFullTextFallsBackToRss() =
        runTest {
            val result = resolve(loadFullText = { "" }, loadRss = { "rss" })

            assertEquals(ReaderContentResult.Content("rss", ReaderContentSource.RSS), result)
        }

    @Test
    fun fullTextExceptionFallsBackToRss() =
        runTest {
            val result = resolve(loadFullText = { throw IllegalArgumentException("bad article") }, loadRss = { "rss" })

            assertEquals(ReaderContentResult.Content("rss", ReaderContentSource.RSS), result)
        }

    @Test
    fun unavailableFullTextAndNullRssReturnsUnavailable() =
        runTest {
            assertEquals(ReaderContentResult.Unavailable, resolve(loadFullText = { null }, loadRss = { null }))
        }

    @Test
    fun unavailableFullTextAndBlankRssReturnsUnavailable() =
        runTest {
            assertEquals(ReaderContentResult.Unavailable, resolve(loadFullText = { null }, loadRss = { "" }))
        }

    @Test
    fun rssOnlyArticleUsesRss() =
        runTest {
            val result = resolve(loadFullText = null, loadRss = { "rss" })

            assertEquals(ReaderContentResult.Content("rss", ReaderContentSource.RSS), result)
        }

    @Test
    fun fullTextFailureWithRssBodyUsesRssBodyInsteadOfSnippet() =
        runTest {
            val rssBody = readerRssHtml("<p>RSS article body</p>", "short snippet", fullTextRequested = true)

            assertEquals(
                ReaderContentResult.Content("<p>RSS article body</p>", ReaderContentSource.RSS),
                resolve(loadFullText = { null }, loadRss = { rssBody }),
            )
        }

    @Test
    fun fullTextFailureWithOnlySnippetIsUnavailable() =
        runTest {
            val rssBody = readerRssHtml(null, "short snippet", fullTextRequested = true)

            assertEquals(ReaderContentResult.Unavailable, resolve(loadFullText = { null }, loadRss = { rssBody }))
        }

    @Test
    fun fullTextFailureWithOnlyCommentsSnippetIsUnavailable() =
        runTest {
            val rssBody = readerRssHtml(null, "<a href=\"#comments\">Comments</a>", fullTextRequested = true)

            assertEquals(ReaderContentResult.Unavailable, resolve(loadFullText = { null }, loadRss = { rssBody }))
        }

    @Test
    fun fileReadExceptionMessageIsNotUsedAsReaderContent() =
        runTest {
            val result =
                resolve(
                    loadFullText = {
                        throw FileNotFoundException("8552.full.html.gz: open failed: ENOENT")
                    },
                    loadRss = { null },
                )

            assertEquals(ReaderContentResult.Unavailable, result)
        }

    @Test
    fun normalRssReaderCanStillUseSnippet() {
        assertEquals(
            "normal article snippet",
            readerRssHtml(null, "normal article snippet", fullTextRequested = false),
        )
    }

    @Test
    fun cancellationIsRethrownWithoutLoadingRss() =
        runTest {
            var rssLoaded = false

            assertFailsWith<CancellationException> {
                resolve(
                    loadFullText = { throw CancellationException("cancelled") },
                    loadRss = {
                        rssLoaded = true
                        "rss"
                    },
                )
            }
            assertEquals(false, rssLoaded)
        }

    @Test
    fun customTabActionIsReturnedOnlyOncePerSession() {
        val gate = ReaderFallbackEventGate()

        assertEquals(
            ReaderFallbackAction.OpenCustomTab("https://example.com/article"),
            gate.consume("https://example.com/article"),
        )
        assertEquals(ReaderFallbackAction.AlreadyHandled, gate.consume("https://example.com/article"))
    }

    @Test
    fun newSessionCanEmitAgain() {
        val firstSession = ReaderFallbackEventGate()
        val secondSession = ReaderFallbackEventGate()

        assertIs<ReaderFallbackAction.OpenCustomTab>(firstSession.consume("https://example.com/article"))
        assertIs<ReaderFallbackAction.OpenCustomTab>(secondSession.consume("https://example.com/article"))
    }

    @Test
    fun invalidUrlsDoNotProduceCustomTabAction() {
        listOf(null, "", "not a url", "file:///tmp/article", "https:///missing-host").forEach { url ->
            assertEquals(ReaderFallbackAction.InvalidUrl, ReaderFallbackEventGate().consume(url))
        }
    }

    private suspend fun resolve(
        loadFullText: (suspend () -> String?)?,
        loadRss: suspend () -> String?,
    ): ReaderContentResult<String> =
        resolveReaderContent(
            loadFullText = loadFullText,
            loadRss = loadRss,
            isUsable = String::isNotBlank,
        )
}
