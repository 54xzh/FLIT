package me.rerere.search

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GrokSearchServiceTest {
    @Test
    fun responsesApiBuildsResponsesPayload() {
        val body = GrokSearchService.buildRequestBody(
            query = "latest xAI news",
            commonOptions = SearchCommonOptions(resultSize = 3),
            serviceOptions = SearchServiceOptions.GrokOptions().withApiType(GrokSearchApiType.RESPONSES),
            systemPrompt = "Search with citations",
        )

        assertNotNull(body["input"] as? JsonArray)
        assertNotNull(body["tools"] as? JsonArray)
        assertNull(body["messages"])
        assertNull(body["search_parameters"])
    }

    @Test
    fun chatCompletionsApiBuildsChatPayload() {
        val body = GrokSearchService.buildRequestBody(
            query = "latest xAI news",
            commonOptions = SearchCommonOptions(resultSize = 3),
            serviceOptions = SearchServiceOptions.GrokOptions().withApiType(GrokSearchApiType.CHAT_COMPLETIONS),
            systemPrompt = "Search with citations",
        )

        val searchParameters = body["search_parameters"] as? JsonObject
        val messages = body["messages"] as? JsonArray

        assertNotNull(messages)
        assertNotNull(searchParameters)
        assertNull(body["input"])
        assertNull(body["tools"])
        assertEquals("on", searchParameters?.stringAt("mode"))
        assertEquals("3", searchParameters?.get("max_search_results")?.toString())
    }

    @Test
    fun legacyChatCompletionsPathSelectsChatCompletionsApi() {
        val options = SearchServiceOptions.GrokOptions(
            enableCustom = true,
            legacyCustomPath = "/chat/completions",
        )

        assertEquals(GrokSearchApiType.CHAT_COMPLETIONS, options.resolvedApiType)
    }

    private fun JsonObject.stringAt(key: String): String? {
        return (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
