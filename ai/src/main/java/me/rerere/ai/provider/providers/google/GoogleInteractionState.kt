package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.ui.GoogleInteractionStep
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WebSearchAction
import me.rerere.ai.ui.WebSearchStatus
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import me.rerere.common.http.jsonPrimitiveOrNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** 一次请求的步骤累计状态；显示可以合并，原始步骤始终按 index 保存。 */
internal open class GoogleInteractionState {
    private val turnId = Uuid.random().toString()
    private val steps = linkedMapOf<Int, JsonObject>()
    private val revisions = mutableMapOf<Int, Int>()
    private val stopped = mutableSetOf<Int>()
    private val createdAt = mutableMapOf<Int, Instant>()
    private val emittedThoughts = mutableMapOf<Int, String>()
    private val argumentFragments = mutableMapOf<Int, String>()
    private val emittedContents = mutableMapOf<Int, JsonArray>()
    private data class DisplaySegment(val id: String, val createdAt: Instant)
    private val thoughtSegments = mutableMapOf<Int, DisplaySegment>()
    private val textSegments = mutableMapOf<Int, DisplaySegment>()
    private var nextSegment = 0
    private var lastVisibleSegmentId: String? = null
    private val emittedSearchIndexes = mutableSetOf<Int>()
    private var currentIndex: Int? = null
    private val citations = linkedMapOf<String, UIMessageAnnotation.UrlCitation>()
    private val failedSearches = mutableSetOf<String>()

    val currentStepType: String? get() = currentIndex?.let { steps[it]?.string("type") }
    val currentFunctionCallId: String? get() = currentIndex?.let { steps[it]?.let { step -> step.string("call_id") ?: step.string("id") } }
    val currentFunctionName: String? get() = currentIndex?.let { steps[it]?.string("name") }

    fun consume(event: JsonObject, eventType: String?): Pair<List<UIMessagePart>, List<UIMessageAnnotation>> {
        val type = event.string("event_type") ?: eventType
        val payload = event["step"] as? JsonObject
        val delta = event["delta"] as? JsonObject
        val parts = mutableListOf<UIMessagePart>()
        val index = event["index"]?.jsonPrimitiveOrNull?.intOrNull
            ?: payload?.get("index")?.jsonPrimitiveOrNull?.intOrNull
            ?: if (payload != null) {
                payload.string("id")?.let { id -> steps.entries.firstOrNull { it.value.string("id") == id }?.key }
                    ?: ((steps.keys.maxOrNull() ?: -1) + 1)
            } else currentIndex
        if (index != null && (payload != null || delta != null || type == "step.stop")) {
            currentIndex = index
            createdAt.getOrPut(index) { Clock.System.now() }
            if (payload != null) {
                steps[index] = mergeObject(steps[index] ?: JsonObject(emptyMap()), JsonObject(payload.filterKeys { it != "index" }))
            }
            if (delta != null) applyDelta(index, delta)
            revisions[index] = (revisions[index] ?: 0) + 1
            if (type == "step.stop" || payload?.string("status") in setOf("completed", "stop")) stopped += index
            val step = steps[index]
            if (step != null) {
                when (step.string("type")) {
                    "thought" -> parts += thoughtPart(index, step)
                    "model_output" -> {
                        if (delta != null && delta.string("type") != "text_annotation_delta") {
                            val content = if (delta.string("type") in setOf("image", "audio")) {
                                (step["content"] as? JsonArray)?.lastOrNull() as? JsonObject ?: delta
                            } else
                                buildJsonObject { put("type", "text"); put("text", delta.string("text").orEmpty()) }
                            contentPart(content, index)?.let(parts::add)
                        } else if (payload != null) {
                            val contents = step["content"] as? JsonArray ?: JsonArray(emptyList())
                            val previous = emittedContents[index].orEmpty()
                            // 快照只补未发出的内容；完整结束快照不得重复显示正文。
                            contents.forEachIndexed { position, element ->
                                val content = element as? JsonObject ?: return@forEachIndexed
                                val old = previous.getOrNull(position) as? JsonObject
                                val incoming = if (content.string("type") == "text" && old != null) {
                                    val text = content.string("text").orEmpty()
                                    val oldText = old.string("text").orEmpty()
                                    if (!text.startsWith(oldText)) return@forEachIndexed
                                    JsonObject(content + ("text" to JsonPrimitive(text.removePrefix(oldText))))
                                } else {
                                    if (content == old) return@forEachIndexed
                                    content
                                }
                                contentPart(incoming, index)?.let(parts::add)
                            }
                        }
                        emittedContents[index] = step["content"] as? JsonArray ?: JsonArray(emptyList())
                        // 即使没有文字（媒体输出、注释或停止事件），也保存完整输出步骤。
                        if (parts.isEmpty()) parts += UIMessagePart.Text("", textMetadata(index, visible = false))
                        collectCitations(step["content"])
                    }
                    "function_call" -> {
                        val args = if (delta != null) {
                            delta.string("partial_arguments") ?: delta.string("arguments")
                                ?: (delta["arguments"] as? JsonObject)?.toString().orEmpty()
                        } else if (payload != null && index !in stopped) {
                            when (val arguments = step["arguments"]) {
                                is JsonObject -> arguments.takeIf { it.isNotEmpty() }?.toString().orEmpty()
                                is JsonPrimitive -> arguments.contentOrNull.orEmpty()
                                else -> ""
                            }
                        } else ""
                        parts += UIMessagePart.ToolCall(
                            toolCallId = step.string("call_id") ?: step.string("id").orEmpty(),
                            toolName = step.string("name").orEmpty(),
                            arguments = args,
                            metadata = metadata(index),
                        )
                    }
                    "google_search_call", "google_search_result", "url_context_call", "url_context_result" -> {
                        val search = searchPart(index)
                        // 结果/签名更新原来的行，只有首次出现的调用才中断当前思考段。
                        val callIndex = search.metadataAs<GoogleThoughtMetadata>()?.interactionSteps
                            ?.firstOrNull { it.step.string("type")?.endsWith("_call") == true }?.index ?: index
                        if (emittedSearchIndexes.add(callIndex)) lastVisibleSegmentId = "search:$callIndex"
                        parts += search
                    }
                    else -> parts += UIMessagePart.Text(step.string("text").orEmpty(), metadata(index))
                }
            }
        }
        val interaction = event["interaction"] as? JsonObject
        val status = interaction?.string("status") ?: event.string("status")
        if (status in setOf("completed", "incomplete", "failed", "cancelled", "requires_action") || type == "error") {
            parts += finishSearches()
        }
        return parts to citations.values.toList()
    }

