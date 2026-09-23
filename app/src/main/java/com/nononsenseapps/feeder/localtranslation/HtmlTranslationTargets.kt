package com.nononsenseapps.feeder.localtranslation

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

private val excludedTranslationTags = setOf("pre", "code", "script", "style")

internal fun Element.isTranslationProtected(): Boolean = normalName() in excludedTranslationTags || attr("translate").trim().equals("no", ignoreCase = true)

private val urlOnlyText =
    Regex(
        "(?:[a-z][a-z0-9+.-]*://|//|www\\.|mailto:)\\S+|" +
            "[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}(?::[0-9]+)?(?:[/?#]\\S*)?",
        RegexOption.IGNORE_CASE,
    )

// The source language is determined for the article, not for each short text node.
internal fun collectTranslatableTextNodes(node: Node): List<TextNode> =
    buildList {
        fun visit(current: Node) {
            if (current is Element && current.isTranslationProtected()) {
                return
            }
            if (current is TextNode) {
                val text = current.wholeText.trim()
                // Unicode classifies the information emoji (U+2139) as a letter.
                if (text.any { it.isLetter() && it != '\u2139' } && !urlOnlyText.matches(text)) {
                    add(current)
                }
            }
            current.childNodes().forEach(::visit)
        }
        visit(node)
    }
