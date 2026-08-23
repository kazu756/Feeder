package com.nononsenseapps.feeder.openai

data class UnreadDigestArticleLink(
    val requestIndex: Int,
    val feedItemId: Long,
    val title: String,
)

data class ParsedUnreadDigest(
    val markdown: String,
    val articleLinks: List<UnreadDigestArticleLink>,
)

fun parseUnreadDigestResponse(
    markdown: String,
    prompt: UnreadDigestPrompt,
): ParsedUnreadDigest {
    val referencesByIndex = prompt.articleReferences.associateBy { it.index }
    val seenIndexes = mutableSetOf<Int>()
    val links =
        ARTICLE_MARKER_REGEX
            .findAll(markdown)
            .mapNotNull { match ->
                val index = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val reference = referencesByIndex[index] ?: return@mapNotNull null
                if (!seenIndexes.add(index)) return@mapNotNull null
                UnreadDigestArticleLink(
                    requestIndex = index,
                    feedItemId = reference.feedItemId,
                    title = reference.title,
                )
            }.toList()

    return ParsedUnreadDigest(
        markdown = markdown.replace(ARTICLE_MARKER_REGEX, "").trim(),
        articleLinks = links,
    )
}

private val ARTICLE_MARKER_REGEX = Regex("""\[\[ARTICLE:(\d+)]]""")
