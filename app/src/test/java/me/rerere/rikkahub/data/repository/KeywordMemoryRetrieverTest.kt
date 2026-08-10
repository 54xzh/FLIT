package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.dao.MemoryRetrievalRow
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryRetrievalMode
import me.rerere.rikkahub.data.model.effectiveMemoryRetrievalMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordMemoryRetrieverTest {
    @Test
    fun `legacy retrieval flag maps to compatible mode`() {
        assertEquals(
            MemoryRetrievalMode.VECTOR,
            Assistant(useRagMemoryRetrieval = true).effectiveMemoryRetrievalMode(),
        )
        assertEquals(
            MemoryRetrievalMode.OFF,
            Assistant(useRagMemoryRetrieval = false).effectiveMemoryRetrievalMode(),
        )
        assertEquals(
            MemoryRetrievalMode.KEYWORD,
            Assistant(
                useRagMemoryRetrieval = true,
                memoryRetrievalMode = MemoryRetrievalMode.KEYWORD,
            ).effectiveMemoryRetrievalMode(),
        )
    }

    @Test
    fun `rare keyword ranks above common keyword`() {
        val rows = listOf(
            row(1, "common rare"),
            row(2, "common common common common"),
        )
        val tokenizer = FakeTokenizer(
            mapOf(
                "common rare" to listOf(word("common"), word("rare")),
                "common common common common" to listOf(word("common"), word("common"), word("common"), word("common")),
            )
        )
        val index = KeywordMemoryIndex.build(rows, tokenizer)

        val results = index.search("rare", tokenizer, nowMillis = 0L, limit = 2)

        assertEquals(1, results.first().row.id)
        assertTrue(results.first().matchedTerms.contains("rare"))
    }

    @Test
    fun `technical token gets a stronger exact match`() {
        val rows = listOf(
            row(1, "gpt-4o"),
            row(2, "gpt model"),
        )
        val tokenizer = FakeTokenizer(
            mapOf(
                "gpt-4o" to listOf(KeywordToken("gpt-4o", 1.3f, KeywordTokenKind.TECHNICAL)),
                "gpt model" to listOf(word("gpt"), word("model")),
            )
        )
        val index = KeywordMemoryIndex.build(rows, tokenizer)

        val results = index.search("gpt-4o", tokenizer, nowMillis = 0L, limit = 2)

        assertEquals(1, results.first().row.id)
        assertTrue(results.first().matchedTerms.contains("gpt-4o"))
    }

    @Test
    fun `ordered phrase receives a relevance boost`() {
        val rows = listOf(
            row(1, "red blue"),
            row(2, "red green blue"),
        )
        val tokenizer = FakeTokenizer(
            mapOf(
                "red blue" to listOf(word("red"), word("blue")),
                "red green blue" to listOf(word("red"), word("green"), word("blue")),
            )
        )
        val index = KeywordMemoryIndex.build(rows, tokenizer)

        val results = index.search("red blue", tokenizer, nowMillis = 0L, limit = 2)

        assertEquals(listOf(1, 2), results.map { it.row.id })
    }

    @Test
    fun `top k limits dynamic matches while preserving their score order`() {
        val rows = listOf(
            row(1, "alpha"),
            row(2, "alpha alpha"),
            row(3, "alpha alpha alpha"),
        )
        val tokenizer = FakeTokenizer(
            mapOf(
                "alpha" to listOf(word("alpha")),
                "alpha alpha" to listOf(word("alpha"), word("alpha")),
                "alpha alpha alpha" to listOf(word("alpha"), word("alpha"), word("alpha")),
            )
        )
        val index = KeywordMemoryIndex.build(rows, tokenizer)

        val results = index.search("alpha", tokenizer, nowMillis = 0L, limit = 2)

        assertEquals(2, results.size)
        assertTrue(results[0].score >= results[1].score)
    }

    private fun row(id: Int, content: String) = MemoryRetrievalRow(
        id = id,
        assistantId = "assistant",
        content = content,
        type = 0,
        pinned = false,
        timestamp = 0L,
        significance = null,
    )

    private fun word(value: String) = KeywordToken(value, 1f, KeywordTokenKind.WORD)

    private class FakeTokenizer(
        private val termsByText: Map<String, List<KeywordToken>>,
    ) : MemoryKeywordTokenizer {
        override fun normalize(value: String): String = value.lowercase()

        override fun tokenizeWithKinds(value: String): List<KeywordToken> =
            termsByText[value] ?: value.split(' ')
                .filter { it.isNotBlank() }
                .map { KeywordToken(it, 1f, KeywordTokenKind.WORD) }
    }
}
