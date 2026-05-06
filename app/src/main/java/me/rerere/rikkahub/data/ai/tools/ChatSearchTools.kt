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
import me.rerere.rikkahub.data.db.dao.ChatSearchResultRow
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import kotlin.uuid.Uuid

object ChatSearchTools {

    private const val TOOL_DESCRIPTION = "Search the user's past chat conversations."

    private const val MAX_QUERIES = 8
    private const val MAX_LIMIT = 10
    private const val DEFAULT_LIMIT = 5
    private const val EXCERPT_RADIUS = 200
    private const val MAX_RETURN_CHARS = 4000

    fun create(
        assistantId: Uuid,
        conversationId: Uuid,
        conversationRepo: ConversationRepository,
    ): List<Tool> {
        return listOf(createChatSearchTool(assistantId, conversationId, conversationRepo))
    }

    private fun createChatSearchTool(
        assistantId: Uuid,
        conversationId: Uuid,
        conversationRepo: ConversationRepository,
    ): Tool {
        val currentConversationId = conversationId.toString()
        return Tool(
            name = "chat_search",
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
                            put("description", "Max conversations to return (1-$MAX_LIMIT). Default $DEFAULT_LIMIT.")
                        })
                    },
                    required = listOf("queries"),
                )
            },
            systemPrompt = { _, _ -> CHAT_SEARCH_SYSTEM_PROMPT_TEMPLATE },
            execute = { args ->
                executeSearch(args.jsonObject, assistantId, currentConversationId, conversationRepo)
            },
        )
    }

    private suspend fun executeSearch(
        args: JsonObject,
        assistantId: Uuid,
        currentConversationId: String,
        conversationRepo: ConversationRepository,
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

        val tokenizedClauses = clauses.map { MemoryTools.tokenize(it) }
        if (tokenizedClauses.all { it.isEmpty() }) {
            return error("empty_queries", "queries did not contain any usable terms")
        }

        val allFirstTerms = tokenizedClauses.mapNotNull { it.firstOrNull() }.distinct()

        val candidateRows = mutableMapOf<String, ChatSearchResultRow>()
        withContext(Dispatchers.IO) {
            for (term in allFirstTerms) {
                val rows = conversationRepo.searchChatContentOfAssistant(
                    assistantId = assistantId,
                    searchText = term,
                    limit = limit * 3
                )
                for (row in rows) {
                    if (row.id != currentConversationId) {
                        candidateRows.putIfAbsent(row.id, row)
                    }
                }
            }
        }

        data class Hit(
            val id: String,
            val title: String,
            val snippet: String,
            val updatedAt: Long,
            val matchedTerms: List<String>,
        )

        val hits = candidateRows.values.mapNotNull { row ->
            val (terms, matched) = findMatchedClause(row.searchText, tokenizedClauses)
                ?: return@mapNotNull null
            Hit(
                id = row.id,
                title = row.title,
                snippet = multiExcerpt(row.searchText, terms),
                updatedAt = row.updateAt,
                matchedTerms = matched,
            )
        }.sortedByDescending { it.updatedAt }

        val total = hits.size
        val truncated = mutableListOf<Hit>()
        var charBudget = MAX_RETURN_CHARS
        for (hit in hits.take(limit)) {
            val cost = hit.snippet.length + hit.title.length
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
                        put("conversation_id", hit.id)
                        put("title", hit.title)
                        put("snippet", hit.snippet)
                        put("updated_at", hit.updatedAt)
                        put("matched_terms", buildJsonArray {
                            hit.matchedTerms.forEach { add(JsonPrimitive(it)) }
                        })
                    })
                }
            })
        }
    }

    private fun findMatchedClause(
        content: String,
        tokenizedClauses: List<List<String>>,
    ): Pair<List<String>, List<String>>? {
        for (terms in tokenizedClauses) {
            if (terms.isEmpty()) continue
            if (terms.all { content.contains(it, ignoreCase = true) }) {
                return terms to terms
            }
        }
        return null
    }

    internal fun multiExcerpt(content: String, terms: List<String>): String {
        if (content.length <= EXCERPT_RADIUS * 2) return content

        val positionsPerTerm = terms.map { term -> findAllOccurrences(content, term) }

        if (positionsPerTerm.any { it.isEmpty() }) {
            val fallbackIdx = positionsPerTerm.firstNotNullOfOrNull { it.firstOrNull() } ?: 0
            return excerptAround(content, fallbackIdx, EXCERPT_RADIUS)
        }

        if (positionsPerTerm.size == 1) {
            return excerptAround(content, positionsPerTerm[0][0], EXCERPT_RADIUS)
        }

        val bestPositions = findClosestGroup(positionsPerTerm)
        val minPos = bestPositions.min()
        val maxTermIdx = bestPositions.indices.maxByOrNull { bestPositions[it] } ?: 0
        val maxPos = bestPositions[maxTermIdx] + terms[maxTermIdx].length

        val center = (minPos + maxPos) / 2
        return excerptAround(content, center, EXCERPT_RADIUS)
    }

    private fun findAllOccurrences(content: String, term: String): List<Int> {
        val positions = mutableListOf<Int>()
        var start = 0
        while (true) {
            val idx = content.indexOf(term, start, ignoreCase = true)
            if (idx < 0) break
            positions += idx
            start = idx + 1
        }
        return positions
    }

    private fun findClosestGroup(positionsPerTerm: List<List<Int>>): List<Int> {
        var bestSpan = Int.MAX_VALUE
        var bestGroup = positionsPerTerm.map { it.first() }

        val indices = IntArray(positionsPerTerm.size)

        while (true) {
            val current = positionsPerTerm.indices.map { positionsPerTerm[it][indices[it]] }
            val span = current.max() - current.min()
            if (span < bestSpan) {
                bestSpan = span
                bestGroup = current
            }
            if (span == 0) break

            val minIdx = current.indices.minByOrNull { current[it] } ?: break
            if (indices[minIdx] + 1 >= positionsPerTerm[minIdx].size) break
            indices[minIdx]++
        }

        return bestGroup
    }

    private fun excerptAround(content: String, center: Int, radius: Int): String {
        val start = (center - radius).coerceAtLeast(0)
        val end = (center + radius).coerceAtMost(content.length)
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
