package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.AIRequestLogManager
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MEMORY_CONSOLIDATION_PROMPT
import me.rerere.rikkahub.data.ai.rag.EmbeddingService
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.db.dao.ChatEpisodeDAO
import me.rerere.rikkahub.data.db.dao.EmbeddingCacheDAO
import me.rerere.rikkahub.data.db.entity.ChatEpisodeEntity
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemorySummaryRepository
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import me.rerere.rikkahub.data.ai.rag.VectorEngine
import me.rerere.rikkahub.data.db.entity.MemoryType
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GroupChatTemplate
import me.rerere.rikkahub.data.model.MemoryRetrievalMode
import me.rerere.rikkahub.data.model.buildSeatDisplayNames
import me.rerere.rikkahub.data.model.effectiveMemoryRetrievalMode
import me.rerere.rikkahub.data.model.requiresEmbedding
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.utils.applyPlaceholders

class MemoryConsolidationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val conversationRepository: ConversationRepository by inject()
    private val memoryRepository: MemoryRepository by inject()
    private val memorySummaryRepository: MemorySummaryRepository by inject()
    private val chatEpisodeDAO: ChatEpisodeDAO by inject()
    private val embeddingCacheDAO: EmbeddingCacheDAO by inject()
    private val settingsStore: SettingsStore by inject()
    private val embeddingService: EmbeddingService by inject()
    private val providerManager: me.rerere.ai.provider.ProviderManager by inject()
    private val requestLogManager: AIRequestLogManager by inject()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            consolidateMemories()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("MemoryConsolidation", "Error consolidating memories", e)
            Result.retry()
        }
    }

    /**
     * WorkManager cancellation is cooperative. Check it between model calls and
     * before database writes so one cancelled manual run cannot continue spending
     * tokens on later conversations.
     */
    private suspend fun ensureNotCancelled() {
        currentCoroutineContext().ensureActive()
        if (isStopped) {
            throw CancellationException("Memory consolidation was cancelled")
        }
    }

    private fun getMessagesForConsolidationOrNull(conversation: Conversation): List<UIMessage>? {
        val allMessages = conversation.messageNodes.mapNotNull { node ->
            node.messages.getOrNull(node.selectIndex)
        }

        if (allMessages.size != conversation.messageNodes.size) {
            return null
        }

        var hasUserMessage = false
        var hasAssistantMessage = false

        for (message in allMessages) {
            when (message.role) {
                MessageRole.USER -> hasUserMessage = true
                MessageRole.ASSISTANT -> hasAssistantMessage = true
                else -> Unit
            }

            if (hasUserMessage && hasAssistantMessage) {
                return allMessages
            }
        }

        return null
    }

    private fun isManualConsolidation(isFullScan: Boolean, forceConversationId: String?): Boolean {
        return isFullScan || forceConversationId != null
    }

    /**
     * Reads the latest setting instead of relying on the worker's initial snapshot, so pausing
     * consolidation while a worker is running prevents that worker from writing an episode.
     */
    private fun canProcessConversation(
        assistantId: kotlin.uuid.Uuid,
        conversationUpdateAt: Long,
        isManual: Boolean,
    ): Boolean {
        if (isManual) return true

        val latestAssistant = settingsStore.settingsFlow.value.getAssistantById(assistantId) ?: return false
        return latestAssistant.enableMemory &&
            latestAssistant.enableMemoryConsolidation &&
            latestAssistant.effectiveMemoryRetrievalMode() != MemoryRetrievalMode.OFF &&
            latestAssistant.canConsolidateConversation(
                conversationUpdateAt = conversationUpdateAt,
                isManual = false,
            )
    }

    private suspend fun consolidateMemories() {
        val settings = settingsStore.settingsFlow.value
        val isFullScan = inputData.getBoolean(INPUT_FULL_SCAN, false)
        val forceConversationId = inputData.getString(INPUT_FORCE_CONVERSATION_ID)
        val groupChatTemplateId = inputData.getString(INPUT_GROUP_CHAT_TEMPLATE_ID)

        if (!groupChatTemplateId.isNullOrBlank()) {
            val templateId = runCatching { kotlin.uuid.Uuid.parse(groupChatTemplateId) }.getOrNull()
            val template = templateId?.let { id ->
                settings.groupChatTemplates.firstOrNull { it.id == id }
            }
            if (template != null) {
                consolidateGroupChatTemplate(
                    settings = settings,
                    template = template,
                    isFullScan = isFullScan,
                    forcedConversationId = null,
                )
            }
            return
        }

        if (!forceConversationId.isNullOrBlank()) {
            val conversationId = runCatching { kotlin.uuid.Uuid.parse(forceConversationId) }.getOrNull()
            val conversation = conversationId?.let { id -> conversationRepository.getConversationById(id) }
            if (conversation != null) {
                val template = settings.groupChatTemplates.firstOrNull { it.id == conversation.assistantId }
                if (template != null) {
                    consolidateGroupChatTemplate(
                        settings = settings,
                        template = template,
                        isFullScan = true,
                        forcedConversationId = conversation.id.toString(),
                    )
                    return
                }
            }
        }

        val requestedAssistantId = inputData.getString(INPUT_ASSISTANT_ID)
            ?.let { id -> runCatching { kotlin.uuid.Uuid.parse(id) }.getOrNull() }
        val assistant = requestedAssistantId
            ?.let { id -> settings.getAssistantById(id) }
            ?: settings.getCurrentAssistant()
        val assistantId = assistant.id.toString()

        if (!assistant.enableMemory) {
            if (!isFullScan && forceConversationId.isNullOrBlank()) {
                consolidateAllGroupChatTemplates(settings = settings)
            }
            return
        }

        val summarizerModelId = assistant.summarizerModelId
        val backgroundModelId = summarizerModelId ?: assistant.backgroundModelId ?: settings.chatModelId
        val model = settings.findModelById(backgroundModelId) ?: return
        val provider = model.findProvider(settings.providers) ?: return
        val providerHandler = providerManager.getProviderByType(provider)

        var trackACount = 0
        val now = System.currentTimeMillis()
        val isManual = isManualConsolidation(isFullScan, forceConversationId)
        
        // Only process conversations if consolidation is enabled
        if (assistant.effectiveMemoryRetrievalMode() != MemoryRetrievalMode.OFF &&
            (assistant.enableMemoryConsolidation || forceConversationId != null)
        ) {
            val conversationsToProcess = if (forceConversationId != null) {
                // Manual consolidation: only process the specific conversation
                val targetConversation = conversationRepository.getConversationById(kotlin.uuid.Uuid.parse(forceConversationId))
                if (targetConversation != null) listOf(targetConversation) else emptyList()
            } else if (isFullScan) {
                conversationRepository.getConversationsOfAssistant(assistant.id).first()
            } else {
                conversationRepository.getRecentConversations(settings.assistantId, 10)
            }
            
            for (conversation in conversationsToProcess) {
            ensureNotCancelled()
            // Skip conversations without at least one user & one assistant message
            val allMessages = getMessagesForConsolidationOrNull(conversation) ?: continue

            if (!canProcessConversation(assistant.id, conversation.updateAt.toEpochMilli(), isManual)) {
                continue
            }
            
            // Check if already consolidated (unless forced or full scan)
            if (conversation.isConsolidated && !isFullScan && forceConversationId == null) continue
            
            // CHECK DELAY: Only consolidate if enough time has passed since last update
            // (Skip delay check for forced/manual consolidation)
            val delayMs = assistant.consolidationDelayMinutes * 60 * 1000L
            if (forceConversationId == null && now - conversation.updateAt.toEpochMilli() < delayMs && !isFullScan) {
                Log.i("MemoryConsolidation", "Skipping conversation ${conversation.id} (waiting for delay)")
                continue
            }
            
            // Double check with DAO if we are doing a full scan (heuristic fallback)
            if (isFullScan) {
                val existingEpisodes = chatEpisodeDAO.getEpisodesOfAssistant(assistantId)
                val isProcessed = existingEpisodes.any { 
                    kotlin.math.abs(it.endTime - conversation.updateAt.toEpochMilli()) < 1000 * 60 
                }
                if (isProcessed) {
                    conversationRepository.markAsConsolidated(conversation.id)
                    continue
                }
            }

            // Summarize into an episode with Significance Score
            // Only process messages after the last summary index to avoid redundant processing
            val lastSummaryIndex = conversation.contextSummaryUpToIndex
            val hasSummary = !conversation.contextSummary.isNullOrBlank() && lastSummaryIndex >= 0
            
            val messagesToProcess = if (hasSummary && lastSummaryIndex < allMessages.size) {
                allMessages.subList((lastSummaryIndex + 1).coerceAtMost(allMessages.size), allMessages.size)
            } else {
                allMessages
            }.takeLast(30) // Limit to last 30 for processing
            
            val messagesText = messagesToProcess.joinToString("\n") { "${it.role}: ${it.toText()}" }
            
            // Include context summary if available for better context
            val contextSection = if (hasSummary) {
                """
                **Context Summary (from previous summarization):**
                ${conversation.contextSummary}
                
                **New Messages (${messagesToProcess.size} since last summary):**
                """.trimIndent()
            } else ""
            
            val promptTemplate = assistant.consolidationPrompt.ifBlank {
                DEFAULT_MEMORY_CONSOLIDATION_PROMPT
            }
            val prompt = promptTemplate.applyPlaceholders(
                "context_section" to contextSection,
                "messages_text" to messagesText,
            )
            
            val requestMessages = listOf(UIMessage.user(prompt))
            var requestBodyJson: String? = null
            val params = TextGenerationParams(
                model = model,
                onRequestBody = { requestBodyJson = it },
            )
            val startAt = System.currentTimeMillis()
            var responseText = ""
            var rawResponseText = ""
            var failure: Throwable? = null

            try {
                val response = providerHandler.generateText(
                    providerSetting = provider,
                    messages = requestMessages,
                    params = params,
                )
                ensureNotCancelled()
                rawResponseText = response.rawResponse.orEmpty()
                responseText = response.choices.firstOrNull()?.message?.toContentText().orEmpty()
                if (responseText.isBlank()) continue

                var summary = responseText
                var significance = 5

                runCatching {
                    val jsonStart = responseText.indexOf("{")
                    val jsonEnd = responseText.lastIndexOf("}")
                    if (jsonStart != -1 && jsonEnd != -1) {
                        val jsonStr = responseText.substring(jsonStart, jsonEnd + 1)
                        val json = Json.parseToJsonElement(jsonStr).jsonObject
                        val parsedSummary = json["summary"]?.jsonPrimitiveOrNull?.content?.trim()
                        if (!parsedSummary.isNullOrEmpty()) {
                            summary = parsedSummary
                        }
                        significance = json["significance"]?.jsonPrimitiveOrNull?.intOrNull ?: 5
                    }
                }

                summary = summary.trim()
                if (summary.isEmpty()) continue

                // The switch may have been enabled while this request was in flight.
                if (!canProcessConversation(assistant.id, conversation.updateAt.toEpochMilli(), isManual)) {
                    continue
                }

                val summaryEmbeddingResult = if (isVectorMemoryEnabled(assistant.id)) {
                    runCatching {
                        embeddingService.embedWithModelId(
                            text = summary,
                            assistantId = assistantId,
                            source = AIRequestSource.MEMORY_EMBEDDING,
                        )
                    }.getOrNull()
                } else {
                    null
                }
                val summaryEmbedding = summaryEmbeddingResult?.embeddings?.firstOrNull()
                val embeddingModelId = summaryEmbeddingResult?.modelId

                ensureNotCancelled()
                if (!canProcessConversation(assistant.id, conversation.updateAt.toEpochMilli(), isManual)) {
                    continue
                }

                val conversationId = conversation.id.toString()
                val existingEpisode = chatEpisodeDAO.getEpisodeByConversationIdAndAssistantId(
                    conversationId = conversationId,
                    assistantId = assistantId,
                )

                if (existingEpisode != null) {
                    embeddingCacheDAO.deleteByMemoryId(existingEpisode.id, MemoryType.EPISODIC)
                    chatEpisodeDAO.insertEpisode(
                        existingEpisode.copy(
                            content = summary,
                            embedding = summaryEmbedding?.let { JsonInstant.encodeToString(it) },
                            embeddingModelId = embeddingModelId,
                            endTime = conversation.updateAt.toEpochMilli(),
                            lastAccessedAt = System.currentTimeMillis(),
                            significance = significance,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                    memorySummaryRepository.recordChange(
                        assistantId,
                        MemoryType.EPISODIC,
                        existingEpisode.id,
                        me.rerere.rikkahub.data.db.entity.MemorySummaryChangeType.UPDATED,
                    )
                    Log.i("MemoryConsolidation", "Updated episode (sig=$significance) for conversation ${conversation.id}")
                } else {
                    val episodeId = chatEpisodeDAO.insertEpisode(
                        ChatEpisodeEntity(
                            assistantId = assistantId,
                            content = summary,
                            embedding = summaryEmbedding?.let { JsonInstant.encodeToString(it) },
                            embeddingModelId = embeddingModelId,
                            startTime = conversation.createAt.toEpochMilli(),
                            endTime = conversation.updateAt.toEpochMilli(),
                            lastAccessedAt = System.currentTimeMillis(),
                            significance = significance,
                            conversationId = conversationId,
                        )
                    )
                    memorySummaryRepository.recordChange(
                        assistantId,
                        MemoryType.EPISODIC,
                        episodeId.toInt(),
                        me.rerere.rikkahub.data.db.entity.MemorySummaryChangeType.ADDED,
                    )
                    Log.i("MemoryConsolidation", "Created episode (sig=$significance) for conversation ${conversation.id}")
                }

                conversationRepository.markAsConsolidated(conversation.id)
                trackACount++
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                failure = t
                Log.e("MemoryConsolidation", "Failed to process conversation ${conversation.id}", t)
            } finally {
                val durationMs = System.currentTimeMillis() - startAt
                requestLogManager.logTextGeneration(
                    source = AIRequestSource.MEMORY_CONSOLIDATION,
                    providerSetting = provider,
                    params = params,
                    requestMessages = requestMessages,
                    requestBodyJson = requestBodyJson,
                    responseText = responseText,
                    responseRawText = rawResponseText,
                    stream = false,
                    latencyMs = durationMs,
                    durationMs = durationMs,
                    error = failure,
                )
            }
        }
        
        // Update Track A Stats
        if (trackACount > 0 || isFullScan) {
            val resultMsg = if (trackACount > 0) "Processed $trackACount chats" else "No new chats ready"
            settingsStore.update { currentSettings ->
                currentSettings.copy(
                    assistants = currentSettings.assistants.map { 
                        if (it.id == assistant.id) {
                            it.copy(
                                lastConsolidationTime = now,
                                lastConsolidationResult = resultMsg
                            )
                        } else it
                    }
                )
            }
            }
        } // End of enableMemoryConsolidation check

        // =========================================================================================
        // PRUNING: The "Throw Out" Mechanism
        // =========================================================================================
        ensureNotCancelled()
        val allEpisodes = chatEpisodeDAO.getEpisodesOfAssistant(assistantId)
        
        var prunedCount = 0
        for (episode in allEpisodes) {
            ensureNotCancelled()
            val age = now - episode.startTime
            val timeSinceAccess = now - episode.lastAccessedAt
            
            // Default 30 days retention
            val retentionDays = 30L
            
            val retentionMs = retentionDays * 24 * 60 * 60 * 1000L
            
            // If older than retention period AND not accessed recently (7 days buffer)
            if (age > retentionMs && timeSinceAccess > (7L * 24 * 60 * 60 * 1000L)) {
                embeddingCacheDAO.deleteByMemoryId(episode.id, MemoryType.EPISODIC)
                chatEpisodeDAO.deleteEpisode(episode.id)
                memorySummaryRepository.recordChange(
                    assistantId,
                    MemoryType.EPISODIC,
                    episode.id,
                    me.rerere.rikkahub.data.db.entity.MemorySummaryChangeType.DELETED,
                )
                prunedCount++
            }
        }
        if (prunedCount > 0) {
            Log.i("MemoryConsolidation", "Pruned $prunedCount fading episodic memories")
        }

        // =========================================================================================
        // AUTO-FIX: Embed any memories that are missing embeddings or have wrong model
        // =========================================================================================
        if (isVectorMemoryEnabled(assistant.id)) {
            try {
                ensureNotCancelled()
                val (fixed, failed) = memoryRepository.embedMissingMemories(assistantId)
                if (fixed > 0 || failed > 0) {
                    Log.i("MemoryConsolidation", "Auto-embedded $fixed memories ($failed failed)")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MemoryConsolidation", "Error auto-embedding memories", e)
            }
        }

        if (!isFullScan && forceConversationId.isNullOrBlank()) {
            consolidateAllGroupChatTemplates(settings = settings)
        }
    }

    private suspend fun consolidateAllGroupChatTemplates(settings: Settings) {
        settings.groupChatTemplates.forEach { template ->
            consolidateGroupChatTemplate(
                settings = settings,
                template = template,
                isFullScan = false,
                forcedConversationId = null,
            )
        }
    }

    private fun isVectorMemoryEnabled(assistantId: kotlin.uuid.Uuid): Boolean {
        val currentAssistant = settingsStore.settingsFlow.value.getAssistantById(assistantId)
        return currentAssistant?.enableMemory == true &&
            currentAssistant.effectiveMemoryRetrievalMode().requiresEmbedding
    }

    private suspend fun consolidateGroupChatTemplate(
        settings: Settings,
        template: GroupChatTemplate,
        isFullScan: Boolean,
        forcedConversationId: String?,
    ) {
        val integrationModelId = template.integrationModelId ?: return
        val model = settings.findModelById(integrationModelId) ?: return
        val provider = model.findProvider(settings.providers) ?: return
        val providerHandler = providerManager.getProviderByType(provider)

        val targetAssistants = template.seats
            .asSequence()
            .filter { seat -> seat.overrides.memoryEnabled }
            .map { seat -> seat.assistantId }
            .distinct()
            .mapNotNull { id -> settings.getAssistantById(id) }
            .filter {
                assistant ->
                assistant.enableMemory &&
                    assistant.enableMemoryConsolidation &&
                    assistant.effectiveMemoryRetrievalMode() != MemoryRetrievalMode.OFF
            }
            .distinctBy { it.id }
            .toList()

        if (targetAssistants.isEmpty()) return

        val forcedConversation = forcedConversationId
            ?.let { id -> runCatching { kotlin.uuid.Uuid.parse(id) }.getOrNull() }
            ?.let { id -> conversationRepository.getConversationById(id) }
            ?.takeIf { conversation -> conversation.assistantId == template.id }

        val conversationsToProcess = when {
            forcedConversation != null -> listOf(forcedConversation)
            isFullScan -> conversationRepository.getConversationsOfAssistant(template.id).first()
            else -> conversationRepository.getRecentConversations(template.id, 10)
        }

        val assistantsById = settings.assistants.associateBy { it.id }
        val seatDisplayNames = template.buildSeatDisplayNames(
            assistantsById = assistantsById,
            defaultName = "Assistant",
        )

        val templateName = template.name.trim().ifBlank { "Group Chat" }
        val now = System.currentTimeMillis()
        val isManual = isManualConsolidation(isFullScan, forcedConversationId)

        for (conversation in conversationsToProcess) {
            ensureNotCancelled()
            val allMessages = getMessagesForConsolidationOrNull(conversation) ?: continue

            val eligibleTargetAssistants = targetAssistants.filter { targetAssistant ->
                canProcessConversation(
                    assistantId = targetAssistant.id,
                    conversationUpdateAt = conversation.updateAt.toEpochMilli(),
                    isManual = isManual,
                )
            }
            if (eligibleTargetAssistants.isEmpty()) continue

            if (conversation.isConsolidated && !isFullScan && forcedConversation == null) continue

            val delayMs = template.consolidationDelayMinutes * 60 * 1000L
            if (forcedConversation == null &&
                !isFullScan &&
                now - conversation.updateAt.toEpochMilli() < delayMs
            ) {
                continue
            }

            val lastSummaryIndex = conversation.contextSummaryUpToIndex
            val hasSummary = !conversation.contextSummary.isNullOrBlank() && lastSummaryIndex >= 0

            val messagesToProcess = if (hasSummary && lastSummaryIndex < allMessages.size) {
                allMessages.subList((lastSummaryIndex + 1).coerceAtMost(allMessages.size), allMessages.size)
            } else {
                allMessages
            }.takeLast(30)

            fun resolveSpeakerName(message: UIMessage): String {
                message.speakerSeatId?.let { seatId ->
                    seatDisplayNames[seatId]?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
                }
                message.speakerAssistantId?.let { assistantId ->
                    assistantsById[assistantId]?.name?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
                }
                return when (message.role) {
                    me.rerere.ai.core.MessageRole.USER -> "User"
                    else -> "Assistant"
                }
            }

            val messagesText = messagesToProcess.joinToString("\n") { message ->
                "${resolveSpeakerName(message)}: ${message.toContentText()}"
            }

            val contextSection = if (hasSummary) {
                """
                **Context Summary (from previous summarization):**
                ${conversation.contextSummary}

                **New Messages (${messagesToProcess.size} since last summary):**
                """.trimIndent()
            } else ""

            val prompt = """
                Analyze the following group conversation ($templateName) and create a "Memory Episode".

                **Language**: Detect the primary language of the conversation (prioritize the human user's messages; if mixed, follow the most recent human user message). Write the "summary" in that language.

                $contextSection
                1. **Summary**: Concise summary of what happened (under 100 words).
                2. **Significance**: Rate the emotional impact or importance of this conversation from 1-10 (10 = life-changing, 1 = trivial).

                Conversation:
                $messagesText

                Output JSON format (return only JSON, no extra text):
                {
                    "summary": "...",
                    "significance": 5
                }
            """.trimIndent()

                val requestMessages = listOf(UIMessage.user(prompt))
                var requestBodyJson: String? = null
                val params = TextGenerationParams(
                    model = model,
                    onRequestBody = { requestBodyJson = it },
                )
                val startAt = System.currentTimeMillis()
                var responseText = ""
                var rawResponseText = ""
            var failure: Throwable? = null

            try {
                val response = providerHandler.generateText(
                    providerSetting = provider,
                    messages = requestMessages,
                    params = params,
                )
                ensureNotCancelled()
                rawResponseText = response.rawResponse.orEmpty()
                responseText = response.choices.firstOrNull()?.message?.toContentText().orEmpty()
                if (responseText.isBlank()) continue

                var summary = responseText
                var significance = 5

                runCatching {
                    val jsonStart = responseText.indexOf("{")
                    val jsonEnd = responseText.lastIndexOf("}")
                    if (jsonStart != -1 && jsonEnd != -1) {
                        val jsonStr = responseText.substring(jsonStart, jsonEnd + 1)
                        val json = Json.parseToJsonElement(jsonStr).jsonObject
                        val parsedSummary = json["summary"]?.jsonPrimitiveOrNull?.content?.trim()
                        if (!parsedSummary.isNullOrEmpty()) {
                            summary = parsedSummary
                        }
                        significance = json["significance"]?.jsonPrimitiveOrNull?.intOrNull ?: 5
                    }
                }

                summary = summary.trim()
                if (summary.isEmpty()) continue

                val conversationId = conversation.id.toString()
                var insertedCount = 0

                eligibleTargetAssistants.forEach { targetAssistant ->
                    ensureNotCancelled()
                    if (!canProcessConversation(
                            assistantId = targetAssistant.id,
                            conversationUpdateAt = conversation.updateAt.toEpochMilli(),
                            isManual = isManual,
                        )
                    ) {
                        return@forEach
                    }

                    val targetAssistantId = targetAssistant.id.toString()
                    val summaryEmbeddingResult = if (isVectorMemoryEnabled(targetAssistant.id)) {
                        runCatching {
                            embeddingService.embedWithModelId(
                                text = summary,
                                assistantId = targetAssistantId,
                                source = AIRequestSource.MEMORY_EMBEDDING,
                            )
                        }.getOrNull()
                    } else {
                        null
                    }
                    val summaryEmbedding = summaryEmbeddingResult?.embeddings?.firstOrNull()
                    val embeddingModelId = summaryEmbeddingResult?.modelId

                    ensureNotCancelled()
                    if (!canProcessConversation(
                            assistantId = targetAssistant.id,
                            conversationUpdateAt = conversation.updateAt.toEpochMilli(),
                            isManual = isManual,
                        )
                    ) {
                        return@forEach
                    }

                    val existingEpisode = chatEpisodeDAO.getEpisodeByConversationIdAndAssistantId(
                        conversationId = conversationId,
                        assistantId = targetAssistantId,
                    )

                    if (existingEpisode != null) {
                        embeddingCacheDAO.deleteByMemoryId(existingEpisode.id, MemoryType.EPISODIC)
                        chatEpisodeDAO.insertEpisode(
                            existingEpisode.copy(
                                content = summary,
                                embedding = summaryEmbedding?.let { JsonInstant.encodeToString(it) },
                                embeddingModelId = embeddingModelId,
                                endTime = conversation.updateAt.toEpochMilli(),
                                lastAccessedAt = System.currentTimeMillis(),
                                significance = significance,
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                        memorySummaryRepository.recordChange(
                            targetAssistantId,
                            MemoryType.EPISODIC,
                            existingEpisode.id,
                            me.rerere.rikkahub.data.db.entity.MemorySummaryChangeType.UPDATED,
                        )
                    } else {
                        val episodeId = chatEpisodeDAO.insertEpisode(
                            ChatEpisodeEntity(
                                assistantId = targetAssistantId,
                                content = summary,
                                embedding = summaryEmbedding?.let { JsonInstant.encodeToString(it) },
                                embeddingModelId = embeddingModelId,
                                startTime = conversation.createAt.toEpochMilli(),
                                endTime = conversation.updateAt.toEpochMilli(),
                                lastAccessedAt = System.currentTimeMillis(),
                                significance = significance,
                                conversationId = conversationId,
                            )
                        )
                        memorySummaryRepository.recordChange(
                            targetAssistantId,
                            MemoryType.EPISODIC,
                            episodeId.toInt(),
                            me.rerere.rikkahub.data.db.entity.MemorySummaryChangeType.ADDED,
                        )
                    }

                    insertedCount++
                }

                if (insertedCount > 0) {
                    conversationRepository.markAsConsolidated(conversation.id)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                failure = t
                Log.e("MemoryConsolidation", "Failed to process group chat conversation ${conversation.id}", t)
            } finally {
                val durationMs = System.currentTimeMillis() - startAt
                requestLogManager.logTextGeneration(
                    source = AIRequestSource.MEMORY_CONSOLIDATION,
                    providerSetting = provider,
                    params = params,
                    requestMessages = requestMessages,
                    requestBodyJson = requestBodyJson,
                    responseText = responseText,
                    responseRawText = rawResponseText,
                    stream = false,
                    latencyMs = durationMs,
                    durationMs = durationMs,
                    error = failure,
                )
            }
        }
    }

    companion object {
        const val INPUT_FULL_SCAN = "FULL_SCAN"
        const val INPUT_FORCE_CONVERSATION_ID = "FORCE_CONVERSATION_ID"
        const val INPUT_GROUP_CHAT_TEMPLATE_ID = "GROUP_CHAT_TEMPLATE_ID"
        const val INPUT_ASSISTANT_ID = "ASSISTANT_ID"
    }
}
