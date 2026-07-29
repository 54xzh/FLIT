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
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object DoubaoSearchService : SearchService<SearchServiceOptions.DoubaoSearchOptions> {
    override val name: String = "Doubao Search"

    @Composable
    override fun Description() {
        val uriHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                uriHandler.openUri(
                    "https://console.volcengine.com/search-infinity/api-key?tab=post_paid"
                )
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override val parameters: InputSchema
        get() = InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                    put("minLength", 1)
                    put("maxLength", MAX_QUERY_LENGTH)
                })
            },
            required = listOf("query")
        )

    override val scrapingParameters: InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DoubaoSearchOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = (params["query"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
                ?.trim()
                .orEmpty()
            require(query.isNotEmpty()) { "query is required" }

            val apiKey = serviceOptions.apiKey.trim()
            require(apiKey.isNotEmpty()) { "Doubao Search API key is required" }

            val requestBody = buildRequest(
                query = query.take(MAX_QUERY_LENGTH),
                commonOptions = commonOptions,
            )
            val request = Request.Builder()
                .url(SEARCH_URL)
                .post(
                    json.encodeToString(requestBody)
                        .toRequestBody(JSON_MEDIA_TYPE)
                )
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyRaw = response.body?.string().orEmpty()
                val payload = bodyRaw.takeIf(String::isNotBlank)?.let {
                    runCatching {
                        json.decodeFromString<DoubaoSearchResponse>(it)
                    }.getOrNull()
                }

                if (!response.isSuccessful) {
                    error(
                        payload?.failureMessage()
                            ?: "Doubao Search request failed with HTTP ${response.code}"
                    )
                }

                if (payload == null) {
                    throw SerializationException(
                        "Failed to decode Doubao Search response: ${bodyRaw.take(500)}"
                    )
                }

                payload.failureMessage()?.let(::error)
                SearchResult(items = payload.toSearchResultItems())
            }
        }
    }

    internal fun buildRequest(
        query: String,
        commonOptions: SearchCommonOptions,
    ): DoubaoSearchRequest = DoubaoSearchRequest(
        query = query,
        docCount = commonOptions.resultSize.coerceIn(1, MAX_RESULT_SIZE),
    )

    internal fun DoubaoSearchResponse.toSearchResultItems(): List<SearchResultItem> {
        return result?.documents.orEmpty()
            .sortedBy(DoubaoSearchDocument::rank)
            .mapNotNull { document ->
                val url = document.url?.trim()?.takeIf(String::isNotEmpty)
                    ?: return@mapNotNull null
                val title = document.title?.trim()?.takeIf(String::isNotEmpty)
                    ?: document.hostInfo?.hostname?.trim()?.takeIf(String::isNotEmpty)
                    ?: url
                val text = document.snippets.orEmpty()
                    .mapNotNull { snippet ->
                        snippet.text
                            ?.trim()
                            ?.takeIf {
                                it.isNotEmpty() &&
                                    !snippet.type.equals("image", ignoreCase = true)
                            }
                    }
                    .distinct()
                    .joinToString("\n\n")

                SearchResultItem(
                    title = title,
                    url = url,
                    text = text,
                )
            }
    }

    internal fun DoubaoSearchResponse.failureMessage(): String? {
        responseMetadata.error?.let { apiError ->
            val code = apiError.code?.ifBlank { null } ?: apiError.codeNumber?.toString()
            return formatFailure(
                code = code,
                serverMessage = apiError.message,
                requestId = responseMetadata.requestId,
            )
        }

        val searchResult = result ?: return formatFailure(
            code = null,
            serverMessage = "The service returned no result",
            requestId = responseMetadata.requestId,
        )
        if (searchResult.errorCode != 0) {
            return formatFailure(
                code = searchResult.errorCode.toString(),
                serverMessage = searchResult.errorMessage,
                requestId = responseMetadata.requestId,
            )
        }
        return null
    }

    private fun formatFailure(
        code: String?,
        serverMessage: String?,
        requestId: String,
    ): String {
        val message = when (code) {
            "10400" -> "Invalid search query or request parameters"
            "10403", "10408" -> "The account is not authorized to use Doubao Search"
            "10409", "10410" -> "Doubao Search pay-as-you-go billing is not enabled"
            "10412" -> "The Doubao Search quota is exhausted"
            "10500", "10501" -> "Doubao Search is temporarily unavailable; try again later"
            "700429" -> "Doubao Search rate limit exceeded; try again later"
            "700901" -> "The Doubao Search API key is invalid"
            else -> serverMessage?.takeIf(String::isNotBlank)
                ?: "Doubao Search request failed"
        }
        val codeSuffix = code?.let { " (code $it)" }.orEmpty()
        val requestIdSuffix = requestId.takeIf(String::isNotBlank)
            ?.let { " [requestId: $it]" }
            .orEmpty()
        return "$message$codeSuffix$requestIdSuffix"
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DoubaoSearchOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Doubao Search"))
    }

    @Serializable
    internal data class DoubaoSearchRequest(
        @SerialName("Query")
        val query: String,
        @SerialName("DocCount")
        val docCount: Int,
    )

    @Serializable
    internal data class DoubaoSearchResponse(
        @SerialName("ResponseMetadata")
        val responseMetadata: DoubaoResponseMetadata,
        @SerialName("Result")
        val result: DoubaoSearchResult? = null,
    )

    @Serializable
    internal data class DoubaoResponseMetadata(
        @SerialName("RequestId")
        val requestId: String = "",
        @SerialName("Error")
        val error: DoubaoApiError? = null,
    )

    @Serializable
    internal data class DoubaoApiError(
        @SerialName("CodeN")
        val codeNumber: Int? = null,
        @SerialName("Code")
        val code: String? = null,
        @SerialName("Message")
        val message: String? = null,
    )

    @Serializable
    internal data class DoubaoSearchResult(
        @SerialName("Documents")
        val documents: List<DoubaoSearchDocument> = emptyList(),
        @SerialName("ErrorCode")
        val errorCode: Int = 0,
        @SerialName("ErrorMsg")
        val errorMessage: String = "",
    )

    @Serializable
    internal data class DoubaoSearchDocument(
        @SerialName("Rank")
        val rank: Int = Int.MAX_VALUE,
        @SerialName("Url")
        val url: String? = null,
        @SerialName("Title")
        val title: String? = null,
        @SerialName("Snippet")
        val snippets: List<DoubaoSearchSnippet> = emptyList(),
        @SerialName("HostInfo")
        val hostInfo: DoubaoSearchHostInfo? = null,
    )

    @Serializable
    internal data class DoubaoSearchSnippet(
        @SerialName("Type")
        val type: String? = null,
        @SerialName("Text")
        val text: String? = null,
    )

    @Serializable
    internal data class DoubaoSearchHostInfo(
        @SerialName("Hostname")
        val hostname: String? = null,
    )

    private const val SEARCH_URL = "https://open.feedcoopapi.com/search_api/global_search"
    private const val MAX_QUERY_LENGTH = 100
    private const val MAX_RESULT_SIZE = 20
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
}