    fun finishSearches(): List<UIMessagePart.WebSearch> {
        val searchIndexes = steps.keys.filter { steps[it]?.string("type") in setOf("google_search_call", "google_search_result", "url_context_call", "url_context_result") }
        val searches = searchIndexes.map(::searchPart).distinctBy { it.callId }
        val googleSearchCount = searches.count { it.action == WebSearchAction.Search }
        return searches.map { search ->
            if (search.status in setOf(WebSearchStatus.Searching, WebSearchStatus.InProgress)) {
                failedSearches += search.callId
                search.copy(status = WebSearchStatus.Failed, finishedAt = Clock.System.now())
            } else if (googleSearchCount == 1 && search.action == WebSearchAction.Search) {
                search.copy(sources = citations.values.toList())
            } else search
        }
    }

    private fun applyDelta(index: Int, delta: JsonObject) {
        var step = steps[index] ?: return
        when (step.string("type")) {
            "thought" -> {
                val signature = delta.string("signature") ?: delta.string("thought_signature")
                if (signature != null) step = JsonObject(step + ("signature" to JsonPrimitive(signature)))
                val summary = delta["content"] ?: delta["summary"] ?: delta["thought_summary"]
                    ?: delta.string("text")?.let { buildJsonObject { put("type", "text"); put("text", it) } }
                if (summary != null) {
                    val items = if (summary is JsonArray) summary.toList() else listOf(summary)
                    val summaries = (step["summary"] as? JsonArray).orEmpty().toMutableList()
                    items.forEach { item ->
                        val last = summaries.lastOrNull() as? JsonObject
                        val incoming = item as? JsonObject
                        if (last?.string("type") == "text" && incoming?.string("type") == "text") {
                            summaries[summaries.lastIndex] = JsonObject(mergeObject(last, incoming) +
                                ("text" to JsonPrimitive(last.string("text").orEmpty() + incoming.string("text").orEmpty())))
                        } else summaries += item
                    }
                    step = JsonObject(step + ("summary" to JsonArray(summaries)))
                }
            }
            "model_output" -> {
                val contents = (step["content"] as? JsonArray).orEmpty().toMutableList()
                val type = delta.string("type") ?: "text"
                if (type == "text_annotation_delta") {
                    val position = contents.indexOfLast { (it as? JsonObject)?.string("type") == "text" }
                    val annotations = delta["annotations"] as? JsonArray
                    if (position >= 0 && annotations != null) {
                        val text = contents[position] as? JsonObject ?: JsonObject(emptyMap())
                        contents[position] = JsonObject(text + ("annotations" to JsonArray(((text["annotations"] as? JsonArray).orEmpty() + annotations).distinct())))
                    } else if (annotations != null) {
                        // Agent Platform 可在独立的 model_output 中只发送正文来源注释。
                        contents += buildJsonObject {
                            put("type", "text")
                            put("text", "")
                            put("annotations", annotations)
                        }
                    }
                    collectCitations(delta["annotations"])
                } else if (type in setOf("text", "image", "audio")) {
                    val valueKey = if (type == "text") "text" else "data"
                    val incoming = JsonObject(delta.filterKeys { it != "type" } + ("type" to JsonPrimitive(type)))
                    val last = contents.lastOrNull() as? JsonObject
                    if (last?.string("type") == type) {
                        contents[contents.lastIndex] = JsonObject(mergeObject(last, incoming) +
                            (valueKey to JsonPrimitive(last.string(valueKey).orEmpty() + incoming.string(valueKey).orEmpty())))
                    } else contents += incoming
                }
                step = JsonObject(step + ("content" to JsonArray(contents)))
            }
            "function_call" -> {
                step = mergeObject(step, JsonObject(delta.filterKeys {
                    it != "type" && it != "arguments" && it != "partial_arguments"
                }))
                val fragment = delta.string("partial_arguments") ?: delta.string("arguments")
                if (fragment != null) {
                    val accumulated = argumentFragments[index].orEmpty() + fragment
                    argumentFragments[index] = accumulated
                    val args = runCatching { json.parseToJsonElement(accumulated) }.getOrNull()
                    if (args is JsonObject) step = JsonObject(step + ("arguments" to args))
                } else step = mergeObject(step, JsonObject(delta.filterKeys { it != "type" }))
            }
            else -> {
                val result = delta["result"] as? JsonArray
                val incoming = JsonObject(delta.filterKeys { it != "type" && it != "result" })
                step = mergeObject(step, incoming)
                if (result != null) {
                    val existing = (step["result"] as? JsonArray).orEmpty()
                    step = JsonObject(step + ("result" to JsonArray((existing + result).distinct())))
                }
            }
        }
        steps[index] = step
    }

