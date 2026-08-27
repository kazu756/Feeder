package com.nononsenseapps.feeder.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiTranslationChunkerTest {
    @Test
    fun `chunks prefer block boundaries around target size`() {
        val blocks =
            listOf(
                block("s1", "a".repeat(2_500)),
                block("s2", "b".repeat(2_000)),
                block("s3", "c".repeat(1_000)),
            )

        val chunks = AiTranslationChunker.chunk(blocks, "title")

        assertEquals(listOf(listOf("s1"), listOf("s2", "s3")), chunks.map { it.segments.map(AiTranslationSegment::id) })
        assertEquals(listOf("chunk-0", "chunk-1"), chunks.map(AiTranslationChunk::id))
    }

    @Test
    fun `oversized block is split without exceeding hard max`() {
        val blocks =
            listOf(
                AiTranslationBlock(
                    listOf(
                        AiTranslationSegment("s1", "a".repeat(3_500)),
                        AiTranslationSegment("s2", "b".repeat(3_500)),
                    ),
                ),
            )

        val chunks = AiTranslationChunker.chunk(blocks, "title")

        assertEquals(2, chunks.size)
        assertTrue(chunks.all { chunk -> chunk.segments.sumOf { it.text.length } <= AiTranslationChunker.MAX_CHARS })
    }

    @Test
    fun `long text splitting preserves unicode and emoji`() {
        val text = "😀日本語。".repeat(2_000)

        val parts = AiTranslationChunker.splitLongText(text, maxChars = 101)

        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 101 })
        assertTrue(parts.none { it.lastOrNull()?.isHighSurrogate() == true })
        assertTrue(parts.none { it.firstOrNull()?.isLowSurrogate() == true })
    }

    @Test
    fun `empty blocks produce no chunks`() {
        assertEquals(emptyList(), AiTranslationChunker.chunk(emptyList(), "title"))
    }

    private fun block(
        id: String,
        text: String,
    ) = AiTranslationBlock(listOf(AiTranslationSegment(id, text)))
}
