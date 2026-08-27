package com.nononsenseapps.feeder.openai

internal data class AiTranslationBlock(
    val segments: List<AiTranslationSegment>,
)

internal object AiTranslationChunker {
    const val TARGET_CHARS = 4_000
    const val MAX_CHARS = 6_000

    fun chunk(
        blocks: List<AiTranslationBlock>,
        articleTitle: String,
        targetChars: Int = TARGET_CHARS,
        maxChars: Int = MAX_CHARS,
    ): List<AiTranslationChunk> {
        require(targetChars > 0 && maxChars >= targetChars)
        val chunks = mutableListOf<AiTranslationChunk>()
        var pending = mutableListOf<AiTranslationSegment>()
        var pendingChars = 0

        fun flush() {
            if (pending.isNotEmpty()) {
                chunks += AiTranslationChunk("chunk-${chunks.size}", articleTitle, pending)
                pending = mutableListOf()
                pendingChars = 0
            }
        }

        blocks.forEach { block ->
            val blockChars = block.segments.sumOf { it.text.length }
            if (blockChars <= maxChars) {
                if (pending.isNotEmpty() && pendingChars + blockChars > targetChars) {
                    flush()
                }
                pending += block.segments
                pendingChars += blockChars
            } else {
                flush()
                block.segments.forEach { segment ->
                    if (pending.isNotEmpty() && pendingChars + segment.text.length > maxChars) {
                        flush()
                    }
                    pending += segment
                    pendingChars += segment.text.length
                }
                flush()
            }
        }
        flush()
        return chunks
    }

    fun splitLongText(
        text: String,
        maxChars: Int = MAX_CHARS,
    ): List<String> {
        require(maxChars > 0)
        if (text.length <= maxChars) {
            return listOf(text)
        }

        val parts = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + maxChars).coerceAtMost(text.length)
            if (end < text.length && end > start && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) {
                end--
            }
            if (end < text.length) {
                val safeBoundary = findBoundary(text, start, end)
                if (safeBoundary > start) {
                    end = safeBoundary
                }
            }
            parts += text.substring(start, end)
            start = end
        }
        return parts
    }

    private fun findBoundary(
        text: String,
        start: Int,
        end: Int,
    ): Int {
        val minimum = start + ((end - start) * 3 / 5)
        for (index in end downTo minimum + 1) {
            val previous = text[index - 1]
            if (previous in SENTENCE_BOUNDARIES || previous.isWhitespace()) {
                return index
            }
        }
        return end
    }

    private val SENTENCE_BOUNDARIES = setOf('.', '!', '?', '。', '！', '？', ';', '；')
}
