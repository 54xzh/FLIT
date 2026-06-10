package me.rerere.search

import androidx.compose.runtime.Composable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.InputSchema
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlin.uuid.Uuid

interface SearchService<T : SearchServiceOptions> {
    val name: String

    val parameters: InputSchema?

    val scrapingParameters: InputSchema?

    @Composable
    fun Description()

    suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: T
    ): Result<SearchResult>

    suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: T
    ): Result<ScrapedResult>

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T : SearchServiceOptions> getService(options: T): SearchService<T> {
            return when (options) {
                is SearchServiceOptions.TavilyOptions -> TavilySearchService
                is SearchServiceOptions.ExaOptions -> ExaSearchService
                is SearchServiceOptions.ZhipuOptions -> ZhipuSearchService
                is SearchServiceOptions.BingLocalOptions -> BingSearchService
                is SearchServiceOptions.SearXNGOptions -> SearXNGService
                is SearchServiceOptions.LinkUpOptions -> LinkUpService
                is SearchServiceOptions.BraveOptions -> BraveSearchService
                is SearchServiceOptions.MetasoOptions -> MetasoSearchService
                is SearchServiceOptions.OllamaOptions -> OllamaSearchService
                is SearchServiceOptions.PerplexityOptions -> PerplexitySearchService
                is SearchServiceOptions.FirecrawlOptions -> FirecrawlSearchService
                is SearchServiceOptions.JinaOptions -> JinaSearchService
                is SearchServiceOptions.BochaOptions -> BochaSearchService
                is SearchServiceOptions.NanoGPTOptions -> NanoGPTSearchService
                is SearchServiceOptions.GrokOptions -> GrokSearchService
                is SearchServiceOptions.SerperOptions -> SerperSearchService
            } as SearchService<T>
        }

        internal val httpClient by lazy {
            OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        internal val json by lazy {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
    }
}

@Serializable
enum class MultiSearchStrategy {
    @SerialName("parallel") PARALLEL,
    @SerialName("sequential") SEQUENTIAL,
}

@Serializable
enum class GrokSearchApiType(val path: String) {
    @SerialName("responses")
    RESPONSES("/responses"),

    @SerialName("chat_completions")
    CHAT_COMPLETIONS("/chat/completions"),
}

@Serializable
enum class ExaSearchType(val value: String) {
    @SerialName("auto")
    AUTO("auto"),

    @SerialName("fast")
    FAST("fast"),

    @SerialName("instant")
    INSTANT("instant"),

    @SerialName("deep-lite")
    DEEP_LITE("deep-lite"),

    @SerialName("deep")
    DEEP("deep"),

    @SerialName("deep-reasoning")
    DEEP_REASONING("deep-reasoning"),
}

@Serializable
data class SearchCommonOptions(
    val resultSize: Int = 5,
    val multiSearchStrategy: MultiSearchStrategy = MultiSearchStrategy.PARALLEL,
)

@Serializable
data class SearchResult(
    val answer: String? = null,
    val items: List<SearchResultItem>,
) {
    @Serializable
    data class SearchResultItem(
        val title: String,
        val url: String,
        val text: String,
    )
}

@Serializable
data class ScrapedResult(
    val urls: List<ScrapedResultUrl>,
)

@Serializable
data class ScrapedResultUrl(
    val url: String,
    val content: String,
    val metadata: ScrapedResultMetadata? = null,
)

@Serializable
data class ScrapedResultMetadata(
    val title: String? = null,
    val description: String? = null,
    val language: String? = null,
)

@Serializable
sealed class SearchServiceOptions {
    abstract val id: Uuid

    companion object {
        val DEFAULT = BingLocalOptions()

        val TYPES = mapOf(
            BingLocalOptions::class to "Bing",
            ZhipuOptions::class to "智谱",
            TavilyOptions::class to "Tavily",
            ExaOptions::class to "Exa",
            SearXNGOptions::class to "SearXNG",
            LinkUpOptions::class to "LinkUp",
            BraveOptions::class to "Brave",
            MetasoOptions::class to "秘塔",
            OllamaOptions::class to "Ollama",
            PerplexityOptions::class to "Perplexity",
            FirecrawlOptions::class to "Firecrawl",
            JinaOptions::class to "Jina",
            BochaOptions::class to "博查",
            NanoGPTOptions::class to "NanoGPT",
            GrokOptions::class to "Grok",
            SerperOptions::class to "Serper",
        )
    }

