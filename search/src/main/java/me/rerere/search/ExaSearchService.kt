package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object ExaSearchService : SearchService<SearchServiceOptions.ExaOptions> {
    override val name: String = "Exa"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://dashboard.exa.ai/api-keys")
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
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override val scrapingParameters: InputSchema
        get() = InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "URL to scrape for full page content")
                })
            },
            required = listOf("url")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ExaOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = (params["query"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: error("query is required")
            val body = buildRequestBody(query, commonOptions, serviceOptions)
            val response = executeRequest(buildSearchRequest(body, serviceOptions))
            SearchResult(items = response.toSearchResultItems())
        }
    }

    internal fun buildRequestBody(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ExaOptions
    ): JsonObject = buildJsonObject {
        put("query", JsonPrimitive(query))
        put("type", JsonPrimitive(serviceOptions.searchType.value))
        put("numResults", JsonPrimitive(commonOptions.resultSize))
        put("contents", buildJsonObject {
            if (serviceOptions.dynamicHighlights) {
                put("highlights", buildJsonObject {
                    put("dynamic", true)
                })
            } else {
                put("highlights", true)
            }
        })
    }

    internal fun buildSearchRequest(
        body: JsonObject,
        serviceOptions: SearchServiceOptions.ExaOptions,
    ): Request = buildRequest("search", body, serviceOptions.apiKey).apply {
        if (serviceOptions.dynamicHighlights) {
            addHeader("Exa-Beta", "dynamic-highlights-2026-08-28")
        }
    }.build()

    private fun buildRequest(path: String, body: JsonObject, apiKey: String): Request.Builder =
        Request.Builder()
            .url("https://api.exa.ai/$path")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $apiKey")

    private suspend fun executeRequest(request: Request): ExaData {
        return httpClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) error("response failed #${response.code}")
            val bodyRaw = response.body?.string() ?: error("Failed to get response body")
            try {
                json.decodeFromString<ExaData>(bodyRaw)
            } catch (e: SerializationException) {
                throw SerializationException("Failed to decode Exa response: ${bodyRaw.take(500)}", e)
            }
        }
    }

    internal fun ExaData.toSearchResultItems(): List<SearchResultItem> {
        return results.map {
            SearchResultItem(
                title = it.title?.takeIf { title -> title.isNotBlank() } ?: it.url,
                url = it.url,
                text = it.highlights.orEmpty()
                    .filter { highlight -> highlight.isNotBlank() }
                    .joinToString("\n\n")
                    .ifBlank { it.text.orEmpty() }
            )
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ExaOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = (params["url"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: error("url is required")
            val body = buildScrapeRequestBody(url)
            val request = buildRequest("contents", body, serviceOptions.apiKey).build()
            executeRequest(request).toScrapedResult()
        }
    }

    internal fun buildScrapeRequestBody(url: String): JsonObject = buildJsonObject {
        put("urls", buildJsonArray { add(url) })
        put("text", true)
    }

    internal fun ExaData.toScrapedResult(): ScrapedResult {
        val urls = results.mapNotNull { item ->
            item.text?.takeIf { it.isNotBlank() }?.let { content ->
                ScrapedResultUrl(
                    url = item.url,
                    content = content,
                    metadata = ScrapedResultMetadata(title = item.title),
                )
            }
        }
        if (urls.isEmpty()) {
            val reason = statuses.firstOrNull { it.status == "error" }?.error?.tag
            error("Exa returned no page content${reason?.let { ": $it" }.orEmpty()}")
        }
        return ScrapedResult(urls = urls)
    }

    @Serializable
    data class ExaData(
        @SerialName("requestId")
        val requestId: String? = null,
        @SerialName("autopromptString")
        val autopromptString: String? = null,
        @SerialName("resolvedSearchType")
        val resolvedSearchType: String? = null,
        @SerialName("results")
        val results: List<ExaResult> = emptyList(),
        @SerialName("costDollars")
        val costDollars: ExaCostDollars? = null,
        val statuses: List<ExaContentStatus> = emptyList(),
    )

    @Serializable
    data class ExaContentStatus(
        val id: String? = null,
        val status: String,
        val error: ExaContentError? = null,
    )

    @Serializable
    data class ExaContentError(val tag: String? = null)

    @Serializable
    data class ExaResult(
        @SerialName("id")
        val id: String? = null,
        @SerialName("title")
        val title: String? = null,
        @SerialName("url")
        val url: String,
        @SerialName("publishedDate")
        val publishedDate: String? = null,
        @SerialName("author")
        val author: String? = null,
        @SerialName("text")
        val text: String? = null,
        @SerialName("highlights")
        val highlights: List<String>? = null,
    )

    @Serializable
    data class ExaCostDollars(
        @SerialName("total")
        val total: Double? = null,
        @SerialName("search")
        val search: ExaSearchCost? = null,
        @SerialName("contents")
        val contents: ExaContentsCost? = null
    )

    @Serializable
    data class ExaSearchCost(
        @SerialName("neural")
        val neural: Double? = null
    )

    @Serializable
    data class ExaContentsCost(
        @SerialName("text")
        val text: Double? = null,
        @SerialName("highlights")
        val highlights: Double? = null,
    )
}
