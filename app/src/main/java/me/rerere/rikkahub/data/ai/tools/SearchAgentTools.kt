package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.withoutBuiltInSearchTools
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.rikkahub.data.ai.AIRequestLogManager
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.AssistantSearchMode
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import java.net.URI
import java.time.LocalDate
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

private const val SEARCH_AGENT_TOOL_NAME = "search_agent"
private const val SEARCH_AGENT_MAX_STEPS = 16
private const val SEARCH_AGENT_IDLE_TIMEOUT_MS = 60_000L
private const val SEARCH_AGENT_STEP_LIMIT_CODE = "search_agent_step_limit_reached"
private const val FALLBACK_SOURCE_LIMIT = 12

private val MARKDOWN_URL_REGEX = Regex("""\[([^\]\n]{1,240})]\((https?://[^\s)]+)\)""")
private val RAW_URL_REGEX = Regex("""https?://[^\s<>"'`]+""")
private val MARKDOWN_LINK_FLAVOUR by lazy {
    GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)
}
private val MARKDOWN_LINK_PARSER by lazy {
    MarkdownParser(MARKDOWN_LINK_FLAVOUR)
}

object SearchAgentTools {
    fun create(
        settings: Settings,
        searchMode: AssistantSearchMode,
        providerManager: ProviderManager,
        requestLogManager: AIRequestLogManager,
        json: Json,
        progressStore: SearchAgentProgressStore,
    ): Tool? {
        val agentModel = settings.searchAgentModelId
            ?.let(settings::findModelById)
            ?.takeIf { model -> model.abilities.contains(ModelAbility.TOOL) }
            ?: return null
        agentModel.findProvider(settings.providers) ?: return null
        val runtimeAgentModel = agentModel.withoutBuiltInSearchTools()

        return Tool(
            name = SEARCH_AGENT_TOOL_NAME,
            description = "delegate web search and page reading to a search agent",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("task", buildJsonObject {
                            put("type", "string")
                            put("description", "Search or page-reading task. Include only the necessary context.")
                        })
                        put("urls", buildJsonObject {
                            put("type", "array")
                            put("description", "Optional URLs to read.")
                            put("items", buildJsonObject {
                                put("type", "string")
                            })
                        })
                    },
                    required = listOf("task"),
                )
            },
            systemPrompt = { _, _ -> SEARCH_AGENT_MAIN_TOOL_PROMPT_TEMPLATE },
            execute = { args ->
                val toolCallId = coroutineContext[me.rerere.ai.core.ToolCallContext]?.toolCallId
                    ?: error("ToolCallContext missing for search_agent")
                val params = args.jsonObject
                val task = params["task"]?.jsonPrimitiveOrNull?.contentOrNull?.trim().orEmpty()
                val urls = params["urls"]?.let { element ->
                    (element as? JsonArray)?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull?.trim() }
                }.orEmpty().filter { it.isNotBlank() }
                require(task.isNotBlank()) { "task is required" }

                val runner = SearchAgentRunner(
                    settings = settings,
                    searchMode = searchMode,
                    model = runtimeAgentModel,
                    providerManager = providerManager,
                    requestLogManager = requestLogManager,
                    json = json,
                    toolCallId = toolCallId,
                    progressStore = progressStore,
                )

                runner.run(task = task, urls = urls)
            },
        )
    }
}

