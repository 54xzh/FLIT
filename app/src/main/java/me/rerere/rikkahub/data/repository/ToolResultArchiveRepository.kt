package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.ai.rag.EmbeddingService
import me.rerere.rikkahub.data.ai.rag.VectorEngine
import me.rerere.rikkahub.data.db.dao.EmbeddingCacheDAO
import me.rerere.rikkahub.data.db.dao.ToolResultArchiveDao
import me.rerere.rikkahub.data.db.dao.ToolResultArchiveChunkDao
import me.rerere.rikkahub.data.db.entity.EmbeddingCacheEntity
import me.rerere.rikkahub.data.db.entity.MemoryType
import me.rerere.rikkahub.data.db.entity.ToolResultArchiveEntity
import me.rerere.rikkahub.data.db.entity.ToolResultArchiveChunkEntity
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import java.util.concurrent.ConcurrentHashMap

class ToolResultArchiveRepository(
    private val dao: ToolResultArchiveDao,
    private val chunkDao: ToolResultArchiveChunkDao,
    private val embeddingService: EmbeddingService,
    private val embeddingCacheDAO: EmbeddingCacheDAO,
    private val appScope: AppScope,
) {
    private companion object {
        const val TAG = "ToolResultArchiveRepo"
    }

    private val internalToolNames = setOf("create_memory", "edit_memory", "delete_memory")

    private data class ChunkIndexTask(
        val conversationId: String,
        val assistantId: String,
        val toolCallId: String,
        val toolName: String,
        val userTurnIndex: Int,
        val arguments: JsonElement,
        val content: JsonElement,
    )

    private val chunkIndexQueue = Channel<ChunkIndexTask>(capacity = Channel.BUFFERED)
    private val chunkIndexInFlight = ConcurrentHashMap.newKeySet<String>()

    init {
        appScope.launch {
            for (task in chunkIndexQueue) {
                val key = "${task.conversationId}:${task.toolCallId}"
                try {
                    indexChunksForToolResult(task)
                } catch (t: Throwable) {
                    Log.w(TAG, "Chunk prebuild failed for ${task.toolName}/${task.toolCallId}: ${t.message}", t)
                } finally {
                    chunkIndexInFlight.remove(key)
                }
            }
        }
    }

    suspend fun backfillFromMessages(
        conversationId: String,
        assistantId: String,
        messages: List<UIMessage>,
        enableRagIndexing: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        if (conversationId.isBlank()) return@withContext
        if (assistantId.isBlank()) return@withContext
        if (messages.isEmpty()) return@withContext

        var userTurnIndex = 0
        val chunkTasks = mutableListOf<ChunkIndexTask>()
        val entities = buildList {
            messages.forEach { message ->
                if (message.role == MessageRole.USER) userTurnIndex++
                if (message.role != MessageRole.TOOL) return@forEach
                message.getToolResults().forEachIndexed { index, result ->
                    if (result.toolName in internalToolNames) return@forEach
                    val resolvedToolCallId = result.toolCallId.ifBlank {
                        "msg_${message.id}_$index"
                    }
                    chunkTasks.add(
                        ChunkIndexTask(
                            conversationId = conversationId,
                            assistantId = assistantId,
                            toolCallId = resolvedToolCallId,
                            toolName = result.toolName,
                            userTurnIndex = userTurnIndex,
                            arguments = result.arguments,
                            content = result.content,
                        )
                    )
                    add(
                        ToolResultArchiveEntity(
                            conversationId = conversationId,
                            assistantId = assistantId,
                            toolCallId = resolvedToolCallId,
                            toolName = result.toolName,
                            argumentsJson = result.arguments.toString(),
                            contentJson = result.content.toString(),
                            metadataJson = result.metadata?.toString(),
                            extractText = buildExtractText(result.toolName, result.arguments, result.content),
                            userTurnIndex = userTurnIndex,
                        )
                    )
                }
            }
        }

        if (entities.isNotEmpty()) {
            dao.insertAll(entities)
        }

        if (enableRagIndexing) {
            ensureChunkRowsExist(chunkTasks)
            enqueueChunkIndexing(chunkTasks)
        }
    }

    suspend fun archiveToolResults(
        conversationId: String,
        assistantId: String,
        userTurnIndex: Int,
        results: List<UIMessagePart.ToolResult>,
        enableRagIndexing: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        if (conversationId.isBlank()) return@withContext
        if (assistantId.isBlank()) return@withContext
        if (results.isEmpty()) return@withContext

        val filtered = results.filter { it.toolName !in internalToolNames }
        val entities = filtered.mapIndexed { index, result ->
            val resolvedToolCallId = result.toolCallId.ifBlank {
                "gen_${result.toolName}_${userTurnIndex}_$index"
            }
            ToolResultArchiveEntity(
                conversationId = conversationId,
                assistantId = assistantId,
                toolCallId = resolvedToolCallId,
                toolName = result.toolName,
                argumentsJson = result.arguments.toString(),
                contentJson = result.content.toString(),
                metadataJson = result.metadata?.toString(),
                extractText = buildExtractText(result.toolName, result.arguments, result.content),
                userTurnIndex = userTurnIndex.coerceAtLeast(0),
            )
        }

        if (entities.isNotEmpty()) {
            dao.insertAll(entities)
        }

        if (enableRagIndexing) {
            enqueueChunkIndexing(
                filtered.mapIndexed { index, result ->
                    val resolvedToolCallId = result.toolCallId.ifBlank {
                        "gen_${result.toolName}_${userTurnIndex}_$index"
                    }
                    ChunkIndexTask(
                        conversationId = conversationId,
                        assistantId = assistantId,
                        toolCallId = resolvedToolCallId,
                        toolName = result.toolName,
                        userTurnIndex = userTurnIndex.coerceAtLeast(0),
                        arguments = result.arguments,
                        content = result.content,
                    )
                }
            )
        }
    }

    private suspend fun ensureChunkRowsExist(tasks: List<ChunkIndexTask>) {
        if (tasks.isEmpty()) return

        val conversationId = tasks.first().conversationId
        if (conversationId.isBlank()) return

        val existingToolCallIds = runCatching {
            chunkDao.getToolCallIdsByConversationId(conversationId)
        }.getOrNull()?.toHashSet() ?: hashSetOf()

        tasks.asSequence()
            .distinctBy { it.toolCallId }
            .filter { it.toolCallId.isNotBlank() }
            .filterNot { existingToolCallIds.contains(it.toolCallId) }
            .forEach { task ->
                val body = buildChunkBodyText(task.toolName, task.arguments, task.content)
                if (body.isBlank()) return@forEach

                val chunkTexts = chunkText(body)
                if (chunkTexts.isEmpty()) return@forEach

                val entities = chunkTexts.mapIndexed { index, chunkText ->
                    ToolResultArchiveChunkEntity(
                        conversationId = task.conversationId,
                        assistantId = task.assistantId,
                        toolCallId = task.toolCallId,
                        toolName = task.toolName,
                        chunkIndex = index,
                        chunkText = chunkText,
                        userTurnIndex = task.userTurnIndex.coerceAtLeast(0),
                    )
                }
                chunkDao.insertAll(entities)
            }
    }

    private fun enqueueChunkIndexing(tasks: List<ChunkIndexTask>) {
        if (tasks.isEmpty()) return
        tasks.forEach { task ->
            if (task.conversationId.isBlank() || task.assistantId.isBlank()) return@forEach
            if (task.toolCallId.isBlank() || task.toolName.isBlank()) return@forEach

            val key = "${task.conversationId}:${task.toolCallId}"
            if (!chunkIndexInFlight.add(key)) return@forEach

            val result = chunkIndexQueue.trySend(task)
            if (result.isFailure) {
                chunkIndexInFlight.remove(key)
            }
        }
    }

    private suspend fun indexChunksForToolResult(task: ChunkIndexTask) = withContext(Dispatchers.IO) {
        val embeddingModelId = embeddingService.getEmbeddingModelId(task.assistantId)

        val existing = chunkDao.getByToolCallId(task.conversationId, task.toolCallId)
        val upToDate = existing.isNotEmpty() && existing.all {
            !it.embedding.isNullOrBlank() && it.embeddingModelId == embeddingModelId
        }
        if (upToDate) return@withContext

        val chunks = if (existing.isEmpty()) {
            val body = buildChunkBodyText(task.toolName, task.arguments, task.content)
            if (body.isBlank()) return@withContext

            val chunkTexts = chunkText(body)
            if (chunkTexts.isEmpty()) return@withContext

            val entities = chunkTexts.mapIndexed { index, chunkText ->
                ToolResultArchiveChunkEntity(
                    conversationId = task.conversationId,
                    assistantId = task.assistantId,
                    toolCallId = task.toolCallId,
                    toolName = task.toolName,
                    chunkIndex = index,
                    chunkText = chunkText,
                    userTurnIndex = task.userTurnIndex.coerceAtLeast(0),
                )
            }
            val insertedIds = chunkDao.insertAll(entities)
            entities.zip(insertedIds).mapNotNull { (entity, id) ->
                if (id <= 0) return@mapNotNull null
                entity.copy(id = id.toInt())
            }
        } else {
            existing.filter {
                it.embedding.isNullOrBlank() || it.embeddingModelId != embeddingModelId
            }
        }

        if (chunks.isEmpty()) return@withContext

        val contextPrefix = buildChunkContextPrefix(task.toolName, task.arguments, task.content)
        embedAndPersistChunkEmbeddings(
            assistantId = task.assistantId,
            embeddingModelId = embeddingModelId,
            contextPrefix = contextPrefix,
            chunks = chunks,
        )
    }

    private suspend fun embedAndPersistChunkEmbeddings(
        assistantId: String,
        embeddingModelId: String,
        contextPrefix: String,
        chunks: List<ToolResultArchiveChunkEntity>,
    ) = withContext(Dispatchers.IO) {
        if (chunks.isEmpty()) return@withContext

        val batchSize = 8
        val prefix = contextPrefix.trim().take(800)

        chunks.chunked(batchSize).forEach { batch ->
            val inputs = batch.map { chunk ->
                val chunkText = chunk.chunkText.trim()
                if (prefix.isBlank()) chunkText else "$prefix\n\n$chunkText"
            }

            val result = runCatching {
                embeddingService.embedBatch(
                    texts = inputs,
                    assistantId = assistantId,
                    source = AIRequestSource.TOOL_RESULT_EMBEDDING,
                )
            }.getOrElse { t ->
                Log.w(TAG, "Chunk embedding batch failed (tool=${chunks.firstOrNull()?.toolName}): ${t.message}", t)
                return@forEach
            }

            batch.zip(result.embeddings).forEach { (chunk, embedding) ->
                val embeddingJson = JsonInstant.encodeToString(embedding)
                chunkDao.updateEmbedding(chunk.id, embeddingJson, result.modelId)
                embeddingCacheDAO.insertEmbedding(
                    EmbeddingCacheEntity(
                        memoryId = chunk.id,
                        memoryType = MemoryType.TOOL_RESULT_CHUNK,
                        modelId = result.modelId,
                        embedding = embeddingJson,
                    )
                )
            }
        }
    }

    private fun buildChunkContextPrefix(
        toolName: String,
        arguments: JsonElement,
        content: JsonElement,
    ): String {
        return when (toolName) {
            "search_agent" -> {
                val task = (arguments as? JsonObject)
                    ?.get("task")
                    ?.jsonPrimitiveOrNull
                    ?.contentOrNull
                val summary = (content as? JsonObject)
                    ?.get("summary")
                    ?.jsonPrimitiveOrNull
                    ?.contentOrNull
                buildString {
                    append("tool: search_agent")
                    if (!task.isNullOrBlank()) append("\ntask: ${task.take(240)}")
                    if (!summary.isNullOrBlank()) append("\nsummary: ${summary.take(240)}")
                }
            }

            "search_web" -> {
                val query = (arguments as? JsonObject)
                    ?.get("query")
                    ?.jsonPrimitiveOrNull
                    ?.contentOrNull
                buildString {
                    append("tool: search_web")
                    if (!query.isNullOrBlank()) append("\nquery: $query")
                }
            }

            "scrape_web" -> {
                val url = (arguments as? JsonObject)
                    ?.get("url")
                    ?.jsonPrimitiveOrNull
                    ?.contentOrNull
                val title = (content as? JsonObject)
                    ?.get("title")
                    ?.jsonPrimitiveOrNull
                    ?.contentOrNull
                buildString {
                    append("tool: scrape_web")
                    if (!url.isNullOrBlank()) append("\nurl: $url")
                    if (!title.isNullOrBlank()) append("\ntitle: ${title.take(200)}")
                }
            }

            else -> "tool: $toolName"
        }
    }

    private fun buildChunkBodyText(
        toolName: String,
        arguments: JsonElement,
        content: JsonElement,
    ): String {
        val maxChars = 80_000
        val raw = when (toolName) {
            "search_agent" -> buildSearchAgentFullText(arguments, content)
            "search_web" -> buildSearchWebFullText(arguments, content)
            "scrape_web" -> buildScrapeWebFullText(arguments, content)
            else -> buildGenericFullText(toolName, arguments, content)
        }
        return raw.trim().take(maxChars)
    }

    private fun chunkText(text: String): List<String> {
        val cleaned = text.replace("\r\n", "\n").trim()
        if (cleaned.isBlank()) return emptyList()

        val chunkSize = 1100
        val overlap = 160
        val maxChunks = 48

        if (cleaned.length <= chunkSize) return listOf(cleaned)

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < cleaned.length && chunks.size < maxChunks) {
            val endExclusive = (start + chunkSize).coerceAtMost(cleaned.length)
            val slice = cleaned.substring(start, endExclusive).trim()
            if (slice.isNotBlank()) chunks.add(slice)
            if (endExclusive == cleaned.length) break
            start = (endExclusive - overlap).coerceAtLeast(start + 1)
        }
        return chunks
    }

    private fun buildSearchWebFullText(arguments: JsonElement, content: JsonElement): String {
        val argsObject = arguments as? JsonObject
        val query = argsObject?.get("query")?.jsonPrimitiveOrNull?.contentOrNull

        val root = content as? JsonObject
        val items = root?.get("items") as? JsonArray

        return buildString {
            if (!query.isNullOrBlank()) appendLine("query: $query")
            if (items != null) {
                appendLine("results:")
                items.jsonArray.take(30).forEach { item ->
                    val obj = item as? JsonObject ?: return@forEach
                    val title = obj["title"]?.jsonPrimitiveOrNull?.contentOrNull
                    val url = obj["url"]?.jsonPrimitiveOrNull?.contentOrNull
                    val text = obj["text"]?.jsonPrimitiveOrNull?.contentOrNull
                    if (!title.isNullOrBlank()) appendLine("title: ${title.take(240)}")
                    if (!url.isNullOrBlank()) appendLine("url: $url")
                    if (!text.isNullOrBlank()) {
                        appendLine(text.trim())
                    }
                    appendLine()
                }
            }
        }
    }

    private fun buildSearchAgentFullText(arguments: JsonElement, content: JsonElement): String {
        val argsObject = arguments as? JsonObject
        val task = argsObject?.get("task")?.jsonPrimitiveOrNull?.contentOrNull
        val urls = (argsObject?.get("urls") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
            .orEmpty()

        val root = content as? JsonObject
        val summary = root?.get("summary")?.jsonPrimitiveOrNull?.contentOrNull
        val sources = root?.get("sources") as? JsonArray
        val notes = root?.get("notes") as? JsonArray

        return buildString {
            if (!task.isNullOrBlank()) appendLine("task: $task")
            if (urls.isNotEmpty()) {
                appendLine("urls:")
                urls.take(20).forEach { appendLine("- $it") }
            }
            if (!summary.isNullOrBlank()) {
                appendLine("summary:")
                appendLine(summary.trim())
            }
            if (sources != null) {
                appendLine("sources:")
                sources.jsonArray.take(30).forEach { source ->
                    val obj = source as? JsonObject ?: return@forEach
                    val id = obj["id"]?.jsonPrimitiveOrNull?.contentOrNull
                    val title = obj["title"]?.jsonPrimitiveOrNull?.contentOrNull
                    val url = obj["url"]?.jsonPrimitiveOrNull?.contentOrNull
                    val snippet = obj["snippet"]?.jsonPrimitiveOrNull?.contentOrNull
                    if (!id.isNullOrBlank()) appendLine("id: $id")
                    if (!title.isNullOrBlank()) appendLine("title: ${title.take(240)}")
                    if (!url.isNullOrBlank()) appendLine("url: $url")
                    if (!snippet.isNullOrBlank()) appendLine("snippet: ${snippet.take(600)}")
                    appendLine()
                }
            }
            if (notes != null) {
                appendLine("notes:")
                notes.jsonArray.take(10).forEach { note ->
                    val text = note.jsonPrimitiveOrNull?.contentOrNull
                    if (!text.isNullOrBlank()) appendLine("- ${text.take(400)}")
                }
            }
        }
    }

    private fun buildScrapeWebFullText(arguments: JsonElement, content: JsonElement): String {
        val argsObject = arguments as? JsonObject
        val url = argsObject?.get("url")?.jsonPrimitiveOrNull?.contentOrNull

        val root = content as? JsonObject
        val title = root?.get("title")?.jsonPrimitiveOrNull?.contentOrNull
        val text = root?.get("text")?.jsonPrimitiveOrNull?.contentOrNull

        return buildString {
            if (!url.isNullOrBlank()) appendLine("url: $url")
            if (!title.isNullOrBlank()) appendLine("title: ${title.take(300)}")
            if (!text.isNullOrBlank()) {
                appendLine()
                appendLine(text.trim())
            }
        }
    }

    private fun buildGenericFullText(toolName: String, arguments: JsonElement, content: JsonElement): String {
        return buildString {
            appendLine("tool: $toolName")
            appendLine("arguments:")
            appendLine(arguments.toString())
            appendLine()
            appendLine("result:")
            appendLine(content.toString())
        }
    }

    suspend fun retrieveRelevantToolResultsWithScores(
        conversationId: String,
        assistantId: String,
        query: String,
        maxUserTurnIndexExclusive: Int,
        limit: Int = 6,
        similarityThreshold: Float = 0.35f,
    ): List<Pair<ToolResultArchiveEntity, Float>> = withContext(Dispatchers.IO) {
        if (conversationId.isBlank()) return@withContext emptyList()
        if (assistantId.isBlank()) return@withContext emptyList()
        if (query.isBlank()) return@withContext emptyList()
        if (maxUserTurnIndexExclusive <= 0) return@withContext emptyList()

        val candidates = dao.getByConversationBeforeTurn(
            conversationId = conversationId,
            maxUserTurnIndexExclusive = maxUserTurnIndexExclusive,
        )
        if (candidates.isEmpty()) return@withContext emptyList()

        val queryEmbedding = runCatching {
            embeddingService.embed(
                text = query,
                assistantId = assistantId,
                source = AIRequestSource.TOOL_RESULT_RAG,
            )
        }.getOrElse { t ->
            Log.w(TAG, "Failed to compute query embedding for tool result chunk RAG: ${t.message}", t)
            return@withContext emptyList()
        }

        val scored = candidates.mapNotNull { entity ->
            val embedding = getOrCreateEmbedding(entity, assistantId) ?: return@mapNotNull null
            val similarity = VectorEngine.cosineSimilarity(queryEmbedding, embedding)
            if (similarity >= similarityThreshold) entity to similarity else null
        }.sortedByDescending { it.second }
            .take(limit)

        if (scored.isNotEmpty()) {
            dao.touch(scored.map { it.first.id }, System.currentTimeMillis())
        }

        scored
    }

    suspend fun retrieveRelevantToolResultChunksWithScores(
        conversationId: String,
        assistantId: String,
        query: String,
        maxUserTurnIndexExclusive: Int,
        limit: Int = 6,
        similarityThreshold: Float = 0.35f,
    ): List<Pair<ToolResultArchiveChunkEntity, Float>> = withContext(Dispatchers.IO) {
        if (conversationId.isBlank()) return@withContext emptyList()
        if (assistantId.isBlank()) return@withContext emptyList()
        if (query.isBlank()) return@withContext emptyList()
        if (maxUserTurnIndexExclusive <= 0) return@withContext emptyList()
        if (limit <= 0) return@withContext emptyList()

        val candidates = chunkDao.getByConversationBeforeTurn(
            conversationId = conversationId,
            maxUserTurnIndexExclusive = maxUserTurnIndexExclusive,
        )

        val queryEmbedding = runCatching {
            embeddingService.embed(
                text = query,
                assistantId = assistantId,
                source = AIRequestSource.TOOL_RESULT_RAG,
            )
        }.getOrNull() ?: return@withContext emptyList()

        val embeddingModelId = embeddingService.getEmbeddingModelId(assistantId)

        val scored = mutableListOf<Pair<ToolResultArchiveChunkEntity, Float>>()
        val hasMissingEmbeddings = candidates.any { chunk ->
            chunk.embedding.isNullOrBlank() || chunk.embeddingModelId != embeddingModelId
        }
        if (candidates.isNotEmpty()) {
            val (ready, _) = candidates.partition {
                !it.embedding.isNullOrBlank() && it.embeddingModelId == embeddingModelId
            }
            ready.forEach { chunk ->
                val embedding = runCatching {
                    JsonInstant.decodeFromString<List<Float>>(chunk.embedding ?: "")
                }.getOrNull() ?: return@forEach
                val similarity = VectorEngine.cosineSimilarity(queryEmbedding, embedding)
                scored.add(chunk to similarity)
            }
        }

        if (scored.size < limit && (candidates.isEmpty() || hasMissingEmbeddings || scored.isEmpty())) {
            val additional = retrieveChunksByToolLevelPreselection(
                conversationId = conversationId,
                assistantId = assistantId,
                queryEmbedding = queryEmbedding,
                maxUserTurnIndexExclusive = maxUserTurnIndexExclusive,
                limit = limit,
                similarityThreshold = similarityThreshold,
            )
            scored.addAll(additional)
        }

        val dedupScored = scored
            .distinctBy { it.first.id }
            .sortedByDescending { it.second }

        val finalScored = dedupScored
            .asSequence()
            .filter { it.second >= similarityThreshold }
            .take(limit)
            .toList()

        if (finalScored.isNotEmpty()) {
            chunkDao.touch(finalScored.map { it.first.id }, System.currentTimeMillis())
        }

        finalScored
    }

    private suspend fun retrieveChunksByToolLevelPreselection(
        conversationId: String,
        assistantId: String,
        queryEmbedding: List<Float>,
        maxUserTurnIndexExclusive: Int,
        limit: Int,
        similarityThreshold: Float,
    ): List<Pair<ToolResultArchiveChunkEntity, Float>> = withContext(Dispatchers.IO) {
        if (conversationId.isBlank()) return@withContext emptyList()
        if (assistantId.isBlank()) return@withContext emptyList()
        if (maxUserTurnIndexExclusive <= 0) return@withContext emptyList()
        if (limit <= 0) return@withContext emptyList()

        val toolCandidates = dao.getByConversationBeforeTurn(
            conversationId = conversationId,
            maxUserTurnIndexExclusive = maxUserTurnIndexExclusive,
        )
        if (toolCandidates.isEmpty()) return@withContext emptyList()

        val scoredTools = mutableListOf<Pair<ToolResultArchiveEntity, Float>>()
        toolCandidates
            .asSequence()
            .sortedByDescending { it.userTurnIndex }
            .take(80)
            .forEach { entity ->
                val embedding = getOrCreateEmbedding(entity, assistantId) ?: return@forEach
                val similarity = VectorEngine.cosineSimilarity(queryEmbedding, embedding)
                scoredTools.add(entity to similarity)
            }
        scoredTools.sortByDescending { it.second }

        val selectedTools = scoredTools
            .asSequence()
            .filter { it.second >= similarityThreshold }
            .take(4)
            .map { it.first }
            .toList()

        if (selectedTools.isEmpty()) return@withContext emptyList()

        val chunks = mutableListOf<ToolResultArchiveChunkEntity>()
        selectedTools.forEach { entity ->
            val toolCallId = entity.toolCallId
            if (toolCallId.isBlank()) return@forEach

            var existing = chunkDao.getByToolCallId(conversationId, toolCallId)
            if (existing.isEmpty()) {
                val arguments = runCatching {
                    JsonInstant.parseToJsonElement(entity.argumentsJson)
                }.getOrNull() ?: JsonObject(emptyMap())

                val content = runCatching {
                    JsonInstant.parseToJsonElement(entity.contentJson)
                }.getOrNull() ?: JsonObject(emptyMap())

                val body = buildChunkBodyText(entity.toolName, arguments, content)
                if (body.isNotBlank()) {
                    val chunkTexts = chunkText(body)
                    if (chunkTexts.isNotEmpty()) {
                        val chunkEntities = chunkTexts.mapIndexed { index, chunkText ->
                            ToolResultArchiveChunkEntity(
                                conversationId = conversationId,
                                assistantId = assistantId,
                                toolCallId = toolCallId,
                                toolName = entity.toolName,
                                chunkIndex = index,
                                chunkText = chunkText,
                                userTurnIndex = entity.userTurnIndex.coerceAtLeast(0),
                            )
                        }
                        chunkDao.insertAll(chunkEntities)
                        existing = chunkDao.getByToolCallId(conversationId, toolCallId)
                    }
                }
            }

            existing.filterTo(chunks) { chunk ->
                chunk.userTurnIndex < maxUserTurnIndexExclusive
            }
        }

        if (chunks.isEmpty()) return@withContext emptyList()

        val chunkEmbeddings = getOrCreateChunkEmbeddings(
            chunks = chunks,
            assistantId = assistantId,
        )

        val scoredChunks = chunkEmbeddings
            .map { (chunk, embedding) ->
                val similarity = VectorEngine.cosineSimilarity(queryEmbedding, embedding)
                chunk to similarity
            }
            .sortedByDescending { it.second }

        scoredChunks
            .asSequence()
            .filter { it.second >= similarityThreshold }
            .take(limit)
            .toList()
    }

    private suspend fun getOrCreateEmbedding(
        entity: ToolResultArchiveEntity,
        assistantId: String,
    ): List<Float>? {
        val modelId = embeddingService.getEmbeddingModelId(assistantId)

        // Check cache first
        val cached = embeddingCacheDAO.getEmbedding(entity.id, MemoryType.TOOL_RESULT, modelId)
        if (cached != null) {
            return runCatching {
                JsonInstant.decodeFromString<List<Float>>(cached.embedding)
            }.getOrNull()
        }

        // Check existing embedding in entity (fallback)
        if (!entity.embedding.isNullOrBlank() && entity.embeddingModelId == modelId) {
            val decoded = runCatching {
                JsonInstant.decodeFromString<List<Float>>(entity.embedding)
            }.getOrNull()
            if (decoded != null) {
                embeddingCacheDAO.insertEmbedding(
                    EmbeddingCacheEntity(
                        memoryId = entity.id,
                        memoryType = MemoryType.TOOL_RESULT,
                        modelId = modelId,
                        embedding = entity.embedding,
                    )
                )
                return decoded
            }
        }

        // Generate new embedding
        val embedding = runCatching {
            embeddingService.embed(
                text = entity.extractText,
                assistantId = assistantId,
                source = AIRequestSource.TOOL_RESULT_EMBEDDING,
            )
        }.getOrNull() ?: return null

        val embeddingJson = JsonInstant.encodeToString(embedding)
        dao.updateEmbedding(entity.id, embeddingJson, modelId)
        embeddingCacheDAO.insertEmbedding(
            EmbeddingCacheEntity(
                memoryId = entity.id,
                memoryType = MemoryType.TOOL_RESULT,
                modelId = modelId,
                embedding = embeddingJson,
            )
        )
        return embedding
    }

    private suspend fun getOrCreateChunkEmbeddings(
        chunks: List<ToolResultArchiveChunkEntity>,
        assistantId: String,
    ): List<Pair<ToolResultArchiveChunkEntity, List<Float>>> = withContext(Dispatchers.IO) {
        if (chunks.isEmpty()) return@withContext emptyList()
        val modelId = embeddingService.getEmbeddingModelId(assistantId)

        val results = mutableListOf<Pair<ToolResultArchiveChunkEntity, List<Float>>>()
        val (ready, needsEmbedding) = chunks.partition { chunk ->
            !chunk.embedding.isNullOrBlank() && chunk.embeddingModelId == modelId
        }

        ready.forEach { chunk ->
            val embedding = runCatching {
                JsonInstant.decodeFromString<List<Float>>(chunk.embedding ?: "")
            }.getOrNull() ?: return@forEach
            results.add(chunk to embedding)
        }

        val batchSize = 8

        needsEmbedding.chunked(batchSize).forEach { batch ->
            val inputs = batch.map { chunk ->
                "tool: ${chunk.toolName}\n\n${chunk.chunkText}".trim()
            }
            val embeddingResult = runCatching {
                embeddingService.embedBatch(
                    texts = inputs,
                    assistantId = assistantId,
                    source = AIRequestSource.TOOL_RESULT_EMBEDDING,
                )
            }.getOrElse { t ->
                Log.w(TAG, "Chunk embedding batch failed (tool=${batch.firstOrNull()?.toolName}): ${t.message}", t)
                return@forEach
            }

            batch.zip(embeddingResult.embeddings).forEach { (chunk, embedding) ->
                val embeddingJson = JsonInstant.encodeToString(embedding)
                chunkDao.updateEmbedding(chunk.id, embeddingJson, embeddingResult.modelId)
                embeddingCacheDAO.insertEmbedding(
                    EmbeddingCacheEntity(
                        memoryId = chunk.id,
                        memoryType = MemoryType.TOOL_RESULT_CHUNK,
                        modelId = embeddingResult.modelId,
                        embedding = embeddingJson,
                    )
                )
                results.add(chunk.copy(embedding = embeddingJson, embeddingModelId = embeddingResult.modelId) to embedding)
            }
        }

        results
    }

    private fun buildExtractText(
        toolName: String,
        arguments: JsonElement,
        content: JsonElement,
    ): String {
        val raw = when (toolName) {
            "search_agent" -> buildSearchAgentExtract(arguments, content)
            "search_web" -> buildSearchWebExtract(arguments, content)
            "scrape_web" -> buildScrapeWebExtract(arguments, content)
            else -> buildGenericExtract(toolName, arguments, content)
        }
        return raw.trim().take(12_000)
    }

    private fun buildSearchAgentExtract(arguments: JsonElement, content: JsonElement): String {
        val argsObject = arguments as? JsonObject
        val task = argsObject?.get("task")?.jsonPrimitiveOrNull?.contentOrNull
        val root = content as? JsonObject
        val summary = root?.get("summary")?.jsonPrimitiveOrNull?.contentOrNull
        val sources = root?.get("sources") as? JsonArray
        val notes = root?.get("notes") as? JsonArray

        return buildString {
            appendLine("tool: search_agent")
            if (!task.isNullOrBlank()) appendLine("task: ${task.take(400)}")
            if (!summary.isNullOrBlank()) {
                appendLine("summary:")
                appendLine(summary.take(3000))
            }
            if (sources != null) {
                appendLine("sources:")
                sources.jsonArray.take(10).forEach { source ->
                    val obj = source as? JsonObject ?: return@forEach
                    val id = obj["id"]?.jsonPrimitiveOrNull?.contentOrNull
                    val title = obj["title"]?.jsonPrimitiveOrNull?.contentOrNull
                    val url = obj["url"]?.jsonPrimitiveOrNull?.contentOrNull
                    val snippet = obj["snippet"]?.jsonPrimitiveOrNull?.contentOrNull
                    append("- ")
                    if (!id.isNullOrBlank()) append("id=$id ")
                    if (!title.isNullOrBlank()) append("title=${title.take(160)} ")
                    if (!url.isNullOrBlank()) append("url=$url ")
                    if (!snippet.isNullOrBlank()) append("snippet=${snippet.take(280)}")
                    appendLine()
                }
            }
            if (notes != null) {
                notes.jsonArray.take(5).forEach { note ->
                    val text = note.jsonPrimitiveOrNull?.contentOrNull
                    if (!text.isNullOrBlank()) appendLine("note: ${text.take(240)}")
                }
            }
        }
    }

    private fun buildSearchWebExtract(arguments: JsonElement, content: JsonElement): String {
        val argsObject = arguments as? JsonObject
        val query = argsObject?.get("query")?.jsonPrimitiveOrNull?.contentOrNull

        val root = content as? JsonObject
        val items = root?.get("items") as? JsonArray

        return buildString {
            appendLine("tool: search_web")
            if (!query.isNullOrBlank()) appendLine("query: $query")
            if (items != null) {
                appendLine("results:")
                items.jsonArray.take(10).forEach { item ->
                    val obj = item as? JsonObject ?: return@forEach
                    val id = obj["id"]?.jsonPrimitiveOrNull?.contentOrNull
                    val title = obj["title"]?.jsonPrimitiveOrNull?.contentOrNull
                    val url = obj["url"]?.jsonPrimitiveOrNull?.contentOrNull
                    val text = obj["text"]?.jsonPrimitiveOrNull?.contentOrNull
                    append("- ")
                    if (!id.isNullOrBlank()) append("id=$id ")
                    if (!title.isNullOrBlank()) append("title=${title.take(160)} ")
                    if (!url.isNullOrBlank()) append("url=$url ")
                    if (!text.isNullOrBlank()) append("snippet=${text.take(280)}")
                    appendLine()
                }
            }
        }
    }

    private fun buildScrapeWebExtract(arguments: JsonElement, content: JsonElement): String {
        val argsObject = arguments as? JsonObject
        val url = argsObject?.get("url")?.jsonPrimitiveOrNull?.contentOrNull

        val root = content as? JsonObject
        val title = root?.get("title")?.jsonPrimitiveOrNull?.contentOrNull
        val text = root?.get("text")?.jsonPrimitiveOrNull?.contentOrNull

        return buildString {
            appendLine("tool: scrape_web")
            if (!url.isNullOrBlank()) appendLine("url: $url")
            if (!title.isNullOrBlank()) appendLine("title: ${title.take(200)}")
            if (!text.isNullOrBlank()) {
                appendLine("content:")
                appendLine(text.take(4000))
            }
        }
    }

    private fun buildGenericExtract(toolName: String, arguments: JsonElement, content: JsonElement): String {
        return buildString {
            appendLine("tool: $toolName")
            appendLine("arguments:")
            appendLine(arguments.toString().take(2000))
            appendLine("result:")
            appendLine(content.toString().take(8000))
        }
    }
}
