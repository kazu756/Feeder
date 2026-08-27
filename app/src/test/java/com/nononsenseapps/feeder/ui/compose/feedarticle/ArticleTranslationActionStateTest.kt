package com.nononsenseapps.feeder.ui.compose.feedarticle

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArticleTranslationActionStateTest {
    @Test
    fun `AI translation stays available while a translation result is displayed`() {
        assertTrue(
            shouldShowAiTranslationAction(
                canSummarize = true,
                hasArticleLink = true,
                translationOperationLoading = false,
            ),
        )
    }

    @Test
    fun `AI translation is hidden while either translation operation is running`() {
        assertFalse(
            shouldShowAiTranslationAction(
                canSummarize = true,
                hasArticleLink = true,
                translationOperationLoading = true,
            ),
        )
    }

    @Test
    fun `standard translation is hidden while AI translation is running`() {
        assertFalse(
            shouldShowStandardTranslationAction(
                canTranslate = true,
                translationOperationLoading = true,
            ),
        )
    }

    @Test
    fun `translation actions return after loading finishes`() {
        assertTrue(
            shouldShowStandardTranslationAction(
                canTranslate = true,
                translationOperationLoading = false,
            ),
        )
        assertTrue(
            shouldShowAiTranslationAction(
                canSummarize = true,
                hasArticleLink = true,
                translationOperationLoading = false,
            ),
        )
    }
}