private class SearchAgentRunner(
    private val settings: Settings,
    private val searchMode: AssistantSearchMode,
    private val model: Model,
    private val providerManager: ProviderManager,
    private val requestLogManager: AIRequestLogManager,
    private val json: Json,
    private val toolCallId: String,
    private val progressStore: SearchAgentProgressStore,
) {
    private val stepList = mutableListOf<SearchAgentStep>()
    private val observedSourcesById = linkedMapOf<String, JsonObject>()

    /** 把当前 stepList 同步推到进度仓库。 */
    private fun publishProgress(finished: Boolean = false) {
        val task = (stepList.firstOrNull() as? SearchAgentStep.TaskStep)?.text
        progressStore.update(toolCallId) { progress ->
            progress?.copy(steps = stepList.toList(), finished = finished)
                ?: SearchAgentProgress(task = task, steps = stepList.toList(), finished = finished)
        }
    }

    /** 追加并发布一条步骤。 */
    private fun appendStep(step: SearchAgentStep) {
        stepList += step
        publishProgress()
    }

    /** 把最后一条同地替换为新的步骤（用于 Running -> Done）。 */
    private fun replaceLastStep(transform: (SearchAgentStep) -> SearchAgentStep) {
        val last = stepList.lastOrNull() ?: return
        stepList[stepList.lastIndex] = transform(last)
        publishProgress()
    }

    suspend fun run(task: String, urls: List<String>): JsonObject {
        appendStep(SearchAgentStep.TaskStep(text = buildTaskDetail(task = task, urls = urls)))
        return runCatching {
            runInternal(task = task, urls = urls)
        }.getOrElse { throwable ->
            val message = when (throwable) {
                is kotlinx.coroutines.TimeoutCancellationException -> "搜索子代理 60 秒内没有新进展，请稍后重试。"
                else -> "搜索子代理失败：${throwable.message ?: throwable::class.simpleName.orEmpty()}"
            }
            appendStep(SearchAgentStep.ErrorStep(title = "搜索子代理失败", detail = message))
            progressStore.finish(toolCallId)
            buildAgentResult(
                summary = "",
                sources = emptyList(),
                notes = listOf(message),
            )
        }.withMetadata(stepList.map { it.toJson() })
    }

    private fun buildTaskDetail(task: String, urls: List<String>): String = buildString {
        appendLine(task)
        if (urls.isNotEmpty()) {
            appendLine()
            append("URLs: ")
            append(urls.joinToString(", "))
        }
    }

    private suspend fun runInternal(task: String, urls: List<String>): JsonObject {
        val internalTools = SearchTools.createSearchTools(settings, searchMode).toList()
        require(internalTools.isNotEmpty()) { "No search tools available for search agent" }

        val providerSetting = model.findProvider(settings.providers) ?: error("Search agent provider not found")
        val provider = providerManager.getProviderByType(providerSetting)
        val toolInstructions = internalTools
            .mapNotNull { tool -> tool.systemPrompt(model, emptyList()).takeIf { it.isNotBlank() } }
            .joinToString(separator = "\n\n")

        var messages = listOf(
            UIMessage.system(
                buildSystemPrompt(toolInstructions = toolInstructions)
            ),
            UIMessage.user(
                buildUserPrompt(task = task, urls = urls)
            ),
        )
        var stepLimitReached = false

        repeat(SEARCH_AGENT_MAX_STEPS + 1) { stepIndex ->
            var requestBodyJson: String? = null
            val toolsForStep = if (stepLimitReached) emptyList() else internalTools
            val params = TextGenerationParams(
                model = model,
                temperature = 0.1f,
                tools = toolsForStep,
                onRequestBody = { requestBodyJson = it },
            )
            val startAt = System.currentTimeMillis()
            var failure: Throwable? = null
            var rawResponseText = ""
            val requestMessages = messages
            try {
                val chunk = withTimeout(SEARCH_AGENT_IDLE_TIMEOUT_MS) {
                    provider.generateText(
                        providerSetting = providerSetting,
                        messages = requestMessages,
                        params = params,
                    )
                }
                rawResponseText = chunk.rawResponse.orEmpty()
                messages = messages.handleMessageChunk(chunk = chunk, model = model)
                // 被动展示思考：若本轮模型返回了 reasoning，发布一条折叠步骤
                extractReasoning(messages)?.takeIf { it.isNotBlank() }?.let {
                    appendStep(SearchAgentStep.ReasoningStep(text = it))
                }
            } catch (t: Throwable) {
                failure = t
                throw t
            } finally {
                requestLogManager.logTextGeneration(
                    source = AIRequestSource.SEARCH_AGENT,
                    providerSetting = providerSetting,
                    params = params,
                    requestMessages = requestMessages,
                    requestBodyJson = requestBodyJson,
                    responseText = messages.lastOrNull()?.toContentText().orEmpty(),
                    responseRawText = rawResponseText,
                    stream = false,
                    latencyMs = System.currentTimeMillis() - startAt,
                    durationMs = System.currentTimeMillis() - startAt,
                    error = failure,
                )
            }

            val toolCalls = messages.lastOrNull()?.getToolCalls().orEmpty()
            if (toolCalls.isEmpty()) {
                val finalText = messages.lastOrNull()?.toContentText().orEmpty()
                val finalResult = normalizeFinalResult(finalText).let { result ->
                    if (stepLimitReached) result.withAdditionalNote(stepLimitNote()) else result
                }
                appendStep(
                    SearchAgentStep.FinalStep(
                        detail = finalResult["summary"]?.jsonPrimitiveOrNull?.contentOrNull
                            ?.take(180)
                            .orEmpty(),
                    ),
                )
                progressStore.finish(toolCallId)
                return finalResult
            }

            if (stepLimitReached) {
                val message = stepLimitNote()
                appendStep(SearchAgentStep.ErrorStep(title = "已达到搜索轮数上限", detail = message))
                progressStore.finish(toolCallId)
                return buildAgentResult(
                    summary = "",
                    sources = emptyList(),
                    notes = listOf(message),
                )
            }

            val resolvedToolCalls = toolCalls.mapIndexed { index, call ->
                call.copy(
                    toolCallId = call.toolCallId.ifBlank {
                        "search_agent_${stepIndex}_${index}_${Uuid.random()}"
                    }
                )
            }
            if (resolvedToolCalls != toolCalls) {
                messages = messages.replaceLastToolCalls(resolvedToolCalls)
            }

            val reachedStepLimit = stepIndex == SEARCH_AGENT_MAX_STEPS - 1
            val results = if (reachedStepLimit) {
                appendStep(buildStepLimitNoticeStep())
                stepLimitReached = true
                resolvedToolCalls.map(::buildStepLimitToolResult)
            } else {
                resolvedToolCalls.map { toolCall ->
                    executeInternalTool(toolCall = toolCall, internalTools = internalTools)
                }
            }
            messages = messages + UIMessage(
                role = MessageRole.TOOL,
                parts = results,
            )
        }
        error("Unreachable search agent step loop exit")
    }

    private suspend fun executeInternalTool(
        toolCall: UIMessagePart.ToolCall,
        internalTools: List<Tool>,
    ): UIMessagePart.ToolResult {
        val args = parseToolArguments(toolCall)
        val tool = internalTools.firstOrNull { it.name == toolCall.toolName }
        // 先发布一条 Running 步骤（带 title），执行完替换为 Done
        val runningTitle = buildInternalToolTitle(toolName = toolCall.toolName, args = args)
        appendStep(
            SearchAgentStep.ToolCallStep(
                toolName = toolCall.toolName,
                title = runningTitle,
                detail = "执行中",
                urls = emptyList(),
                status = SearchAgentStep.ToolCallStep.Status.Running,
            ),
        )
        val result = runCatching {
            requireNotNull(tool) { "Tool ${toolCall.toolName} not found" }
            withTimeout(SEARCH_AGENT_IDLE_TIMEOUT_MS) {
                tool.execute(args)
            }
        }
        result.exceptionOrNull()?.let { throwable ->
            if (throwable is kotlinx.coroutines.TimeoutCancellationException) {
                val timeoutContent = buildJsonObject {
                    put("error", "timeout")
                    put("message", "内部工具 60 秒内没有返回结果。")
                }
                replaceLastStep {
                    buildInternalToolDoneStep(
                        toolName = toolCall.toolName,
                        title = runningTitle,
                        args = args,
                        content = timeoutContent,
                        failed = true,
                    )
                }
                throw throwable
            }
        }
        val content = result.getOrElse { throwable ->
            buildJsonObject {
                put("error", throwable.message ?: throwable::class.simpleName.orEmpty())
            }
        }
        rememberObservedSources(content = content)
        val doneStep = buildInternalToolDoneStep(
            toolName = toolCall.toolName,
            title = runningTitle,
            args = args,
            content = content,
            failed = result.isFailure,
        )
        replaceLastStep { doneStep }
        return UIMessagePart.ToolResult(
            toolCallId = toolCall.toolCallId,
            toolName = toolCall.toolName,
            content = content,
            arguments = args,
        )
    }

    private fun parseToolArguments(toolCall: UIMessagePart.ToolCall): JsonElement {
        return runCatching {
            json.parseToJsonElement(toolCall.arguments.ifBlank { "{}" })
        }.getOrElse {
            JsonObject(emptyMap())
        }
    }

    private fun buildStepLimitToolResult(toolCall: UIMessagePart.ToolCall): UIMessagePart.ToolResult {
        val args = parseToolArguments(toolCall)
        return UIMessagePart.ToolResult(
            toolCallId = toolCall.toolCallId,
            toolName = toolCall.toolName,
            content = buildJsonObject {
                put("error", SEARCH_AGENT_STEP_LIMIT_CODE)
                put("message", "已达到搜索轮数上限。请立即基于已有搜索结果输出最终 JSON，不要继续调用工具。")
            },
            arguments = args,
        )
    }

    private fun buildStepLimitNoticeStep(): SearchAgentStep.ToolCallStep {
        return SearchAgentStep.ToolCallStep(
            toolName = SEARCH_AGENT_TOOL_NAME,
            title = "已达到搜索轮数上限",
            detail = "搜索子代理已执行 ${SEARCH_AGENT_MAX_STEPS} 轮，已要求它基于已有资料总结。",
            urls = emptyList(),
            status = SearchAgentStep.ToolCallStep.Status.Done,
        )
    }

    private fun stepLimitNote(): String {
        return "搜索子代理已达到 ${SEARCH_AGENT_MAX_STEPS} 轮上限，已基于已有资料总结，结果可能不完整。"
    }

    private fun buildSystemPrompt(toolInstructions: String): String = """
        You are a web search sub-agent.
        Today is ${LocalDate.now()}.

        Your job:
        - Search the web or read the provided URLs.
        - Return only the facts needed for the task.
        - Keep the result short.
        - Cite every important factual sentence with `[citation,domain](id)`.
        - Only include sources that are actually cited in `summary`.
        - Do not invent sources or citation IDs.
        - If sources conflict or are weak, say that in `notes`.
        - For medical, legal, financial, or other high-risk topics, summarize sources but note that it is not professional advice.

        Return JSON only:
        {
          "summary": "short cited summary",
          "sources": [
            {"id": "source id used in summary", "title": "source title", "url": "https://...", "snippet": "what this source supports"}
          ],
          "notes": ["optional uncertainty or failure notes"]
        }

        $toolInstructions
    """.trimIndent()

    private fun buildUserPrompt(task: String, urls: List<String>): String = buildString {
        appendLine("Task:")
        appendLine(task)
        if (urls.isNotEmpty()) {
            appendLine()
            appendLine("URLs:")
            urls.forEach { appendLine("- $it") }
        }
    }

    private fun normalizeFinalResult(text: String): JsonObject {
        val parsed = parseJsonObjectFromText(text)
        if (parsed == null) {
            val summary = text.trim()
            val citedSources = extractCitationIds(summary)
                .mapNotNull(observedSourcesById::get)
            val linkedSources = extractLinkedSources(summary)
            val sources = mergeSources(citedSources, linkedSources)
            return buildAgentResult(
                summary = summary,
                sources = sources,
                notes = if (sources.isEmpty()) {
                    listOf("搜索子代理没有返回标准 JSON。")
                } else {
                    emptyList()
                }
            )
        }

        val summary = parsed["summary"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
        val citedIds = extractCitationIds(summary)
        val jsonSources = parsed["sources"]?.let { it as? JsonArray }
            ?.mapNotNull { it as? JsonObject }
            ?.filter { source ->
                val id = source["id"]?.jsonPrimitiveOrNull?.contentOrNull
                id != null && id in citedIds
            }
            .orEmpty()
        val observedCitedSources = citedIds.mapNotNull(observedSourcesById::get)
        val linkedSources = extractLinkedSources(summary)
        val sources = mergeSources(jsonSources, observedCitedSources, linkedSources)
        val notes = parsed["notes"]?.let { it as? JsonArray }
            ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
            .orEmpty()
            .let { existing ->
                if (summary.isNotBlank() && citedIds.isEmpty() && linkedSources.isEmpty()) {
                    existing + "搜索子代理没有在总结中引用来源。"
                } else {
                    existing
                }
            }

        return buildAgentResult(summary = summary, sources = sources, notes = notes)
    }

    private fun parseJsonObjectFromText(text: String): JsonObject? = parseSearchAgentJsonObject(text)

    private fun extractCitationIds(summary: String): Set<String> {
        return Regex("""\[citation,[^\]]+]\(([^)]+)\)""")
            .findAll(summary)
            .map { it.groupValues.getOrNull(1).orEmpty().trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun extractLinkedSources(text: String): List<JsonObject> {
        val sources = linkedMapOf<String, FallbackSource>()

        fun addSource(rawUrl: String, rawTitle: String?, id: String? = null) {
            val url = cleanSourceUrl(rawUrl) ?: return
            val key = SearchTools.urlDedupKey(url).ifBlank { url }
            val title = rawTitle
                ?.trim()
                ?.take(240)
                ?.takeIf { it.isNotBlank() && !it.startsWith("citation,", ignoreCase = true) }
            if (sources.containsKey(key)) return
            sources[key] = FallbackSource(
                id = id?.takeIf { it.isNotBlank() },
                title = title,
                url = url,
            )
        }

        extractMarkdownLinks(text).forEach { link ->
            val cleanUrl = cleanSourceUrl(link.url)
            addSource(
                rawUrl = link.url,
                rawTitle = link.title,
                id = if (link.title.startsWith("citation,", ignoreCase = true)) cleanUrl else null,
            )
        }

        MARKDOWN_URL_REGEX.findAll(text).forEach { match ->
            val label = match.groupValues.getOrNull(1).orEmpty()
            val url = match.groupValues.getOrNull(2).orEmpty()
            val cleanUrl = cleanSourceUrl(url)
            addSource(
                rawUrl = url,
                rawTitle = label,
                id = if (label.startsWith("citation,", ignoreCase = true)) cleanUrl else null,
            )
        }

        RAW_URL_REGEX.findAll(text).forEach { match ->
            addSource(rawUrl = match.value, rawTitle = null)
        }

        return sources.values
            .take(FALLBACK_SOURCE_LIMIT)
            .mapIndexed { index, source ->
                val domain = sourceDomain(source.url)
                buildJsonObject {
                    put("id", source.id ?: "source_${index + 1}")
                    put("title", source.title ?: domain ?: source.url)
                    put("url", source.url)
                    put("snippet", "子代理输出中引用了该链接。")
                }
            }
    }

    private fun extractMarkdownLinks(text: String): List<MarkdownLink> {
        val root = MARKDOWN_LINK_PARSER.buildMarkdownTreeFromString(text)
        val links = mutableListOf<MarkdownLink>()

        fun visit(node: ASTNode) {
            when (node.type) {
                MarkdownElementTypes.INLINE_LINK -> {
                    val destination = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)
                        ?.getTextInNode(text)
                        .orEmpty()
                    val title = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)
                        ?.getTextInNode(text)
                        ?.trim('[', ']')
                        .orEmpty()
                    links += MarkdownLink(url = destination, title = title)
                }

                MarkdownElementTypes.AUTOLINK -> {
                    links += MarkdownLink(url = node.getTextInNode(text).trim('<', '>'), title = "")
                }

                GFMTokenTypes.GFM_AUTOLINK -> {
                    links += MarkdownLink(url = node.getTextInNode(text), title = "")
                }
            }

            node.children.forEach(::visit)
        }

        visit(root)
        return links
    }

    private fun mergeSources(vararg sourceGroups: List<JsonObject>): List<JsonObject> {
        val seen = mutableSetOf<String>()
        return sourceGroups.asSequence().flatten().filter { source ->
            val url = source["url"]?.jsonPrimitiveOrNull?.contentOrNull
            val id = source["id"]?.jsonPrimitiveOrNull?.contentOrNull
            val key = url?.let(SearchTools::urlDedupKey)?.takeIf { it.isNotBlank() }
                ?: id?.takeIf { it.isNotBlank() }
                ?: return@filter false
            seen.add(key)
        }.toList()
    }

    private fun cleanSourceUrl(rawUrl: String): String? {
        val url = rawUrl
            .trim()
            .trim('<', '>')
            .trimEnd('.', ',', ';', ':', '!', '?')
            .trimEnd('。', '，', '；', '：', '！', '？')
            .trimUnbalancedClosing(open = '(', close = ')')
            .trimUnbalancedClosing(open = '[', close = ']')
            .trimUnbalancedClosing(open = '{', close = '}')
            .trimEnd('）', '》', '>')
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if (scheme != "http" && scheme != "https") return null
        if (host.isNullOrBlank()) return null
        return url
    }

    private fun sourceDomain(url: String): String? {
        return runCatching { URI(url).host?.removePrefix("www.") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.trimUnbalancedClosing(open: Char, close: Char): String {
        var value = this
        while (value.lastOrNull() == close && value.count { it == close } > value.count { it == open }) {
            value = value.dropLast(1)
        }
        return value
    }

    private fun rememberObservedSources(content: JsonElement) {
        val items = (content as? JsonObject)?.get("items") as? JsonArray ?: return
        items.mapNotNull { it as? JsonObject }.forEach { item ->
            val id = item["id"]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }
            val url = item["url"]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@forEach
            val title = item["title"]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: sourceDomain(url)
                ?: url
            val snippet = item["snippet"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: item["text"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: item["content"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: ""
            val source = buildJsonObject {
                put("id", id ?: url)
                put("title", title.take(240))
                put("url", url)
                put("snippet", snippet.take(600))
            }
            if (id != null) {
                observedSourcesById[id] = source
            }
        }
    }

    /** 运行中的 title（只看工具名和参数）。 */
    private fun buildInternalToolTitle(toolName: String, args: JsonElement): String {
        return when (toolName) {
            "search_web" -> {
                val query = (args as? JsonObject)?.get("query")?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
                "搜索：$query"
            }
            "scrape_web" -> "读取网页"
            else -> toolName
        }
    }

    private fun buildInternalToolDoneStep(
        toolName: String,
        title: String,
        args: JsonElement,
        content: JsonElement,
        failed: Boolean,
    ): SearchAgentStep.ToolCallStep {
        return when (toolName) {
            "search_web" -> {
                val items = (content as? JsonObject)?.get("items") as? JsonArray
                SearchAgentStep.ToolCallStep(
                    toolName = toolName,
                    title = title,
                    detail = if (failed) content.shortError() else "返回 ${items?.size ?: 0} 条结果",
                    urls = items?.mapNotNull { item ->
                        (item as? JsonObject)?.get("url")?.jsonPrimitiveOrNull?.contentOrNull
                    }.orEmpty(),
                    status = SearchAgentStep.ToolCallStep.Status.Done,
                )
            }

            "scrape_web" -> {
                val urls = args.extractUrls()
                val scraped = (content as? JsonObject)?.get("urls") as? JsonArray
                SearchAgentStep.ToolCallStep(
                    toolName = toolName,
                    title = title,
                    detail = if (failed) content.shortError() else "读取 ${scraped?.size ?: urls.size} 个网页",
                    urls = urls,
                    status = SearchAgentStep.ToolCallStep.Status.Done,
                )
            }

            else -> SearchAgentStep.ToolCallStep(
                toolName = toolName,
                title = title,
                detail = if (failed) content.shortError() else "工具执行完成",
                urls = emptyList(),
                status = SearchAgentStep.ToolCallStep.Status.Done,
            )
        }
    }

    /** 从最新消息里提取内部模型的 reasoning（若有）。 */
    private fun extractReasoning(messages: List<UIMessage>): String? {
        val parts = messages.lastOrNull()?.parts ?: return null
        return parts.filterIsInstance<UIMessagePart.Reasoning>()
            .joinToString(separator = "\n") { it.reasoning }
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun JsonElement.extractUrls(): List<String> {
        val obj = this as? JsonObject ?: return emptyList()
        val single = obj["url"]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }
        val many = (obj["urls"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull?.takeIf(String::isNotBlank) }
            .orEmpty()
        return listOfNotNull(single) + many
    }

    private fun JsonElement.shortError(): String {
        val obj = this as? JsonObject
        return obj?.get("error")?.jsonPrimitiveOrNull?.contentOrNull
            ?.lineSequence()
            ?.firstOrNull()
            ?.take(220)
            ?: "工具执行失败"
    }
}

internal fun parseSearchAgentJsonObject(text: String): JsonObject? {
    val trimmed = text.trim()
    runCatching { return JsonInstant.parseToJsonElement(trimmed) as? JsonObject }
    val candidate = extractSearchAgentJsonObjectCandidate(trimmed) ?: return null
    runCatching { return JsonInstant.parseToJsonElement(candidate) as? JsonObject }
    val sanitized = sanitizeSearchAgentJsonLikeObject(candidate)
    if (sanitized != candidate) {
        runCatching { return JsonInstant.parseToJsonElement(sanitized) as? JsonObject }
    }
    return null
}

private fun extractSearchAgentJsonObjectCandidate(text: String): String? {
    val fencedBlock = extractFencedCodeBlock(text)
    if (fencedBlock != null) {
        val fencedCandidate = extractBracedObject(fencedBlock)
        if (fencedCandidate != null) return fencedCandidate
    }
    return extractBracedObject(text)
}

private fun extractFencedCodeBlock(text: String): String? {
    val start = text.indexOf("```")
    if (start < 0) return null
    val end = text.lastIndexOf("```")
    if (end <= start) return null
    val bodyStart = text.indexOf('\n', start + 3).takeIf { it >= 0 } ?: return null
    return text.substring(bodyStart + 1, end).trim()
}

private fun extractBracedObject(text: String): String? {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return text.substring(start, end + 1)
}

private fun sanitizeSearchAgentJsonLikeObject(text: String): String {
    val out = StringBuilder(text.length + 32)
    var inString = false
    var escaped = false
    var index = 0

    while (index < text.length) {
        val ch = text[index]
        if (inString) {
            when {
                escaped -> {
                    out.append(ch)
                    escaped = false
                }
                ch == '\\' -> {
                    out.append(ch)
                    escaped = true
                }
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch == '"' -> {
                    if (looksLikeStringTerminator(text, index)) {
                        out.append(ch)
                        inString = false
                    } else {
                        out.append("\\\"")
                    }
                }
                else -> out.append(ch)
            }
        } else {
            out.append(ch)
            if (ch == '"') {
                inString = true
            }
        }
        index++
    }
    return out.toString()
}

private fun looksLikeStringTerminator(text: String, quoteIndex: Int): Boolean {
    var index = quoteIndex + 1
    while (index < text.length && text[index].isWhitespace()) {
        index++
    }
    return index >= text.length ||
        text[index] == ':' ||
        text[index] == ',' ||
        text[index] == '}' ||
        text[index] == ']'
}

private fun buildAgentResult(
    summary: String,
    sources: List<JsonObject>,
    notes: List<String>,
): JsonObject = buildJsonObject {
    put("summary", summary)
    put("sources", JsonArray(sources))
    put("notes", buildJsonArray {
        notes.filter { it.isNotBlank() }.distinct().forEach { add(JsonPrimitive(it)) }
    })
}

private fun JsonObject.withAdditionalNote(note: String): JsonObject {
    val summary = this["summary"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val sources = (this["sources"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()
    val notes = (this["notes"] as? JsonArray)
        ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
        .orEmpty() + note
    return buildAgentResult(summary = summary, sources = sources, notes = notes)
}

private fun JsonObject.withMetadata(steps: List<JsonObject>): JsonObject {
    val map = toMutableMap()
    map[SEARCH_AGENT_METADATA_KEY] = buildJsonObject {
        put(SEARCH_AGENT_STEPS_KEY, JsonArray(steps))
    }
    return JsonObject(map)
}

private data class FallbackSource(
    val id: String?,
    val title: String?,
    val url: String,
)

private data class MarkdownLink(
    val url: String,
    val title: String,
)

private fun ASTNode.getTextInNode(text: String): String {
    return text.substring(startOffset, endOffset)
}

private fun ASTNode.findChildOfTypeRecursive(vararg types: IElementType): ASTNode? {
    if (type in types) return this
    for (child in children) {
        val result = child.findChildOfTypeRecursive(*types)
        if (result != null) return result
    }
    return null
}

private fun List<UIMessage>.replaceLastToolCalls(resolvedToolCalls: List<UIMessagePart.ToolCall>): List<UIMessage> {
    val last = lastOrNull() ?: return this
    var index = 0
    val updated = last.copy(
        parts = last.parts.map { part ->
            if (part is UIMessagePart.ToolCall && index < resolvedToolCalls.size) {
                resolvedToolCalls[index++]
            } else {
                part
            }
        }
    )
    return dropLast(1) + updated
}
