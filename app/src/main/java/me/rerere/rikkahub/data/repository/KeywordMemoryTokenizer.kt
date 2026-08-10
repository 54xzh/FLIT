package me.rerere.rikkahub.data.repository

import android.icu.text.BreakIterator
import android.icu.text.Normalizer2
import android.icu.text.Transliterator
import android.icu.util.ULocale
import android.util.Log
import com.huaban.analysis.jieba.JiebaSegmenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal fun interface KeywordTextNormalizer {
    fun normalize(value: String): String
}

internal fun interface KeywordWordBreaker {
    fun breakWords(value: String): List<String>
}

internal class IcuKeywordTextNormalizer : KeywordTextNormalizer {
    private val normalizer = Normalizer2.getNFKCCasefoldInstance()
    private val traditionalToSimplified = runCatching {
        Transliterator.getInstance("Traditional-Simplified")
    }.getOrNull()

    override fun normalize(value: String): String {
        return runCatching {
            val folded = normalizer.normalize(value)
            traditionalToSimplified?.let { transliterator ->
                synchronized(transliterator) { transliterator.transliterate(folded) }
            } ?: folded
        }.getOrElse { value }
    }
}

internal class IcuKeywordWordBreaker : KeywordWordBreaker {
    override fun breakWords(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        val iterator = BreakIterator.getWordInstance(ULocale.ROOT)
        iterator.setText(value)
        return buildList {
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                val candidate = value.substring(start, end).trim()
                if (candidate.isNotBlank() && candidate.any { it.isLetterOrDigit() }) {
                    add(candidate)
                }
                start = end
                end = iterator.next()
            }
        }
    }
}

