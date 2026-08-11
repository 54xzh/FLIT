package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.rerere.rikkahub.data.db.dao.MemoryRetrievalRow
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryRetrievalMode
import me.rerere.rikkahub.data.model.effectiveMemoryRetrievalMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

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
    fun `explicit query word wins over an earlier auxiliary bigram`() {
        val rows = listOf(row(1, "system"))
        val tokenizer = FakeTokenizer(
            mapOf(
                "system" to listOf(word("系统")),
                "compound system" to listOf(
                    word("操作系统"),
                    KeywordToken("系统", 0.35f, KeywordTokenKind.BIGRAM),
                    word("系统"),
                ),
            ),
        )
        val index = KeywordMemoryIndex.build(rows, tokenizer)

        val results = index.search("compound system", tokenizer, nowMillis = 0L, limit = 2)

        assertEquals(listOf(1), results.map { it.row.id })
        assertEquals(listOf("系统"), results.first().matchedTerms)
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

    @Test
    fun `real tokenizer keeps android and removes degree word`() = runBlocking {
        val tokenizer = realTokenizer()
        tokenizer.prepare()

        val tokens = tokenizer.tokenizeWithKinds("但是 安卓手机很卡")

        assertEquals(listOf("安卓", "手机", "卡"), tokens.primaryValues())
        assertFalse(tokens.any { it.kind == KeywordTokenKind.BIGRAM && it.value == "安卓" })
    }

    @Test
    fun `real tokenizer removes standalone multi-character degree word`() = runBlocking {
        val tokenizer = realTokenizer()
        tokenizer.prepare()

        val tokens = tokenizer.tokenizeWithKinds("鸿蒙手机非常流畅")

        assertEquals(listOf("鸿蒙", "手机", "流畅"), tokens.primaryValues())
    }

    @Test
    fun `real tokenizer preserves complete technical identifiers`() = runBlocking {
        val tokenizer = realTokenizer()
        tokenizer.prepare()

        val tokens = tokenizer.tokenizeWithKinds("GPT-4o. 和 C++.")
        val primaryValues = tokens.primaryValues()

        assertEquals(listOf("gpt-4o", "c++"), primaryValues)
        assertFalse(primaryValues.any { it in setOf("gpt", "4o", "c") })
    }

    @Test
    fun `sentence period does not turn ordinary english word into technical token`() = runBlocking {
        val tokenizer = realTokenizer()
        tokenizer.prepare()
        val index = KeywordMemoryIndex.build(
            rows = listOf(row(1, "phone.")),
            tokenizer = tokenizer,
        )

        val results = index.search("phone", tokenizer, nowMillis = 0L, limit = 2)

        assertEquals(listOf(1), results.map { it.row.id })
        assertEquals(listOf("phone"), results.first().matchedTerms)
    }

    @Test
    fun `degree word correction does not strip ordinary words`() = runBlocking {
        val tokenizer = realTokenizer()
        tokenizer.prepare()

        val tokens = tokenizer.tokenizeWithKinds("太原 更换 最后")

        assertEquals(listOf("太原", "更换", "最后"), tokens.primaryValues())
    }

    @Test
    fun `stop-word-only query produces no dynamic matches`() = runBlocking {
        val tokenizer = realTokenizer()
        tokenizer.prepare()
        val index = KeywordMemoryIndex.build(
            rows = listOf(row(1, "很"), row(2, "安卓手机")),
            tokenizer = tokenizer,
        )

        val results = index.search("很 非常", tokenizer, nowMillis = 0L, limit = 2)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `specific android problem ranks above generic phone memory`() = runBlocking {
        val tokenizer = realTokenizer()
        tokenizer.prepare()
        val index = KeywordMemoryIndex.build(
            rows = listOf(
                row(1, "安卓手机卡顿严重"),
                row(2, "手机外观漂亮"),
            ),
            tokenizer = tokenizer,
        )

        val results = index.search("安卓手机很卡", tokenizer, nowMillis = 0L, limit = 2)

        assertEquals(1, results.first().row.id)
        assertEquals(listOf("安卓", "手机"), results.first().matchedTerms)
    }

    @Test
    fun `real tokenizer initializes jieba only once under concurrency`() = runBlocking {
        val factoryCalls = AtomicInteger(0)
        val tokenizer = KeywordMemoryTokenizer(
            textNormalizer = KeywordTextNormalizer { it.lowercase() },
            wordBreaker = KeywordWordBreaker { listOf(it) },
            segmenterFactory = {
                factoryCalls.incrementAndGet()
                com.huaban.analysis.jieba.JiebaSegmenter()
            },
        )

        coroutineScope {
            List(8) { async { tokenizer.prepare() } }.awaitAll()
        }

        assertEquals(1, factoryCalls.get())
    }

    @Test
    fun `real tokenizer falls back without crashing when jieba initialization fails`() = runBlocking {
        val tokenizer = KeywordMemoryTokenizer(
            textNormalizer = KeywordTextNormalizer { it.lowercase() },
            wordBreaker = KeywordWordBreaker { value -> value.split(' ') },
            stopWordsLoader = { setOf("很") },
            segmenterFactory = { error("test initialization failure") },
        )

        tokenizer.prepare()
        val tokens = tokenizer.tokenizeWithKinds("很 手机")

        assertEquals(listOf("手机"), tokens.primaryValues())
    }

    @Test
    fun `index rebuilds entirely when jieba fails during tokenization`() = runBlocking {
        val segmentCalls = AtomicInteger(0)
        val tokenizer = KeywordMemoryTokenizer(
            textNormalizer = KeywordTextNormalizer { it.lowercase() },
            wordBreaker = KeywordWordBreaker { value -> value.split(' ') },
            stopWordsLoader = { emptySet() },
            segmentWords = { _, value ->
                if (segmentCalls.incrementAndGet() == 2) error("test runtime failure")
                value.split(' ')
            },
        )
        tokenizer.prepare()

        val index = KeywordMemoryIndex.build(
            rows = listOf(row(1, "alpha"), row(2, "beta")),
            tokenizer = tokenizer,
        )
        val results = index.search("alpha", tokenizer, nowMillis = 0L, limit = 2)

        assertEquals(tokenizer.revision, index.tokenizerRevision)
        assertEquals(listOf(1), results.map { it.row.id })
    }

    @Test
    fun `index build and search respond to cancellation checks`() {
        val rows = List(20) { index -> row(index + 1, "memory-$index") }
        val tokenizer = FakeTokenizer(rows.associate { memoryRow ->
            memoryRow.content to listOf(word(memoryRow.content))
        })
        var buildChecks = 0

        assertThrows(CancellationException::class.java) {
            KeywordMemoryIndex.build(rows, tokenizer) {
                if (++buildChecks >= 5) throw CancellationException("cancel build")
            }
        }

        val index = KeywordMemoryIndex.build(rows, tokenizer)
        var searchChecks = 0
        assertThrows(CancellationException::class.java) {
            index.search("memory-1", tokenizer, limit = 5) {
                if (++searchChecks >= 5) throw CancellationException("cancel search")
            }
        }
    }

    @Test
    fun `stop-word-only query still preserves pinned memories at repository merge`() = runBlocking {
        val tokenizer = realTokenizer()
        tokenizer.prepare()
        val pinned = row(1, "置顶记忆", pinned = true)

        val results = mergeKeywordMemoryHits(
            rows = listOf(pinned, row(2, "普通记忆")),
            matchedHits = emptyList(),
            limit = 2,
            normalize = tokenizer::normalize,
        )

        assertEquals(listOf(1), results.map { it.row.id })
    }

    private fun row(
        id: Int,
        content: String,
        pinned: Boolean = false,
    ) = MemoryRetrievalRow(
        id = id,
        assistantId = "assistant",
        content = content,
        type = 0,
        pinned = pinned,
        timestamp = 0L,
        significance = null,
    )

    private fun word(value: String) = KeywordToken(value, 1f, KeywordTokenKind.WORD)

    private fun realTokenizer() = KeywordMemoryTokenizer(
        textNormalizer = KeywordTextNormalizer { it.lowercase() },
        wordBreaker = KeywordWordBreaker { value ->
            when (value) {
                "很卡" -> listOf("很", "卡")
                else -> listOf(value)
            }
        },
    )

    private fun List<KeywordToken>.primaryValues(): List<String> =
        filter { it.kind != KeywordTokenKind.BIGRAM }.map { it.value }

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
