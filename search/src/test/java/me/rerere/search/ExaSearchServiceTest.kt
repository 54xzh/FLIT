package me.rerere.search

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ExaSearchServiceTest {
    @Test
    fun buildRequestBodyUsesConfiguredSearchTypeAndHighlights() {
        val body = ExaSearchService.buildRequestBody(
            query = "latest Kotlin news",
            commonOptions = SearchCommonOptions(resultSize = 3),
            serviceOptions = SearchServiceOptions.ExaOptions(searchType = ExaSearchType.DEEP_LITE),
        )

        assertEquals("latest Kotlin news", body.stringAt("query"))
        assertEquals("deep-lite", body.stringAt("type"))
        assertEquals("3", body["numResults"].toString())
        assertEquals(true, body.jsonObjectAt("contents")?.get("highlights")?.jsonPrimitive?.boolean)
    }

    @Test
    fun exaOptionsDefaultsToAutoSearchType() {
        val options = SearchServiceOptions.ExaOptions()

        assertEquals(ExaSearchType.AUTO, options.searchType)
    }

    @Test
    fun decodesCurrentResponseShapeAndMapsHighlights() {
        val payload = """
            {
              "requestId": "req_123",
              "results": [
                {
                  "id": "https://example.com/article",
                  "url": "https://example.com/article",
                  "title": null,
                  "highlights": [
                    "First relevant highlight.",
                    "Second relevant highlight."
                  ]
                }
              ],
              "costDollars": {
                "total": 0.005,
                "search": {
                  "neural": 0.005
                }
              }
            }
        """.trimIndent()

        val response = SearchService.json.decodeFromString<ExaSearchService.ExaData>(payload)
        val items = ExaSearchService.run { response.toSearchResultItems() }

        assertEquals(1, items.size)
        assertEquals("https://example.com/article", items.first().title)
        assertEquals("https://example.com/article", items.first().url)
        assertEquals("First relevant highlight.\n\nSecond relevant highlight.", items.first().text)
    }

    @Test
    fun fallsBackToTextWhenHighlightsAreMissing() {
        val payload = """
            {
              "results": [
                {
                  "url": "https://example.com/article",
                  "title": "Example",
                  "text": "Fallback text"
                }
              ]
            }
        """.trimIndent()

        val response = SearchService.json.decodeFromString<ExaSearchService.ExaData>(payload)
        val items = ExaSearchService.run { response.toSearchResultItems() }

        assertEquals("Fallback text", items.first().text)
    }

    private fun JsonObject.stringAt(key: String): String? {
        return (this[key] as? JsonPrimitive)?.content
    }

    private fun JsonObject.jsonObjectAt(key: String): JsonObject? {
        return this[key] as? JsonObject
    }
}