internal class KeywordMemoryTokenizer(
    private val textNormalizer: KeywordTextNormalizer = IcuKeywordTextNormalizer(),
    private val wordBreaker: KeywordWordBreaker = IcuKeywordWordBreaker(),
    private val stopWordsLoader: () -> Set<String> = ::loadKeywordStopWords,
    private val segmenterFactory: () -> JiebaSegmenter = ::JiebaSegmenter,
    private val segmentWords: (JiebaSegmenter, String) -> List<String> = { segmenter, value ->
        segmenter.sentenceProcess(value)
    },
) : MemoryKeywordTokenizer {
    private sealed interface SegmenterState {
        data object Uninitialized : SegmenterState
        data class Ready(val segmenter: JiebaSegmenter) : SegmenterState
        data object Failed : SegmenterState
    }

    private val prepareMutex = Mutex()
    private val backendRevision = AtomicInteger(0)
    private val runtimeFailureLogged = AtomicBoolean(false)

    @Volatile
    private var segmenterState: SegmenterState = SegmenterState.Uninitialized

    @Volatile
    private var stopWords: Set<String> = FALLBACK_STOP_WORDS

    override val revision: Int
        get() = backendRevision.get()

    internal val isJiebaReady: Boolean
        get() = segmenterState is SegmenterState.Ready

    override suspend fun prepare() {
        if (segmenterState != SegmenterState.Uninitialized) return
        withContext(Dispatchers.IO) {
            prepareMutex.withLock {
                if (segmenterState != SegmenterState.Uninitialized) return@withLock
                stopWords = runCatching(stopWordsLoader)
                    .onFailure { error -> Log.w(TAG, "Unable to load keyword stop words", error) }
                    .getOrDefault(FALLBACK_STOP_WORDS)
                segmenterState = try {
                    SegmenterState.Ready(segmenterFactory())
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    Log.w(TAG, "Unable to initialize Jieba tokenizer; using ICU fallback", error)
                    SegmenterState.Failed
                }
                backendRevision.incrementAndGet()
            }
        }
    }

    override fun normalize(value: String): String = textNormalizer.normalize(value)

    override fun tokenizeWithKinds(value: String): List<KeywordToken> {
        val normalized = normalize(value)
        if (normalized.isBlank()) return emptyList()

        val tokens = mutableListOf<KeywordToken>()
        var cursor = 0
        technicalTokenRegex.findAll(normalized)
            .mapNotNull { match ->
                match.value.normalizedTechnicalToken()?.let { word -> match to word }
            }
            .forEach { (match, word) ->
                addOrdinaryTokens(normalized.substring(cursor, match.range.first), tokens)
                addTechnicalToken(word, tokens)
                cursor = match.range.last + 1
            }
        addOrdinaryTokens(normalized.substring(cursor), tokens)

        return tokens
            .groupBy { it.value to it.kind }
            .map { (_, grouped) -> grouped.maxBy { it.weight } }
    }

    private fun addOrdinaryTokens(
        value: String,
        destination: MutableList<KeywordToken>,
    ) {
        if (value.isBlank()) return
        val candidates = segmentWithJieba(value) ?: wordBreaker.breakWords(value)
        candidates.forEach { candidate ->
            splitDegreeWord(candidate).forEach { word ->
                addWordToken(word, destination)
            }
        }
    }

    private fun segmentWithJieba(value: String): List<String>? {
        val state = segmenterState
        if (state !is SegmenterState.Ready) return null
        return try {
            synchronized(state.segmenter) {
                segmentWords(state.segmenter, value)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            synchronized(this) {
                if (segmenterState === state) {
                    segmenterState = SegmenterState.Failed
                    backendRevision.incrementAndGet()
                }
            }
            if (runtimeFailureLogged.compareAndSet(false, true)) {
                Log.w(TAG, "Jieba tokenization failed; using ICU fallback", error)
            }
            null
        }
    }

    private fun splitDegreeWord(candidate: String): List<String> {
        val trimmed = candidate.trim()
        if (trimmed.length < 2 || !trimmed.all { it.isHanCharacter() }) return listOf(trimmed)
        val prefix = DEGREE_WORDS.firstOrNull { degreeWord ->
            trimmed.length > degreeWord.length && trimmed.startsWith(degreeWord)
        } ?: return listOf(trimmed)
        val broken = wordBreaker.breakWords(trimmed)
        if (broken.firstOrNull() != prefix || broken.joinToString(separator = "") != trimmed) {
            return listOf(trimmed)
        }
        return broken.drop(1)
    }

    private fun addWordToken(
        candidate: String,
        destination: MutableList<KeywordToken>,
    ) {
        val word = candidate.trim()
        if (word.isBlank() || word.none { it.isLetterOrDigit() } || word in stopWords) return
        destination += KeywordToken(word, WORD_WEIGHT, KeywordTokenKind.WORD)
        if (word.length >= MIN_BIGRAM_SOURCE_LENGTH && word.all { it.isHanCharacter() }) {
            val chars = word.toCharArray()
            for (index in 0 until chars.lastIndex) {
                val bigram = String(chars, index, 2)
                if (bigram !in stopWords) {
                    destination += KeywordToken(bigram, BIGRAM_WEIGHT, KeywordTokenKind.BIGRAM)
                }
            }
        }
    }

    private fun addTechnicalToken(
        candidate: String,
        destination: MutableList<KeywordToken>,
    ) {
        if (candidate !in stopWords) {
            destination += KeywordToken(candidate, TECHNICAL_WEIGHT, KeywordTokenKind.TECHNICAL)
        }
    }

    private fun String.normalizedTechnicalToken(): String? {
        val withoutTrailingSeparators = trimEnd { it in TECHNICAL_TRAILING_SEPARATORS }
        return withoutTrailingSeparators.takeIf { it.containsTechnicalCharacter() }
    }

    private fun String.containsTechnicalCharacter(): Boolean =
        any { it.isDigit() || it in TECHNICAL_CHARACTERS }

    private fun Char.isHanCharacter(): Boolean =
        this in '\u4E00'..'\u9FFF' || this in '\u3400'..'\u4DBF' || this in '\uF900'..'\uFAFF'

    companion object {
        private const val TAG = "KeywordMemoryTokenizer"
        private const val STOP_WORDS_RESOURCE = "memory_retrieval_stopwords.txt"
        private const val WORD_WEIGHT = 1f
        private const val TECHNICAL_WEIGHT = 1.3f
        private const val BIGRAM_WEIGHT = 0.35f
        private const val MIN_BIGRAM_SOURCE_LENGTH = 3
        private const val TECHNICAL_CHARACTERS = ".:_/@+#-"
        private const val TECHNICAL_TRAILING_SEPARATORS = ".:/@-"

        private val technicalTokenRegex = Regex("[A-Za-z0-9][A-Za-z0-9._:/@+#-]*")
        private val DEGREE_WORDS = listOf(
            "非常", "比较", "特别", "十分", "极其", "过于", "格外",
            "很", "太", "挺", "更", "最",
        )
        private val FALLBACK_STOP_WORDS = setOf(
            "的", "了", "吗", "呢", "啊", "哦", "我", "你", "他", "她", "它", "我们", "你们", "他们",
            "这", "那", "是", "在", "和", "与", "及", "请", "问", "什么", "记得",
            "很", "太", "挺", "更", "最", "非常", "比较", "特别", "十分", "极其", "过于", "格外",
            "a", "an", "the", "is", "are", "was", "were", "be", "to", "of", "and", "or", "in", "on", "at", "for",
            "i", "you", "he", "she", "it", "we", "they", "do", "did", "does", "what", "which", "who", "please",
        )

        private fun loadKeywordStopWords(): Set<String> {
            val stream = KeywordMemoryTokenizer::class.java.classLoader
                ?.getResourceAsStream(STOP_WORDS_RESOURCE)
                ?: error("Missing $STOP_WORDS_RESOURCE")
            return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.map(String::trim)
                    .filter { line -> line.isNotEmpty() && !line.startsWith('#') }
                    .toSet()
            }
        }
    }
}
