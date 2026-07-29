package me.rerere.search

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubaoSearchServiceTest {
    @Test
    fun requestUsesDocumentedFieldNamesAndClampsResultSize() {
        val request = DoubaoSearchService.buildRequest(
            query = "北京周边游玩景点推荐",
            commonOptions = SearchCommonOptions(resultSize = 32),
        )

        val json = SearchService.json.encodeToJsonElement(
            DoubaoSearchService.DoubaoSearchRequest.serializer(),
            request,
        ) as JsonObject

        assertEquals("北京周边游玩景点推荐", json.stringAt("Query"))
        assertEquals(20, (json["DocCount"] as JsonPrimitive).content.toInt())
        assertNull(json["query"])
        assertNull(json["docCount"])
    }

    @Test
    fun mapsTextSnippetsAndIgnoresImagesAndInvalidDocuments() {
        val payload = """
            {
              "ResponseMetadata": {
                "RequestId": "request-1",
                "Action": "",
                "Version": "",
                "Service": "",
                "Region": ""
              },
              "Result": {
                "TotalDocCount": 20,
                "Documents": [
                  {
                    "Rank": 1,
                    "Url": "https://example.com/second",
                    "Title": "",
                    "Snippet": [
                      { "Type": "text", "Text": "Second result" },
                      { "Type": "image", "Image": { "ImageUrl": "https://example.com/a.jpg" } },
                      { "Text": "Text without a type" },
                      { "Type": "text", "Text": "Second result" }
                    ],
                    "HostInfo": { "Hostname": "Example" }
                  },
                  {
                    "Rank": 0,
                    "Url": "https://example.com/first",
                    "Title": "First",
                    "Snippet": [
                      { "Type": "text", "Text": "First result" }
                    ]
                  },
                  {
                    "Rank": 2,
                    "Title": "Missing URL",
                    "Snippet": []
                  }
                ],
                "ErrorCode": 0,
                "ErrorMsg": ""
              }
            }
        """.trimIndent()

        val response = SearchService.json.decodeFromString<DoubaoSearchService.DoubaoSearchResponse>(payload)
        val items = DoubaoSearchService.run { response.toSearchResultItems() }

        assertEquals(2, items.size)
        assertEquals("First", items[0].title)
        assertEquals("https://example.com/first", items[0].url)
        assertEquals("First result", items[0].text)
        assertEquals("Example", items[1].title)
        assertEquals("Second result\n\nText without a type", items[1].text)
    }

    @Test
    fun reportsMetadataAuthenticationErrorWithRequestId() {
        val payload = """
            {
              "ResponseMetadata": {
                "RequestId": "request-auth",
                "Error": {
                  "CodeN": 700901,
                  "Code": "700901",
                  "Message": "invalid api key"
                }
              },
              "Result": null
            }
        """.trimIndent()

        val response = SearchService.json.decodeFromString<DoubaoSearchService.DoubaoSearchResponse>(payload)
        val message = DoubaoSearchService.run { response.failureMessage() }.orEmpty()

        assertTrue(message.contains("API key is invalid"))
        assertTrue(message.contains("700901"))
        assertTrue(message.contains("request-auth"))
    }

    @Test
    fun reportsResultRateLimitErrorWithRequestId() {
        val payload = """
            {
              "ResponseMetadata": {
                "RequestId": "request-limit"
              },
              "Result": {
                "Documents": [],
                "ErrorCode": 700429,
                "ErrorMsg": "too many requests"
              }
            }
        """.trimIndent()

        val response = SearchService.json.decodeFromString<DoubaoSearchService.DoubaoSearchResponse>(payload)
        val message = DoubaoSearchService.run { response.failureMessage() }.orEmpty()

        assertTrue(message.contains("rate limit exceeded"))
        assertTrue(message.contains("700429"))
        assertTrue(message.contains("request-limit"))
    }

    @Test
    fun acceptsSuccessfulEmptyResult() {
        val payload = """
            {
              "ResponseMetadata": {
                "RequestId": "request-empty"
              },
              "Result": {
                "Documents": [],
                "ErrorCode": 0,
                "ErrorMsg": ""
              }
            }
        """.trimIndent()

        val response = SearchService.json.decodeFromString<DoubaoSearchService.DoubaoSearchResponse>(payload)

        assertNull(DoubaoSearchService.run { response.failureMessage() })
        assertTrue(DoubaoSearchService.run { response.toSearchResultItems() }.isEmpty())
    }

    private fun JsonObject.stringAt(key: String): String? {
        return (this[key] as? JsonPrimitive)?.content
    }
}
