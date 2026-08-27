package com.nononsenseapps.feeder.openai

import com.nononsenseapps.feeder.archmodel.OpenAISettings
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

class AiArticleTranslator(
    private val openAIApi: OpenAIApi,
) {
    private val json =
        Json {
            ignoreUnknownKeys = false
            explicitNulls = false
        }

    suspend fun translate(
        title: String,
        html: String,
        settings: OpenAISettings,
    ): AiArticleTranslation {
        require(settings.canSummarize) { "AI settings are not configured" }
        val document = Jsoup.parseBodyFragment(html)
        val protectedSnapshot = protectedSnapshot(document.body())
        val nodeTargets = mutableListOf<NodeTarget>()
        val segmentsByBlock = linkedMapOf<Element, MutableList<AiTranslationSegment>>()
        var segmentIndex = 0

        collectTextNodes(document.body()).forEach { textNode ->
            val block = translationBlock(textNode) ?: return@forEach
            val wholeText = textNode.wholeText
            val leadingWhitespace = wholeText.takeWhile(Char::isWhitespace)
            val trailingWhitespace = wholeText.takeLastWhile(Char::isWhitespace)
            val text = wholeText.drop(leadingWhitespace.length).dropLast(trailingWhitespace.length)
            if (text.isBlank()) {
                return@forEach
            }
            if (!isTranslatableAiText(text)) {
                return@forEach
            }

            val segmentIds = mutableListOf<String>()
            AiTranslationChunker.splitLongText(text).forEach { part ->
                val id = "s${segmentIndex++}"
                segmentIds += id
                segmentsByBlock.getOrPut(block, ::mutableListOf) += AiTranslationSegment(id, part)
            }
            nodeTargets += NodeTarget(textNode, leadingWhitespace, trailingWhitespace, segmentIds)
        }

        val blocks = mutableListOf<AiTranslationBlock>()
        if (title.isNotBlank()) {
            blocks += AiTranslationBlock(listOf(AiTranslationSegment(TITLE_SEGMENT_ID, title)))
        }
        blocks += segmentsByBlock.values.map(::AiTranslationBlock)
        val chunks = AiTranslationChunker.chunk(blocks, articleTitle = title)
        if (chunks.isEmpty()) {
            return AiArticleTranslation(title, html)
        }

        val translations = linkedMapOf<String, String>()
        chunks.forEach { chunk ->
            val rawResponse =
                when (val result = openAIApi.translateChunkWithAi(chunk, settings)) {
                    is OpenAIApi.AiTranslationChunkResult.Success -> result.content
                    is OpenAIApi.AiTranslationChunkResult.Error -> throw IllegalStateException(result.message)
                }
            translations += AiTranslationPrompt.parseAndValidate(rawResponse, chunk.segments, json)
        }

        nodeTargets.forEach { target ->
            val translatedText = target.segmentIds.joinToString(separator = "") { translations.getValue(it) }
            target.textNode.text(target.leadingWhitespace + translatedText + target.trailingWhitespace)
        }
        check(protectedSnapshot == protectedSnapshot(document.body())) { "AI translation changed protected HTML content" }

        return AiArticleTranslation(
            translatedTitle = translations[TITLE_SEGMENT_ID] ?: title,
            translatedHtml = document.body().html(),
        )
    }

    private fun translationBlock(textNode: TextNode): Element? {
        var current: Node? = textNode.parentNode()
        var block: Element? = null
        while (current is Element) {
            val tag = current.normalName()
            if (tag in EXCLUDED_TAGS) {
                return null
            }
            if (block == null && tag in TRANSLATABLE_BLOCK_TAGS) {
                block = current
            }
            current = current.parentNode()
        }
        return block
    }

    private fun collectTextNodes(node: Node): List<TextNode> =
        buildList {
            fun visit(current: Node) {
                if (current is TextNode) {
                    add(current)
                }
                current.childNodes().forEach(::visit)
            }
            visit(node)
        }

    private fun protectedSnapshot(body: Element): ProtectedHtmlSnapshot =
        ProtectedHtmlSnapshot(
            structure =
                body.getAllElements().map { element ->
                    ProtectedElement(
                        tag = element.normalName(),
                        attributes = element.attributes().asList().map { it.key to it.value },
                    )
                },
            excludedContents =
                body
                    .getAllElements()
                    .filter { it.normalName() in EXCLUDED_TAGS }
                    .map(Element::outerHtml),
        )

    private data class NodeTarget(
        val textNode: TextNode,
        val leadingWhitespace: String,
        val trailingWhitespace: String,
        val segmentIds: List<String>,
    )

    private data class ProtectedHtmlSnapshot(
        val structure: List<ProtectedElement>,
        val excludedContents: List<String>,
    )

    private data class ProtectedElement(
        val tag: String,
        val attributes: List<Pair<String, String>>,
    )

    companion object {
        private const val TITLE_SEGMENT_ID = "article-title"
        private val EXCLUDED_TAGS = setOf("script", "style", "code", "pre", "noscript")
        private val TRANSLATABLE_BLOCK_TAGS =
            setOf(
                "h1",
                "h2",
                "h3",
                "h4",
                "h5",
                "h6",
                "p",
                "blockquote",
                "li",
                "td",
                "th",
                "caption",
                "figcaption",
            )
    }
}

data class AiArticleTranslation(
    val translatedTitle: String,
    val translatedHtml: String,
)

internal fun isTranslatableAiText(text: String): Boolean {
    val trimmedText = text.trim()
    if (trimmedText.isEmpty() || URL_ONLY_PATTERN.matches(trimmedText)) {
        return false
    }

    var index = 0
    while (index < trimmedText.length) {
        val codePoint = Character.codePointAt(trimmedText, index)
        if (Character.isLetter(codePoint)) {
            return true
        }
        index += Character.charCount(codePoint)
    }
    return false
}

private val URL_ONLY_PATTERN = Regex("""(?i)^(?:https?://|www\.)\S+$""")
