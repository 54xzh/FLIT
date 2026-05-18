package me.rerere.search

import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

private const val TAG = "GrokSearchService"
private const val DEFAULT_GROK_BASE_URL = "https://api.x.ai/v1"
private const val DEFAULT_GROK_SYSTEM_PROMPT =
    "You are a helpful search assistant. Search the web to find accurate and up-to-date information for the user's query. Provide a comprehensive answer with citations."

object GrokSearchService : SearchService<SearchServiceOptions.GrokOptions> {
    override val name: String = "Grok"

    @Composable
    override fun Description() {
        val uriHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                uriHandler.openUri("https://console.x.ai/")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override val parameters: InputSchema?
        get() = InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "The question to ask, can be a natural language question")
                })
            },
            required = listOf("query")
        )

    override val scrapingParameters: InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.GrokOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (serviceOptions.apiKey.isBlank()) {
                error("Grok API key is required")
            }

            val query = (params["query"] as? JsonPrimitive)?.contentOrNull
                ?: error("query is required")

            val apiType = serviceOptions.resolvedApiType
            val baseUrl = if (serviceOptions.enableCustom) {
                serviceOptions.customBaseUrl.ifBlank { DEFAULT_GROK_BASE_URL }.trimEnd('/')
            } else {
                DEFAULT_GROK_BASE_URL
            }
            val endpoint = baseUrl + apiType.path
            val systemPrompt = if (serviceOptions.enableCustom && serviceOptions.customSystemPrompt.isNotBlank()) {
                serviceOptions.customSystemPrompt
            } else {
                DEFAULT_GROK_SYSTEM_PROMPT
            }

            val body = buildRequestBody(
                query = query,
                commonOptions = commonOptions,
                serviceOptions = serviceOptions,
                systemPrompt = systemPrompt,
                apiType = apiType,
            )

            Log.i(TAG, "search: $query")

            val request = Request.Builder()
                .url(endpoint)
                .post(body.toString().toRequestBody())
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .addHeader("Content-Type", "application/json")
                .build()

            when (apiType) {
                GrokSearchApiType.RESPONSES -> {
                    if (serviceOptions.enableStream) {
                        searchResponsesWithStreaming(request, commonOptions)
                    } else {
                        searchResponsesWithoutStreaming(request, commonOptions)
                    }
                }

                GrokSearchApiType.CHAT_COMPLETIONS -> {
                    if (serviceOptions.enableStream) {
                        searchChatCompletionsWithStreaming(request, commonOptions)
                    } else {
                        searchChatCompletionsWithoutStreaming(request, commonOptions)
                    }
                }
            }
        }
    }

    internal fun buildRequestBody(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.GrokOptions,
        systemPrompt: String,
        apiType: GrokSearchApiType = serviceOptions.resolvedApiType,
    ): JsonObject {
        return when (apiType) {
            GrokSearchApiType.RESPONSES -> buildResponsesRequestBody(
                query = query,
                serviceOptions = serviceOptions,
                systemPrompt = systemPrompt,
            )

            GrokSearchApiType.CHAT_COMPLETIONS -> buildChatCompletionsRequestBody(
                query = query,
                commonOptions = commonOptions,
                serviceOptions = serviceOptions,
                systemPrompt = systemPrompt,
            )
        }
    }

    private fun buildResponsesRequestBody(
        query: String,
        serviceOptions: SearchServiceOptions.GrokOptions,
        systemPrompt: String,
    ): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(serviceOptions.model))
        put("stream", JsonPrimitive(serviceOptions.enableStream))
        put("input", buildJsonArray {
            add(buildJsonObject {
                put("role", JsonPrimitive("system"))
                put("content", JsonPrimitive(systemPrompt))
            })
            add(buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", JsonPrimitive(query))
            })
        })
        put("tools", buildJsonArray {
            add(buildJsonObject {
                put("type", JsonPrimitive("web_search"))
            })
            add(buildJsonObject {
                put("type", JsonPrimitive("x_search"))
            })
        })
        put("store", JsonPrimitive(false))
    }

    private fun buildChatCompletionsRequestBody(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.GrokOptions,
        systemPrompt: String,
    ): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(serviceOptions.model))
        put("stream", JsonPrimitive(serviceOptions.enableStream))
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", JsonPrimitive("system"))
                put("content", JsonPrimitive(systemPrompt))
            })
            add(buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", JsonPrimitive(query))
            })
        })
        put("search_parameters", buildJsonObject {
            put("mode", JsonPrimitive("on"))
            put("return_citations", JsonPrimitive(true))
            if (commonOptions.resultSize > 0) {
                put("max_search_results", JsonPrimitive(commonOptions.resultSize))
            }
            put("sources", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("web"))
                })
                add(buildJsonObject {
                    put("type", JsonPrimitive("x"))
                })
            })
        })
    }

    private suspend fun searchResponsesWithoutStreaming(
        request: Request,
        commonOptions: SearchCommonOptions
    ): SearchResult {
        val response = httpClient.newCall(request).await()
        if (!response.isSuccessful) {
            error("response failed #${response.code}: ${response.body?.string()}")
        }

        val responseBody = response.body.string().let {
            json.decodeFromString<GrokResponse>(it)
        }

        return parseGrokResponse(responseBody, commonOptions)
    }

    private suspend fun searchResponsesWithStreaming(
        request: Request,
        commonOptions: SearchCommonOptions
    ): SearchResult {
        var completedResponse: JsonObject? = null
        var streamError: Throwable? = null

        callbackFlow<Unit> {
            val listener = object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    Log.d(TAG, "onEvent: $id/$type $data")
                    if (type == "response.completed") {
                        val eventData = runCatching {
                            json.parseToJsonElement(data).jsonObject
                        }.getOrNull()
                        if (eventData != null) {
                            completedResponse = eventData
                            close()
                        }
                    }
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                    val statusCode = response?.code ?: -1
                    val body = runCatching { response?.body?.string() }.getOrNull().orEmpty()
                    streamError = Exception("Stream failed #$statusCode: $body", t)
                    close(streamError)
                }

                override fun onClosed(eventSource: EventSource) {
                    close()
                }
            }

            val eventSource = EventSources.createFactory(httpClient)
                .newEventSource(request, listener)

            awaitClose {
                eventSource.cancel()
            }
        }.collect {}

        streamError?.let { throw it }

        val result = completedResponse
            ?: throw Exception("Stream completed without response")

        val responseObject = result["response"]?.jsonObject
            ?: error("response not found in completed event")

        return parseGrokResponseFromJson(responseObject, commonOptions)
    }

    private suspend fun searchChatCompletionsWithoutStreaming(
        request: Request,
        commonOptions: SearchCommonOptions
    ): SearchResult {
        val response = httpClient.newCall(request).await()
        if (!response.isSuccessful) {
            error("response failed #${response.code}: ${response.body?.string()}")
        }

        val responseObject = response.body.string().let {
            json.parseToJsonElement(it) as? JsonObject
        } ?: error("response is not a JSON object")

        return parseChatCompletionsResponse(responseObject, commonOptions)
    }

    private suspend fun searchChatCompletionsWithStreaming(
        request: Request,
        commonOptions: SearchCommonOptions
    ): SearchResult {
        val answerBuilder = StringBuilder()
        var citations = emptyList<GrokCitation>()
        var streamError: Throwable? = null

        callbackFlow<Unit> {
            val listener = object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    Log.d(TAG, "onEvent: $id/$type $data")
                    if (data == "[DONE]") {
                        close()
                        return
                    }

                    val eventData = runCatching {
                        json.parseToJsonElement(data) as? JsonObject
                    }.getOrNull() ?: return

                    eventData["error"]?.let { errorBody ->
                        streamError = Exception(errorBody.toString())
                        close(streamError)
                        return
                    }

                    val choice = eventData.arrayAt("choices")?.firstObject()
                    val delta = choice?.objectAt("delta")
                    delta?.stringAt("content")?.let { answerBuilder.append(it) }

                    val chunkCitations = extractChatCompletionsCitations(eventData, delta)
                    if (chunkCitations.isNotEmpty()) {
                        citations = chunkCitations
                    }

                    if (!choice?.stringAt("finish_reason").isNullOrBlank()) {
                        close()
                    }
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                    val statusCode = response?.code ?: -1
                    val body = runCatching { response?.body?.string() }.getOrNull().orEmpty()
                    streamError = Exception("Stream failed #$statusCode: $body", t)
                    close(streamError)
                }

                override fun onClosed(eventSource: EventSource) {
                    close()
                }
            }

            val eventSource = EventSources.createFactory(httpClient)
                .newEventSource(request, listener)

            awaitClose {
                eventSource.cancel()
            }
        }.collect {}

        streamError?.let { throw it }

        return SearchResult(
            answer = answerBuilder.toString().takeIf { it.isNotBlank() },
            items = citations.toResultItems(commonOptions),
        )
    }

    private fun parseGrokResponse(responseBody: GrokResponse, commonOptions: SearchCommonOptions): SearchResult {
        val messageOutput = responseBody.output.firstOrNull {
            it.type == "message" && it.role == "assistant"
        }
        val textContent = messageOutput?.content?.firstOrNull {
            it.type == "output_text"
        }

        val items = textContent?.annotations
            ?.filter { it.type == "url_citation" && !it.url.isNullOrBlank() }
            ?.distinctBy { it.url }
            ?.take(commonOptions.resultSize)
            ?.map { annotation ->
                SearchResultItem(
                    title = annotation.title?.takeIf { it.isNotBlank() } ?: annotation.url.orEmpty(),
                    url = annotation.url.orEmpty(),
                    text = "",
                )
            }
            .orEmpty()

        return SearchResult(
            answer = textContent?.text,
            items = items,
        )
    }

    private fun parseGrokResponseFromJson(responseObject: JsonObject, commonOptions: SearchCommonOptions): SearchResult {
        val outputArray = responseObject["output"]?.jsonArray
            ?: return SearchResult(answer = null, items = emptyList())

        var answer: String? = null
        val items = mutableListOf<SearchResultItem>()

        for (outputItem in outputArray) {
            val output = outputItem.jsonObject
            val type = output["type"]?.jsonPrimitive?.content

            if (type == "message") {
                val contentArray = output["content"]?.jsonArray
                for (contentItem in contentArray.orEmpty()) {
                    val content = contentItem.jsonObject
                    val contentType = content["type"]?.jsonPrimitive?.content
                    if (contentType == "output_text") {
                        answer = content["text"]?.jsonPrimitive?.content
                        val annotations = content["annotations"]?.jsonArray
                        for (annotationItem in annotations.orEmpty()) {
                            val annotation = annotationItem.jsonObject
                            val annotationType = annotation["type"]?.jsonPrimitive?.content
                            if (annotationType == "url_citation") {
                                val url = annotation["url"]?.jsonPrimitive?.content
                                if (!url.isNullOrBlank() && items.none { it.url == url }) {
                                    val title = annotation["title"]?.jsonPrimitive?.content
                                        ?.takeIf { it.isNotBlank() }
                                        ?: url
                                    if (items.size < commonOptions.resultSize) {
                                        items.add(SearchResultItem(
                                            title = title,
                                            url = url,
                                            text = "",
                                        ))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return SearchResult(answer = answer, items = items)
    }

    private fun parseChatCompletionsResponse(
        responseObject: JsonObject,
        commonOptions: SearchCommonOptions
    ): SearchResult {
        val message = responseObject
            .arrayAt("choices")
            ?.firstObject()
            ?.objectAt("message")

        val answer = message?.stringAt("content")
        val citations = extractChatCompletionsCitations(responseObject, message)

        return SearchResult(
            answer = answer,
            items = citations.toResultItems(commonOptions),
        )
    }

    private fun extractChatCompletionsCitations(
        responseObject: JsonObject,
        messageObject: JsonObject?,
    ): List<GrokCitation> {
        val topLevelCitations = responseObject.arrayAt("citations")
            .orEmpty()
            .mapNotNull { it.toCitation() }

        val messageCitations = messageObject
            ?.arrayAt("annotations")
            .orEmpty()
            .mapNotNull { it.toCitation() }

        return (topLevelCitations + messageCitations)
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.GrokOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Grok"))
    }

    @Serializable
    private data class GrokResponse(
        val output: List<GrokOutputItem> = emptyList(),
    )

    @Serializable
    private data class GrokOutputItem(
        val type: String,
        val role: String? = null,
        val content: List<GrokContent>? = null,
    )

    @Serializable
    private data class GrokContent(
        val type: String,
        val text: String? = null,
        val annotations: List<GrokAnnotation>? = null,
    )

    @Serializable
    private data class GrokAnnotation(
        val type: String,
        val url: String? = null,
        val title: String? = null,
        @SerialName("start_index") val startIndex: Int? = null,
        @SerialName("end_index") val endIndex: Int? = null,
    )

    private data class GrokCitation(
        val title: String?,
        val url: String,
    )

    private fun List<GrokCitation>.toResultItems(commonOptions: SearchCommonOptions): List<SearchResultItem> {
        return distinctBy { it.url }
            .take(commonOptions.resultSize)
            .map { citation ->
                SearchResultItem(
                    title = citation.title?.takeIf { it.isNotBlank() } ?: citation.url,
                    url = citation.url,
                    text = "",
                )
            }
    }

    private fun JsonObject.arrayAt(key: String): JsonArray? {
        return this[key] as? JsonArray
    }

    private fun JsonObject.objectAt(key: String): JsonObject? {
        return this[key] as? JsonObject
    }

    private fun JsonObject.stringAt(key: String): String? {
        return (this[key] as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonArray.firstObject(): JsonObject? {
        return firstOrNull() as? JsonObject
    }

    private fun kotlinx.serialization.json.JsonElement.toCitation(): GrokCitation? {
        return when (this) {
            is JsonPrimitive -> {
                contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { GrokCitation(title = null, url = it) }
            }

            is JsonObject -> {
                val citationObject = objectAt("url_citation")
                val url = citationObject?.stringAt("url") ?: stringAt("url")
                val title = citationObject?.stringAt("title") ?: stringAt("title")
                url
                    ?.takeIf { it.isNotBlank() }
                    ?.let { GrokCitation(title = title, url = it) }
            }

            else -> null
        }
    }
}
