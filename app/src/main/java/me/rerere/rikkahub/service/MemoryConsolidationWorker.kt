package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import me.rerere.rikkahub.data.repository.MemoryConsolidationScheduler.Companion.PROGRESS_CURRENT
import me.rerere.rikkahub.data.repository.MemoryConsolidationScheduler.Companion.PROGRESS_TOTAL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
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
import me.rerere.rikkahub.data.db.dao.MemoryConsolidationDao
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.EmbeddingCacheDAO
import me.rerere.rikkahub.data.db.entity.ChatEpisodeEntity
import me.rerere.rikkahub.data.db.entity.MemoryConsolidationClaimEntity
import me.rerere.rikkahub.data.db.entity.MemoryConsolidationRecordEntity
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
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.UUID

class MemoryConsolidationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val conversationRepository: ConversationRepository by inject()
    private val memoryRepository: MemoryRepository by inject()
    private val memorySummaryRepository: MemorySummaryRepository by inject()
    private val chatEpisodeDAO: ChatEpisodeDAO by inject()
    private val memoryConsolidationDao: MemoryConsolidationDao by inject()
    private val database: AppDatabase by inject()
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

    private suspend fun isAlreadyConsolidated(
        conversation: Conversation,
        targetAssistantId: String,
    ): Boolean {
        val conversationId = conversation.id.toString()
        if (memoryConsolidationDao.hasRecord(conversationId, targetAssistantId)) return true

        // Existing rows predate durable history. Backfill them without paying for
        // another model request. The legacy flag covers already-pruned episodes,
        // but only while this conversation has no per-target history at all:
        // one completed group-chat target must not make a newly added target look
        // completed too.
        val hasLegacyEpisode = chatEpisodeDAO
            .getEpisodeByConversationIdAndAssistantId(conversationId, targetAssistantId) != null
        val hasLegacyEvidence = hasLegacyEpisode || (
            conversation.isConsolidated && !memoryConsolidationDao.hasRecordsOfConversation(conversationId)
        )
        if (!hasLegacyEvidence) return false

        database.withTransaction {
            memoryConsolidationDao.upsertRecord(
                MemoryConsolidationRecordEntity(
                    conversationId = conversationId,
                    assistantId = targetAssistantId,
                    completedAt = System.currentTimeMillis(),
                ),
            )
        }
        conversationRepository.markAsConsolidated(conversation.id)
        return true
    }

    private suspend fun claimConversation(conversationId: String): String? {
        val now = System.currentTimeMillis()
        val token = UUID.randomUUID().toString()
        return database.withTransaction {
            val existing = memoryConsolidationDao.getClaim(conversationId)
            if (existing != null) {
                if (now - existing.claimedAt < CLAIM_TIMEOUT_MILLIS) return@withTransaction null
                memoryConsolidationDao.deleteClaim(conversationId)
            }
            val inserted = memoryConsolidationDao.insertClaim(
                MemoryConsolidationClaimEntity(
                    conversationId = conversationId,
                    claimToken = token,
                    claimedAt = now,
                ),
            )
            token.takeIf { inserted != -1L }
        }
    }

    private suspend fun releaseClaim(conversationId: String, claimToken: String) {
        memoryConsolidationDao.deleteClaimIfTokenMatches(conversationId, claimToken)
    }

    /**
     * The old conversation-level flag had no group-chat target information. If
     * it is the only evidence left, treat all targets that existed in this
     * template scan as legacy-completed in one step. Subsequent scans use the
     * durable per-target rows, so adding a seat later still processes it.
     */
    private suspend fun backfillLegacyGroupRecordsIfNeeded(
        conversation: Conversation,
        targetAssistantIds: List<String>,
    ) {
        if (!conversation.isConsolidated || targetAssistantIds.isEmpty()) return
        val conversationId = conversation.id.toString()
        if (memoryConsolidationDao.hasRecordsOfConversation(conversationId)) return
        database.withTransaction {
            if (!memoryConsolidationDao.hasRecordsOfConversation(conversationId)) {
                memoryConsolidationDao.upsertRecords(
                    targetAssistantIds.map { targetAssistantId ->
                        MemoryConsolidationRecordEntity(
                            conversationId = conversationId,
                            assistantId = targetAssistantId,
                            completedAt = System.currentTimeMillis(),
                        )
                    },
                )
            }
        }
    }

    private suspend fun isConversationStillCurrent(conversation: Conversation): Boolean {
        val latest = conversationRepository.getConversationById(conversation.id) ?: return false
        return latest.assistantId == conversation.assistantId &&
            latest.messageNodes == conversation.messageNodes &&
            latest.truncateIndex == conversation.truncateIndex
    }

    private fun consolidationLogMetadata(
        conversationId: String,
        assistantId: String,
        isFullScan: Boolean,
        forceConversationId: String?,
        groupTemplateId: String? = null,
    ): Map<String, String> = buildMap {
        put(
            "trigger",
            when {
                groupTemplateId != null && forceConversationId != null -> "manual_single_conversation"
                groupTemplateId != null && isFullScan -> "manual_group_full_scan"
                forceConversationId != null -> "manual_single_conversation"
                isFullScan -> "manual_full_scan"
                else -> "automatic"
            },
        )
        put("work_id", id.toString())
        put("assistant_id", assistantId)
        put("conversation_id", conversationId)
        groupTemplateId?.let { put("group_chat_template_id", it) }
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

            val eligibleConversations = if (isManual) {
                conversationsToProcess.filter { conversation ->
                    getMessagesForConsolidationOrNull(conversation) != null &&
                        canProcessConversation(assistant.id, conversation.updateAt.toEpochMilli(), isManual) &&
                        (forceConversationId != null || !isAlreadyConsolidated(conversation, assistantId))
                }
            } else {
                conversationsToProcess
            }

            if (isManual) {
                setProgress(
                    workDataOf(
                        PROGRESS_CURRENT to 0,
                        PROGRESS_TOTAL to eligibleConversations.size,
                    )
                )
            }

            var processedInLoop = 0
            for (conversation in eligibleConversations) {
                ensureNotCancelled()
                val processed = processSingleConversation(
                    conversation = conversation,
                    assistant = assistant,
                    assistantId = assistantId,
                    isManual = isManual,
                    isFullScan = isFullScan,
                    forceConversationId = forceConversationId,
                    now = now,
                    provider = provider,
                    model = model,
                )
                if (processed) {
                    trackACount++
                }
                if (isManual && !isStopped) {
                    processedInLoop++
                    setProgress(
                        workDataOf(
                            PROGRESS_CURRENT to processedInLoop,
                            PROGRESS_TOTAL to eligibleConversations.size,
                        )
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
            // An episode represents the latest state of a conversation. Using the
            // creation time punished long-lived chats even when they were updated recently.
            val age = now - episode.endTime
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

        val targetAssistantIds = targetAssistants.map { it.id.toString() }
        if (forcedConversation == null) {
            for (conversation in conversationsToProcess) {
                backfillLegacyGroupRecordsIfNeeded(
                    conversation = conversation,
                    targetAssistantIds = targetAssistantIds,
                )
            }
        }

        val now = System.currentTimeMillis()
        val isManual = isManualConsolidation(isFullScan, forcedConversationId)

        val eligibleConversations = if (isManual) {
            conversationsToProcess.filter { conversation ->
                val allMessages = getMessagesForConsolidationOrNull(conversation)
                if (allMessages == null) return@filter false
                val eligibleTargetAssistants = targetAssistants.filter { targetAssistant ->
                    canProcessConversation(
                        assistantId = targetAssistant.id,
                        conversationUpdateAt = conversation.updateAt.toEpochMilli(),
                        isManual = isManual,
                    )
                }
                if (eligibleTargetAssistants.isEmpty()) return@filter false
                val targetAssistantsToProcess = if (forcedConversation != null) {
                    eligibleTargetAssistants
                } else {
                    eligibleTargetAssistants.filterNot { targetAssistant ->
                        isAlreadyConsolidated(conversation, targetAssistant.id.toString())
                    }
                }
                targetAssistantsToProcess.isNotEmpty()
            }
        } else {
            conversationsToProcess
        }

        if (isManual) {
            setProgress(
                workDataOf(
                    PROGRESS_CURRENT to 0,
                    PROGRESS_TOTAL to eligibleConversations.size,
                )
            )
        }

        val assistantsById = settings.assistants.associateBy { it.id }
        val seatDisplayNames = template.buildSeatDisplayNames(
            assistantsById = assistantsById,
            defaultName = "Assistant",
        )

        val templateName = template.name.trim().ifBlank { "Group Chat" }

        var processedGroupInLoop = 0
        for (conversation in eligibleConversations) {
            ensureNotCancelled()
            processGroupConversation(
                conversation = conversation,
                targetAssistants = targetAssistants,
                isManual = isManual,
                isFullScan = isFullScan,
                forcedConversationId = forcedConversationId,
                now = now,
                template = template,
                provider = provider,
                model = model,
                seatDisplayNames = seatDisplayNames,
                assistantsById = assistantsById,
                templateName = templateName,
            )
            if (isManual && !isStopped) {
                processedGroupInLoop++
                setProgress(
                    workDataOf(
                        PROGRESS_CURRENT to processedGroupInLoop,
                        PROGRESS_TOTAL to eligibleConversations.size,
                    )
                )
            }
        }
    }

    private suspend fun processSingleConversation(
        conversation: Conversation,
        assistant: Assistant,
        assistantId: String,
        isManual: Boolean,
        isFullScan: Boolean,
        forceConversationId: String?,
        now: Long,
        provider: ProviderSetting,
        model: Model,
    ): Boolean {
        val providerHandler = providerManager.getProviderByType(provider)
        val allMessages = getMessagesForConsolidationOrNull(conversation) ?: return false

        if (!canProcessConversation(assistant.id, conversation.updateAt.toEpochMilli(), isManual)) {
            return false
        }

        if (forceConversationId == null && isAlreadyConsolidated(conversation, assistantId)) {
            return false
        }

        val delayMs = assistant.consolidationDelayMinutes * 60 * 1000L
        if (!isManual && now - conversation.updateAt.toEpochMilli() < delayMs) {
            Log.i("MemoryConsolidation", "Skipping conversation ${conversation.id} (waiting for delay)")
            return false
        }

        val conversationId = conversation.id.toString()
        val claimToken = claimConversation(conversationId) ?: return false

        val lastSummaryIndex = conversation.contextSummaryUpToIndex
        val hasSummary = !conversation.contextSummary.isNullOrBlank() && lastSummaryIndex >= 0

        val messagesToProcess = if (hasSummary && lastSummaryIndex < allMessages.size) {
            allMessages.subList((lastSummaryIndex + 1).coerceAtMost(allMessages.size), allMessages.size)
        } else {
            allMessages
        }.takeLast(30)

        val messagesText = messagesToProcess.joinToString("\n") { "${it.role}: ${it.toText()}" }

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
            if (responseText.isBlank()) return false

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
            if (summary.isEmpty()) return false

            if (!canProcessConversation(assistant.id, conversation.updateAt.toEpochMilli(), isManual)) {
                return false
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
                return false
            }

            if (!isConversationStillCurrent(conversation)) {
                return false
            }
            val existingEpisode = chatEpisodeDAO.getEpisodeByConversationIdAndAssistantId(
                conversationId = conversationId,
                assistantId = assistantId,
            )

            var episodeChangeType = me.rerere.rikkahub.data.db.entity.MemorySummaryChangeType.ADDED
            var episodeId = 0
            database.withTransaction {
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
                    episodeId = existingEpisode.id
                    episodeChangeType = me.rerere.rikkahub.data.db.entity.MemorySummaryChangeType.UPDATED
                } else {
                    episodeId = chatEpisodeDAO.insertEpisode(
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
                    ).toInt()
                }
                memoryConsolidationDao.upsertRecord(
                    MemoryConsolidationRecordEntity(
                        conversationId = conversationId,
                        assistantId = assistantId,
                        completedAt = System.currentTimeMillis(),
                    ),
                )
            }
            memorySummaryRepository.recordChange(
                assistantId,
                MemoryType.EPISODIC,
                episodeId,
                episodeChangeType,
            )
            conversationRepository.markAsConsolidated(conversation.id)
            return true
        } catch (t: Throwable) {
            if (t is CancellationException) {
                failure = t
                throw t
            }
            failure = t
            Log.e("MemoryConsolidation", "Failed to process conversation ${conversation.id}", t)
            return false
        } finally {
            withContext(NonCancellable) {
                runCatching { releaseClaim(conversationId, claimToken) }
                    .onFailure { Log.w("MemoryConsolidation", "Failed to release conversation claim", it) }
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
                    metadata = consolidationLogMetadata(
                        conversationId = conversationId,
                        assistantId = assistantId,
                        isFullScan = isFullScan,
                        forceConversationId = forceConversationId,
                    ),
                )
            }
        }
    }

    private suspend fun processGroupConversation(
        conversation: Conversation,
        targetAssistants: List<Assistant>,
        isManual: Boolean,
        isFullScan: Boolean,
        forcedConversationId: String?,
        now: Long,
        template: GroupChatTemplate,
        provider: ProviderSetting,
        model: Model,
        seatDisplayNames: Map<kotlin.uuid.Uuid, String>,
        assistantsById: Map<kotlin.uuid.Uuid, Assistant>,
        templateName: String,
    ) {
        val providerHandler = providerManager.getProviderByType(provider)
        val allMessages = getMessagesForConsolidationOrNull(conversation) ?: return

        val eligibleTargetAssistants = targetAssistants.filter { targetAssistant ->
            canProcessConversation(
                assistantId = targetAssistant.id,
                conversationUpdateAt = conversation.updateAt.toEpochMilli(),
                isManual = isManual,
            )
        }
        if (eligibleTargetAssistants.isEmpty()) return

        val targetAssistantsToProcess = if (forcedConversationId != null) {
            eligibleTargetAssistants
        } else {
            eligibleTargetAssistants.filterNot { targetAssistant ->
                isAlreadyConsolidated(conversation, targetAssistant.id.toString())
            }
        }
        if (targetAssistantsToProcess.isEmpty()) return

        val delayMs = template.consolidationDelayMinutes * 60 * 1000L
        if (!isManual && now - conversation.updateAt.toEpochMilli() < delayMs) {
            return
        }

        val conversationId = conversation.id.toString()
        val claimToken = claimConversation(conversationId) ?: return

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
            if (responseText.isBlank()) return

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
            if (summary.isEmpty()) return

            if (!isConversationStillCurrent(conversation)) {
                return
            }
            var insertedCount = 0

            targetAssistantsToProcess.forEach { targetAssistant ->
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

                var episodeChangeType = me.rerere.rikkahub.data.db.entity.MemorySummaryChangeType.ADDED
                var episodeId = 0
                database.withTransaction {
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
                        episodeId = existingEpisode.id
                        episodeChangeType = me.rerere.rikkahub.data.db.entity.MemorySummaryChangeType.UPDATED
                    } else {
                        episodeId = chatEpisodeDAO.insertEpisode(
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
                        ).toInt()
                    }
                    memoryConsolidationDao.upsertRecord(
                        MemoryConsolidationRecordEntity(
                            conversationId = conversationId,
                            assistantId = targetAssistantId,
                            completedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                memorySummaryRepository.recordChange(
                    targetAssistantId,
                    MemoryType.EPISODIC,
                    episodeId,
                    episodeChangeType,
                )

                insertedCount++
            }

            if (insertedCount > 0) {
                conversationRepository.markAsConsolidated(conversation.id)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) {
                failure = t
                throw t
            }
            failure = t
            Log.e("MemoryConsolidation", "Failed to process group chat conversation ${conversation.id}", t)
        } finally {
            withContext(NonCancellable) {
                runCatching { releaseClaim(conversationId, claimToken) }
                    .onFailure { Log.w("MemoryConsolidation", "Failed to release conversation claim", it) }
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
                    metadata = consolidationLogMetadata(
                        conversationId = conversationId,
                        assistantId = targetAssistantsToProcess.joinToString(",") { it.id.toString() },
                        isFullScan = isFullScan,
                        forceConversationId = forcedConversationId,
                        groupTemplateId = template.id.toString(),
                    ),
                )
            }
        }
    }

    companion object {
        private const val CLAIM_TIMEOUT_MILLIS = 30L * 60L * 1000L
        const val INPUT_FULL_SCAN = "FULL_SCAN"
        const val INPUT_FORCE_CONVERSATION_ID = "FORCE_CONVERSATION_ID"
        const val INPUT_GROUP_CHAT_TEMPLATE_ID = "GROUP_CHAT_TEMPLATE_ID"
        const val INPUT_ASSISTANT_ID = "ASSISTANT_ID"
    }
}