    private fun thoughtPart(index: Int, step: JsonObject): UIMessagePart.Reasoning {
        val text = summaryText(step["summary"] ?: step["thought_summary"] ?: step["content"])
            .ifEmpty { step.string("text").orEmpty() }
        val emitted = emittedThoughts[index].orEmpty()
        val newText = if (text.startsWith(emitted)) text.removePrefix(emitted) else ""
        emittedThoughts[index] = text
        val segment = displaySegment(thoughtSegments, index, newText.isNotEmpty())
        val metadata = metadata(index).toMutableMap()
        metadata["google_interactions_reasoning_id"] = JsonPrimitive(segment.id)
        return UIMessagePart.Reasoning(
            reasoning = newText,
            createdAt = segment.createdAt,
            finishedAt = if (index in stopped) Clock.System.now() else null,
            metadata = JsonObject(metadata),
        )
    }

    private fun contentPart(content: JsonObject, index: Int): UIMessagePart? = when (content.string("type") ?: "text") {
        "text" -> content.string("text")?.takeIf { it.isNotEmpty() }?.let { UIMessagePart.Text(it, textMetadata(index, visible = true)) }
        "image", "audio" -> {
            val data = content.string("data")?.takeIf { it.isNotBlank() }
            val url = data?.let { if (it.startsWith("data:")) it else "data:${content.string("mime_type") ?: if (content.string("type") == "audio") "audio/mp3" else "image/png"};base64,$it" }
            if (url == null) null else if (content.string("type") == "audio") UIMessagePart.Audio(url, metadata(index)) else UIMessagePart.Image(url, metadata(index))
        }
        else -> null
    }

