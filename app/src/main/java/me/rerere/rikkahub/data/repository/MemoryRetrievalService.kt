package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.ai.rag.EmbeddingService
import me.rerere.rikkahub.data.ai.rag.EmbeddingTimeoutPolicy
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
    val timeoutMillis: Long? = null,
    val recordAccess: Boolean = true,
    val includePinnedOnFailure: Boolean = true,
)

enum class MemoryRetrievalOutcome {
    SUCCESS,
    PARTIAL,
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
    private val embeddingService: EmbeddingService,
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
                        recordAccess = request.recordAccess,
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
                        hits = if (request.includePinnedOnFailure) pinnedHits(request) else emptyList(),
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
                val timeoutMillis = request.timeoutMillis
                    ?: embeddingService.getRetrievalTimeoutMillis()
                val startedAtNanos = System.nanoTime()
                val fallbackPinned = if (request.includePinnedOnFailure) {
                    withTimeoutOrNull(timeoutMillis) { pinnedHitsOnFailure(request) }.orEmpty()
                } else {
                    emptyList()
                }
                val remainingMillis = remainingTimeoutMillis(startedAtNanos, timeoutMillis)
                if (remainingMillis <= 0L) {
                    return MemoryRetrievalResult(
                        hits = fallbackPinned,
                        outcome = MemoryRetrievalOutcome.FAILED,
                    )
                }
                try {
                    memoryRepository.scheduleEmbeddingBackfillIfNeeded(
                        assistantId = request.assistantId,
                        includeCore = request.includeCore,
                        includeEpisodes = request.includeEpisodes,
                    )
                    val scoredHits = withTimeoutOrNull(
                        remainingMillis
                    ) {
                        val queryEmbedding = embeddingService.embed(
                            text = request.query,
                            assistantId = request.assistantId,
                            source = AIRequestSource.MEMORY_RETRIEVAL,
                            timeoutPolicy = EmbeddingTimeoutPolicy.RETRIEVAL,
                        )
                        withContext(Dispatchers.IO) {
                            memoryRepository.retrieveRelevantMemoriesWithScoresByEmbedding(
                                assistantId = request.assistantId,
                                queryEmbedding = queryEmbedding,
                                limit = limit,
                                similarityThreshold = request.similarityThreshold,
                                includeCore = request.includeCore,
                                includeEpisodes = request.includeEpisodes,
                                recordAccess = request.recordAccess,
                            )
                        }
                    } ?: return MemoryRetrievalResult(
                        hits = fallbackPinned,
                        outcome = MemoryRetrievalOutcome.FAILED,
                    )
                    val hits = scoredHits.map { (memory, score) ->
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
                        hits = fallbackPinned,
                        outcome = MemoryRetrievalOutcome.FAILED,
                    )
                }
            }

            MemoryRetrievalMode.HYBRID -> retrieveHybrid(request, limit)
        }
    }

    private suspend fun retrieveHybrid(
        request: MemoryRetrievalRequest,
        limit: Int,
    ): MemoryRetrievalResult {
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

        val candidateLimit = hybridCandidateLimit(limit)
        val branchTimeoutMillis = request.timeoutMillis
            ?: embeddingService.getRetrievalTimeoutMillis()
        val startedAtNanos = System.nanoTime()
        val (keywordResult, vectorResult, pinnedFallback) = coroutineScope {
            val pinned = if (request.includePinnedOnFailure) {
                async {
                    withTimeoutOrNull(branchTimeoutMillis) { pinnedHitsOnFailure(request) }.orEmpty()
                }
            } else {
                null
            }
            val keyword = async {
                withTimeoutOrNull(branchTimeoutMillis) {
                    retrieve(
                        request.copy(
                            mode = MemoryRetrievalMode.KEYWORD,
                            limit = candidateLimit,
                            recordAccess = false,
                            includePinnedOnFailure = false,
                        )
                    )
                } ?: MemoryRetrievalResult(
                    hits = emptyList(),
                    outcome = MemoryRetrievalOutcome.FAILED,
                )
            }
            val vector = async {
                retrieve(
                    request.copy(
                        mode = MemoryRetrievalMode.VECTOR,
                        limit = candidateLimit,
                        timeoutMillis = branchTimeoutMillis,
                        recordAccess = false,
                        includePinnedOnFailure = false,
                    )
                )
            }
            val keywordResult = keyword.await()
            val vectorResult = vector.await()
            val bothFailed =
                keywordResult.outcome == MemoryRetrievalOutcome.FAILED &&
                    vectorResult.outcome == MemoryRetrievalOutcome.FAILED
            val pinnedFallback = if (bothFailed) {
                pinned?.await().orEmpty()
            } else {
                pinned?.cancel()
                emptyList()
            }
            Triple(keywordResult, vectorResult, pinnedFallback)
        }

        val keywordFailed = keywordResult.outcome == MemoryRetrievalOutcome.FAILED
        val vectorFailed = vectorResult.outcome == MemoryRetrievalOutcome.FAILED
        val outcome = hybridRetrievalOutcome(keywordResult.outcome, vectorResult.outcome)
        if (outcome == MemoryRetrievalOutcome.FAILED) {
            return MemoryRetrievalResult(
                hits = pinnedFallback,
                outcome = outcome,
            )
        }

        val hits = mergeHybridHits(
            keywordHits = if (keywordFailed) emptyList() else keywordResult.hits,
            vectorHits = if (vectorFailed) emptyList() else vectorResult.hits,
            limit = limit,
        )
        if (request.recordAccess) {
            val remainingMillis = remainingTimeoutMillis(startedAtNanos, branchTimeoutMillis)
            if (remainingMillis > 0L) {
                withTimeoutOrNull(remainingMillis) {
                    memoryRepository.updateLastAccessed(hits.map { it.memory })
                }
            }
        }
        return MemoryRetrievalResult(
            hits = hits,
            outcome = outcome,
        )
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

    private suspend fun pinnedHitsOnFailure(request: MemoryRetrievalRequest): List<MemoryRetrievalHit> =
        try {
            pinnedHits(request)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            emptyList()
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

private fun remainingTimeoutMillis(startedAtNanos: Long, totalMillis: Long): Long {
    val elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L
    return totalMillis - elapsedMillis
}

private data class HybridCandidate(
    val hit: MemoryRetrievalHit,
    val keywordRank: Int? = null,
    val vectorRank: Int? = null,
) {
    val score: Float
        get() = reciprocalRank(keywordRank) + reciprocalRank(vectorRank)

    val matchedByBoth: Boolean
        get() = keywordRank != null && vectorRank != null
}

internal fun mergeHybridHits(
    keywordHits: List<MemoryRetrievalHit>,
    vectorHits: List<MemoryRetrievalHit>,
    limit: Int,
): List<MemoryRetrievalHit> {
    val seenPinnedContent = mutableSetOf<String>()
    val pinned = (keywordHits + vectorHits)
        .asSequence()
        .filter { it.memory.pinned }
        .distinctBy { it.memory.identityKey() }
        .sortedWith(
            compareByDescending<MemoryRetrievalHit> { it.memory.timestamp }
                .thenBy { it.memory.id },
        )
        .filter { seenPinnedContent.add(it.memory.normalizedContentKey()) }
        .toList()

    val keywordDynamic = keywordHits.filterNot { it.memory.pinned }
    val vectorDynamic = vectorHits.filterNot { it.memory.pinned }
    val candidates = LinkedHashMap<String, HybridCandidate>()
    keywordDynamic.forEachIndexed { index, hit ->
        candidates[hit.memory.identityKey()] = HybridCandidate(
            hit = hit,
            keywordRank = index + 1,
        )
    }
    vectorDynamic.forEachIndexed { index, hit ->
        val key = hit.memory.identityKey()
        val existing = candidates[key]
        candidates[key] = if (existing == null) {
            HybridCandidate(hit = hit, vectorRank = index + 1)
        } else {
            existing.copy(vectorRank = index + 1)
        }
    }

    val normalizedPinnedContent = pinned.mapTo(mutableSetOf()) { it.memory.normalizedContentKey() }
    val seenContent = normalizedPinnedContent
    val dynamic = candidates.values
        .sortedWith(
            compareByDescending<HybridCandidate> { it.score }
                .thenByDescending { it.matchedByBoth }
                .thenBy { it.keywordRank ?: Int.MAX_VALUE }
                .thenBy { it.vectorRank ?: Int.MAX_VALUE }
                .thenByDescending { it.hit.memory.significance ?: 0 }
                .thenByDescending { it.hit.memory.timestamp }
                .thenBy { it.hit.memory.id },
        )
        .mapNotNull { candidate ->
            val contentKey = candidate.hit.memory.normalizedContentKey()
            if (!seenContent.add(contentKey)) return@mapNotNull null
            candidate.hit.copy(
                score = candidate.score,
                mode = MemoryRetrievalMode.HYBRID,
            )
        }
        .take(limit.coerceAtLeast(0))

    return pinned.map { it.copy(score = 1f, mode = MemoryRetrievalMode.HYBRID) } + dynamic
}

private fun reciprocalRank(rank: Int?): Float =
    if (rank == null) 0f else 1f / (10f + rank)

internal fun hybridCandidateLimit(limit: Int): Int =
    maxOf(15, limit.coerceAtLeast(0) * 3).coerceAtMost(50)

internal fun hybridRetrievalOutcome(
    keywordOutcome: MemoryRetrievalOutcome,
    vectorOutcome: MemoryRetrievalOutcome,
): MemoryRetrievalOutcome = when {
    keywordOutcome == MemoryRetrievalOutcome.FAILED && vectorOutcome == MemoryRetrievalOutcome.FAILED ->
        MemoryRetrievalOutcome.FAILED
    keywordOutcome == MemoryRetrievalOutcome.FAILED || vectorOutcome == MemoryRetrievalOutcome.FAILED ->
        MemoryRetrievalOutcome.PARTIAL
    else -> MemoryRetrievalOutcome.SUCCESS
}

private fun AssistantMemory.identityKey(): String = "$type:$id"

private fun AssistantMemory.normalizedContentKey(): String {
    val normalized = content.trim().lowercase().replace(Regex("\\s+"), " ")
    return normalized.ifBlank { identityKey() }
}
