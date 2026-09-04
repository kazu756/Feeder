package com.nononsenseapps.feeder.openai

data class UnreadDigestArticleLink(
    val requestIndex: Int,
    val feedItemId: Long,
    val title: String,
)

data class ParsedUnreadDigest(
    val markdown: String,
    val articleLinks: List<UnreadDigestArticleLink>,
    val content: List<UnreadDigestContent>,
)

sealed interface UnreadDigestContent {
    data class Markdown(
        val value: String,
    ) : UnreadDigestContent

    data class ArticleLink(
        val value: UnreadDigestArticleLink,
    ) : UnreadDigestContent
}

fun parseUnreadDigestResponse(
    markdown: String,
    prompt: UnreadDigestPrompt,
): ParsedUnreadDigest {
    val referencesByIndex = prompt.articleReferences.associateBy { it.index }
    val seenIndexes = mutableSetOf<Int>()
    val links = mutableListOf<UnreadDigestArticleLink>()
    val content = mutableListOf<UnreadDigestContent>()
    val pendingMarkdown = StringBuilder()
    var previousEnd = 0

    ARTICLE_MARKER_REGEX.findAll(markdown).forEach { match ->
        pendingMarkdown.append(markdown.substring(previousEnd, match.range.first))
        previousEnd = match.range.last + 1

        val index = match.groupValues[1].toIntOrNull()
        val reference = index?.let(referencesByIndex::get)
        if (index != null && reference != null && seenIndexes.add(index)) {
            pendingMarkdown.toString().trim().takeIf(String::isNotEmpty)?.let {
                content += UnreadDigestContent.Markdown(it)
            }
            pendingMarkdown.clear()
            val link =
                UnreadDigestArticleLink(
                    requestIndex = index,
                    feedItemId = reference.feedItemId,
                    title = reference.title,
                )
            links += link
            content += UnreadDigestContent.ArticleLink(link)
        }
    }
    pendingMarkdown.append(markdown.substring(previousEnd))
    pendingMarkdown.toString().trim().takeIf(String::isNotEmpty)?.let {
        content += UnreadDigestContent.Markdown(it)
    }

    return ParsedUnreadDigest(
        markdown = markdown.replace(ARTICLE_MARKER_REGEX, "").trim(),
        articleLinks = links,
        content = content,
    )
}

private val ARTICLE_MARKER_REGEX = Regex("""\[\[ARTICLE:(\d+)]]""")
