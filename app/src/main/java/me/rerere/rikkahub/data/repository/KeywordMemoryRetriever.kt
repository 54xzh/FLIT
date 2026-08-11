package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.dao.MemoryRetrievalRow
import kotlin.math.ln

internal enum class KeywordTokenKind {
    WORD,
    TECHNICAL,
    BIGRAM,
}

internal data class KeywordToken(
    val value: String,
    val weight: Float,
    val kind: KeywordTokenKind,
)

internal data class KeywordDocument(
    val row: MemoryRetrievalRow,
    val tokens: List<KeywordToken>,
    val termFrequency: Map<String, Float>,
    val primaryTerms: List<String>,
    val technicalTerms: Set<String>,
    val length: Float,
)

internal data class KeywordSearchHit(
    val row: MemoryRetrievalRow,
    val score: Float,
    val matchedTerms: List<String>,
)

internal interface MemoryKeywordTokenizer {
    val revision: Int get() = 0
    suspend fun prepare() = Unit
    fun normalize(value: String): String
    fun tokenizeWithKinds(value: String): List<KeywordToken>
}

/**
 * Local lexical retrieval. It is intentionally independent of Room and the embedding service so
 * that ranking can be tested with a deterministic tokenizer.
 */
internal class KeywordMemoryIndex private constructor(
    private val documents: List<KeywordDocument>,
    private val documentFrequency: Map<String, Int>,
    private val averageDocumentLength: Float,
    val estimatedTermCount: Int,
    val tokenizerRevision: Int,
) {
    companion object {
        private const val BM25_K1 = 1.2f
        private const val BM25_B = 0.75f
        private const val MAX_QUERY_TERMS = 32
        private const val COVERAGE_BOOST = 0.20f
        private const val PHRASE_BOOST = 0.15f
        private const val TECHNICAL_BOOST = 0.10f
        private const val CORE_BOOST = 0.05f
        private const val EPISODE_SIGNIFICANCE_BOOST = 0.05f
        private const val EPISODE_RECENCY_BOOST = 0.05f
        private const val EPISODE_RECENCY_HALF_LIFE_DAYS = 7.0

        fun build(
            rows: List<MemoryRetrievalRow>,
            tokenizer: MemoryKeywordTokenizer,
            checkCancelled: () -> Unit = {},
        ): KeywordMemoryIndex {
            var revision: Int
            var index: KeywordMemoryIndex
            do {
                revision = tokenizer.revision
                checkCancelled()
                index = buildOnce(rows, tokenizer, revision, checkCancelled)
            } while (revision != tokenizer.revision)
            return index
        }

        private fun buildOnce(
            rows: List<MemoryRetrievalRow>,
            tokenizer: MemoryKeywordTokenizer,
            tokenizerRevision: Int,
            checkCancelled: () -> Unit,
        ): KeywordMemoryIndex {
            val documents = rows.map { row ->
                checkCancelled()
                val tokens = tokenizer.tokenizeWithKinds(row.content)
                val termFrequency = buildMap {
                    tokens.forEach { token ->
                        put(token.value, (get(token.value) ?: 0f) + token.weight)
                    }
                }
                KeywordDocument(
                    row = row,
                    tokens = tokens,
                    termFrequency = termFrequency,
                    primaryTerms = tokens
                        .asSequence()
                        .filter { it.kind != KeywordTokenKind.BIGRAM }
                        .map { it.value }
                        .distinct()
                        .toList(),
                    technicalTerms = tokens
                        .asSequence()
                        .filter { it.kind == KeywordTokenKind.TECHNICAL }
                        .map { it.value }
                        .toSet(),
                    length = tokens.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1f),
                )
            }
            val documentFrequency = buildMap {
                documents.forEach { document ->
                    checkCancelled()
                    document.termFrequency.keys.forEach { term ->
                        put(term, (get(term) ?: 0) + 1)
                    }
                }
            }
            val averageDocumentLength = documents
                .map { it.length }
                .average()
                .takeIf { it.isFinite() }
                ?.toFloat()
                ?.coerceAtLeast(1f)
                ?: 1f
            val estimatedTermCount = documents.sumOf { it.termFrequency.size }
            return KeywordMemoryIndex(
                documents = documents,
                documentFrequency = documentFrequency,
                averageDocumentLength = averageDocumentLength,
                estimatedTermCount = estimatedTermCount,
                tokenizerRevision = tokenizerRevision,
            )
        }
    }

    fun search(
        query: String,
        tokenizer: MemoryKeywordTokenizer,
        nowMillis: Long = System.currentTimeMillis(),
        limit: Int,
        checkCancelled: () -> Unit = {},
    ): List<KeywordSearchHit> {
        val rawQueryTokens = tokenizer.tokenizeWithKinds(query)
            .groupBy { it.value }
            .map { (_, tokens) ->
                tokens.maxWith(
                    compareBy<KeywordToken> { it.kind.queryPriority }
                        .thenBy { it.weight },
                )
            }
        val primaryQueryTokens = rawQueryTokens.filter { it.kind != KeywordTokenKind.BIGRAM }
        val bigramQueryTokens = rawQueryTokens.filter { it.kind == KeywordTokenKind.BIGRAM }
        val selectedQueryValues = primaryQueryTokens
            .sortedByDescending { token -> inverseDocumentFrequency(token.value) }
            .take(MAX_QUERY_TERMS)
            .map { it.value }
            .toMutableSet()
        if (selectedQueryValues.size < MAX_QUERY_TERMS) {
            bigramQueryTokens
                .sortedByDescending { token -> inverseDocumentFrequency(token.value) }
                .take(MAX_QUERY_TERMS - selectedQueryValues.size)
                .forEach { selectedQueryValues += it.value }
        }
        val queryTokens = rawQueryTokens.filter { it.value in selectedQueryValues }
        if (queryTokens.isEmpty() || limit <= 0) return emptyList()

        val primaryQueryTerms = queryTokens
            .asSequence()
            .filter { it.kind != KeywordTokenKind.BIGRAM }
            .map { it.value }
            .distinct()
            .toList()
        if (primaryQueryTerms.isEmpty()) return emptyList()

        val scoreComparator = compareByDescending<KeywordSearchHit> { it.row.pinned }
            .thenByDescending { it.score }
            .thenByDescending { it.row.significance ?: 0 }
            .thenByDescending { it.row.timestamp }
            .thenBy { it.row.id }
        var comparisonCount = 0
        val cancellableComparator = Comparator<KeywordSearchHit> { left, right ->
            if (++comparisonCount % 1024 == 0) checkCancelled()
            scoreComparator.compare(left, right)
        }
        return documents.asSequence()
            .mapNotNull { document ->
                checkCancelled()
                score(document, queryTokens, primaryQueryTerms, nowMillis)
            }
            .sortedWith(
                cancellableComparator,
            )
            .toList()
            .let { hits ->
                val pinned = hits.filter { it.row.pinned }
                val dynamic = hits.filterNot { it.row.pinned }.take(limit.coerceAtLeast(0))
                (pinned + dynamic)
                    .distinctBy { it.row.id }
                    .sortedWith(
                        compareByDescending<KeywordSearchHit> { it.row.pinned }
                            .thenByDescending { it.score }
                            .thenByDescending { it.row.timestamp }
                            .thenBy { it.row.id },
                    )
            }
    }

    private fun score(
        document: KeywordDocument,
        queryTokens: List<KeywordToken>,
        primaryQueryTerms: List<String>,
        nowMillis: Long,
    ): KeywordSearchHit? {
        var bm25 = 0f
        val matchedTerms = mutableListOf<String>()
        var matchedPrimaryCount = 0
        var hasTechnicalMatch = false

        queryTokens.forEach { queryToken ->
            val termFrequency = document.termFrequency[queryToken.value] ?: return@forEach
            val idf = inverseDocumentFrequency(queryToken.value)
            val denominator = termFrequency + BM25_K1 * (
                1f - BM25_B + BM25_B * document.length / averageDocumentLength
            )
            bm25 += idf * (termFrequency * (BM25_K1 + 1f) / denominator) * queryToken.weight
            if (queryToken.kind != KeywordTokenKind.BIGRAM) {
                matchedTerms += queryToken.value
                matchedPrimaryCount++
            }
            if (queryToken.kind == KeywordTokenKind.TECHNICAL && queryToken.value in document.technicalTerms) {
                hasTechnicalMatch = true
            }
        }

        if (bm25 <= 0f || matchedPrimaryCount == 0) return null

        val coverage = matchedPrimaryCount.toFloat() / primaryQueryTerms.size.coerceAtLeast(1)
        val phraseBoost = if (containsOrderedPhrase(document.primaryTerms, primaryQueryTerms)) {
            PHRASE_BOOST
        } else {
            0f
        }
        val technicalBoost = if (hasTechnicalMatch) TECHNICAL_BOOST else 0f
        val metadataBoost = if (document.row.pinned) {
            1f
        } else if (document.row.type == 0) {
            1f + CORE_BOOST
        } else {
            val significance = (document.row.significance ?: 0).coerceIn(0, 10) / 10f
            val ageDays = ((nowMillis - document.row.timestamp).coerceAtLeast(0L) / 86_400_000.0)
            val recency = (1.0 / (1.0 + ageDays / EPISODE_RECENCY_HALF_LIFE_DAYS)).toFloat()
            1f + significance * EPISODE_SIGNIFICANCE_BOOST + recency * EPISODE_RECENCY_BOOST
        }

        return KeywordSearchHit(
            row = document.row,
            score = bm25 * (1f + coverage.coerceIn(0f, 1f) * COVERAGE_BOOST + phraseBoost + technicalBoost) * metadataBoost,
            matchedTerms = matchedTerms.distinct().take(8),
        )
    }

    private fun inverseDocumentFrequency(term: String): Float {
        val totalDocuments = documents.size.coerceAtLeast(1)
        val documentCount = documentFrequency[term] ?: 0
        return ln(1.0 + (totalDocuments - documentCount + 0.5) / (documentCount + 0.5)).toFloat()
    }

    private fun containsOrderedPhrase(documentTerms: List<String>, queryTerms: List<String>): Boolean {
        if (queryTerms.size < 2) return false
        if (queryTerms.size > documentTerms.size) return false
        return documentTerms.windowed(queryTerms.size).any { window ->
            window == queryTerms
        }
    }
}

private val KeywordTokenKind.queryPriority: Int
    get() = when (this) {
        KeywordTokenKind.BIGRAM -> 0
        KeywordTokenKind.WORD -> 1
        KeywordTokenKind.TECHNICAL -> 2
    }
