package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryRetrievalMode

data class MemoryRetrievalRequest(
    val assistantId: String,
    val mode: MemoryRetrievalMode,
    val query: String,
    val limit: Int = 5,
    val similarityThreshold: Float = 0.45f,
    val includeCore: Boolean = true,
    val includeEpisodes: Boolean = true,
)

enum class MemoryRetrievalOutcome {
    SUCCESS,
    EMPTY_QUERY,
    FAILED,
}

data class MemoryRetrievalHit(
    val memory: AssistantMemory,
    val score: Float,
    val matchedTerms: List<String> = emptyList(),
    val mode: MemoryRetrievalMode,
)

data class MemoryRetrievalResult(
    val hits: List<MemoryRetrievalHit>,
    val outcome: MemoryRetrievalOutcome,
)

/** Single mode switch used by all automatic memory injection entry points. */
class MemoryRetrievalService(
    private val memoryRepository: MemoryRepository,
) {
    suspend fun retrieve(request: MemoryRetrievalRequest): MemoryRetrievalResult {
        val limit = request.limit.coerceIn(0, 50)
        return when (request.mode) {
            MemoryRetrievalMode.OFF -> MemoryRetrievalResult(
                hits = withContext(Dispatchers.IO) {
                    memoryRepository.getMemoriesOfAssistant(request.assistantId).map {
                        MemoryRetrievalHit(
                            memory = it,
                            score = 0f,
                            mode = MemoryRetrievalMode.OFF,
                        )
                    }
                },
                outcome = MemoryRetrievalOutcome.SUCCESS,
            )

            MemoryRetrievalMode.KEYWORD -> {
                try {
                    val hits = memoryRepository.retrieveKeywordMemoriesWithScores(
                        assistantId = request.assistantId,
                        query = request.query,
                        limit = limit,
                        includeCore = request.includeCore,
                        includeEpisodes = request.includeEpisodes,
                    ).map { hit ->
                        MemoryRetrievalHit(
                            memory = hit.toAssistantMemory(),
                            score = hit.score,
                            matchedTerms = hit.matchedTerms,
                            mode = MemoryRetrievalMode.KEYWORD,
                        )
                    }
                    MemoryRetrievalResult(
                        hits = hits,
                        outcome = if (request.query.isBlank()) {
                            MemoryRetrievalOutcome.EMPTY_QUERY
                        } else {
                            // A non-blank keyword query with no matches is a successful empty result.
                            // Callers must not reuse a previous turn's topic in that case.
                            MemoryRetrievalOutcome.SUCCESS
                        },
                    )
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    MemoryRetrievalResult(
                        hits = pinnedHits(request),
                        outcome = MemoryRetrievalOutcome.FAILED,
                    )
                }
            }

            MemoryRetrievalMode.VECTOR -> {
                if (limit <= 0) {
                    return MemoryRetrievalResult(
                        hits = pinnedHits(request),
                        outcome = MemoryRetrievalOutcome.SUCCESS,
                    )
                }
                if (request.query.isBlank()) {
                    return MemoryRetrievalResult(
                        hits = pinnedHits(request),
                        outcome = MemoryRetrievalOutcome.EMPTY_QUERY,
                    )
                }
                try {
                    val hits = withContext(Dispatchers.IO) {
                        memoryRepository.retrieveRelevantMemoriesWithScores(
                            assistantId = request.assistantId,
                            query = request.query,
                            limit = limit,
                            similarityThreshold = request.similarityThreshold,
                            includeCore = request.includeCore,
                            includeEpisodes = request.includeEpisodes,
                        )
                    }.map { (memory, score) ->
                        MemoryRetrievalHit(
                            memory = memory,
                            score = score,
                            mode = MemoryRetrievalMode.VECTOR,
                        )
                    }
                    MemoryRetrievalResult(hits, MemoryRetrievalOutcome.SUCCESS)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    MemoryRetrievalResult(
                        hits = pinnedHits(request),
                        outcome = MemoryRetrievalOutcome.FAILED,
                    )
                }
            }
        }
    }

    private suspend fun pinnedHits(request: MemoryRetrievalRequest): List<MemoryRetrievalHit> {
        if (!request.includeCore) return emptyList()
        return withContext(Dispatchers.IO) {
            memoryRepository.getPinnedMemoriesOfAssistant(request.assistantId).map {
                MemoryRetrievalHit(
                    memory = it,
                    score = 1f,
                    mode = request.mode,
                )
            }
        }
    }

    private fun KeywordSearchHit.toAssistantMemory(): AssistantMemory = AssistantMemory(
        id = row.id,
        content = row.content,
        type = row.type,
        hasEmbedding = false,
        embeddingModelId = null,
        timestamp = row.timestamp,
        significance = row.significance,
        pinned = row.pinned,
    )
}
