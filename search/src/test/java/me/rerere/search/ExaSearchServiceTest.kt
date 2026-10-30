package me.rerere.search

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExaSearchServiceTest {
    @Test
    fun buildRequestBodyUsesConfiguredSearchTypeAndHighlights() {
        val body = ExaSearchService.buildRequestBody(
            query = "latest Kotlin news",
            commonOptions = SearchCommonOptions(resultSize = 3),
            serviceOptions = SearchServiceOptions.ExaOptions(
                searchType = ExaSearchType.DEEP_LITE,
                dynamicHighlights = true,
            ),
        )

        assertEquals("latest Kotlin news", body.stringAt("query"))
        assertEquals("deep-lite", body.stringAt("type"))
        assertEquals("3", body["numResults"].toString())
        assertEquals(true, body.jsonObjectAt("contents")?.jsonObjectAt("highlights")?.get("dynamic")?.jsonPrimitive?.boolean)
        val request = ExaSearchService.buildSearchRequest(body, SearchServiceOptions.ExaOptions(dynamicHighlights = true))
        assertEquals("dynamic-highlights-2026-08-28", request.header("Exa-Beta"))
    }

    @Test
    fun defaultOptionsUseRegularHighlightsWithoutBetaHeader() {
        val options = SearchServiceOptions.ExaOptions()
        val body = ExaSearchService.buildRequestBody("Kotlin", SearchCommonOptions(), options)

        assertEquals(true, body.jsonObjectAt("contents")?.get("highlights")?.jsonPrimitive?.boolean)
        assertNull(ExaSearchService.buildSearchRequest(body, options).header("Exa-Beta"))
    }

    @Test
    fun oldExaSettingsDisableDynamicHighlightsAndEnabledSettingSurvivesSerialization() {
        val oldSettings = """{"apiKey":"test-key","searchType":"fast"}"""
        val options = SearchService.json.decodeFromString<SearchServiceOptions.ExaOptions>(oldSettings)

        assertFalse(options.dynamicHighlights)
        assertEquals(ExaSearchType.FAST, options.searchType)
        val saved = SearchService.json.encodeToString(options.copy(dynamicHighlights = true))
        assertTrue(SearchService.json.decodeFromString<SearchServiceOptions.ExaOptions>(saved).dynamicHighlights)
    }

    @Test
    fun scrapeRequestsFullTextAtTopLevel() {
        val body = ExaSearchService.buildScrapeRequestBody("https://example.com/page")

        assertEquals("""["https://example.com/page"]""", body["urls"].toString())
        assertEquals(true, body["text"]?.jsonPrimitive?.boolean)
        assertNull(body["contents"])
        assertNull(body["highlights"])
        val schema = ExaSearchService.scrapingParameters as me.rerere.ai.core.InputSchema.Obj
        assertTrue(schema.required.orEmpty().contains("url"))
    }

    @Test
    fun scrapeReturnsFullTextAndMetadataInsteadOfHighlights() {
        val payload = """
            {
              "results": [{
                "url": "https://example.com/page",
                "title": "Example",
                "text": "Full page content",
                "highlights": ["Short excerpt"]
              }],
              "statuses": [{"id": "https://example.com/page", "status": "success", "source": "cached"}]
            }
        """.trimIndent()
        val response = SearchService.json.decodeFromString<ExaSearchService.ExaData>(payload)
        val result = ExaSearchService.run { response.toScrapedResult() }

        assertEquals("Full page content", result.urls.single().content)
        assertEquals("https://example.com/page", result.urls.single().url)
        assertEquals("Example", result.urls.single().metadata?.title)
    }

    @Test
    fun scrapeReportsPerUrlFailureEvenWhenHttpRequestSucceeded() {
        val payload = """
            {"results": [], "statuses": [{"id": "https://example.com", "status": "error",
              "error": {"tag": "CRAWL_NOT_FOUND", "httpStatusCode": 404}}]}
        """.trimIndent()
        val response = SearchService.json.decodeFromString<ExaSearchService.ExaData>(payload)

        val error = assertThrows(IllegalStateException::class.java) {
            ExaSearchService.run { response.toScrapedResult() }
        }
        assertTrue(error.message.orEmpty().contains("CRAWL_NOT_FOUND"))
    }

    @Test
    fun scrapeRejectsBlankContent() {
        val response = ExaSearchService.ExaData(results = listOf(
            ExaSearchService.ExaResult(url = "https://example.com", text = "  "),
        ))
        assertThrows(IllegalStateException::class.java) {
            ExaSearchService.run { response.toScrapedResult() }
        }
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
