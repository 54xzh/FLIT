package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.AssistantSearchMode
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.search.MultiSearchStrategy
import me.rerere.search.SearchService
import me.rerere.search.SearchResult
import me.rerere.search.SearchServiceOptions
import kotlin.uuid.Uuid
import java.net.URI

object SearchTools {
    fun createSearchTools(settings: Settings, searchMode: AssistantSearchMode): Set<Tool> {
        return when (searchMode) {
            is AssistantSearchMode.Off,
            is AssistantSearchMode.BuiltIn -> emptySet()
            is AssistantSearchMode.Provider -> createSearchTools(settings, providerIndex = searchMode.index)
            is AssistantSearchMode.MultiProvider -> createMultiProviderSearchTools(settings, providerIndices = searchMode.indices)
        }
    }

    fun createSearchTools(settings: Settings, providerIndex: Int? = null): Set<Tool> {
        val effectiveIndex = providerIndex ?: settings.searchServiceSelected
        return buildSet {
            add(
                Tool(
                    name = "search_web",
                    description = "search web for latest information",
                    parameters = {
                        val options = settings.searchServices.getOrElse(
                            index = effectiveIndex,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        service.parameters
                    },
                    execute = {
                        val options = settings.searchServices.getOrElse(
                            index = effectiveIndex,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        val result = service.search(
                            params = it.jsonObject,
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = options,
                        )
                        val results =
                            JsonInstantPretty.encodeToJsonElement(result.getOrThrow()).jsonObject.let { json ->
                                val map = json.toMutableMap()
                                val items = map["items"]
                                if (items is JsonArray) {
                                    map["items"] = JsonArray(items.mapIndexed { index, item ->
                                        if (item is JsonObject) {
                                            JsonObject(item.toMutableMap().apply {
                                                put("id", JsonPrimitive(Uuid.random().toString().take(6)))
                                                put("index", JsonPrimitive(index + 1))
                                            })
                                        } else {
                                            item
                                        }
                                    })
                                }
                                JsonObject(map).withSearchResultGuidance(includeProviderErrors = false)
                            }
                        results
                    },
                    systemPrompt = { model, _ ->
                        if (model.tools.isNotEmpty()) return@Tool ""
                        SEARCH_WEB_SYSTEM_PROMPT_TEMPLATE
                    },
                )
            )

            val options = settings.searchServices.getOrElse(
                index = effectiveIndex,
                defaultValue = { SearchServiceOptions.DEFAULT })
            val service = SearchService.getService(options)
            if (service.scrapingParameters != null) {
                add(
                    Tool(
                        name = "scrape_web",
                        description = "scrape web for content",
                        parameters = {
                            val options = settings.searchServices.getOrElse(
                                index = effectiveIndex,
                                defaultValue = { SearchServiceOptions.DEFAULT })
                            val service = SearchService.getService(options)
                            service.scrapingParameters
                        },
                        execute = {
                            val options = settings.searchServices.getOrElse(
                                index = effectiveIndex,
                                defaultValue = { SearchServiceOptions.DEFAULT })
                            val service = SearchService.getService(options)
                            val result = service.scrape(
                                params = it.jsonObject,
                                commonOptions = settings.searchCommonOptions,
                                serviceOptions = options,
                            )
                            JsonInstantPretty.encodeToJsonElement(result.getOrThrow()).jsonObject
                        },
                        systemPrompt = { _, _ -> SCRAPE_WEB_SYSTEM_PROMPT_TEMPLATE },
                    )
                )
            }
        }
    }

    internal data class ProviderSearchOutcome(
        val providerName: String,
        val result: Result<SearchResult>,
    )

    internal data class ProviderSearchError(
        val providerName: String,
        val message: String,
    )

    internal data class MergedSearchResult(
        val answer: String?,
        val items: List<SearchResult.SearchResultItem>,
        val errors: List<ProviderSearchError>,
    )

    private fun createMultiProviderSearchTools(settings: Settings, providerIndices: List<Int>): Set<Tool> {
        val sanitizedIndices = providerIndices
            .asSequence()
            .filter { index -> index >= 0 && index < settings.searchServices.size }
            .distinct()
            .sorted()
            .toList()

        if (sanitizedIndices.isEmpty()) return emptySet()

        val primaryIndex = sanitizedIndices.first()

        return buildSet {
            add(
                Tool(
                    name = "search_web",
                    description = "search web for latest information",
                    parameters = {
                        val primaryOptions = settings.searchServices.getOrElse(
                            index = primaryIndex,
                            defaultValue = { SearchServiceOptions.DEFAULT }
                        )
                        val primaryService = SearchService.getService(primaryOptions)
                        primaryService.parameters
                    },
                    execute = { args ->
                        val outcomes: List<ProviderSearchOutcome> =
                            when (settings.searchCommonOptions.multiSearchStrategy) {
                                MultiSearchStrategy.PARALLEL -> supervisorScope {
                                    sanitizedIndices.map { index ->
                                        async {
                                            val options = settings.searchServices.getOrElse(
                                                index = index,
                                                defaultValue = { SearchServiceOptions.DEFAULT }
                                            )
                                            val service = SearchService.getService(options)
                                            val searchResult = runCatching {
                                                service.search(
                                                    params = args.jsonObject,
                                                    commonOptions = settings.searchCommonOptions,
                                                    serviceOptions = options,
                                                ).getOrThrow()
                                            }.normalizeEmptyAsFailure()
                                            ProviderSearchOutcome(providerName = service.name, result = searchResult)
                                        }
                                    }.awaitAll()
                                }
                                MultiSearchStrategy.SEQUENTIAL -> {
                                    val results = mutableListOf<ProviderSearchOutcome>()
                                    for (index in sanitizedIndices) {
                                        val options = settings.searchServices.getOrElse(
                                            index = index,
                                            defaultValue = { SearchServiceOptions.DEFAULT }
                                        )
                                        val service = SearchService.getService(options)
                                        val searchResult = runCatching {
                                            service.search(
                                                params = args.jsonObject,
                                                commonOptions = settings.searchCommonOptions,
                                                serviceOptions = options,
                                            ).getOrThrow()
                                        }.normalizeEmptyAsFailure()
                                        results += ProviderSearchOutcome(providerName = service.name, result = searchResult)
                                        if (searchResult.isSuccess) break
                                    }
                                    results
                                }
                            }

                        val merged = mergeProviderSearchOutcomes(outcomes)

                        val base = JsonInstantPretty.encodeToJsonElement(
                            SearchResult(answer = merged.answer, items = merged.items)
                        ).jsonObject

                        val map = base.toMutableMap()
                        map["errors"] = buildJsonArray {
                            merged.errors.forEach { error ->
                                add(
                                    buildJsonObject {
                                        put("provider", JsonPrimitive(error.providerName))
                                        put("message", JsonPrimitive(error.message))
                                    }
                                )
                            }
                        }

                        val results = JsonObject(map).let { json ->
                            val newMap = json.toMutableMap()
                            val items = newMap["items"]
                            if (items is JsonArray) {
                                newMap["items"] = JsonArray(items.mapIndexed { index, item ->
                                    if (item is JsonObject) {
                                        JsonObject(item.toMutableMap().apply {
                                            put("id", JsonPrimitive(Uuid.random().toString().take(6)))
                                            put("index", JsonPrimitive(index + 1))
                                        })
                                    } else {
                                        item
                                    }
                                })
                            }
                            JsonObject(newMap).withSearchResultGuidance(includeProviderErrors = true)
                        }

                        results
                    },
                    systemPrompt = { model, _ ->
                        if (model.tools.isNotEmpty()) return@Tool ""
                        SEARCH_WEB_SYSTEM_PROMPT_TEMPLATE
                    },
                )
            )

            val firstScrapableIndex = sanitizedIndices.firstOrNull { index ->
                val options = settings.searchServices.getOrElse(index) { SearchServiceOptions.DEFAULT }
                val service = SearchService.getService(options)
                service.scrapingParameters != null
            }

            if (firstScrapableIndex != null) {
                add(
                    Tool(
                        name = "scrape_web",
                        description = "scrape web for content",
                        parameters = {
                            val options = settings.searchServices.getOrElse(firstScrapableIndex) { SearchServiceOptions.DEFAULT }
                            val service = SearchService.getService(options)
                            service.scrapingParameters
                        },
                        execute = { args ->
                            val options = settings.searchServices.getOrElse(firstScrapableIndex) { SearchServiceOptions.DEFAULT }
                            val service = SearchService.getService(options)
                            val result = service.scrape(
                                params = args.jsonObject,
                                commonOptions = settings.searchCommonOptions,
                                serviceOptions = options,
                            )
                            JsonInstantPretty.encodeToJsonElement(result.getOrThrow()).jsonObject
                        },
                        systemPrompt = { _, _ -> SCRAPE_WEB_SYSTEM_PROMPT_TEMPLATE },
                    )
                )
            }
        }
    }

    private class EmptySearchResultException :
        RuntimeException("Search provider returned no answer and no items")

    private fun SearchResult.isMeaningful(): Boolean =
        !answer.isNullOrBlank() || items.isNotEmpty()

    private fun Result<SearchResult>.normalizeEmptyAsFailure(): Result<SearchResult> =
        fold(
            onSuccess = { result ->
                if (result.isMeaningful()) Result.success(result)
                else Result.failure(EmptySearchResultException())
            },
            onFailure = { Result.failure(it) }
        )

    private fun JsonObject.withSearchResultGuidance(includeProviderErrors: Boolean): JsonObject {
        val map = toMutableMap()
        map["citation_rules"] = JsonPrimitive(
            searchWebToolResultGuidance(includeProviderErrors = includeProviderErrors)
        )
        return JsonObject(map)
    }

    internal fun mergeProviderSearchOutcomes(outcomes: List<ProviderSearchOutcome>): MergedSearchResult {
        var mergedAnswer: String? = null
        val mergedItems = mutableListOf<SearchResult.SearchResultItem>()
        val seen = HashSet<String>(outcomes.size * 8)
        val errors = mutableListOf<ProviderSearchError>()

        outcomes.forEach { outcome ->
            val result = outcome.result.getOrNull()
            if (result != null) {
                if (mergedAnswer == null && !result.answer.isNullOrBlank()) {
                    mergedAnswer = result.answer
                }
                result.items.forEach { item ->
                    val key = urlDedupKey(item.url)
                    if (seen.add(key)) mergedItems.add(item)
                }
            } else {
                val message = outcome.result.exceptionOrNull()?.message ?: "Unknown error"
                errors.add(ProviderSearchError(providerName = outcome.providerName, message = message))
            }
        }

        return MergedSearchResult(
            answer = mergedAnswer,
            items = mergedItems,
            errors = errors,
        )
    }

    internal fun urlDedupKey(rawUrl: String): String {
        val trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
        val host = parsed.host?.lowercase()
        if (host.isNullOrBlank()) return trimmed

        val path = (parsed.rawPath ?: "").trimEnd('/')
        val query = parsed.rawQuery
        return buildString {
            append(host)
            append(path)
            if (!query.isNullOrBlank()) {
                append('?')
                append(query)
            }
        }
    }
}
