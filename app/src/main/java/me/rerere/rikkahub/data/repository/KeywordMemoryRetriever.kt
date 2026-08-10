package me.rerere.rikkahub.data.repository

import android.icu.text.BreakIterator
import android.icu.text.Normalizer2
import android.icu.text.Transliterator
import android.icu.util.ULocale
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
        ): KeywordMemoryIndex {
            val documents = rows.map { row ->
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
            )
        }
    }

    fun search(
        query: String,
        tokenizer: MemoryKeywordTokenizer,
        nowMillis: Long = System.currentTimeMillis(),
        limit: Int,
    ): List<KeywordSearchHit> {
        val rawQueryTokens = tokenizer.tokenizeWithKinds(query)
            .distinctBy { it.value }
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

        return documents.asSequence()
            .mapNotNull { document -> score(document, queryTokens, primaryQueryTerms, nowMillis) }
            .sortedWith(
                compareByDescending<KeywordSearchHit> { it.row.pinned }
                    .thenByDescending { it.score }
                    .thenByDescending { it.row.significance ?: 0 }
                    .thenByDescending { it.row.timestamp }
                    .thenBy { it.row.id },
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

internal class KeywordMemoryTokenizer : MemoryKeywordTokenizer {
    private val technicalTokenRegex = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}._:/@+#-]*[\\p{N}._:/@+#-]+[\\p{L}\\p{N}._:/@+#-]*")
    private val normalizer = Normalizer2.getNFKCCasefoldInstance()
    private val traditionalToSimplified = runCatching {
        Transliterator.getInstance("Traditional-Simplified")
    }.getOrNull()

    private val stopWords = setOf(
        "的", "了", "吗", "呢", "啊", "哦", "我", "你", "他", "她", "它", "我们", "你们", "他们",
        "这", "那", "是", "在", "和", "与", "及", "请", "问", "什么", "记得",
        "a", "an", "the", "is", "are", "was", "were", "be", "to", "of", "and", "or", "in", "on", "at", "for",
        "i", "you", "he", "she", "it", "we", "they", "do", "did", "does", "what", "which", "who", "please",
    )

    override fun normalize(value: String): String {
        return runCatching {
            val folded = normalizer.normalize(value)
            traditionalToSimplified?.let { transliterator ->
                synchronized(transliterator) { transliterator.transliterate(folded) }
            } ?: folded
        }.getOrElse { value }
    }

    override fun tokenizeWithKinds(value: String): List<KeywordToken> {
        val normalized = normalize(value)
        if (normalized.isBlank()) return emptyList()

        val tokens = mutableListOf<KeywordToken>()
        val iterator = BreakIterator.getWordInstance(ULocale.ROOT)
        iterator.setText(normalized)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val candidate = normalized.substring(start, end).trim()
            if (candidate.isNotBlank() && candidate.any { it.isLetterOrDigit() } && candidate !in stopWords) {
                if (candidate.containsTechnicalCharacter()) {
                    tokens += KeywordToken(candidate, 1.3f, KeywordTokenKind.TECHNICAL)
                } else {
                    tokens += KeywordToken(candidate, 1f, KeywordTokenKind.WORD)
                }
                if (candidate.all { it.isHanCharacter() }) {
                    val chars = candidate.toCharArray()
                    for (index in 0 until chars.lastIndex) {
                        val bigram = String(chars, index, 2)
                        if (bigram !in stopWords) {
                            tokens += KeywordToken(bigram, 0.35f, KeywordTokenKind.BIGRAM)
                        }
                    }
                }
            }
            start = end
            end = iterator.next()
        }

        // Word boundaries split many useful identifiers at punctuation (for example, gpt-4o).
        // Add the complete identifier as a stronger token while keeping ordinary word tokens.
        technicalTokenRegex.findAll(normalized).forEach { match ->
            val candidate = match.value
            if (candidate !in stopWords && candidate.containsTechnicalCharacter()) {
                tokens += KeywordToken(candidate, 1.3f, KeywordTokenKind.TECHNICAL)
            }
        }

        return tokens
            .groupBy { it.value to it.kind }
            .map { (_, grouped) -> grouped.maxBy { it.weight } }
    }

    private fun String.containsTechnicalCharacter(): Boolean =
        any { it.isDigit() || it in ".:_/@+#-" }

    private fun Char.isHanCharacter(): Boolean =
        this in '\u4E00'..'\u9FFF' || this in '\u3400'..'\u4DBF' || this in '\uF900'..'\uFAFF'
}
