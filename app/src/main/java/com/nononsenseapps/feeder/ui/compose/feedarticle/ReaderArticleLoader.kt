package com.nononsenseapps.feeder.ui.compose.feedarticle

import com.nononsenseapps.feeder.archmodel.Article
import com.nononsenseapps.feeder.archmodel.ArticleKey
import org.jsoup.Jsoup

/** Input to the shared Reader features; never writes article rows or fetches Miniflux content. */
data class ReaderArticleContent(
    val key: ArticleKey,
    val article: Article,
    val html: String,
) {
    val plainText: String get() = Jsoup.parse(html, article.feedUrl.orEmpty()).body().text()
}

class ReaderArticleLoader(
    private val minifluxBody: suspend (ArticleKey) -> String?,
    private val localBody: suspend (Article, Boolean, Boolean, Boolean) -> String?,
) {
    suspend fun load(
        key: ArticleKey,
        article: Article,
        fullText: Boolean,
        summary: Boolean = false,
        cachedOnly: Boolean = false,
    ): ReaderArticleContent? {
        if (article.item == null || article.id != key.localId) return null
        val html = if (key.isMiniflux) minifluxBody(key) else localBody(article, fullText, summary, cachedOnly)
        return html?.takeIf(String::isNotBlank)?.let { ReaderArticleContent(key, article, it) }
    }
}
