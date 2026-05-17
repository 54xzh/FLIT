package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.merge
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.AskUserState
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UsedLorebookEntry
import me.rerere.ai.ui.UsedMemory
import me.rerere.ai.ui.UsedMode
import me.rerere.ai.ui.UsedSessionMemory
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.limitContext
import me.rerere.ai.ui.truncate
import me.rerere.ai.util.HttpStatusException
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_LEARNING_MODE_PROMPT
import me.rerere.rikkahub.data.ai.tools.MEMORY_CONTEXT_VARIABLE
import me.rerere.rikkahub.data.ai.tools.MEMORY_MANAGEMENT_SYSTEM_PROMPT_TEMPLATE
import me.rerere.rikkahub.data.ai.tools.MEMORY_MANAGEMENT_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.SESSION_MEMORY_CONTEXT_VARIABLE
import me.rerere.rikkahub.data.ai.tools.SESSION_MEMORY_MANAGEMENT_SYSTEM_PROMPT_TEMPLATE
import me.rerere.rikkahub.data.ai.tools.SESSION_MEMORY_MANAGEMENT_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.renderConfiguredToolSystemPrompt
import me.rerere.rikkahub.data.ai.tools.renderConfiguredSystemPrompt
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentSummaryTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getHttpRetryDelaySeconds
import me.rerere.rikkahub.data.datastore.getHttpRetryMaxRetries
import me.rerere.rikkahub.data.ai.rag.EmbeddingService
import me.rerere.rikkahub.data.db.entity.ToolResultArchiveEntity
import me.rerere.rikkahub.data.db.entity.ToolResultArchiveChunkEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookActivationType
import me.rerere.rikkahub.data.model.LorebookEntry
import me.rerere.rikkahub.data.model.Mode
import me.rerere.rikkahub.data.model.ModeAttachmentType
import me.rerere.rikkahub.data.model.SessionMemory
import me.rerere.rikkahub.data.model.ToolResultHistoryMode
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.ToolResultArchiveRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.R
import java.io.IOException
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private val MEMORY_TOOL_NAMES = setOf("create_memory", "edit_memory", "delete_memory")
private val SESSION_MEMORY_TOOL_NAMES = setOf(
    "create_session_memory",
    "edit_session_memory",
    "delete_session_memory",
)
private val INTERNAL_MEMORY_TOOL_NAMES = MEMORY_TOOL_NAMES + SESSION_MEMORY_TOOL_NAMES
private const val SESSION_MEMORY_MAX_COUNT = 50
private const val SESSION_MEMORY_MAX_CONTENT_CHARS = 3000
private const val MCP_TOOL_APPROVAL_REJECTED_TEXT = "User declined this call"
private const val META_ANTHROPIC_TYPE = "anthropic_type"
private const val TYPE_SERVER_TOOL_USE = "server_tool_use"
private const val CLAUDE_WEB_SEARCH_TOOL_NAME = "web_search"
private const val GROK_WEB_SEARCH_TOOL_NAME = "web_search"
private const val GROK_X_SEARCH_TOOL_NAME = "x_search"
private val RETRYABLE_HTTP_STATUS_CODES = setOf(408, 429, 500, 502, 503, 504)

/**
 * Result of building messages, includes both the messages and info about activated context sources.
 */
data class BuildMessagesResult(
    val messages: List<UIMessage>,
    val usedLorebookEntries: List<UsedLorebookEntry> = emptyList(),
    val usedModes: List<UsedMode> = emptyList(),
    val usedMemories: List<UsedMemory> = emptyList(),
    val usedSessionMemories: List<UsedSessionMemory> = emptyList(),
)

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>,
        val finishReasons: Set<String> = emptySet(),
    ) : GenerationChunk
}

internal fun shouldRetryHttpRequest(
    throwable: Throwable,
    attempt: Int,
    maxRetries: Int,
    emittedAnyChunk: Boolean = false,
): Boolean {
    if (throwable.hasCancellationException()) return false
    if (maxRetries <= 0) return false
    if (emittedAnyChunk) return false
    if (!throwable.isRetryableHttpOrNetworkError()) return false
    return attempt <= maxRetries
}

internal fun computeHttpRetryDelayMs(retryDelaySeconds: Int): Long {
    return retryDelaySeconds.coerceIn(1, 30) * 1_000L
}

private fun Throwable.isRetryableHttpOrNetworkError(): Boolean {
    return hasRetryableHttpStatusCode() || hasRetryableNetworkError()
}

private fun Throwable.hasCancellationException(): Boolean {
    val visited = HashSet<Throwable>()
    var current: Throwable? = this
    while (current != null && visited.add(current)) {
        if (current is CancellationException) return true
        current = current.cause
    }
    return false
}

private fun Throwable.hasRetryableHttpStatusCode(): Boolean {
    val visited = HashSet<Throwable>()
    var current: Throwable? = this
    while (current != null && visited.add(current)) {
        if (current is HttpStatusException && current.statusCode in RETRYABLE_HTTP_STATUS_CODES) {
            return true
        }
        current = current.cause
    }
    return false
}

private fun Throwable.hasRetryableNetworkError(): Boolean {
    val visited = HashSet<Throwable>()
    var current: Throwable? = this
    while (current != null && visited.add(current)) {
        if (current is IOException && !current.isCanceledIOException()) {
            return true
        }
        current = current.cause
    }
    return false
}

