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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    override val scrapingParameters: InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ExaOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val body = buildRequestBody(query, commonOptions, serviceOptions)

            val request = Request.Builder()
                .url("https://api.exa.ai/search")
                .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyRaw = response.body?.string() ?: error("Failed to get response body")
                val response = runCatching {
                    json.decodeFromString<ExaData>(bodyRaw)
                }.onFailure {
                    throw SerializationException(
                        "Failed to decode Exa response: ${bodyRaw.take(500)}"
                    )
                }.getOrThrow()

                return@withContext Result.success(
                    SearchResult(
                        items = response.toSearchResultItems()
                    ))
            } else {
                println(response.body?.string())
                error("response failed #${response.code}")
            }
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
            put("highlights", JsonPrimitive(true))
        })
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
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Exa"))
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
        val costDollars: ExaCostDollars? = null
    )

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
