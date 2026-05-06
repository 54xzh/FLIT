package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import kotlin.uuid.Uuid

object MemoryTools {

    private const val TOOL_DESCRIPTION = "Search this assistant's saved memories."

    private const val MAX_QUERIES = 8
    private const val MAX_LIMIT = 30
    private const val DEFAULT_LIMIT = 8
    private const val EPISODE_WINDOW_RADIUS = 60
    private const val MAX_RETURN_CHARS = 3000
    private const val SCOPE_ALL = "all"
    private const val SCOPE_CORE = "core"
    private const val SCOPE_EPISODIC = "episodic"

    fun create(
        assistantId: Uuid,
        memoryRepository: MemoryRepository,
    ): List<Tool> {
        return listOf(createMemorySearchTool(assistantId, memoryRepository))
    }

    private fun createMemorySearchTool(
        assistantId: Uuid,
        memoryRepository: MemoryRepository,
    ): Tool {
        val assistantIdStr = assistantId.toString()
        return Tool(
            name = "memory_search",
            description = TOOL_DESCRIPTION,
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("queries", buildJsonObject {
                            put("type", "array")
                            put("description",
                                "Array of query strings (1-$MAX_QUERIES). Space inside one string = AND. " +
                                    "Different strings = OR. Quote phrases with double quotes."
                            )
                            put("items", buildJsonObject {
                                put("type", "string")
                            })
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "Max items to return (1-$MAX_LIMIT). Default $DEFAULT_LIMIT.")
                        })
                        put("scope", buildJsonObject {
                            put("type", "string")
                            put("description", "Filter by memory type: \"all\" | \"core\" | \"episodic\". Default \"all\".")
                        })
                    },
                    required = listOf("queries"),
                )
            },
            systemPrompt = { _, _ -> MEMORY_SEARCH_SYSTEM_PROMPT_TEMPLATE },
            execute = { args ->
                executeSearch(args.jsonObject, assistantIdStr, memoryRepository)
            },
        )
    }

    private suspend fun executeSearch(
        args: JsonObject,
        assistantId: String,
        memoryRepository: MemoryRepository,
    ): JsonObject {
        val rawQueries = (args["queries"] as? JsonArray)
            ?: return error("invalid_query", "queries must be an array of strings")

        val clauses = rawQueries.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull?.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_QUERIES)

        if (clauses.isEmpty()) {
            return error("empty_queries", "queries must contain at least one non-empty string")
        }

        val limit = args["limit"]?.jsonPrimitiveOrNull?.intOrNull
            ?.coerceIn(1, MAX_LIMIT) ?: DEFAULT_LIMIT

        val scope = args["scope"]?.jsonPrimitiveOrNull?.contentOrNull?.trim()?.lowercase()
            ?: SCOPE_ALL
        val includeCore = scope == SCOPE_ALL || scope == SCOPE_CORE
        val includeEpisodic = scope == SCOPE_ALL || scope == SCOPE_EPISODIC
        if (!includeCore && !includeEpisodic) {
            return error("invalid_scope", "scope must be one of: all | core | episodic")
        }

        // Pre-tokenize each clause: List<List<String>> (outer=OR, inner=AND)
        val tokenizedClauses = clauses.map { tokenize(it) }
        if (tokenizedClauses.all { it.isEmpty() }) {
            return error("empty_queries", "queries did not contain any usable terms")
        }

        val memories = if (includeCore) {
            withContext(Dispatchers.IO) { memoryRepository.getMemoryEntitiesOfAssistant(assistantId) }
        } else {
            emptyList()
        }
        val episodes = if (includeEpisodic) {
            withContext(Dispatchers.IO) { memoryRepository.getEpisodeEntitiesOfAssistant(assistantId) }
        } else {
            emptyList()
        }

        data class Hit(
            val id: Int,                  // sign-encoded id (core positive, episodic negative)
            val type: String,             // "core" | "episodic"
            val content: String,          // possibly-window-trimmed content
            val timestamp: Long,
            val pinned: Boolean,
            val significance: Int?,
            val matchedTerms: List<String>,
        )

        fun matchedTermsOf(content: String): List<String>? {
            // Returns the AND-terms of the first satisfied OR clause, or null if none match.
            for (terms in tokenizedClauses) {
                if (terms.isEmpty()) continue
                if (terms.all { content.contains(it, ignoreCase = true) }) {
                    return terms
                }
            }
            return null
        }

        val coreHits = memories.mapNotNull { mem ->
            val matched = matchedTermsOf(mem.content) ?: return@mapNotNull null
            Hit(
                id = mem.id,
                type = "core",
                content = mem.content,
                timestamp = mem.createdAt,
                pinned = mem.pinned,
                significance = null,
                matchedTerms = matched,
            )
        }

        val episodeHits = episodes.mapNotNull { ep ->
            val matched = matchedTermsOf(ep.content) ?: return@mapNotNull null
            Hit(
                id = -ep.id,
                type = "episodic",
                content = excerpt(ep.content, matched.firstOrNull()),
                timestamp = maxOf(ep.endTime, ep.startTime),
                pinned = false,
                significance = ep.significance,
                matchedTerms = matched,
            )
        }

        val ranked = (coreHits + episodeHits).sortedWith(
            compareByDescending<Hit> { it.pinned }.thenByDescending { it.timestamp }
        )

        val total = ranked.size
        val truncated = mutableListOf<Hit>()
        var charBudget = MAX_RETURN_CHARS
        for (hit in ranked.take(limit)) {
            val cost = hit.content.length
            if (truncated.isNotEmpty() && cost > charBudget) break
            truncated += hit
            charBudget -= cost
            if (charBudget <= 0) break
        }

        return buildJsonObject {
            put("ok", true)
            put("total", total)
            put("returned", truncated.size)
            put("items", buildJsonArray {
                truncated.forEach { hit ->
                    add(buildJsonObject {
                        put("memory_id", hit.id)
                        put("type", hit.type)
                        put("content", hit.content)
                        put("timestamp", hit.timestamp)
                        put("pinned", JsonPrimitive(hit.pinned))
                        if (hit.significance != null) {
                            put("significance", hit.significance)
                        }
                        put("matched_terms", buildJsonArray {
                            hit.matchedTerms.forEach { add(JsonPrimitive(it)) }
                        })
                    })
                }
            })
        }
    }

    /**
     * Split a clause into AND-tokens.
     * - Whitespace-separated.
     * - A double-quoted run is kept verbatim (without the quotes).
     * - Unclosed quote: treat the rest of the string as one token (stripping the leading quote).
     * - Empty quoted runs are dropped.
     */
    internal fun tokenize(clause: String): List<String> {
        val tokens = mutableListOf<String>()
        val buf = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < clause.length) {
            val c = clause[i]
            when {
                c == '"' -> inQuote = !inQuote
                c.isWhitespace() && !inQuote -> {
                    if (buf.isNotEmpty()) {
                        tokens += buf.toString()
                        buf.setLength(0)
                    }
                }
                else -> buf.append(c)
            }
            i++
        }
        if (buf.isNotEmpty()) tokens += buf.toString()
        return tokens.filter { it.isNotBlank() }
    }

    /**
     * Returns true if [content] satisfies the query rules:
     * any clause whose AND-terms are all substrings of content (case-insensitive).
     * Exposed for unit-testing.
     */
    internal fun matches(content: String, clauses: List<String>): Boolean {
        for (clause in clauses) {
            val terms = tokenize(clause)
            if (terms.isEmpty()) continue
            if (terms.all { content.contains(it, ignoreCase = true) }) return true
        }
        return false
    }

    /**
     * Return a window of [content] around the first occurrence of [anchor].
     * If anchor missing or content already short enough, returns content unchanged.
     */
    private fun excerpt(content: String, anchor: String?): String {
        if (anchor.isNullOrEmpty()) return content
        if (content.length <= EPISODE_WINDOW_RADIUS * 2 + anchor.length) return content
        val idx = content.indexOf(anchor, ignoreCase = true)
        if (idx < 0) return content.take(EPISODE_WINDOW_RADIUS * 2 + anchor.length)
        val start = (idx - EPISODE_WINDOW_RADIUS).coerceAtLeast(0)
        val end = (idx + anchor.length + EPISODE_WINDOW_RADIUS).coerceAtMost(content.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < content.length) "…" else ""
        return prefix + content.substring(start, end) + suffix
    }

    private fun error(code: String, message: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("error_code", code)
        put("error", message)
    }
}