private fun IOException.isCanceledIOException(): Boolean {
    val normalizedMessage = message?.lowercase().orEmpty()
    return normalizedMessage == "canceled" || normalizedMessage == "cancelled"
}

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val toolResultArchiveRepository: ToolResultArchiveRepository,
    private val aiLoggingManager: AILoggingManager,
    private val requestLogManager: AIRequestLogManager,
    private val embeddingService: EmbeddingService,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        conversationId: Uuid? = null,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        sessionMemories: List<SessionMemory> = emptyList(),
        enableSessionMemoryTools: Boolean = false,
        onSessionMemoriesChanged: suspend (List<SessionMemory>) -> Unit = {},
        enableMemoryTools: Boolean = true,
        tools: List<Tool> = emptyList(),
        truncateIndex: Int = -1,
        maxSteps: Int = 256,
        enabledModeIds: Set<Uuid> = emptySet(),
        source: AIRequestSource = AIRequestSource.OTHER,
        toolApprovalHandler: ToolApprovalHandler? = null,
        askUserHandler: AskUserHandler? = null,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages
        var currentSessionMemories = sessionMemories

        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")
            var latestFinishReasons: Set<String> = emptySet()

            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                // Only add memory tools if memory is enabled AND memory is available for this run
                // (temporary chats pass `memories = null` to opt-out).
                if (assistant.enableMemory && memories != null && enableMemoryTools) {
                    buildMemoryTools(
                        onCreation = { content ->
                            val normalizedContent = content.trim()
                            memoryRepo.addMemory(assistant.id.toString(), normalizedContent)
                        },
                        onUpdate = { id, content ->
                            val normalizedContent = content.trim()
                            memoryRepo.updateContent(id, normalizedContent)
                        },
                        onDelete = { id ->
                            memoryRepo.deleteMemory(id)
                        }
                    ).let(this::addAll)
                }
                if (assistant.enableSessionMemory && enableSessionMemoryTools) {
                    buildSessionMemoryTools(
                        getMemories = { currentSessionMemories },
                        onChange = { updatedMemories ->
                            currentSessionMemories = updatedMemories
                            onSessionMemoriesChanged(updatedMemories)
                        },
                    ).let(this::addAll)
                }
                addAll(tools)
            }

            generateInternal(
                assistant = assistant,
                settings = settings,
                messages = messages,
                conversationId = conversationId,
                onUpdateMessages = { updatedMessages, finishReasons ->
                    if (finishReasons.isNotEmpty()) {
                        latestFinishReasons = finishReasons
                    }
                    messages = updatedMessages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant
                    )
                    emit(
                        GenerationChunk.Messages(
                            messages.visualTransforms(
                                transformers = outputTransformers,
                                context = context,
                                model = model,
                                assistant = assistant
                            ),
                            finishReasons = finishReasons,
                        )
                    )
                },
                transformers = inputTransformers,
                model = model,
                providerImpl = providerImpl,
                provider = provider,
                tools = toolsInternal,
                memories = memories ?: emptyList(),
                sessionMemories = if (assistant.enableSessionMemory) currentSessionMemories else emptyList(),
                truncateIndex = truncateIndex,
                stream = assistant.streamOutput,
                enabledModeIds = enabledModeIds,
                source = source,
            )
            messages = messages.visualTransforms(
                transformers = outputTransformers,
                context = context,
                model = model,
                assistant = assistant
            )
            messages = messages.onGenerationFinish(
                transformers = outputTransformers,
                context = context,
                model = model,
                assistant = assistant
            )
            emit(GenerationChunk.Messages(messages))

            val toolCalls = messages.last().getToolCalls().filterNot { toolCall ->
                val isServerToolUseByMetadata = toolCall.metadata
                    ?.get(META_ANTHROPIC_TYPE)
                    ?.jsonPrimitive
                    ?.contentOrNull == TYPE_SERVER_TOOL_USE
                val isClaudeBuiltInWebSearchToolCall =
                    toolCall.toolName == CLAUDE_WEB_SEARCH_TOOL_NAME &&
                        model.tools.contains(BuiltInTools.ClaudeWebSearch)
                val isGrokBuiltInToolCall =
                    (toolCall.toolName == GROK_WEB_SEARCH_TOOL_NAME && model.tools.contains(BuiltInTools.GrokWebSearch)) ||
                        (toolCall.toolName == GROK_X_SEARCH_TOOL_NAME && model.tools.contains(BuiltInTools.GrokXSearch))
                isServerToolUseByMetadata || isClaudeBuiltInWebSearchToolCall || isGrokBuiltInToolCall
            }
            if (toolCalls.isEmpty()) {
                val shouldResumePauseTurn = latestFinishReasons.any { reason ->
                    reason.trim().lowercase(Locale.US) == "pause_turn"
                }
                if (shouldResumePauseTurn) {
                    Log.i(TAG, "generateText: pause_turn detected at step #$stepIndex, resume next step")
                    continue
                }
                // no tool calls, break
                break
            }

            val lastMessageIndex = messages.lastIndex
            val lastMessage = messages[lastMessageIndex]
            val resolvedToolCalls = toolCalls.mapIndexed { index, toolCall ->
                val resolvedToolCallId = toolCall.toolCallId.ifBlank {
                    "gen_${toolCall.toolName}_${index}_${Uuid.random()}"
                }
                toolCall.copy(toolCallId = resolvedToolCallId)
            }
            if (resolvedToolCalls != toolCalls) {
                var toolCallIndex = 0
                messages = messages.toMutableList().apply {
                    set(
                        lastMessageIndex,
                        lastMessage.copy(
                            parts = lastMessage.parts.map { part ->
                                if (part is UIMessagePart.ToolCall && toolCallIndex < resolvedToolCalls.size) {
                                    resolvedToolCalls[toolCallIndex++]
                                } else {
                                    part
                                }
                            }
                        )
                    )
                }
            }

            suspend fun updateToolApproval(
                toolCallId: String,
                toolName: String,
                state: ToolApprovalState,
            ) {
                val currentLastMessage = messages[lastMessageIndex]
                val parts = currentLastMessage.parts.toMutableList()
                val idx = parts.indexOfFirst { part ->
                    part is UIMessagePart.ToolApproval && part.toolCallId == toolCallId
                }
                if (idx >= 0) {
                    val existing = parts[idx] as UIMessagePart.ToolApproval
                    parts[idx] = existing.copy(state = state)
                } else {
                    parts.add(
                        UIMessagePart.ToolApproval(
                            toolCallId = toolCallId,
                            toolName = toolName,
                            state = state,
                        )
                    )
                }
                messages = messages.toMutableList().apply {
                    set(lastMessageIndex, currentLastMessage.copy(parts = parts))
                }
                emit(
                    GenerationChunk.Messages(
                        messages.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                        )
                    )
                )
            }

            suspend fun updateAskUser(
                toolCallId: String,
                question: String,
                options: List<String>,
                state: AskUserState,
                answer: String? = null,
                questions: List<UIMessagePart.AskUserQuestion>? = null,
                answers: List<String>? = null,
            ) {
                val currentLastMessage = messages[lastMessageIndex]
                val parts = currentLastMessage.parts.toMutableList()
                val idx = parts.indexOfFirst { part ->
                    part is UIMessagePart.AskUser && part.toolCallId == toolCallId
                }
                if (idx >= 0) {
                    val existing = parts[idx] as UIMessagePart.AskUser
                    parts[idx] = existing.copy(
                        state = state,
                        answer = answer,
                        questions = questions ?: existing.questions,
                        answers = answers ?: existing.answers,
                    )
                } else {
                    parts.add(
                        UIMessagePart.AskUser(
                            toolCallId = toolCallId,
                            question = question,
                            options = options,
                            questions = questions,
                            state = state,
                            answer = answer,
                            answers = answers,
                        )
                    )
                }
                messages = messages.toMutableList().apply {
                    set(lastMessageIndex, currentLastMessage.copy(parts = parts))
                }
                emit(
                    GenerationChunk.Messages(
                        messages.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                        )
                    )
                )
            }

            // handle tool calls
            val results = arrayListOf<UIMessagePart.ToolResult>()
            resolvedToolCalls.forEach { toolCall ->
                val resolvedToolCallId = toolCall.toolCallId
                runCatching {
                    val tool = toolsInternal.find { tool -> tool.name == toolCall.toolName }
                        ?: error("Tool ${toolCall.toolName} not found")
                    val args = json.parseToJsonElement(toolCall.arguments.ifBlank { "{}" })

                    val result = if (tool.requiresUserApproval) {
                        val rejectionText = MCP_TOOL_APPROVAL_REJECTED_TEXT
                        val cid = conversationId
                        val handler = toolApprovalHandler
                        if (cid == null || handler == null) {
                            JsonPrimitive(rejectionText)
                        } else {
                            updateToolApproval(
                                toolCallId = resolvedToolCallId,
                                toolName = toolCall.toolName,
                                state = ToolApprovalState.Pending,
                            )
                            val approved = runCatching {
                                handler.requestApproval(
                                    ToolApprovalRequest(
                                        conversationId = cid,
                                        toolCallId = resolvedToolCallId,
                                        toolName = toolCall.toolName,
                                        arguments = args,
                                    )
                                )
                            }.getOrElse { false }
                            updateToolApproval(
                                toolCallId = resolvedToolCallId,
                                toolName = toolCall.toolName,
                                state = if (approved) ToolApprovalState.Approved else ToolApprovalState.Rejected,
                            )
                            if (approved) {
                                Log.i(TAG, "generateText: executing tool ${tool.name} with args: $args")
                                tool.execute(args)
                            } else {
                                JsonPrimitive(rejectionText)
                            }
                        }
                    } else if (toolCall.toolName == "ask_user" && conversationId != null && askUserHandler != null) {
                        val questionsArray = args.jsonObject["questions"]?.jsonArray
                        val parsedQuestions = if (questionsArray != null && questionsArray.isNotEmpty()) {
                            questionsArray.mapNotNull { item ->
                                val obj = item.jsonObjectOrNull ?: return@mapNotNull null
                                val q = obj["question"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                                val opts = obj["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                                UIMessagePart.AskUserQuestion(question = q, options = opts)
                            }
                        } else {
                            val singleQuestion = args.jsonObject["question"]?.jsonPrimitive?.contentOrNull ?: ""
                            val singleOptions = args.jsonObject["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                            if (singleQuestion.isNotBlank()) listOf(UIMessagePart.AskUserQuestion(question = singleQuestion, options = singleOptions)) else emptyList()
                        }

                        if (parsedQuestions.isEmpty()) {
                            JsonPrimitive("No valid questions provided.")
                        } else if (parsedQuestions.size == 1) {
                            val q = parsedQuestions.first()
                            updateAskUser(
                                toolCallId = resolvedToolCallId,
                                question = q.question,
                                options = q.options,
                                state = AskUserState.Pending,
                            )
                            val answer = runCatching {
                                askUserHandler.askUser(
                                    AskUserRequest(
                                        conversationId = conversationId,
                                        toolCallId = resolvedToolCallId,
                                        question = q.question,
                                        options = q.options,
                                    )
                                )
                            }.getOrNull()
                            if (answer != null) {
                                updateAskUser(
                                    toolCallId = resolvedToolCallId,
                                    question = q.question,
                                    options = q.options,
                                    state = AskUserState.Answered,
                                    answer = answer,
                                )
                                buildJsonObject { put("answer", answer) }
                            } else {
                                updateAskUser(
                                    toolCallId = resolvedToolCallId,
                                    question = q.question,
                                    options = q.options,
                                    state = AskUserState.Dismissed,
                                )
                                JsonPrimitive("The user dismissed the question without answering.")
                            }
                        } else {
                            val firstQ = parsedQuestions.first()
                            updateAskUser(
                                toolCallId = resolvedToolCallId,
                                question = firstQ.question,
                                options = firstQ.options,
                                questions = parsedQuestions,
                                state = AskUserState.Pending,
                            )
                            val allAnswers = runCatching {
                                askUserHandler.askUser(
                                    AskUserRequest(
                                        conversationId = conversationId,
                                        toolCallId = resolvedToolCallId,
                                        question = firstQ.question,
                                        options = firstQ.options,
                                    )
                                )
                            }.getOrNull()
                            if (allAnswers != null) {
                                val answerList = allAnswers.split("\n---\n")
                                updateAskUser(
                                    toolCallId = resolvedToolCallId,
                                    question = firstQ.question,
                                    options = firstQ.options,
                                    questions = parsedQuestions,
                                    state = AskUserState.Answered,
                                    answers = answerList,
                                )
                                buildJsonObject {
                                    put("answers", buildJsonArray {
                                        parsedQuestions.forEachIndexed { index, q ->
                                            add(buildJsonObject {
                                                put("question", q.question)
                                                put("answer", answerList.getOrElse(index) { "No answer" })
                                            })
                                        }
                                    })
                                }
                            } else {
                                updateAskUser(
                                    toolCallId = resolvedToolCallId,
                                    question = firstQ.question,
                                    options = firstQ.options,
                                    questions = parsedQuestions,
                                    state = AskUserState.Dismissed,
                                )
                                JsonPrimitive("The user dismissed the questions without answering.")
                            }
                        }
                    } else {
                        Log.i(TAG, "generateText: executing tool ${tool.name} with args: $args")
                        tool.execute(args)
                    }

                    results += UIMessagePart.ToolResult(
                        toolName = toolCall.toolName,
                        toolCallId = resolvedToolCallId,
                        content = result,
                        arguments = args,
                        metadata = toolCall.metadata
                    )
                }.onFailure {
                    it.printStackTrace()
                    results += UIMessagePart.ToolResult(
                        toolName = toolCall.toolName,
                        toolCallId = resolvedToolCallId,
                        metadata = toolCall.metadata,
                        content = buildJsonObject {
                            put(
                                "error",
                                JsonPrimitive(buildString {
                                    append("[${it.javaClass.name}] ${it.message}")
                                    append("\n${it.stackTraceToString()}")
                                })
                            )
                        },
                        arguments = runCatching {
                            json.parseToJsonElement(toolCall.arguments)
                        }.getOrElse { JsonObject(emptyMap()) }
                    )
                }
            }

            conversationId?.let { id ->
                if (results.isNotEmpty()) {
                    val userTurnIndex = messages.count { it.role == MessageRole.USER }
                    toolResultArchiveRepository.archiveToolResults(
                        conversationId = id.toString(),
                        assistantId = assistant.id.toString(),
                        userTurnIndex = userTurnIndex,
                        results = results,
                        enableRagIndexing = settings.displaySetting.toolResultHistoryMode == ToolResultHistoryMode.RAG,
                    )
                }
            }
            messages = messages + UIMessage(
                role = MessageRole.TOOL,
                parts = results
            )
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant
                    )
                )
            )
        }

    }.flowOn(Dispatchers.IO)

    suspend fun buildMessages(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        conversationId: Uuid?,
        model: Model,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        sessionMemories: List<SessionMemory>,
        truncateIndex: Int,
        enabledModeIds: Set<Uuid> = emptySet(),
    ): BuildMessagesResult {
        val allMessages = messages.truncate(truncateIndex)
        val conversation = conversationId?.let { conversationRepo.getConversationById(it) }
        val contextSummarySection = conversation?.let { convo ->
            val summary = convo.contextSummary?.trim().orEmpty()
            val summaryUpToIndex = convo.contextSummaryUpToIndex
            if (summary.isNotEmpty() && summaryUpToIndex in 0 until allMessages.size) {
                buildString {
                    appendLine("Conversation summary of earlier turns (use as prior context):")
                    appendLine(summary)
                    append("Continue using newer messages below as the most recent context.")
                }
            } else {
                ""
            }
        }.orEmpty()

        val summaryUpToIndex = conversation?.contextSummaryUpToIndex ?: -1
        val contextBaseMessages = if (contextSummarySection.isNotBlank() && summaryUpToIndex in 0 until allMessages.size) {
            allMessages.drop(summaryUpToIndex + 1)
        } else {
            allMessages
        }

        val historyLimitedMessages = if (assistant.enableHistorySummarization) {
            assistant.maxHistoryMessages?.let { limit ->
                if (limit > 0) contextBaseMessages.limitContext(limit) else contextBaseMessages
            } ?: contextBaseMessages
        } else {
            contextBaseMessages
        }

        val rawContextMessages = historyLimitedMessages
            .limitContext(assistant.contextMessageSize.coerceAtLeast(0))

        val totalUserTurnCount = allMessages.count { it.role == MessageRole.USER }
        val userTurnIndexByMessageId = buildMap<Uuid, Int> {
            var currentTurn = 0
            allMessages.forEach { message ->
                if (message.role == MessageRole.USER) currentTurn++
                put(message.id, currentTurn)
            }
        }

        val toolResultHistoryMode = settings.displaySetting.toolResultHistoryMode
        val keepUserMessages = settings.displaySetting.toolResultKeepUserMessages.coerceAtLeast(0)
        val toolResultRagSimilarityThreshold = settings.displaySetting.toolResultRagSimilarityThreshold
            .takeIf { it.isFinite() }
            ?.coerceIn(0f, 1f)
            ?: 0.45f
        val contextMessages = if (
            conversationId != null &&
            (toolResultHistoryMode == ToolResultHistoryMode.RAG ||
                toolResultHistoryMode == ToolResultHistoryMode.DISCARD)
        ) {
            rawContextMessages.filterNot { message ->
                val turnIndex = userTurnIndexByMessageId[message.id] ?: totalUserTurnCount
                val isOld = (totalUserTurnCount - turnIndex) > keepUserMessages
                if (!isOld) return@filterNot false

                val hasExternalToolCall = message.getToolCalls().any { it.toolName !in INTERNAL_MEMORY_TOOL_NAMES }
                val hasExternalToolResult = message.getToolResults().any { it.toolName !in INTERNAL_MEMORY_TOOL_NAMES }
                hasExternalToolCall || hasExternalToolResult
            }
        } else {
            rawContextMessages
        }

        val effectiveContextMessages = assistant.maxSearchResultsRetained?.let { maxSearches ->
            if (maxSearches > 0) {
                val searchResultIndices = contextMessages.mapIndexedNotNull { index, msg ->
                    val hasSearchResult = msg.parts.any { part ->
                        part is UIMessagePart.ToolResult && part.toolName == "search_web"
                    }
                    if (hasSearchResult) index else null
                }

                val indicesToPrune = searchResultIndices.dropLast(maxSearches).toSet()
                if (indicesToPrune.isNotEmpty()) {
                    contextMessages.mapIndexed { index, msg ->
                        if (index in indicesToPrune) {
                            msg.copy(
                                parts = msg.parts.map { part ->
                                    if (part is UIMessagePart.ToolResult && part.toolName == "search_web") {
                                        part.copy(
                                            content = buildJsonObject {
                                                put(
                                                    "note",
                                                    JsonPrimitive("Earlier search results pruned to save context"),
                                                )
                                            },
                                        )
                                    } else {
                                        part
                                    }
                                },
                            )
                        } else {
                            msg
                        }
                    }
                } else {
                    contextMessages
                }
            } else {
                contextMessages
            }
        } ?: contextMessages

        val imageArchivedMessages = archiveOldImageMessages(effectiveContextMessages, assistant)
        preSummarizeDocuments(imageArchivedMessages, assistant, settings)
        val documentArchivedMessages = archiveOldDocumentMessages(imageArchivedMessages, assistant, settings)

        // Token estimator (rough estimate: 4 chars per token)
        fun estimateTokens(text: String) = text.length / 4
        fun estimateTokens(message: UIMessage) = estimateTokens(message.toText())

        val maxTokens = assistant.maxTokenUsage
        var currentTokens = 0

        // Cosine similarity for RAG matching
        fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
            if (a.size != b.size) return 0f
            var dotProduct = 0f
            var normA = 0f
            var normB = 0f
            for (i in a.indices) {
                dotProduct += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
            return if (denominator == 0f) 0f else dotProduct / denominator
        }

        // Helper to check if and why lorebook entry activated
        fun getLorebookEntryActivationReason(
            entry: LorebookEntry,
            recentMessages: List<String>,
            queryEmbedding: List<Float>? = null,
        ): String? {
            if (!entry.enabled) return null
            return when (entry.activationType) {
                LorebookActivationType.ALWAYS -> "Always Active"
                LorebookActivationType.KEYWORDS -> {
                    val searchText = recentMessages.joinToString(" ")
                    val matchingKeyword = entry.keywords.firstOrNull { keyword ->
                        if (entry.useRegex) {
                            try {
                                val regex = if (entry.caseSensitive) {
                                    Regex(keyword)
                                } else {
                                    Regex(keyword, RegexOption.IGNORE_CASE)
                                }
                                regex.containsMatchIn(searchText)
                            } catch (e: Exception) {
                                Log.w(TAG, "Invalid regex in lorebook entry: $keyword", e)
                                false
                            }
                        } else {
                            if (entry.caseSensitive) {
                                searchText.contains(keyword)
                            } else {
                                searchText.contains(keyword, ignoreCase = true)
                            }
                        }
                    }
                    if (matchingKeyword != null) "Keyword: $matchingKeyword" else null
                }

                LorebookActivationType.RAG -> {
                    // RAG activation uses embedding similarity
                    if (entry.embedding.isNullOrEmpty()) {
                        Log.d(TAG, "RAG entry '${entry.name}' has no embedding, skipping")
                        null
                    } else if (queryEmbedding == null) {
                        Log.d(TAG, "No query embedding available for RAG matching")
                        null
                    } else {
                        // Compute cosine similarity
                        val similarity = cosineSimilarity(entry.embedding, queryEmbedding)
                        val threshold = 0.7f // Similarity threshold for activation
                        val activated = similarity >= threshold
                        if (activated) {
                            val scoreStr = runCatching {
                                "%.2f".format(Locale.US, similarity)
                            }.getOrElse {
                                similarity.toString().take(4)
                            }
                            Log.d(TAG, "RAG entry '${entry.name}' activated with similarity $similarity")
                            "RAG Match ($scoreStr)"
                        }
                        else null
                    }
                }
            }
        }

        // Get recent message text for lorebook keyword scanning
        val recentMessagesForScan = documentArchivedMessages.takeLast(10).map { it.toText() }

        val toolResultRagPrompt = run {
            if (toolResultHistoryMode != ToolResultHistoryMode.RAG) return@run ""
            val id = conversationId ?: run {
                Log.w(TAG, "Tool result RAG enabled but conversationId is null; skipping archived retrieval (temporary chat?)")
                return@run ""
            }

            val maxUserTurnIndexExclusive = (totalUserTurnCount - keepUserMessages).coerceAtLeast(0)
            if (maxUserTurnIndexExclusive <= 0) return@run ""

            val queryText = documentArchivedMessages.asReversed()
                .asSequence()
                .filter { it.role == MessageRole.USER }
                .take(3)
                .toList()
                .asReversed()
                .joinToString(separator = "\n") { it.toContentText() }
                .trim()
            if (queryText.isBlank()) return@run ""

            val results = toolResultArchiveRepository.retrieveRelevantToolResultChunksWithScores(
                conversationId = id.toString(),
                assistantId = assistant.id.toString(),
                query = queryText,
                maxUserTurnIndexExclusive = maxUserTurnIndexExclusive,
                limit = 6,
                similarityThreshold = toolResultRagSimilarityThreshold,
            )
            if (results.isEmpty()) {
                val fallback = toolResultArchiveRepository.retrieveRelevantToolResultsWithScores(
                    conversationId = id.toString(),
                    assistantId = assistant.id.toString(),
                    query = queryText,
                    maxUserTurnIndexExclusive = maxUserTurnIndexExclusive,
                    limit = 6,
                    similarityThreshold = toolResultRagSimilarityThreshold,
                )
                if (fallback.isEmpty()) {
                    Log.w(
                        TAG,
                        "Tool result RAG retrieved nothing (chunks=0, tools=0) beforeTurn<$maxUserTurnIndexExclusive>",
                    )
                    return@run ""
                }
                return@run buildToolResultRagPrompt(fallback, maxChars = 6_000)
            }

            if (settings.enableRagLogging) {
                Log.d(
                    "ToolRAG",
                    "Retrieved ${results.size} chunks (beforeTurn<$maxUserTurnIndexExclusive threshold=$toolResultRagSimilarityThreshold) for query='${queryText.take(120)}'"
                )
                results.forEach { (chunk, score) ->
                    Log.d(
                        "ToolRAG",
                        " - tool=${chunk.toolName} call=${chunk.toolCallId} chunk=${chunk.chunkIndex} score=${
                            String.format(Locale.US, "%.3f", score)
                        } text='${chunk.chunkText.trim().take(120)}'"
                    )
                }
            }

            buildToolResultChunkRagPrompt(results, maxChars = 6_000)
        }

        // New conversations copy defaults from Assistant.enabledModeIds.
        val enabledModes = settings.modes.filter { enabledModeIds.contains(it.id) }

        val usedModes = enabledModes.mapIndexed { index, mode ->
            val reason = if (enabledModeIds.contains(mode.id)) {
                "Activated by user"
            } else {
                "Default enabled"
            }
            UsedMode(
                modeId = mode.id.toString(),
                modeName = mode.name,
                modeIcon = mode.icon,
                priority = enabledModes.size - index,
                activationReason = reason,
            )
        }

        // Collect enabled lorebooks assigned to this assistant
        val lorebooksForAssistant = settings.lorebooks
            .filter { it.enabled && assistant.enabledLorebookIds.contains(it.id) }

        // Check if any lorebook entries use RAG activation
        val hasRagEntries = lorebooksForAssistant.any { lorebook ->
            lorebook.entries.any { it.activationType == LorebookActivationType.RAG && it.enabled }
        }

        // Compute query embedding only if there are RAG entries
        val queryEmbedding: List<Float>? = if (hasRagEntries) {
            try {
                val queryText = recentMessagesForScan.takeLast(3).joinToString("\n")
                if (queryText.isNotBlank()) {
                    embeddingService.embed(text = queryText)
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "Failed to compute query embedding for RAG", e)
                null
            }
        } else null

        data class ActivatedEntryWithLorebook(
            val lorebook: Lorebook,
            val entry: LorebookEntry,
            val entryIndex: Int,
            val reason: String,
        )

        val activatedEntriesWithLorebook = lorebooksForAssistant
            .flatMap { lorebook ->
                lorebook.entries.mapIndexedNotNull { index, entry ->
                    val reason = getLorebookEntryActivationReason(entry, recentMessagesForScan, queryEmbedding)
                    if (reason != null) {
                        ActivatedEntryWithLorebook(lorebook, entry, index, reason)
                    } else {
                        null
                    }
                }
            }
        val activatedEntries = activatedEntriesWithLorebook.map { it.entry }

        val usedLorebookEntries = activatedEntriesWithLorebook.mapIndexed { priority, activated ->
            val coverJson = activated.lorebook.cover?.let { cover ->
                runCatching { json.encodeToString(Avatar.serializer(), cover) }.getOrNull()
            }
            UsedLorebookEntry(
                lorebookId = activated.lorebook.id.toString(),
                lorebookName = activated.lorebook.name,
                lorebookCover = coverJson,
                entryId = activated.entry.id.toString(),
                entryName = activated.entry.name,
                entryIndex = activated.entryIndex,
                priority = activatedEntriesWithLorebook.size - priority,
                activationReason = activated.reason,
            )
        }

        val beforeSystemModes = enabledModes.filter { it.injectionPosition == InjectionPosition.BEFORE_SYSTEM }
        val afterSystemModes = enabledModes.filter { it.injectionPosition == InjectionPosition.AFTER_SYSTEM }
        val beforeSystemEntries = activatedEntries.filter { it.injectionPosition == InjectionPosition.BEFORE_SYSTEM }
        val afterSystemEntries = activatedEntries.filter { it.injectionPosition == InjectionPosition.AFTER_SYSTEM }
        val inChatModeInjections = enabledModes
            .filter {
                it.prompt.isNotBlank() && (
                    it.injectionPosition == InjectionPosition.TOP_OF_CHAT ||
                        it.injectionPosition == InjectionPosition.BEFORE_LATEST ||
                        it.injectionPosition == InjectionPosition.AT_DEPTH
                    )
            }
            .map { mode ->
                InChatPromptInjection(
                    position = mode.injectionPosition,
                    prompt = mode.prompt,
                    depth = mode.depth,
                )
            }
        val inChatEntryInjections = activatedEntries
            .filter {
                it.prompt.isNotBlank() && (
                    it.injectionPosition == InjectionPosition.TOP_OF_CHAT ||
                        it.injectionPosition == InjectionPosition.BEFORE_LATEST ||
                        it.injectionPosition == InjectionPosition.AT_DEPTH
                    )
            }
            .map { entry ->
                InChatPromptInjection(
                    position = entry.injectionPosition,
                    prompt = entry.prompt,
                    depth = entry.depth,
                )
            }
        val inChatInjections = inChatModeInjections + inChatEntryInjections

        // 1. Base System Prompt (BEFORE_SYSTEM modes/entries + System + Learning + AFTER_SYSTEM modes/entries + Tools)
        val baseSystemPromptBuilder = StringBuilder()

        // BEFORE_SYSTEM injections
        beforeSystemModes.forEach { mode ->
            baseSystemPromptBuilder.append(mode.prompt)
            baseSystemPromptBuilder.appendLine()
        }
        beforeSystemEntries.forEach { entry ->
            baseSystemPromptBuilder.append(entry.prompt)
            baseSystemPromptBuilder.appendLine()
        }

        if (assistant.systemPrompt.isNotBlank()) {
            baseSystemPromptBuilder.append(assistant.systemPrompt)
        }
        if (assistant.learningMode) {
            baseSystemPromptBuilder.appendLine()
            baseSystemPromptBuilder.append(settings.learningModePrompt.ifEmpty { DEFAULT_LEARNING_MODE_PROMPT })
            baseSystemPromptBuilder.appendLine()
        }

        // AFTER_SYSTEM injections
        afterSystemModes.forEach { mode ->
            baseSystemPromptBuilder.appendLine()
            baseSystemPromptBuilder.append(mode.prompt)
        }
        afterSystemEntries.forEach { entry ->
            baseSystemPromptBuilder.appendLine()
            baseSystemPromptBuilder.append(entry.prompt)
        }

        tools.forEach { tool ->
            baseSystemPromptBuilder.appendLine()
            baseSystemPromptBuilder.append(
                tool.renderConfiguredSystemPrompt(
                    settings = settings,
                    model = model,
                    messages = documentArchivedMessages,
                )
            )
        }
        val baseSystemPrompt = baseSystemPromptBuilder.toString()
        currentTokens += estimateTokens(baseSystemPrompt)
        if (contextSummarySection.isNotBlank()) {
            currentTokens += estimateTokens(contextSummarySection)
        }
        currentTokens += inChatInjections.sumOf { estimateTokens(it.prompt) }

        // 2. Prepare Candidates
        // Chat History (reverse order to prioritize recent)
        val chatHistoryCandidates = documentArchivedMessages.reversed()
        
        // Memories (Prepare effective memories including recent chats if enabled)
        val shouldInjectMemories = assistant.enableMemory &&
            (!assistant.useRagMemoryRetrieval || assistant.ragLimit > 0 || memories.any { it.pinned })

        val effectiveMemoriesCandidates = if (shouldInjectMemories) {
            val recentChatMemories = if (assistant.enableRecentChatsReference && messages.size <= 2) {
                val today = java.time.LocalDate.now()
                val recentConversations = conversationRepo.getRecentConversations(
                    assistantId = assistant.id,
                    limit = 4,
                ).filter {
                    java.time.LocalDateTime.ofInstant(it.updateAt, java.time.ZoneId.systemDefault()).toLocalDate() == today
                        && it.id != conversationId
                }.take(3)
                recentConversations.map { conversation ->
                    AssistantMemory(
                        id = -1,
                        content = "Participated in conversation: ${conversation.title}",
                        type = 1,
                        timestamp = conversation.updateAt.toEpochMilli()
                    )
                }
            } else {
                emptyList()
            }
            val pinnedFirst = memories.filter { it.pinned } + memories.filterNot { it.pinned }
            (pinnedFirst + recentChatMemories).distinctBy { it.content } // Avoid duplicates
        } else {
            emptyList()
        }

        // 3. Allocation Logic
        val selectedMessages = mutableListOf<UIMessage>()
        val selectedMemories = mutableListOf<AssistantMemory>()
        
        val remainingTokens = maxTokens - currentTokens
        if (remainingTokens <= 0) {
            // Edge case: System prompt too large. Just return minimums.
            Log.w(TAG, "buildMessages: System prompt exceeds max tokens!")
        }

        // Minimums
        val minChatHistory = 2.coerceAtMost(chatHistoryCandidates.size)
        val pinnedCandidates = effectiveMemoriesCandidates.filter { it.pinned }
        val remainingMemoryCandidates = effectiveMemoriesCandidates.filterNot { it.pinned }
        val minMemoriesTotal = if (assistant.enableMemory) 2.coerceAtMost(effectiveMemoriesCandidates.size) else 0
        val minUnpinnedMemories = (minMemoriesTotal - pinnedCandidates.size)
            .coerceAtLeast(0)
            .coerceAtMost(remainingMemoryCandidates.size)

        // Add minimums first
        var usedTokens = 0
        
        // Add min chat history
        chatHistoryCandidates.take(minChatHistory).forEach {
            selectedMessages.add(it)
            usedTokens += estimateTokens(it)
        }
        
        // Add min memories
        pinnedCandidates.forEach {
            selectedMemories.add(it)
            usedTokens += estimateTokens(it.content)
        }
        remainingMemoryCandidates.take(minUnpinnedMemories).forEach {
            selectedMemories.add(it)
            usedTokens += estimateTokens(it.content)
        }

        // Distribute remaining tokens
        var availableTokens = remainingTokens - usedTokens
        if (availableTokens > 0) {
            val remainingChatHistory = chatHistoryCandidates.drop(minChatHistory)
            val remainingMemories = remainingMemoryCandidates.drop(minUnpinnedMemories)
            
            when (assistant.contextPriority) {
                me.rerere.rikkahub.data.model.ContextPriority.CHAT_HISTORY -> {
                    // Prioritize Chat History
                    for (msg in remainingChatHistory) {
                        val cost = estimateTokens(msg)
                        if (availableTokens >= cost) {
                            selectedMessages.add(msg)
                            availableTokens -= cost
                        } else break
                    }
                    for (mem in remainingMemories) {
                        val cost = estimateTokens(mem.content)
                        if (availableTokens >= cost) {
                            selectedMemories.add(mem)
                            availableTokens -= cost
                        }
                    }
                }
                me.rerere.rikkahub.data.model.ContextPriority.MEMORIES -> {
                    // Prioritize Memories
                    for (mem in remainingMemories) {
                        val cost = estimateTokens(mem.content)
                        if (availableTokens >= cost) {
                            selectedMemories.add(mem)
                            availableTokens -= cost
                        }
                    }
                    for (msg in remainingChatHistory) {
                        val cost = estimateTokens(msg)
                        if (availableTokens >= cost) {
                            selectedMessages.add(msg)
                            availableTokens -= cost
                        } else break
                    }
                }
                me.rerere.rikkahub.data.model.ContextPriority.BALANCED -> {
                    // Balanced (e.g. 50/50 split of remaining, or round-robin)
                    // Simple round-robin approach
                    var msgIndex = 0
                    var memIndex = 0
                    var addedSomething = true
                    while (addedSomething && availableTokens > 0) {
                        addedSomething = false
                        // Try add message
                        if (msgIndex < remainingChatHistory.size) {
                            val msg = remainingChatHistory[msgIndex]
                            val cost = estimateTokens(msg)
                            if (availableTokens >= cost) {
                                selectedMessages.add(msg)
                                availableTokens -= cost
                                msgIndex++
                                addedSomething = true
                            }
                        }
                        // Try add memory
                        if (memIndex < remainingMemories.size) {
                            val mem = remainingMemories[memIndex]
                            val cost = estimateTokens(mem.content)
                            if (availableTokens >= cost) {
                                selectedMemories.add(mem)
                                availableTokens -= cost
                                memIndex++
                                addedSomething = true
                            }
                        }
                    }
                }
            }
        }

        // 4. Construct Final List
        // Collect all attachments from enabled modes
        val modeAttachmentParts = enabledModes.flatMap { mode ->
            mode.attachments.map { attachment ->
                when (attachment.type) {
                    ModeAttachmentType.IMAGE -> UIMessagePart.Image(url = attachment.url)
                    ModeAttachmentType.VIDEO -> UIMessagePart.Video(url = attachment.url)
                    ModeAttachmentType.AUDIO -> UIMessagePart.Audio(url = attachment.url)
                    ModeAttachmentType.DOCUMENT -> UIMessagePart.Document(
                        url = attachment.url,
                        fileName = attachment.fileName,
                        mime = attachment.mime,
                    )
                }
            }
        }

        // Collect attachments from activated lorebook entries
        val lorebookAttachmentParts = activatedEntries.flatMap { entry ->
            entry.attachments.map { attachment ->
                when (attachment.type) {
                    ModeAttachmentType.IMAGE -> UIMessagePart.Image(url = attachment.url)
                    ModeAttachmentType.VIDEO -> UIMessagePart.Video(url = attachment.url)
                    ModeAttachmentType.AUDIO -> UIMessagePart.Audio(url = attachment.url)
                    ModeAttachmentType.DOCUMENT -> UIMessagePart.Document(
                        url = attachment.url,
                        fileName = attachment.fileName,
                        mime = attachment.mime,
                    )
                }
            }
        }

        // Combine all context attachments
        val allContextAttachments = modeAttachmentParts + lorebookAttachmentParts
        val selectedMessagesByHistoryOrder = selectedMessages.sortedBy { messages.indexOf(it) }
        val selectedMessagesWithInjections = applyInChatPromptInjections(
            baseMessages = selectedMessagesByHistoryOrder,
            injections = inChatInjections,
        )

        val builtMessages = buildList {
            val finalSystemPrompt = buildString {
                append(baseSystemPrompt)
                if (contextSummarySection.isNotBlank()) {
                    appendLine()
                    append(contextSummarySection)
                }
                val includeMemoryToolInstructions = tools.any { it.name in MEMORY_TOOL_NAMES }
                val includeSessionMemoryToolInstructions = tools.any { it.name in SESSION_MEMORY_TOOL_NAMES }
                val sessionMemoryPrompt = buildSessionMemoryPrompt(
                    settings = settings,
                    model = model,
                    memories = sessionMemories,
                    includeToolInstructions = includeSessionMemoryToolInstructions,
                )
                if (sessionMemoryPrompt.isNotBlank()) {
                    appendLine()
                    append(sessionMemoryPrompt)
                }
                val memoryPrompt = buildMemoryPrompt(
                    settings = settings,
                    model = model,
                    memories = selectedMemories,
                    includeToolInstructions = includeMemoryToolInstructions,
                )
                if (memoryPrompt.isNotBlank()) {
                    appendLine()
                    append(memoryPrompt)
                }
                if (toolResultRagPrompt.isNotBlank()) {
                    appendLine()
                    append(toolResultRagPrompt)
                }
            }
            if (finalSystemPrompt.isNotBlank()) {
                add(UIMessage.system(finalSystemPrompt))
            }

            // Add mode and lorebook attachments as a user message if there are any
            if (allContextAttachments.isNotEmpty()) {
                add(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = allContextAttachments,
                    )
                )
            }

            // Restore chat history order
            addAll(selectedMessagesWithInjections)
        }

        val usedMemories = selectedMemories.mapIndexed { index, memory ->
            val reason = when {
                memory.pinned -> "Pinned"
                memory.id == -1 -> "Recent episode boost"
                assistant.useRagMemoryRetrieval -> "Contextually relevant"
                else -> "Always included"
            }
            UsedMemory(
                memoryId = memory.id,
                memoryContent = memory.content.take(50) + if (memory.content.length > 50) "..." else "",
                memoryType = memory.type,
                priority = selectedMemories.size - index,
                activationReason = reason,
            )
        }
        val usedSessionMemories = sessionMemories.mapIndexed { index, memory ->
            UsedSessionMemory(
                memoryId = memory.id,
                memoryContent = memory.content.take(50) + if (memory.content.length > 50) "..." else "",
                priority = sessionMemories.size - index,
                activationReason = "Active in this conversation",
            )
        }

        return BuildMessagesResult(
            messages = builtMessages,
            usedLorebookEntries = usedLorebookEntries,
            usedModes = usedModes,
            usedMemories = usedMemories,
            usedSessionMemories = usedSessionMemories,
        )
    }

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        conversationId: Uuid?,
        onUpdateMessages: suspend (List<UIMessage>, Set<String>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        sessionMemories: List<SessionMemory>,
        truncateIndex: Int,
        stream: Boolean,
        enabledModeIds: Set<Uuid> = emptySet(),
        source: AIRequestSource,
    ) {
        val buildResult = buildMessages(
            assistant = assistant,
            settings = settings,
            messages = messages,
            conversationId = conversationId,
            model = model,
            tools = tools,
            memories = memories,
            sessionMemories = sessionMemories,
            truncateIndex = truncateIndex,
            enabledModeIds = enabledModeIds,
        )
        val internalMessages = buildResult.messages.transforms(transformers, context, model, assistant)
        val usedLorebookEntries = buildResult.usedLorebookEntries
        val usedModes = buildResult.usedModes
        val usedMemories = buildResult.usedMemories
        val usedSessionMemories = buildResult.usedSessionMemories
        val hasContextSources = usedLorebookEntries.isNotEmpty() ||
            usedModes.isNotEmpty() ||
            usedMemories.isNotEmpty() ||
            usedSessionMemories.isNotEmpty()

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            thinkingBudget = assistant.thinkingBudget,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }
        )
        if (stream) {
            aiLoggingManager.addLog(AILogging.Generation(
                params = params,
                messages = messages,
                providerSetting = provider,
                stream = true
            ))
            val startAt = System.currentTimeMillis()
            var firstChunkAt: Long? = null
            var failure: Throwable? = null
            val rawResponseText = StringBuilder()
            val maxHttpRetries = settings.getHttpRetryMaxRetries()
            val httpRetryDelayMs = computeHttpRetryDelayMs(settings.getHttpRetryDelaySeconds())
            var streamAttempt = 0
            try {
                while (true) {
                    streamAttempt += 1
                    var emittedAnyChunk = false
                    try {
                        providerImpl.streamText(
                            providerSetting = provider,
                            messages = internalMessages,
                            params = params
                        ).collect { chunk ->
                            emittedAnyChunk = true
                            if (firstChunkAt == null) firstChunkAt = System.currentTimeMillis()
                            chunk.rawResponse
                                ?.takeIf { it.isNotBlank() }
                                ?.let {
                                    if (rawResponseText.isNotEmpty()) rawResponseText.append("\n")
                                    rawResponseText.append(it)
                                }
                            messages = messages.handleMessageChunk(chunk = chunk, model = model)
                            chunk.usage?.let { usage ->
                                messages = messages.mapIndexed { index, message ->
                                    if (index == messages.lastIndex) {
                                        message.copy(usage = message.usage.merge(usage))
                                    } else {
                                        message
                                    }
                                }
                            }
                            val finishReasons = when {
                                chunk.finishReasons.isNotEmpty() -> chunk.finishReasons
                                else -> chunk.choices
                                    .mapNotNull { choice -> choice.finishReason?.trim() }
                                    .filter { reason -> reason.isNotBlank() && reason != "unknown" }
                                    .toSet()
                            }
                            onUpdateMessages(messages, finishReasons)
                        }
                        break
                    } catch (t: Throwable) {
                        if (!shouldRetryHttpRequest(t, attempt = streamAttempt, maxRetries = maxHttpRetries, emittedAnyChunk = emittedAnyChunk)) {
                            throw t
                        }

                        Log.w(
                            TAG,
                            "generateInternal(stream): got retryable HTTP/network error, retry ${streamAttempt}/$maxHttpRetries in ${httpRetryDelayMs}ms",
                            t,
                        )
                        delay(httpRetryDelayMs)
                    }
                }

                if (hasContextSources) {
                    messages = messages.mapIndexed { index, message ->
                        if (index == messages.lastIndex && message.role == MessageRole.ASSISTANT) {
                            message.copy(
                                usedLorebookEntries = usedLorebookEntries.ifEmpty { null },
                                usedModes = usedModes.ifEmpty { null },
                                usedMemories = usedMemories.ifEmpty { null },
                                usedSessionMemories = usedSessionMemories.ifEmpty { null },
                            )
                        } else {
                            message
                        }
                    }
                    onUpdateMessages(messages, emptySet())
                }
            } catch (t: Throwable) {
                failure = t
                throw t
            } finally {
                requestLogManager.logTextGeneration(
                    source = source,
                    providerSetting = provider,
                    params = params,
                    requestMessages = internalMessages,
                    responseText = messages.lastOrNull()?.toContentText().orEmpty(),
                    responseRawText = rawResponseText.toString(),
                    stream = true,
                    latencyMs = firstChunkAt?.let { it - startAt },
                    durationMs = (System.currentTimeMillis() - startAt),
                    error = failure,
                )
            }
        } else {
            aiLoggingManager.addLog(AILogging.Generation(
                params = params,
                messages = messages,
                providerSetting = provider,
                stream = false
            ))
            val startAt = System.currentTimeMillis()
            var failure: Throwable? = null
            var rawResponseText = ""
            val maxHttpRetries = settings.getHttpRetryMaxRetries()
            val httpRetryDelayMs = computeHttpRetryDelayMs(settings.getHttpRetryDelaySeconds())
            var nonStreamAttempt = 0
            try {
                while (true) {
                    nonStreamAttempt += 1
                    try {
                        val chunk = providerImpl.generateText(
                            providerSetting = provider,
                            messages = internalMessages,
                            params = params,
                        )
                        rawResponseText = chunk.rawResponse.orEmpty()
                        messages = messages.handleMessageChunk(chunk = chunk, model = model)
                        chunk.usage?.let { usage ->
                            messages = messages.mapIndexed { index, message ->
                                if (index == messages.lastIndex) {
                                    message.copy(
                                        usage = message.usage.merge(usage)
                                    )
                                } else {
                                    message
                                }
                            }
                        }

                        if (hasContextSources) {
                            messages = messages.mapIndexed { index, message ->
                                if (index == messages.lastIndex && message.role == MessageRole.ASSISTANT) {
                                    message.copy(
                                        usedLorebookEntries = usedLorebookEntries.ifEmpty { null },
                                        usedModes = usedModes.ifEmpty { null },
                                        usedMemories = usedMemories.ifEmpty { null },
                                        usedSessionMemories = usedSessionMemories.ifEmpty { null },
                                    )
                                } else {
                                    message
                                }
                            }
                        }
                        val finishReasons = when {
                            chunk.finishReasons.isNotEmpty() -> chunk.finishReasons
                            else -> chunk.choices
                                .mapNotNull { choice -> choice.finishReason?.trim() }
                                .filter { reason -> reason.isNotBlank() && reason != "unknown" }
                                .toSet()
                        }
                        onUpdateMessages(messages, finishReasons)
                        break
                    } catch (t: Throwable) {
                        if (!shouldRetryHttpRequest(t, attempt = nonStreamAttempt, maxRetries = maxHttpRetries)) {
                            throw t
                        }

                        Log.w(
                            TAG,
                            "generateInternal(non-stream): got retryable HTTP/network error, retry ${nonStreamAttempt}/$maxHttpRetries in ${httpRetryDelayMs}ms",
                            t,
                        )
                        delay(httpRetryDelayMs)
                    }
                }
            } catch (t: Throwable) {
                failure = t
                throw t
            } finally {
                requestLogManager.logTextGeneration(
                    source = source,
                    providerSetting = provider,
                    params = params,
                    requestMessages = internalMessages,
                    responseText = messages.lastOrNull()?.toContentText().orEmpty(),
                    responseRawText = rawResponseText,
                    stream = false,
                    latencyMs = (System.currentTimeMillis() - startAt),
                    durationMs = (System.currentTimeMillis() - startAt),
                    error = failure,
                )
            }
        }
    }

    private fun buildMemoryTools(
        onCreation: suspend (String) -> AssistantMemory,
        onUpdate: suspend (Int, String) -> AssistantMemory,
        onDelete: suspend (Int) -> Unit
    ) = listOf(
        Tool(
            name = "create_memory",
            description = "Create a new memory record.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "Content of the memory.")
                        })
                    },
                    required = listOf("content")
                )
            },
            execute = {
                val params = it.jsonObject
                val content =
                    params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                json.encodeToJsonElement(AssistantMemory.serializer(), onCreation(content))
            }
        ),
        Tool(
            name = "edit_memory",
            description = "Update an existing memory record.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "integer")
                            put("description", "ID of the memory to update.")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "New content for the memory.")
                        })
                    },
                    required = listOf("id", "content"),
                )
            },
            execute = {
                val params = it.jsonObject
                val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                val content =
                    params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                json.encodeToJsonElement(
                    AssistantMemory.serializer(), onUpdate(id, content)
                )
            }
        ),
        Tool(
            name = "delete_memory",
            description = "Delete a memory record.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "integer")
                            put("description", "ID of the memory to delete.")
                        })
                    },
                    required = listOf("id")
                )
            },
            execute = {
                val params = it.jsonObject
                val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                onDelete(id)
                JsonPrimitive(true)
            }
        )
    )

    private fun buildSessionMemoryTools(
        getMemories: () -> List<SessionMemory>,
        onChange: suspend (List<SessionMemory>) -> Unit,
    ) = listOf(
        Tool(
            name = "create_session_memory",
            description = "Create a memory that stays active only in the current conversation.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "Important detail to keep active in the current conversation.")
                        })
                    },
                    required = listOf("content"),
                )
            },
            execute = { args ->
                val content = args.jsonObject["content"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: error("content is required")
                validateSessionMemoryContent(content)
                val current = getMemories()
                val existing = current.firstOrNull { it.content.equals(content, ignoreCase = true) }
                if (existing != null) {
                    json.encodeToJsonElement(SessionMemory.serializer(), existing)
                } else {
                    if (current.size >= SESSION_MEMORY_MAX_COUNT) {
                        error("session memory limit reached; edit an existing memory instead")
                    }
                    val now = System.currentTimeMillis()
                    val created = SessionMemory(
                        id = nextSessionMemoryId(current),
                        content = content,
                        createdAt = now,
                        updatedAt = now,
                    )
                    onChange(current + created)
                    json.encodeToJsonElement(SessionMemory.serializer(), created)
                }
            },
        ),
        Tool(
            name = "edit_session_memory",
            description = "Update an existing memory that applies only to the current conversation.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "integer")
                            put("description", "ID of the session memory to update.")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "New content for the session memory.")
                        })
                    },
                    required = listOf("id", "content"),
                )
            },
            execute = { args ->
                val params = args.jsonObject
                val id = params["id"]?.jsonPrimitive?.intOrNull
                    ?: error("id is required")
                val content = params["content"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: error("content is required")
                validateSessionMemoryContent(content)
                val current = getMemories()
                val existing = current.firstOrNull { it.id == id }
                    ?: error("session memory not found")
                val updated = existing.copy(
                    content = content,
                    updatedAt = System.currentTimeMillis(),
                )
                onChange(current.map { memory -> if (memory.id == id) updated else memory })
                json.encodeToJsonElement(SessionMemory.serializer(), updated)
            },
        ),
        Tool(
            name = "delete_session_memory",
            description = "Delete a memory that no longer applies to the current conversation.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "integer")
                            put("description", "ID of the session memory to delete.")
                        })
                    },
                    required = listOf("id"),
                )
            },
            execute = { args ->
                val id = args.jsonObject["id"]?.jsonPrimitive?.intOrNull
                    ?: error("id is required")
                val current = getMemories()
                val updated = current.filterNot { it.id == id }
                if (updated.size == current.size) {
                    error("session memory not found")
                }
                onChange(updated)
                JsonPrimitive(true)
            },
        ),
    )

    private fun nextSessionMemoryId(memories: List<SessionMemory>): Int {
        return (memories.maxOfOrNull { it.id } ?: 0) + 1
    }

    private fun validateSessionMemoryContent(content: String) {
        if (content.isBlank()) {
            error("content must not be empty")
        }
        if (content.length > SESSION_MEMORY_MAX_CONTENT_CHARS) {
            error("content is too long; keep it under $SESSION_MEMORY_MAX_CONTENT_CHARS characters")
        }
    }

    private fun buildToolResultRagPrompt(
        results: List<Pair<ToolResultArchiveEntity, Float>>,
        maxChars: Int,
    ): String {
        if (results.isEmpty()) return ""
        val hardLimit = maxChars.coerceAtLeast(0)
        if (hardLimit == 0) return ""

        val builder = StringBuilder()
        builder.appendLine("## Tool Results (RAG)")
        builder.appendLine("Retrieved from archived tool calls in this conversation.")

        val perItemSoftLimit = 1600
        results.forEach { (entity, score) ->
            if (builder.length >= hardLimit) return@forEach

            val header = "- tool=${entity.toolName} tool_call_id=${entity.toolCallId} score=${
                String.format(Locale.US, "%.3f", score)
            }"
            val headerLine = header.take((hardLimit - builder.length).coerceAtLeast(0))
            if (headerLine.isBlank()) return@forEach
            builder.appendLine(headerLine)

            val snippet = entity.extractText.trim().take(perItemSoftLimit)
            if (snippet.isNotBlank() && builder.length < hardLimit) {
                val indented = snippet.prependIndent("  ")
                val remaining = (hardLimit - builder.length).coerceAtLeast(0)
                if (remaining > 0) {
                    builder.appendLine(indented.take(remaining))
                }
            }
        }

        return builder.toString().trim().take(hardLimit)
    }

    private fun buildToolResultChunkRagPrompt(
        results: List<Pair<ToolResultArchiveChunkEntity, Float>>,
        maxChars: Int,
    ): String {
        if (results.isEmpty()) return ""
        val hardLimit = maxChars.coerceAtLeast(0)
        if (hardLimit == 0) return ""

        val builder = StringBuilder()
        builder.appendLine("## Tool Results (RAG)")
        builder.appendLine("Retrieved from archived tool call chunks in this conversation.")

        val perItemSoftLimit = 1200
        results.forEach { (chunk, score) ->
            if (builder.length >= hardLimit) return@forEach

            val header = "- tool=${chunk.toolName} tool_call_id=${chunk.toolCallId} chunk=${chunk.chunkIndex} score=${
                String.format(Locale.US, "%.3f", score)
            }"
            val headerLine = header.take((hardLimit - builder.length).coerceAtLeast(0))
            if (headerLine.isBlank()) return@forEach
            builder.appendLine(headerLine)

            val snippet = chunk.chunkText.trim().take(perItemSoftLimit)
            if (snippet.isNotBlank() && builder.length < hardLimit) {
                val indented = snippet.prependIndent("  ")
                val remaining = (hardLimit - builder.length).coerceAtLeast(0)
                if (remaining > 0) {
                    builder.appendLine(indented.take(remaining))
                }
            }
        }

        return builder.toString().trim().take(hardLimit)
    }

    private fun buildSessionMemoryPrompt(
        settings: Settings,
        model: Model,
        memories: List<SessionMemory>,
        includeToolInstructions: Boolean,
    ): String {
        val shouldIncludeToolInstructions =
            includeToolInstructions && model.abilities.contains(ModelAbility.TOOL)
        if (memories.isEmpty() && !shouldIncludeToolInstructions) {
            return ""
        }

        val sessionMemoryContext = buildSessionMemoryContext(memories)
        if (!shouldIncludeToolInstructions) {
            return buildString {
                append("## Session Memories\n")
                append(sessionMemoryContext)
            }
        }

        return renderConfiguredToolSystemPrompt(
            settings = settings,
            key = SESSION_MEMORY_MANAGEMENT_TOOL_NAME,
            defaultTemplate = SESSION_MEMORY_MANAGEMENT_SYSTEM_PROMPT_TEMPLATE,
            variables = mapOf(SESSION_MEMORY_CONTEXT_VARIABLE to sessionMemoryContext),
        )
    }

    private fun buildSessionMemoryContext(memories: List<SessionMemory>): String {
        return buildString {
            append("Session memories apply only to the current conversation and stay active in future turns of this conversation.\n")
            if (memories.isNotEmpty()) {
                memories.forEach { memory ->
                    append("- [ID: ${memory.id}] ${memory.content}\n")
                }
            } else {
                append("No session memories have been saved yet.\n")
            }
        }
            .trimEnd()
    }

    private suspend fun buildMemoryPrompt(
        settings: Settings,
        model: Model,
        memories: List<AssistantMemory>,
        includeToolInstructions: Boolean,
    ): String {
        val shouldIncludeToolInstructions =
            includeToolInstructions && model.abilities.contains(ModelAbility.TOOL)
        if (memories.isEmpty() && !shouldIncludeToolInstructions) {
            return ""
        }

        Log.d(
            TAG,
            "buildMemoryPrompt: memories=${memories.size}, includeToolInstructions=$shouldIncludeToolInstructions",
        )

        val memoryContext = buildMemoryContext(memories)
        if (!shouldIncludeToolInstructions) {
            return buildString {
                append("## Memories\n")
                append(memoryContext)
            }
        }

        return renderConfiguredToolSystemPrompt(
            settings = settings,
            key = MEMORY_MANAGEMENT_TOOL_NAME,
            defaultTemplate = MEMORY_MANAGEMENT_SYSTEM_PROMPT_TEMPLATE,
            variables = mapOf(MEMORY_CONTEXT_VARIABLE to memoryContext),
        )
    }

    private fun buildMemoryContext(memories: List<AssistantMemory>): String {
        val coreMemories = memories.filter { it.type == 0 } // CORE
        val episodicMemories = memories.filter { it.type == 1 } // EPISODIC

        return buildString {
            if (memories.isNotEmpty()) {
                append("These are memories that you can reference in the future conversations.\n")
            } else {
                append("No memories were injected for this turn (none exist, none matched, or embeddings are unavailable).\n")
            }

            if (coreMemories.isNotEmpty()) {
                append("### Core Memories\n")
                coreMemories.forEach { memory ->
                    append("- [ID: ${memory.id}] ${memory.content}\n")
                }
            }

            if (episodicMemories.isNotEmpty()) {
                append("### Episodic Memories\n")

                val now = java.time.LocalDate.now()
                val yesterday = now.minusDays(1)
                val lastWeek = now.minusWeeks(1)

                val groupedEpisodes = episodicMemories.groupBy { memory ->
                    val date = java.time.Instant.ofEpochMilli(memory.timestamp)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()

                    when {
                        date.isEqual(now) -> "Today"
                        date.isEqual(yesterday) -> "Yesterday"
                        date.isAfter(lastWeek) -> "This Week"
                        else -> "Older"
                    }
                }

                // Order: Today -> Yesterday -> This Week -> Older
                listOf("Today", "Yesterday", "This Week", "Older").forEach { group ->
                    val memoriesInGroup = groupedEpisodes[group]
                    if (!memoriesInGroup.isNullOrEmpty()) {
                        append("#### $group\n")
                        memoriesInGroup.sortedByDescending { it.timestamp }.forEach { memory ->
                            append("- ${memory.content}\n")
                        }
                    }
                }
            }
        }
            .trimEnd()
    }

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""

            val params = TextGenerationParams(
                model = model,
                temperature = 0.3f,
            )
            val requestMessages = messages
            val startAt = System.currentTimeMillis()
            var firstChunkAt: Long? = null
            var failure: Throwable? = null
            val rawResponseText = StringBuilder()
            val maxHttpRetries = settings.getHttpRetryMaxRetries()
            val httpRetryDelayMs = computeHttpRetryDelayMs(settings.getHttpRetryDelaySeconds())
            var streamAttempt = 0
            try {
                while (true) {
                    streamAttempt += 1
                    var emittedAnyChunk = false
                    try {
                        providerHandler.streamText(
                            providerSetting = provider,
                            messages = messages,
                            params = params,
                        ).collect { chunk ->
                            emittedAnyChunk = true
                            if (firstChunkAt == null) firstChunkAt = System.currentTimeMillis()
                            chunk.rawResponse
                                ?.takeIf { it.isNotBlank() }
                                ?.let {
                                    if (rawResponseText.isNotEmpty()) rawResponseText.append("\n")
                                    rawResponseText.append(it)
                                }
                            messages = messages.handleMessageChunk(chunk)
                            translatedText = messages.lastOrNull()?.toContentText() ?: ""

                            if (translatedText.isNotBlank()) {
                                onStreamUpdate?.invoke(translatedText)
                                emit(translatedText)
                            }
                        }
                        break
                    } catch (t: Throwable) {
                        if (!shouldRetryHttpRequest(t, attempt = streamAttempt, maxRetries = maxHttpRetries, emittedAnyChunk = emittedAnyChunk)) {
                            throw t
                        }

                        Log.w(
                            TAG,
                            "translateText(stream): got retryable HTTP/network error, retry ${streamAttempt}/$maxHttpRetries in ${httpRetryDelayMs}ms",
                            t,
                        )
                        delay(httpRetryDelayMs)
                    }
                }
            } catch (t: Throwable) {
                failure = t
                throw t
            } finally {
                requestLogManager.logTextGeneration(
                    source = AIRequestSource.TRANSLATION,
                    providerSetting = provider,
                    params = params,
                    requestMessages = requestMessages,
                    responseText = translatedText,
                    responseRawText = rawResponseText.toString(),
                    stream = true,
                    latencyMs = firstChunkAt?.let { it - startAt },
                    durationMs = (System.currentTimeMillis() - startAt),
                    error = failure,
                )
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val params = TextGenerationParams(
                model = model,
                temperature = 0.3f,
                topP = 0.95f,
                customBody = listOf(
                    CustomBody(
                        key = "translation_options",
                        value = buildJsonObject {
                            put("source_lang", JsonPrimitive("auto"))
                            put(
                                "target_lang",
                                JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                            )
                        }
                    )
                )
            )
            val startAt = System.currentTimeMillis()
            var failure: Throwable? = null
            var translatedText = ""
            var rawResponseText = ""
            val maxHttpRetries = settings.getHttpRetryMaxRetries()
            val httpRetryDelayMs = computeHttpRetryDelayMs(settings.getHttpRetryDelaySeconds())
            var nonStreamAttempt = 0
            try {
                while (true) {
                    nonStreamAttempt += 1
                    try {
                        val response = providerHandler.generateText(
                            providerSetting = provider,
                            messages = messages,
                            params = params,
                        )
                        rawResponseText = response.rawResponse.orEmpty()
                        translatedText = response.choices.firstOrNull()?.message?.toContentText() ?: ""
                        break
                    } catch (t: Throwable) {
                        if (!shouldRetryHttpRequest(t, attempt = nonStreamAttempt, maxRetries = maxHttpRetries)) {
                            throw t
                        }

                        Log.w(
                            TAG,
                            "translateText(non-stream): got retryable HTTP/network error, retry ${nonStreamAttempt}/$maxHttpRetries in ${httpRetryDelayMs}ms",
                            t,
                        )
                        delay(httpRetryDelayMs)
                    }
                }
            } catch (t: Throwable) {
                failure = t
                throw t
            } finally {
                requestLogManager.logTextGeneration(
                    source = AIRequestSource.TRANSLATION,
                    providerSetting = provider,
                    params = params,
                    requestMessages = messages,
                    responseText = translatedText,
                    responseRawText = rawResponseText,
                    stream = false,
                    latencyMs = (System.currentTimeMillis() - startAt),
                    durationMs = (System.currentTimeMillis() - startAt),
                    error = failure,
                )
            }

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 将超出阈值的旧消息中的图片替换为 OCR 文字，以节省上下文 Token。
     * 只处理本地 file:// 图片；最近 [Assistant.archiveImagesAfterMessageAge] 条消息中的图片保留原样。
     */
    private suspend fun archiveOldImageMessages(
        messages: List<UIMessage>,
        assistant: Assistant,
    ): List<UIMessage> {
        val threshold = assistant.archiveImagesAfterMessageAge?.takeIf { it > 0 } ?: return messages
        val archiveBeforeIndex = (messages.size - threshold).coerceAtLeast(0)
        if (archiveBeforeIndex <= 0) return messages

        return withContext(Dispatchers.IO) {
            messages.mapIndexed { index, message ->
                if (index >= archiveBeforeIndex) return@mapIndexed message  // 最近消息，保留原图
                var changed = false
                val updatedParts = buildList {
                    message.parts.forEach { part ->
                        if (part is UIMessagePart.Image && part.url.startsWith("file:")) {
                            val ocrText = OcrTransformer.performOcr(part)
                            if (ocrText.isNotBlank()) {
                                changed = true
                                add(UIMessagePart.Text(ocrText))
                            } else {
                                add(part)
                            }
                        } else {
                            add(part)
                        }
                    }
                }
                if (changed) message.copy(parts = updatedParts) else message
            }
        }
    }

    /**
     * 对即将进入归档区间（阈值前 1-2 轮）的文件，后台发起 AI 摘要（fire-and-forget）。
     * 摘要结果写入缓存，供 [archiveOldDocumentMessages] 直接读取。
     */
    private fun preSummarizeDocuments(
        messages: List<UIMessage>,
        assistant: Assistant,
        settings: Settings,
    ) {
        val threshold = assistant.archiveDocumentsAfterMessageAge?.takeIf { it > 0 } ?: return
        val archiveBeforeIndex = (messages.size - threshold).coerceAtLeast(0)
        val prewarmEnd = (archiveBeforeIndex + 2).coerceAtMost(messages.size)
        for (i in archiveBeforeIndex until prewarmEnd) {
            messages[i].parts.filterIsInstance<UIMessagePart.Document>().forEach { doc ->
                DocumentSummaryTransformer.prewarm(doc, settings, assistant)
            }
        }
    }

    /**
     * 将超出阈值的旧消息中的文件替换为 AI 摘要，以节省上下文 Token。
     * 只从缓存读取摘要；缓存未命中则保留原文，并后台补发摘要预热。
     */
    private fun archiveOldDocumentMessages(
        messages: List<UIMessage>,
        assistant: Assistant,
        settings: Settings,
    ): List<UIMessage> {
        val threshold = assistant.archiveDocumentsAfterMessageAge?.takeIf { it > 0 } ?: return messages
        val archiveBeforeIndex = (messages.size - threshold).coerceAtLeast(0)
        if (archiveBeforeIndex <= 0) return messages

        return messages.mapIndexed { index, message ->
            if (index >= archiveBeforeIndex) return@mapIndexed message
            var changed = false
            val updatedParts = buildList {
                message.parts.forEach { part ->
                    if (part is UIMessagePart.Document) {
                        val cached = DocumentSummaryTransformer.getCached(part.url)
                        if (cached != null) {
                            changed = true
                            add(UIMessagePart.Text(cached))
                        } else {
                            DocumentSummaryTransformer.prewarm(part, settings, assistant)
                            add(part)
                        }
                    } else {
                        add(part)
                    }
                }
            }
            if (changed) message.copy(parts = updatedParts) else message
        }
    }
}
