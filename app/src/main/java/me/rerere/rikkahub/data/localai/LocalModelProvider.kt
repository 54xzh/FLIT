package me.rerere.rikkahub.data.localai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.parametersOrEmptyObject
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/** Adapts the optional on-device runtime to the app's normal streaming Provider contract. */
class LocalModelProvider(
    private val repository: LocalModelRepository,
    private val runtime: LocalRuntimeManager,
) : Provider<ProviderSetting.Local> {
    override suspend fun listModels(providerSetting: ProviderSetting.Local): List<Model> = providerSetting.models

    override suspend fun generateText(
        providerSetting: ProviderSetting.Local,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        val output = StringBuilder()
        streamText(providerSetting, messages, params).collect { chunk ->
            chunk.choices.firstOrNull()?.delta?.parts
                ?.filterIsInstance<UIMessagePart.Text>()
                ?.forEach { output.append(it.text) }
        }
        return textChunk(params.model, output.toString(), finishReason = "stop")
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Local,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = channelFlow {
        val record = repository.get(params.model.id)
            ?: throw LocalRuntimeUnavailableException("This local model is no longer available.")
        check(record.state == LocalModelState.READY) { "This local model is not ready yet." }
        val toolsPrompt = buildToolsPrompt(params)
        val request = LocalInferenceRequest(
            modelFile = repository.fileFor(record),
            format = record.format,
            messages = buildPromptMessages(
                messages = messages,
                toolsPrompt = toolsPrompt,
            ),
            temperature = params.temperature,
            topP = params.topP,
            maxTokens = params.maxTokens,
            reasoningLevel = params.reasoningLevel,
            toolsPrompt = toolsPrompt,
        )
        val thinkingParser = ThinkingTagParser()
        var finishReason = "stop"
        runtime.withLoadedModel(params.model.id, request) { bridge ->
            bridge.generate(request).collect { event ->
                when (event) {
                    is LocalInferenceEvent.Text -> thinkingParser.append(event.value).forEach { segment ->
                        send(segmentChunk(params.model, segment))
                    }
                    is LocalInferenceEvent.ToolCall -> send(toolCallChunk(params.model, event))
                    is LocalInferenceEvent.Finished -> finishReason = event.reason
                }
            }
        }
        thinkingParser.finish().forEach { segment -> send(segmentChunk(params.model, segment)) }
        send(textChunk(params.model, "", finishReason = finishReason))
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): ImageGenerationResult = throw UnsupportedOperationException("Local models do not generate images")

    /**
     * Leaves syntax and role naming to the embedded GGUF template. The generic system turn only
     * preserves the default helpful-assistant behavior used by common instruct templates.
     */
    private fun buildPromptMessages(
        messages: List<UIMessage>,
        toolsPrompt: String,
    ): List<LocalInferenceMessage> = buildList {
        if (toolsPrompt.isNotEmpty()) {
            add(LocalInferenceMessage(role = "system", content = toolsPrompt))
        } else if (messages.none { it.role == MessageRole.SYSTEM }) {
            add(LocalInferenceMessage(role = "system", content = DEFAULT_SYSTEM_PROMPT))
        }
        messages.forEach { message ->
            val content = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString(separator = "") { it.text }
            if (content.isNotEmpty()) {
                add(
                    LocalInferenceMessage(
                        role = when (message.role) {
                            MessageRole.SYSTEM -> "system"
                            MessageRole.USER -> "user"
                            MessageRole.ASSISTANT -> "assistant"
                            MessageRole.TOOL -> "tool"
                        },
                        content = content,
                    ),
                )
            }
        }
    }

    private fun buildToolsPrompt(params: TextGenerationParams): String = buildString {
        if (params.tools.isEmpty()) return@buildString
        append("Available tools. Return a structured tool call with a tool name and JSON arguments.\n")
        params.tools.forEach { tool ->
            append("- ").append(tool.name).append(": ").append(tool.description).append('\n')
            append(JsonInstant.encodeToString(tool.parametersOrEmptyObject())).append('\n')
        }
    }

    private fun textChunk(model: Model, text: String, finishReason: String? = null): MessageChunk = MessageChunk(
        id = Uuid.random().toString(),
        model = model.modelId,
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = if (text.isEmpty()) emptyList() else listOf(UIMessagePart.Text(text)),
                ),
                message = null,
                finishReason = finishReason,
            ),
        ),
        finishReasons = finishReason?.let(::setOf) ?: emptySet(),
    )

    private fun toolCallChunk(model: Model, event: LocalInferenceEvent.ToolCall): MessageChunk = MessageChunk(
        id = event.id.ifBlank { Uuid.random().toString() },
        model = model.modelId,
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.ToolCall(event.id, event.name, event.arguments)),
                ),
                message = null,
                finishReason = null,
            ),
        ),
    )

    private fun segmentChunk(model: Model, segment: ThinkingSegment): MessageChunk = when (segment) {
        is ThinkingSegment.Text -> textChunk(model, segment.value)
        is ThinkingSegment.Reasoning -> reasoningChunk(model, segment.value)
        ThinkingSegment.Finished -> textChunk(model, "")
    }

    private fun reasoningChunk(model: Model, reasoning: String): MessageChunk = MessageChunk(
        id = Uuid.random().toString(),
        model = model.modelId,
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Reasoning(reasoning = reasoning, finishedAt = null)),
                ),
                message = null,
                finishReason = null,
            ),
        ),
    )

    private sealed interface ThinkingSegment {
        data class Text(val value: String) : ThinkingSegment
        data class Reasoning(val value: String) : ThinkingSegment
        data object Finished : ThinkingSegment
    }

    /**
     * Keeps the tail of each token fragment until a split `<think>` or `</think>` tag can be
     * identified. This prevents early thought tokens from briefly appearing in the answer body.
     */
    private class ThinkingTagParser {
        private val buffer = StringBuilder()
        private var inThinking = false

        fun append(fragment: String): List<ThinkingSegment> {
            buffer.append(fragment)
            return drain(flush = false)
        }

        fun finish(): List<ThinkingSegment> = drain(flush = true)

        private fun drain(flush: Boolean): List<ThinkingSegment> = buildList {
            while (buffer.isNotEmpty()) {
                val marker = if (inThinking) CLOSE_TAG else OPEN_TAG
                val markerIndex = buffer.indexOf(marker)
                if (markerIndex >= 0) {
                    addContent(buffer.substring(0, markerIndex))
                    buffer.delete(0, markerIndex + marker.length)
                    if (inThinking) add(ThinkingSegment.Finished)
                    inThinking = !inThinking
                    continue
                }

                val safeLength = if (flush) buffer.length else (buffer.length - marker.length + 1).coerceAtLeast(0)
                if (safeLength == 0) break
                addContent(buffer.substring(0, safeLength))
                buffer.delete(0, safeLength)
            }
        }

        private fun MutableList<ThinkingSegment>.addContent(value: String) {
            if (value.isEmpty()) return
            add(if (inThinking) ThinkingSegment.Reasoning(value) else ThinkingSegment.Text(value))
        }

        private companion object {
            const val OPEN_TAG = "<think>"
            const val CLOSE_TAG = "</think>"
        }
    }

    private companion object {
        const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant."
    }
}
