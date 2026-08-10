package me.rerere.rikkahub.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.huaban.analysis.jieba.JiebaSegmenter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeywordMemoryTokenizerInstrumentedTest {
    @Test
    fun tokenizer_loads_resources_and_normalizes_chinese_text() = runBlocking {
        val tokenizer = KeywordMemoryTokenizer()

        tokenizer.prepare()
        assertTrue(tokenizer.isJiebaReady)
        JiebaSegmenter::class.java.classLoader?.getResourceAsStream("dict.txt").use { stream ->
            assertNotNull(stream)
        }
        JiebaSegmenter::class.java.classLoader?.getResourceAsStream("prob_emit.txt").use { stream ->
            assertNotNull(stream)
        }

        val tokens = tokenizer.tokenizeWithKinds(
            "但是 安卓手机很卡，鴻蒙手機非常流暢。太原更换最后",
        )
        val primaryValues = tokens
            .filter { it.kind != KeywordTokenKind.BIGRAM }
            .map { it.value }

        assertEquals(
            listOf("安卓", "手机", "卡", "鸿蒙", "流畅", "太原", "更换", "最后"),
            primaryValues,
        )
        assertFalse(primaryValues.any { it in setOf("很", "非常", "安", "卓") })
    }
}
