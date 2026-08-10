package me.rerere.rikkahub.data.repository

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEmbeddingBackfillPolicyTest {
    @Test
    fun contentRelatedFailures_splitTheBatch() {
        assertTrue(IllegalStateException("Failed to create embedding: 400 bad input").isSplittableEmbeddingBatchFailure())
        assertTrue(IllegalStateException("Request failed with 413").isSplittableEmbeddingBatchFailure())
        assertTrue(IllegalStateException("Embedding batch size mismatch").isSplittableEmbeddingBatchFailure())
    }

    @Test
    fun serviceAndNetworkFailures_doNotFanOutIntoSingleRequests() {
        assertFalse(IllegalStateException("Failed to create embedding: 401 unauthorized").isSplittableEmbeddingBatchFailure())
        assertFalse(IllegalStateException("Failed to create embedding: 429 rate limited").isSplittableEmbeddingBatchFailure())
        assertFalse(IOException("connection reset").isSplittableEmbeddingBatchFailure())
    }

    @Test
    fun corruptCurrentEmbedding_isBackfilled() {
        assertTrue(needsEmbeddingBackfill("memory", "not-json", "model-a", "model-a"))
        assertTrue(needsEmbeddingBackfill("memory", "[]", "model-a", "model-a"))
        assertFalse(needsEmbeddingBackfill("memory", "[1.0,2.0]", "model-a", "model-a"))
    }
}
