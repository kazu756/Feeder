package com.nononsenseapps.feeder.localtranslation

import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

private val translationBlocks = setOf("p", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote")
private val translationInlineTags = setOf("a", "strong", "b", "em", "i", "span", "small", "sup", "sub", "br", "img")

// Bound HTML alignment work, not sentence length. Larger blocks retain the A-path.
internal const val MAX_TRANSLATION_BLOCK_HTML_LENGTH = 16_384

/** Uses Bergamot's HTML alignment for safe blocks; the callback also serves the plain-text A-path. */
internal suspend fun translateBergamotHtml(
    html: String,
    translate: suspend (texts: List<String>, html: Boolean) -> List<String>,
): String {
    val document = Jsoup.parseBodyFragment(html)
    val blocks = mutableListOf<Element>()
    val fallback = mutableListOf<TextNode>()

    fun visit(node: Node) {
        if (node is Element && node.isTranslationProtected()) return
        if (node is Element && node.normalName() in translationBlocks) {
            val targets = collectTranslatableTextNodes(node)
            if (targets.isEmpty()) return
            val hasNestedBlocks = node.getAllElements().drop(1).any { it.normalName() in translationBlocks }
            if (!hasNestedBlocks) {
                if (isSafeTranslationBlock(node, targets)) {
                    blocks.add(node)
                } else {
                    fallback += targets
                }
                return // A block and its descendants must never both be scheduled.
            }
        }
        if (node is TextNode) {
            fallback += collectTranslatableTextNodes(node)
        } else {
            node.childNodes().forEach(::visit)
        }
    }
    visit(document.body())

    for (block in blocks) {
        val replacement =
            try {
                translate(listOf(block.html()), true).singleOrNull()?.let { validatedTranslationBlock(block, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        if (replacement == null) {
            fallback += collectTranslatableTextNodes(block)
        } else {
            block.empty().insertChildren(0, replacement.childNodes().toList())
        }
    }

    if (fallback.isNotEmpty()) {
        val targets = fallback.map(HtmlTextNodeTranslation::from)
        val translated = translate(targets.map(HtmlTextNodeTranslation::text), false)
        check(translated.size == targets.size && translated.all(String::isNotBlank)) { "Invalid plain-text translation response" }
        targets.zip(translated).forEach { (target, text) ->
            target.textNode.text(target.leadingWhitespace + text + target.trailingWhitespace)
        }
    }
    return document.body().html()
}

private fun isSafeTranslationBlock(
    block: Element,
    targets: List<TextNode>,
): Boolean {
    if (block.html().length > MAX_TRANSLATION_BLOCK_HTML_LENGTH) return false
    val targetSet = targets.toSet()

    fun safe(node: Node): Boolean =
        when (node) {
            is TextNode -> node.isBlank || node in targetSet
            is Element -> node.normalName() in translationInlineTags && !node.isTranslationProtected() && node.childNodes().all(::safe)
            else -> false
        }
    return block.childNodes().all(::safe)
}

private fun validatedTranslationBlock(
    original: Element,
    html: String,
): Element? {
    if (html.isBlank()) return null
    val parser = Parser.htmlParser().setTrackErrors(1)
    val translated = original.clone().empty()
    // The response contains inline content only. A heading context can report a spurious EOF error.
    translated.insertChildren(0, parser.parseFragmentInput(html, Element("body"), original.baseUri()))
    if (parser.errors.isNotEmpty() || translated.text().isBlank()) return null

    // Text may move around inline tags with Japanese word order; elements and every attribute may not.
    fun sameStructure(
        source: Element,
        target: Element,
    ): Boolean =
        source.tagName() == target.tagName() &&
            source.attributes() == target.attributes() &&
            target.childNodes().all { it is Element || it is TextNode } &&
            source.childrenSize() == target.childrenSize() &&
            source.children().zip(target.children()).all { (a, b) -> sameStructure(a, b) }

    return translated.takeIf { sameStructure(original, it) }
}