    @Serializable
    @SerialName("bing_local")
    data class BingLocalOptions(
        override val id: Uuid = Uuid.random(),
        val alias: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("zhipu")
    data class ZhipuOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val alias: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("tavily")
    data class TavilyOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val depth: String = "advanced",
        val alias: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("exa")
    data class ExaOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val searchType: ExaSearchType = ExaSearchType.AUTO,
        val alias: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("searxng")
    data class SearXNGOptions(
        override val id: Uuid = Uuid.random(),
        val url: String = "",
        val engines: String = "",
        val language: String = "",
        val username: String = "",
        val password: String = "",
        val alias: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("linkup")
    data class LinkUpOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val depth: String = "standard",
        val alias: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("brave")
    data class BraveOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val alias: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("metaso")
    data class MetasoOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val alias: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("ollama")
    data class OllamaOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val alias: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("perplexity")
    data class PerplexityOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val maxTokensPerPage: Int? = 1024,
        val alias: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("firecrawl")
    data class FirecrawlOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val alias: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("jina")
    data class JinaOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val alias: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("bocha")
    data class BochaOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val summary: Boolean = true,
        val alias: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("nanogpt")
    data class NanoGPTOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val depth: String = "standard",
        val outputType: String = "searchResults",
        val includeImages: Boolean = false,
        val stealthMode: Boolean = false,
        val alias: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("grok")
    data class GrokOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val model: String = "grok-4.20-0309-non-reasoning",
        val enableCustom: Boolean = false,
        val customBaseUrl: String = "https://api.x.ai/v1",
        val apiType: GrokSearchApiType = GrokSearchApiType.RESPONSES,
        @SerialName("customPath")
        val legacyCustomPath: String = GrokSearchApiType.RESPONSES.path,
        val customSystemPrompt: String = "You are a helpful search assistant. Search the web to find accurate and up-to-date information for the user's query. Provide a comprehensive answer with citations.",
        val enableStream: Boolean = false,
        val alias: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("serper")
    data class SerperOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val hl: String = "zh-cn",
        val alias: String = "",
    ) : SearchServiceOptions()
}

val SearchServiceOptions.GrokOptions.resolvedApiType: GrokSearchApiType
    get() = if (
        apiType == GrokSearchApiType.RESPONSES &&
        legacyCustomPath.trim().trimEnd('/').endsWith("/chat/completions", ignoreCase = true)
    ) {
        GrokSearchApiType.CHAT_COMPLETIONS
    } else {
        apiType
    }

fun SearchServiceOptions.GrokOptions.withApiType(apiType: GrokSearchApiType): SearchServiceOptions.GrokOptions {
    return copy(
        apiType = apiType,
        legacyCustomPath = apiType.path,
    )
}

val SearchServiceOptions.rawAlias: String
    get() = when (this) {
        is SearchServiceOptions.BingLocalOptions -> alias
        is SearchServiceOptions.ZhipuOptions -> alias
        is SearchServiceOptions.TavilyOptions -> alias
        is SearchServiceOptions.ExaOptions -> alias
        is SearchServiceOptions.SearXNGOptions -> alias
        is SearchServiceOptions.LinkUpOptions -> alias
        is SearchServiceOptions.BraveOptions -> alias
        is SearchServiceOptions.MetasoOptions -> alias
        is SearchServiceOptions.OllamaOptions -> alias
        is SearchServiceOptions.PerplexityOptions -> alias
        is SearchServiceOptions.FirecrawlOptions -> alias
        is SearchServiceOptions.JinaOptions -> alias
        is SearchServiceOptions.BochaOptions -> alias
        is SearchServiceOptions.NanoGPTOptions -> alias
        is SearchServiceOptions.GrokOptions -> alias
        is SearchServiceOptions.SerperOptions -> alias
    }

val SearchServiceOptions.displayName: String
    get() {
        val alias = when (this) {
            is SearchServiceOptions.BingLocalOptions -> alias
            is SearchServiceOptions.ZhipuOptions -> alias
            is SearchServiceOptions.TavilyOptions -> alias
            is SearchServiceOptions.ExaOptions -> alias
            is SearchServiceOptions.SearXNGOptions -> alias
            is SearchServiceOptions.LinkUpOptions -> alias
            is SearchServiceOptions.BraveOptions -> alias
            is SearchServiceOptions.MetasoOptions -> alias
            is SearchServiceOptions.OllamaOptions -> alias
            is SearchServiceOptions.PerplexityOptions -> alias
            is SearchServiceOptions.FirecrawlOptions -> alias
            is SearchServiceOptions.JinaOptions -> alias
            is SearchServiceOptions.BochaOptions -> alias
            is SearchServiceOptions.NanoGPTOptions -> alias
            is SearchServiceOptions.GrokOptions -> alias
            is SearchServiceOptions.SerperOptions -> alias
        }
        return alias.ifBlank { SearchServiceOptions.TYPES[this::class] ?: "Unknown" }
    }

fun SearchServiceOptions.withAlias(newAlias: String): SearchServiceOptions = when (this) {
    is SearchServiceOptions.BingLocalOptions -> copy(alias = newAlias)
    is SearchServiceOptions.ZhipuOptions -> copy(alias = newAlias)
    is SearchServiceOptions.TavilyOptions -> copy(alias = newAlias)
    is SearchServiceOptions.ExaOptions -> copy(alias = newAlias)
    is SearchServiceOptions.SearXNGOptions -> copy(alias = newAlias)
    is SearchServiceOptions.LinkUpOptions -> copy(alias = newAlias)
    is SearchServiceOptions.BraveOptions -> copy(alias = newAlias)
    is SearchServiceOptions.MetasoOptions -> copy(alias = newAlias)
    is SearchServiceOptions.OllamaOptions -> copy(alias = newAlias)
    is SearchServiceOptions.PerplexityOptions -> copy(alias = newAlias)
    is SearchServiceOptions.FirecrawlOptions -> copy(alias = newAlias)
    is SearchServiceOptions.JinaOptions -> copy(alias = newAlias)
    is SearchServiceOptions.BochaOptions -> copy(alias = newAlias)
    is SearchServiceOptions.NanoGPTOptions -> copy(alias = newAlias)
    is SearchServiceOptions.GrokOptions -> copy(alias = newAlias)
    is SearchServiceOptions.SerperOptions -> copy(alias = newAlias)
}

internal suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { cause, _, _ ->
                    response.closeQuietly()
                }
            }
        })
    }
}
