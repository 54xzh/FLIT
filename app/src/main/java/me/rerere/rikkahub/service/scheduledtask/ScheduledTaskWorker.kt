package me.rerere.rikkahub.service.scheduledtask

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ensureBuiltInSearchTool
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.supportsBuiltInSearch
import me.rerere.ai.provider.withoutBuiltInSearchTools
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.AIRequestLogManager
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.SearchAgentProgressStore
import me.rerere.rikkahub.data.ai.tools.SearchAgentTools
import me.rerere.rikkahub.data.ai.tools.SearchTools
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.db.dao.ScheduledTaskDao
import me.rerere.rikkahub.data.db.dao.ScheduledTaskRunDao
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.db.entity.ScheduledTaskRunEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantSearchMode
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.JsonInstant
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.uuid.Uuid

private const val MAX_ATTEMPTS = 5
private const val NOTIFICATION_CHANNEL_ID = "scheduled_task_done"

class ScheduledTaskWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val settingsStore: SettingsStore by inject()
    private val taskDao: ScheduledTaskDao by inject()
    private val runDao: ScheduledTaskRunDao by inject()
    private val conversationRepository: ConversationRepository by inject()
    private val memoryRepository: MemoryRepository by inject()
    private val generationHandler: GenerationHandler by inject()
    private val templateTransformer: TemplateTransformer by inject()
    private val localTools: LocalTools by inject()
    private val mcpManager: McpManager by inject()
    private val scheduler: ScheduledTaskScheduler by inject()
    private val searchAgentProgressStore: SearchAgentProgressStore by inject()
    private val providerManager: ProviderManager by inject()
    private val requestLogManager: AIRequestLogManager by inject()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString(ScheduledTaskWorkKeys.TASK_ID) ?: return@withContext Result.success()
        val scheduledFor = inputData.getLong(ScheduledTaskWorkKeys.SCHEDULED_FOR, -1L)
            .takeIf { it > 0 } ?: return@withContext Result.success()

        val task = taskDao.getById(taskId) ?: return@withContext Result.success()
        if (!task.enabled) return@withContext Result.success()

        val nowMillis = System.currentTimeMillis()
        val attempt = runAttemptCount + 1
        val run = getOrCreateRun(task, scheduledFor)

        val settings = settingsStore.settingsFlow.first()
        val assistantId = runCatching { Uuid.parse(task.assistantId) }.getOrNull()
        if (assistantId == null) {
            return@withContext finishFinalFailure(
                task = task,
                run = run,
                scheduledFor = scheduledFor,
                assistantName = null,
                errorCode = "ASSISTANT_ID_INVALID",
                errorMessage = "AssistantId is invalid: ${task.assistantId}",
            )
        }
        val assistant = settings.getAssistantById(assistantId)
        if (assistant == null) {
            taskDao.updateEnabled(task.id, false)

            runDao.update(
                run.copy(
                    attempt = attempt,
                    status = ScheduledTaskRunStatus.FAILED,
                    finishedAt = nowMillis,
                    errorCode = "ASSISTANT_NOT_FOUND",
                    errorMessage = "Assistant not found",
                )
            )

            // Assistant is deleted or missing; silently disable the task and avoid sending notifications.
            afterRunUpdateTask(
                task = task.copy(enabled = false),
                nowMillis = nowMillis,
                scheduledFor = scheduledFor,
                lastErrorCode = "ASSISTANT_NOT_FOUND",
                lastErrorAt = nowMillis,
            )

            return@withContext Result.success()
        }

        val locale = Locale.getDefault()
        val zoneId = ZoneId.systemDefault()
        val conversationId = Uuid.parse(run.conversationId)
        val conversationTitle = buildConversationTitle(task.name, scheduledFor, zoneId, locale)

        val baseConversation = Conversation.ofId(conversationId, assistantId = assistantId)
            .copy(
                title = conversationTitle,
                createAt = Instant.ofEpochMilli(scheduledFor),
                updateAt = Instant.ofEpochMilli(nowMillis),
                messageNodes = emptyList(),
            )
        upsertConversation(baseConversation)

        val prompt = ScheduledTaskPromptRenderer.render(task.promptTemplate, scheduledFor, zoneId, locale)
        if (prompt.isBlank()) {
            return@withContext finishFinalFailure(
                task = task,
                run = run,
                scheduledFor = scheduledFor,
                assistantName = assistant.name,
                errorCode = "PROMPT_EMPTY",
                errorMessage = "Prompt is empty",
            )
        }

        val userMessage = UIMessage.user(prompt)
        conversationRepository.updateConversation(
            baseConversation.copy(
                messageNodes = listOf(userMessage.toMessageNode()),
                updateAt = Instant.ofEpochMilli(nowMillis),
            )
        )

        runDao.update(
            run.copy(
                attempt = attempt,
                status = ScheduledTaskRunStatus.PENDING,
                startedAt = nowMillis,
                finishedAt = null,
                errorCode = null,
                errorMessage = null,
            )
        )

        val (model, assistantForRun) = resolveModelAndAssistantForRun(settingsStore = settingsStore, assistant = assistant, task = task)
            ?: return@withContext finishFinalFailure(
                task = task,
                run = run,
                scheduledFor = scheduledFor,
                assistantName = assistant.name,
                errorCode = "MODEL_NOT_FOUND",
                errorMessage = "Model not found",
            )
        val modelProvider = model.findProvider(settings.providers)
        val modelSupportsBuiltIn = model.supportsBuiltInSearch(modelProvider)
        val useBuiltInSearch = modelSupportsBuiltIn && !assistantForRun.enableSearchAgent && (
            assistantForRun.searchMode is AssistantSearchMode.BuiltIn ||
                (assistantForRun.preferBuiltInSearch && assistantForRun.searchMode !is AssistantSearchMode.Off)
            )
        val runtimeModel = if (useBuiltInSearch) {
            model.ensureBuiltInSearchTool(modelProvider)
        } else {
            model.withoutBuiltInSearchTools()
        }

        val memories: List<AssistantMemory>? = if (assistantForRun.enableMemory) {
            if (assistantForRun.useRagMemoryRetrieval) {
                memoryRepository.retrieveRelevantMemories(
                    assistantId = assistantForRun.id.toString(),
                    query = prompt,
                    limit = 50,
                    similarityThreshold = assistantForRun.ragSimilarityThreshold,
                    includeCore = assistantForRun.ragIncludeCore,
                    includeEpisodes = assistantForRun.ragIncludeEpisodes,
                )
            } else {
                memoryRepository.getMemoriesOfAssistant(assistantForRun.id.toString())
            }
        } else {
            null
        }

        val tools = buildTools(
            assistantForRun = assistantForRun,
            model = runtimeModel,
            conversationId = conversationId,
            settings = settings,
        )

        val inputTransformers = listOf(
            PlaceholderTransformer,
            DocumentAsPromptTransformer,
            OcrTransformer,
            templateTransformer,
        )
        val outputTransformers = listOf(
            ThinkTagTransformer,
            Base64ImageToLocalFileTransformer,
            RegexOutputTransformer,
        )

        return@withContext runCatching {
            var latestMessages: List<UIMessage> = listOf(userMessage)
            generationHandler.generateText(
                settings = settings,
                model = runtimeModel,
                messages = listOf(userMessage),
                conversationId = conversationId,
                assistant = assistantForRun,
                memories = memories,
                inputTransformers = inputTransformers,
                outputTransformers = outputTransformers,
                tools = tools,
                truncateIndex = -1,
                source = AIRequestSource.SCHEDULED_MESSAGE,
            ).collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> latestMessages = chunk.messages
                }
            }

            val finalConversation = baseConversation.copy(
                messageNodes = latestMessages.map { it.toMessageNode() },
                updateAt = Instant.now(),
            )
            conversationRepository.updateConversation(finalConversation)

            val assistantReply = latestMessages.lastOrNull { it.role == MessageRole.ASSISTANT }?.toText()
            if (assistantReply.isNullOrBlank()) {
                error("No assistant reply")
            }

            runDao.update(
                run.copy(
                    attempt = attempt,
                    status = ScheduledTaskRunStatus.SUCCESS,
                    finishedAt = System.currentTimeMillis(),
                    modelIdUsed = model.id.toString(),
                )
            )

            val finishedTask = afterRunUpdateTask(
                task = task,
                nowMillis = System.currentTimeMillis(),
                scheduledFor = scheduledFor,
                lastErrorCode = null,
                lastErrorAt = null,
            )

            scheduleNextIfNeeded(finishedTask)
            maybeSendNotification(
                task = finishedTask,
                assistantName = assistant.name,
                conversationId = conversationId,
                content = assistantReply,
                isSuccess = true,
            )

            Result.success()
        }.getOrElse { t ->
            t.printStackTrace()

            val errorMessage = t.message ?: t.javaClass.name
            val isRetryable = isRetryable(t)

            runDao.update(
                run.copy(
                    attempt = attempt,
                    status = ScheduledTaskRunStatus.FAILED,
                    finishedAt = System.currentTimeMillis(),
                    errorCode = if (isRetryable) "RETRYABLE_ERROR" else "FAILED",
                    errorMessage = errorMessage,
                    modelIdUsed = model.id.toString(),
                )
            )

            if (isRetryable && attempt < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                finishFinalFailure(
                    task = task,
                    run = run,
                    scheduledFor = scheduledFor,
                    assistantName = assistant.name,
                    errorCode = if (isRetryable) "RETRY_EXHAUSTED" else "FAILED",
                    errorMessage = errorMessage,
                )
            }
        }
    }

    private suspend fun getOrCreateRun(task: ScheduledTaskEntity, scheduledFor: Long): ScheduledTaskRunEntity {
        val existing = runDao.getByTaskAndScheduledFor(task.id, scheduledFor)
        if (existing != null) return existing

        val runId = Uuid.random().toString()
        val run = ScheduledTaskRunEntity(
            id = runId,
            taskId = task.id,
            assistantId = task.assistantId,
            scheduledFor = scheduledFor,
            conversationId = runId,
            status = ScheduledTaskRunStatus.PENDING,
        )

        val inserted = runDao.insertIgnore(run)
        if (inserted != -1L) return run

        return runDao.getByTaskAndScheduledFor(task.id, scheduledFor) ?: run
    }

    private suspend fun upsertConversation(conversation: Conversation) {
        val existing = conversationRepository.getConversationById(conversation.id)
        if (existing == null) {
            conversationRepository.insertConversation(conversation)
        } else {
            conversationRepository.updateConversation(
                existing.copy(
                    assistantId = conversation.assistantId,
                    title = conversation.title,
                    updateAt = conversation.updateAt,
                    messageNodes = conversation.messageNodes,
                )
            )
        }
    }

    private fun resolveModelAndAssistantForRun(
        settingsStore: SettingsStore,
        assistant: Assistant,
        task: ScheduledTaskEntity,
    ): Pair<me.rerere.ai.provider.Model, Assistant>? {
        val settings = settingsStore.settingsFlow.value

        val overrideModelId = task.overrideModelId
            ?.takeIf { it.isNotBlank() }
            ?.let { id -> runCatching { Uuid.parse(id) }.getOrNull() }
        val modelId = overrideModelId ?: assistant.backgroundModelId ?: assistant.chatModelId ?: settings.chatModelId

        val model = settings.findModelById(modelId) ?: return null

        val assistantForRun = assistant.copy(
            searchMode = resolveSearchModeOverride(task, assistant),
            preferBuiltInSearch = resolvePreferBuiltInSearchOverride(task, assistant),
            enableSearchAgent = when (task.searchOverrideType) {
                ScheduledTaskSearchOverrideType.OFF,
                ScheduledTaskSearchOverrideType.OVERRIDE_PREFER_BUILTIN -> false
                else -> assistant.enableSearchAgent
            },
            mcpServers = resolveMcpServersOverride(task, assistant),
        )

        return model to assistantForRun
    }

    private fun resolveSearchModeOverride(task: ScheduledTaskEntity, assistant: Assistant): AssistantSearchMode {
        return when (task.searchOverrideType) {
            ScheduledTaskSearchOverrideType.INHERIT -> assistant.searchMode
            ScheduledTaskSearchOverrideType.OFF -> AssistantSearchMode.Off
            ScheduledTaskSearchOverrideType.OVERRIDE -> {
                task.searchProviderIndex.takeIf { it >= 0 }?.let { AssistantSearchMode.Provider(it) }
                    ?: AssistantSearchMode.Off
            }
            ScheduledTaskSearchOverrideType.OVERRIDE_PREFER_BUILTIN -> {
                task.searchProviderIndex.takeIf { it >= 0 }?.let { AssistantSearchMode.Provider(it) }
                    ?: AssistantSearchMode.BuiltIn
            }

            else -> assistant.searchMode
        }
    }

    private fun resolvePreferBuiltInSearchOverride(task: ScheduledTaskEntity, assistant: Assistant): Boolean {
        return when (task.searchOverrideType) {
            ScheduledTaskSearchOverrideType.INHERIT -> assistant.preferBuiltInSearch
            ScheduledTaskSearchOverrideType.OFF -> false
            ScheduledTaskSearchOverrideType.OVERRIDE -> false
            ScheduledTaskSearchOverrideType.OVERRIDE_PREFER_BUILTIN -> true
            else -> assistant.preferBuiltInSearch
        }
    }

    private fun resolveMcpServersOverride(task: ScheduledTaskEntity, assistant: Assistant): Set<Uuid> {
        return when (task.mcpOverrideType) {
            ScheduledTaskOverrideType.INHERIT -> assistant.mcpServers
            ScheduledTaskOverrideType.OFF -> emptySet()
            ScheduledTaskOverrideType.OVERRIDE -> {
                task.mcpServerId.toUuidSet()
            }

            else -> assistant.mcpServers
        }
    }

    private fun String?.toUuidSet(): Set<Uuid> {
        val raw = this?.trim().orEmpty()
        if (raw.isBlank()) return emptySet()
        return raw
            .split(',')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { id -> runCatching { Uuid.parse(id) }.getOrNull() }
            .toSet()
    }

    private fun buildTools(
        assistantForRun: Assistant,
        model: me.rerere.ai.provider.Model,
        conversationId: Uuid,
        settings: me.rerere.rikkahub.data.datastore.Settings,
    ): List<Tool> {
        val mcpTools = mcpManager.getAvailableToolsForAssistant(assistantForRun)
        val hasExternalTools = assistantForRun.searchMode !is AssistantSearchMode.Off || mcpTools.isNotEmpty()

        return buildList {
            val modelProvider = model.findProvider(settings.providers)
            val modelSupportsBuiltIn = model.supportsBuiltInSearch(modelProvider)
            val useBuiltInSearch = modelSupportsBuiltIn && !assistantForRun.enableSearchAgent && (
                assistantForRun.searchMode is AssistantSearchMode.BuiltIn ||
                    (assistantForRun.preferBuiltInSearch && assistantForRun.searchMode !is AssistantSearchMode.Off)
                )

            when (val sm = assistantForRun.searchMode) {
                is AssistantSearchMode.Provider,
                is AssistantSearchMode.MultiProvider -> {
                    if (!useBuiltInSearch) {
                        addAll(
                            createEffectiveSearchTools(
                                settings = settings,
                                searchMode = sm,
                                enableSearchAgent = assistantForRun.enableSearchAgent,
                            )
                        )
                    }
                }

                is AssistantSearchMode.BuiltIn -> Unit
                is AssistantSearchMode.Off -> Unit
            }

            addAll(localTools.getTools(assistantForRun.localTools, assistantForRun.id, conversationId))

            mcpTools.forEach { tool ->
                add(
                    Tool(
                        name = tool.name,
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        requiresUserApproval = tool.requireApproval,
                        execute = { args ->
                            val obj = args as? JsonObject ?: JsonObject(emptyMap())
                            mcpManager.callToolForAssistant(assistantForRun, tool.name, obj)
                        },
                    )
                )
            }

            if (!model.abilities.contains(ModelAbility.TOOL) && hasExternalTools) {
                // Tools are configured but the selected model doesn't support tool calling.
                // Keep the run going; the model may still answer without tools.
            }
        }
    }

    private fun createEffectiveSearchTools(
        settings: me.rerere.rikkahub.data.datastore.Settings,
        searchMode: AssistantSearchMode,
        enableSearchAgent: Boolean,
    ): List<Tool> {
        val originalTools = SearchTools.createSearchTools(settings, searchMode).toList()
        if (!enableSearchAgent) return originalTools

        val searchAgentTool = SearchAgentTools.create(
            settings = settings,
            searchMode = searchMode,
            providerManager = providerManager,
            requestLogManager = requestLogManager,
            json = JsonInstant,
            progressStore = searchAgentProgressStore,
        ) ?: return originalTools

        return if (settings.searchAgentOverrideOriginalTools) {
            listOf(searchAgentTool)
        } else {
            listOf(searchAgentTool) + originalTools
        }
    }

    private suspend fun finishFinalFailure(
        task: ScheduledTaskEntity,
        run: ScheduledTaskRunEntity,
        scheduledFor: Long,
        assistantName: String?,
        errorCode: String,
        errorMessage: String,
    ): Result {
        val conversationId = Uuid.parse(run.conversationId)
        val existingConversation = conversationRepository.getConversationById(conversationId)
        if (existingConversation != null) {
            val errorMsg = UIMessage.assistant("Task failed: $errorMessage")
            conversationRepository.updateConversation(
                existingConversation.copy(
                    messageNodes = (existingConversation.currentMessages + errorMsg).map { it.toMessageNode() },
                    updateAt = Instant.now(),
                )
            )
        }

        val finishedTask = afterRunUpdateTask(
            task = task,
            nowMillis = System.currentTimeMillis(),
            scheduledFor = scheduledFor,
            lastErrorCode = errorCode,
            lastErrorAt = System.currentTimeMillis(),
        )

        scheduleNextIfNeeded(finishedTask)

        maybeSendNotification(
            task = finishedTask,
            assistantName = assistantName ?: "Assistant",
            conversationId = conversationId,
            content = errorMessage,
            isSuccess = false,
        )
        return Result.success()
    }

    private suspend fun afterRunUpdateTask(
        task: ScheduledTaskEntity,
        nowMillis: Long,
        scheduledFor: Long,
        lastErrorCode: String?,
        lastErrorAt: Long?,
    ): ScheduledTaskEntity {
        val disabled = task.repeatType == ScheduledTaskRepeatType.ONCE
        if (disabled) {
            taskDao.updateEnabled(task.id, false)
        }

        val nextRunAt = if (!disabled) {
            ScheduledTaskNextRunCalculator.computeNextRunAtMillis(
                task = task.copy(lastScheduledFor = scheduledFor),
                nowMillis = nowMillis,
            )
        } else {
            null
        }

        taskDao.updateRunFields(
            id = task.id,
            lastRunAt = nowMillis,
            lastScheduledFor = scheduledFor,
            nextRunAt = nextRunAt,
            lastErrorCode = lastErrorCode,
            lastErrorAt = lastErrorAt,
        )

        return task.copy(
            enabled = if (disabled) false else task.enabled,
            lastRunAt = nowMillis,
            lastScheduledFor = scheduledFor,
            nextRunAt = nextRunAt,
            lastErrorCode = lastErrorCode,
            lastErrorAt = lastErrorAt,
        )
    }

    private suspend fun scheduleNextIfNeeded(task: ScheduledTaskEntity) {
        val nextRunAt = task.nextRunAt ?: return
        if (!task.enabled) return
        scheduler.scheduleNextAfterRun(
            taskId = task.id,
            scheduledFor = nextRunAt,
            accuracyMode = task.accuracyMode,
        )
    }

    private fun isRetryable(t: Throwable): Boolean {
        if (t is java.io.IOException) return true
        val msg = t.message ?: return false
        return msg.contains("timeout", ignoreCase = true) || msg.contains("429")
    }

    private fun buildConversationTitle(
        taskName: String,
        scheduledFor: Long,
        zoneId: ZoneId,
        locale: Locale,
    ): String {
        val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(scheduledFor), zoneId)
        val time = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", locale))
        return if (taskName.isNotBlank()) "$taskName · $time" else "Scheduled Task · $time"
    }

    private fun maybeSendNotification(
        task: ScheduledTaskEntity,
        assistantName: String,
        conversationId: Uuid,
        content: String,
        isSuccess: Boolean,
    ) {
        if (!task.notifyOnDone) return

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Scheduled Tasks",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        val title = if (task.name.isNotBlank()) {
            if (isSuccess) "$assistantName: ${task.name}" else "$assistantName: ${task.name} (Failed)"
        } else {
            if (isSuccess) assistantName else "$assistantName (Failed)"
        }

        val intent = Intent(applicationContext, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (ActivityCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            nm.notify(conversationId.hashCode(), notification)
        }
    }
}
