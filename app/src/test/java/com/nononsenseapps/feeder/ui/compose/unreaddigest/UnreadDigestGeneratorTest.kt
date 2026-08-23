package com.nononsenseapps.feeder.ui.compose.unreaddigest

import androidx.compose.ui.text.AnnotatedString
import com.nononsenseapps.feeder.openai.OpenAIApi
import com.nononsenseapps.feeder.openai.UnreadDigestArticle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class UnreadDigestGeneratorTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val article = UnreadDigestArticle(1, "Title", "Snippet", "Feed", null, "https://example.com")

    @Test
    fun loadingThenSuccessAndPreventsDuplicateRequest() {
        var requests = 0
        val generator =
            generator(
                createDigest = {
                    requests++
                    OpenAIApi.UnreadDigestResult.Success("[[ARTICLE:1]] digest")
                },
            )

        generator.generate()
        generator.generate()
        assertEquals(UnreadDigestState.Loading, generator.state.value)
        scope.advanceUntilIdle()

        val success = assertIs<UnreadDigestState.Success>(generator.state.value)
        assertEquals(1L, success.articleLinks.single().feedItemId)
        assertEquals("Title", success.articleLinks.single().title)
        assertEquals(1, requests)
    }

    @Test
    fun emptySkipsApiRequest() {
        var requests = 0
        val generator =
            generator(
                articles = emptyList(),
                createDigest = {
                    requests++
                    OpenAIApi.UnreadDigestResult.Success("digest")
                },
            )

        generator.generate()
        scope.advanceUntilIdle()

        assertEquals(UnreadDigestState.Empty, generator.state.value)
        assertEquals(0, requests)
    }

    @Test
    fun errorCanRetry() {
        var requests = 0
        val generator =
            generator {
                requests++
                if (requests == 1) OpenAIApi.UnreadDigestResult.Error("failed") else OpenAIApi.UnreadDigestResult.Success("ok")
            }

        generator.generate()
        scope.advanceUntilIdle()
        assertEquals(UnreadDigestState.Error("failed"), generator.state.value)
        generator.generate()
        scope.advanceUntilIdle()

        assertIs<UnreadDigestState.Success>(generator.state.value)
        assertEquals(2, requests)
    }

    private fun generator(
        articles: List<UnreadDigestArticle> = listOf(article),
        createDigest: suspend () -> OpenAIApi.UnreadDigestResult,
    ) = UnreadDigestGenerator(
        scope = scope,
        loadArticles = { articles },
        createDigest = { createDigest() },
        render = { listOf(AnnotatedString(it)) },
    )
}
