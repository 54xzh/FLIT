package me.rerere.rikkahub.data.repository

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryRetrievalMode
import me.rerere.rikkahub.data.model.effectiveMemoryRetrievalMode
import me.rerere.rikkahub.data.model.requiresEmbedding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridMemoryRetrievalTest {
    @Test
    fun `hybrid mode requires embeddings and keeps legacy defaults`() {
        assertTrue(MemoryRetrievalMode.VECTOR.requiresEmbedding)
        assertTrue(MemoryRetrievalMode.HYBRID.requiresEmbedding)
        assertFalse(MemoryRetrievalMode.KEYWORD.requiresEmbedding)
        assertFalse(MemoryRetrievalMode.OFF.requiresEmbedding)
        assertEquals(
            MemoryRetrievalMode.VECTOR,
            Assistant(useRagMemoryRetrieval = true).effectiveMemoryRetrievalMode(),
        )
        assertEquals(
            MemoryRetrievalMode.OFF,
            Assistant(useRagMemoryRetrieval = false).effectiveMemoryRetrievalMode(),
        )
    }

    @Test
    fun `hybrid mode uses a stable serialized value`() {
        val encoded = Json.encodeToString(MemoryRetrievalMode.HYBRID)

        assertEquals("\"hybrid\"", encoded)
        assertEquals(
            MemoryRetrievalMode.HYBRID,
            Json.decodeFromString<MemoryRetrievalMode>(encoded),
        )
    }

    @Test
    fun `candidate limit grows with final limit and stays bounded`() {
        assertEquals(15, hybridCandidateLimit(0))
        assertEquals(15, hybridCandidateLimit(5))
        assertEquals(18, hybridCandidateLimit(6))
        assertEquals(50, hybridCandidateLimit(50))
    }

    @Test
    fun `hybrid outcome distinguishes partial and total failure`() {
        assertEquals(
            MemoryRetrievalOutcome.SUCCESS,
            hybridRetrievalOutcome(MemoryRetrievalOutcome.SUCCESS, MemoryRetrievalOutcome.SUCCESS),
        )
        assertEquals(
            MemoryRetrievalOutcome.PARTIAL,
            hybridRetrievalOutcome(MemoryRetrievalOutcome.SUCCESS, MemoryRetrievalOutcome.FAILED),
        )
        assertEquals(
            MemoryRetrievalOutcome.PARTIAL,
            hybridRetrievalOutcome(MemoryRetrievalOutcome.FAILED, MemoryRetrievalOutcome.SUCCESS),
        )
        assertEquals(
            MemoryRetrievalOutcome.FAILED,
            hybridRetrievalOutcome(MemoryRetrievalOutcome.FAILED, MemoryRetrievalOutcome.FAILED),
        )
    }

    @Test
    fun `memory found by both branches ranks above single branch hits`() {
        val sharedKeyword = hit(id = 1, score = 8f, mode = MemoryRetrievalMode.KEYWORD, terms = listOf("alpha"))
        val keywordOnly = hit(id = 2, score = 7f, mode = MemoryRetrievalMode.KEYWORD)
        val vectorOnly = hit(id = 3, score = 0.9f, mode = MemoryRetrievalMode.VECTOR)
        val sharedVector = hit(id = 1, score = 0.8f, mode = MemoryRetrievalMode.VECTOR)

        val result = mergeHybridHits(
            keywordHits = listOf(sharedKeyword, keywordOnly),
            vectorHits = listOf(vectorOnly, sharedVector),
            limit = 3,
        )

        assertEquals(listOf(1, 3, 2), result.map { it.memory.id })
        assertEquals(listOf("alpha"), result.first().matchedTerms)
        assertTrue(result.all { it.mode == MemoryRetrievalMode.HYBRID })
    }

    @Test
    fun `pinned memories do not consume dynamic limit and duplicate content is removed`() {
        val pinned = hit(id = 10, content = "Keep Me", pinned = true, mode = MemoryRetrievalMode.KEYWORD)
        val duplicatePinned = hit(id = 11, content = "  keep   me ", pinned = true, mode = MemoryRetrievalMode.VECTOR)
        val dynamicDuplicate = hit(id = 12, content = "KEEP ME", mode = MemoryRetrievalMode.KEYWORD)
        val dynamicFirst = hit(id = 13, content = "First", mode = MemoryRetrievalMode.KEYWORD)
        val dynamicSecond = hit(id = 14, content = "Second", mode = MemoryRetrievalMode.VECTOR)

        val result = mergeHybridHits(
            keywordHits = listOf(pinned, dynamicDuplicate, dynamicFirst),
            vectorHits = listOf(duplicatePinned, dynamicSecond),
            limit = 2,
        )

        assertEquals(3, result.size)
        assertTrue(result.first().memory.id == 10 || result.first().memory.id == 11)
        assertEquals(2, result.count { !it.memory.pinned })
        assertEquals(1, result.count { it.memory.id == 10 || it.memory.id == 11 })
        assertFalse(result.any { it.memory.id == 12 })
    }

    @Test
    fun `ties are deterministic and prefer keyword rank`() {
        val keywordFirst = hit(id = 2, mode = MemoryRetrievalMode.KEYWORD)
        val vectorFirst = hit(id = 1, mode = MemoryRetrievalMode.VECTOR)

        val result = mergeHybridHits(
            keywordHits = listOf(keywordFirst),
            vectorHits = listOf(vectorFirst),
            limit = 2,
        )

        assertEquals(listOf(2, 1), result.map { it.memory.id })
    }

    private fun hit(
        id: Int,
        content: String = "memory-$id",
        score: Float = 1f,
        pinned: Boolean = false,
        mode: MemoryRetrievalMode,
        terms: List<String> = emptyList(),
    ) = MemoryRetrievalHit(
        memory = AssistantMemory(
            id = id,
            content = content,
            timestamp = id.toLong(),
            significance = id.coerceIn(0, 10),
            pinned = pinned,
        ),
        score = score,
        matchedTerms = terms,
        mode = mode,
    )
}
