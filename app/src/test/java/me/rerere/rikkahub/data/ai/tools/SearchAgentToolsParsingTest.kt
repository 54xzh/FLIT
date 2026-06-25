package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SearchAgentToolsParsingTest {
    @Test
    fun parseSearchAgentJsonObject_toleratesRawNewlinesAndQuotesInsideStrings() {
        val text = """
            {
              "summary": "第一行
            第二行里有"引号"[citation,example](source-1)",
              "sources": [
                {
                  "id": "source-1",
                  "title": "Example",
                  "url": "https://example.com",
                  "snippet": "引用说明"
                }
              ],
              "notes": []
            }
        """.trimIndent()

        val parsed = parseSearchAgentJsonObject(text)

        assertNotNull(parsed)
        assertEquals(
            "第一行\n第二行里有\"引号\"[citation,example](source-1)",
            parsed?.get("summary")?.jsonPrimitive?.content,
        )
        assertEquals(1, parsed?.get("sources")?.jsonArray?.size)
    }

    @Test
    fun parseSearchAgentJsonObject_readsJsonInsideCodeFence() {
        val text = """
            先给结论：
            ```json
            {
              "summary": "纯文本总结[citation,example](source-1)",
              "sources": [
                {
                  "id": "source-1",
                  "title": "Example",
                  "url": "https://example.com",
                  "snippet": "引用说明"
                }
              ],
              "notes": ["无"]
            }
            ```
        """.trimIndent()

        val parsed = parseSearchAgentJsonObject(text)

        assertNotNull(parsed)
        assertEquals("纯文本总结[citation,example](source-1)", parsed?.get("summary")?.jsonPrimitive?.content)
        assertEquals("无", parsed?.get("notes")?.jsonArray?.firstOrNull()?.jsonPrimitive?.content)
    }
}
