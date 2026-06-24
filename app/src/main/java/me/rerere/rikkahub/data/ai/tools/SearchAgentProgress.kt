package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/**
 *
 * 执行中用于 UI 实时渲染；完成落地时通过 [toJson] 转回旧版 JsonObject 步骤格式，
 * 保持向后兼容（历史消息的 metadata 仍是 JsonObject）。
 */
sealed class SearchAgentStep {
    /** 第 0 步：主模型派发给搜索子代理的任务。 */
    data class TaskStep(val text: String) : SearchAgentStep()

    /** 内部模型一轮思考（被动出现，默认折叠）。 */
    data class ReasoningStep(val text: String) : SearchAgentStep()

    /** 一次内部工具调用（search_web / scrape_web / 其它）。 */
    data class ToolCallStep(
        val toolName: String,
        val title: String,
        val detail: String,
        val urls: List<String>,
        val status: Status,
    ) : SearchAgentStep() {
        enum class Status { Running, Done }
    }

    /** 错误步骤。 */
    data class ErrorStep(val title: String, val detail: String) : SearchAgentStep()

    /** 收尾"完成总结"步骤。 */
    data class FinalStep(val detail: String) : SearchAgentStep()

    /**
     * 转成落地 metadata 用的旧版 JsonObject 格式：
     * `{type, title, detail, urls}`。status 不写入（落地时一律 Done）。
     */
    fun toJson(): JsonObject = when (this) {
        is TaskStep -> buildJsonObject {
            put("type", "task")
            put("title", "任务")
            put("detail", text)
        }
        is ReasoningStep -> buildJsonObject {
            put("type", "reasoning")
            put("title", "思考")
            put("detail", text)
        }
        is ToolCallStep -> buildJsonObject {
            put("type", "tool")
            put("toolName", toolName)
            put("title", title)
            put("detail", detail)
            put("urls", buildJsonArray {
                urls.distinct().take(8).forEach { add(JsonPrimitive(it)) }
            })
        }
        is ErrorStep -> buildJsonObject {
            put("type", "error")
            put("title", title)
            put("detail", detail)
        }
        is FinalStep -> buildJsonObject {
            put("type", "final")
            put("title", "完成总结")
            put("detail", detail)
        }
    }
}

/**
 * 从 metadata 里的旧版 JsonObject 步骤还原回强类型。
 * status 一律视为 Done（落地数据都是完成态）。
 */
fun JsonObject.toSearchAgentStep(): SearchAgentStep? {
    val type = this["type"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val title = this["title"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val detail = this["detail"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val urls = (this["urls"] as? JsonArray)
        ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
        .orEmpty()
    return when (type) {
        "task" -> SearchAgentStep.TaskStep(text = detail)
        "reasoning" -> SearchAgentStep.ReasoningStep(text = detail)
        "error" -> SearchAgentStep.ErrorStep(title = title, detail = detail)
        "final" -> SearchAgentStep.FinalStep(detail = detail)
        "search", "scrape", "tool" -> SearchAgentStep.ToolCallStep(
            toolName = this["toolName"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
            title = title,
            detail = detail,
            urls = urls,
            status = SearchAgentStep.ToolCallStep.Status.Done,
        )
        else -> null
    }
}

/** 从 metadata 的 steps 数组还原步骤列表。 */
fun parseSearchAgentStepsFromMetadata(metadata: JsonObject?): List<SearchAgentStep> {
    val arr = metadata?.get(SEARCH_AGENT_STEPS_KEY) as? JsonArray ?: return emptyList()
    return arr.mapNotNull { (it as? JsonObject)?.toSearchAgentStep() }
}

const val SEARCH_AGENT_STEPS_KEY = "search_agent_steps"
const val SEARCH_AGENT_METADATA_KEY = "_tool_result_metadata"