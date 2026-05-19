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

private val SERPER_LANGUAGE_OPTIONS = setOf("zh-cn", "zh-tw", "en", "ja")

object SerperSearchService : SearchService<SearchServiceOptions.SerperOptions> {
    override val name: String = "Serper"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://serper.dev/")
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
                put("hl", buildJsonObject {
                    put("type", "string")
                    put("description", "search language")
                    put("enum", buildJsonArray {
                        SERPER_LANGUAGE_OPTIONS.forEach { add(it) }
                    })
                })
            },
            required = listOf("query")
        )

    override val scrapingParameters: InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SerperOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = (params["query"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: error("query is required")
            val hl = normalizeLanguage(
                (params["hl"] as? JsonPrimitive)?.contentOrNull ?: serviceOptions.hl
            )

            val body = buildJsonObject {
                put("q", query)
                put("hl", hl)
            }

            val request = Request.Builder()
                .url("https://google.serper.dev/search")
                .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("X-API-KEY", serviceOptions.apiKey)
                .addHeader("Content-Type", "application/json")
                .build()

            httpClient.newCall(request).await().use { response ->
                val responseBody = response.body.string()
                if (response.isSuccessful) {
                    val serperResponse = runCatching {
                        json.decodeFromString<SerperSearchResponse>(responseBody)
                    }.getOrElse {
                        error("Failed to decode Serper response: $responseBody")
                    }

                    return@withContext Result.success(
                        serperResponse.toSearchResult(commonOptions.resultSize)
                    )
                } else {
                    error("Serper search failed with code ${response.code}: $responseBody")
                }
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SerperOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Serper"))
    }

    @Serializable
    data class SerperSearchResponse(
        @SerialName("knowledgeGraph")
        val knowledgeGraph: KnowledgeGraph? = null,
        val organic: List<OrganicResult> = emptyList(),
    )

    @Serializable
    data class KnowledgeGraph(
        val title: String? = null,
        val type: String? = null,
        val website: String? = null,
        val description: String? = null,
        @SerialName("descriptionLink")
        val descriptionLink: String? = null,
        val attributes: Map<String, String> = emptyMap(),
    )

    @Serializable
    data class OrganicResult(
        val title: String? = null,
        val link: String? = null,
        val snippet: String? = null,
        val date: String? = null,
        val attributes: Map<String, String> = emptyMap(),
        val position: Int? = null,
    )
}

internal fun SerperSearchService.SerperSearchResponse.toSearchResult(limit: Int): SearchResult {
    val safeLimit = limit.coerceAtLeast(0)
    val organicItems = organic
        .sortedWith(compareBy(nullsLast()) { it.position })
        .mapNotNull { result ->
            val url = result.link?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SearchResultItem(
                title = result.title?.takeIf { it.isNotBlank() } ?: url,
                url = url,
                text = result.toText()
            )
        }
        .take(safeLimit)

    val fallbackItem = knowledgeGraph?.toSearchResultItem()
    val items = organicItems.ifEmpty {
        fallbackItem?.let(::listOf) ?: emptyList()
    }

    return SearchResult(
        answer = knowledgeGraph?.description?.takeIf { it.isNotBlank() },
        items = items
    )
}

private fun SerperSearchService.OrganicResult.toText(): String {
    return buildList {
        snippet?.takeIf { it.isNotBlank() }?.let(::add)
        date?.takeIf { it.isNotBlank() }?.let { add("Date: $it") }
        attributes.takeIf { it.isNotEmpty() }?.let { attrs ->
            add(attrs.entries.joinToString("\n") { (key, value) -> "$key: $value" })
        }
    }.joinToString("\n")
}

private fun SerperSearchService.KnowledgeGraph.toSearchResultItem(): SearchResultItem? {
    val url = website?.takeIf { it.isNotBlank() }
        ?: descriptionLink?.takeIf { it.isNotBlank() }
        ?: return null
    val text = buildList {
        type?.takeIf { it.isNotBlank() }?.let(::add)
        description?.takeIf { it.isNotBlank() }?.let(::add)
        attributes.takeIf { it.isNotEmpty() }?.let { attrs ->
            add(attrs.entries.joinToString("\n") { (key, value) -> "$key: $value" })
        }
    }.joinToString("\n")

    return SearchResultItem(
        title = title?.takeIf { it.isNotBlank() } ?: url,
        url = url,
        text = text
    )
}

private fun normalizeLanguage(language: String): String {
    val normalized = language.trim().lowercase()
    if (normalized !in SERPER_LANGUAGE_OPTIONS) {
        error("hl must be one of ${SERPER_LANGUAGE_OPTIONS.joinToString()}")
    }
    return normalized
}