    private fun displaySegment(segments: MutableMap<Int, DisplaySegment>, index: Int, visible: Boolean): DisplaySegment {
        val previous = segments[index]
        val segment = if (previous == null || (visible && lastVisibleSegmentId != previous.id)) {
            DisplaySegment("$turnId:$index:${nextSegment++}", Clock.System.now()).also { segments[index] = it }
        } else previous
        if (visible) lastVisibleSegmentId = segment.id
        return segment
    }

    private fun textMetadata(index: Int, visible: Boolean): JsonObject = JsonObject(metadata(index) +
        ("google_interactions_text_id" to JsonPrimitive(displaySegment(textSegments, index, visible).id)))

    private fun searchPart(index: Int): UIMessagePart.WebSearch {
        val step = steps[index] ?: JsonObject(emptyMap())
        val type = step.string("type").orEmpty()
        val toolType = type.removeSuffix("_call").removeSuffix("_result")
        val id = (if (type.endsWith("_result")) step.string("call_id") else step.string("id"))?.takeIf { it.isNotBlank() }
        val related = steps.filter { (_, other) ->
            other.string("type") in setOf("${toolType}_call", "${toolType}_result") && id != null &&
                (other.string("id") == id || other.string("call_id") == id)
        }.ifEmpty { mapOf(index to step) }
        val call = related.values.firstOrNull { it.string("type") == "${toolType}_call" }
        val resultEntry = related.entries.firstOrNull { it.value.string("type") == "${toolType}_result" }
        val callId = "google_interactions:$toolType:$turnId:${id ?: "index_$index"}"
        val failed = related.values.any { it["is_error"]?.jsonPrimitiveOrNull?.booleanOrNull == true } || callId in failedSearches
        if (failed) failedSearches += callId
        val complete = resultEntry != null && resultEntry.key in stopped
        val status = when { failed -> WebSearchStatus.Failed; complete -> WebSearchStatus.Completed; else -> WebSearchStatus.Searching }
        val args = call?.get("arguments") as? JsonObject
        return UIMessagePart.WebSearch(
            callId = callId,
            status = status,
            action = if (toolType == "url_context") WebSearchAction.OpenPage else WebSearchAction.Search,
            queries = (args?.get("queries") as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }.filter { it.isNotBlank() }.distinct(),
            url = (args?.get("urls") as? JsonArray)?.firstOrNull()?.jsonPrimitiveOrNull?.contentOrNull,
            createdAt = related.keys.minOrNull()?.let { createdAt[it] } ?: Clock.System.now(),
            finishedAt = if (failed || complete) Clock.System.now() else null,
            metadata = GoogleThoughtMetadata(
                interactionStep = step,
                interactionSteps = related.map { (idx, raw) -> GoogleInteractionStep(turnId, idx, raw, revisions[idx] ?: 0) },
            ).toMetadata(),
        )
    }

    private fun metadata(index: Int): JsonObject {
        val step = steps[index] ?: JsonObject(emptyMap())
        return GoogleThoughtMetadata(
            thoughtSignature = step.string("signature") ?: step.string("thought_signature"),
            interactionStep = step,
            interactionSteps = listOf(GoogleInteractionStep(turnId, index, step, revisions[index] ?: 0)),
        ).toMetadata()
    }

    private fun collectCitations(element: JsonElement?) {
        when (element) {
            is JsonArray -> element.forEach(::collectCitations)
            is JsonObject -> {
                if (element.string("type") == "url_citation") {
                    val url = element.string("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return
                    citations[url] = UIMessageAnnotation.UrlCitation(element.string("title")?.takeIf { it.isNotBlank() } ?: url, url)
                }
                element["annotations"]?.let(::collectCitations)
            }
            else -> Unit
        }
    }
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitiveOrNull?.contentOrNull

private fun summaryText(element: JsonElement?): String = when (element) {
    is JsonArray -> element.joinToString("\n") { summaryText(it) }
    is JsonObject -> element.string("text").orEmpty()
    is JsonPrimitive -> element.contentOrNull.orEmpty()
    else -> ""
}

private fun mergeObject(old: JsonObject, incoming: JsonObject): JsonObject = JsonObject(old.toMutableMap().apply {
    incoming.forEach { (key, value) ->
        val previous = this[key]
        this[key] = if (previous is JsonObject && value is JsonObject) mergeObject(previous, value) else value
    }
})
