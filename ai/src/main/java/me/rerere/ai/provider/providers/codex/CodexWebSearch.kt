package me.rerere.ai.provider.providers.codex

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema

internal const val CODEX_WEB_NAMESPACE = "web"
internal const val CODEX_WEB_RUN_NAME = "run"
internal const val CODEX_WEB_RUN_TOOL_NAME = "$CODEX_WEB_NAMESPACE.$CODEX_WEB_RUN_NAME"

internal const val CODEX_WEB_RUN_DESCRIPTION = """
Use this tool for live web research, opening pages, image lookup, screenshots, finance, weather, sports, and time.
The search mode is always live. Combine only the commands needed for the task. Results may contain an error object;
when that happens, do not invent sources or claim that online research succeeded. You may retry with a better query when retryable is true.
"""

/**
 * Codex 服务端通过 Responses 的内置 web_search 工具注册搜索能力。web.run 是服务端预留
 * 的内部实现名，不能作为自定义 function/namespace 下发。
 */
internal const val CODEX_WEB_RUN_SYSTEM_PROMPT = """
## Built-in web search

Live web search is available in this turn. Use it whenever the user asks to search, browse, verify current
information, open a web page, or obtain image, finance, weather, sports, or time data. Do not say that you cannot
access the internet when web search can answer the request.

Search is always live; do not request cached or indexed modes. Cite the returned sources in your answer. If a web
search cannot complete, do not invent sources or claim that online research succeeded.
"""

/**
 * Codex 预留的 web.run 工具参数，供本地工具循环验证和执行调用使用。
 *
 * 不能把这个 schema 放进 Responses 请求的 tools：Codex 服务端已为该预留工具配置了固定 schema。
 */
internal fun codexWebRunParameters(): InputSchema.Obj {
    return InputSchema.Obj(
        properties = buildJsonObject {
            put("search_query", commandArrayProperty(
                "Search the live web.",
                buildJsonObject {
                    put("q", stringProperty("Search query."))
                    put("domains", stringArrayProperty("Optional domains to limit results to."))
                    put("recency", numberProperty("Optional recency limit in days."))
                },
            ))
            put("image_query", commandArrayProperty(
                "Search for images on the live web.",
                buildJsonObject {
                    put("q", stringProperty("Image search query."))
                    put("domains", stringArrayProperty("Optional domains to limit results to."))
                    put("recency", numberProperty("Optional recency limit in days."))
                },
            ))
            put("open", commandArrayProperty(
                "Open a result or URL.",
                buildJsonObject {
                    put("ref_id", stringProperty("Search result reference ID or URL."))
                    put("lineno", numberProperty("Optional line number."))
                },
            ))
            put("click", commandArrayProperty(
                "Open a numbered link from an opened page.",
                buildJsonObject {
                    put("ref_id", stringProperty("Opened page reference ID."))
                    put("id", numberProperty("Link ID."))
                },
            ))
            put("find", commandArrayProperty(
                "Find text in an opened page.",
                buildJsonObject {
                    put("ref_id", stringProperty("Opened page reference ID."))
                    put("pattern", stringProperty("Text to find."))
                },
            ))
            put("screenshot", commandArrayProperty(
                "Take a screenshot of a PDF page.",
                buildJsonObject {
                    put("ref_id", stringProperty("PDF reference ID."))
                    put("pageno", numberProperty("Zero-based PDF page number."))
                },
            ))
            put("finance", commandArrayProperty(
                "Look up an equity, fund, index, or cryptocurrency price.",
                buildJsonObject {
                    put("ticker", stringProperty("Ticker symbol."))
                    put("type", stringProperty("Asset type."))
                    put("market", stringProperty("Market or country code."))
                },
            ))
            put("weather", commandArrayProperty(
                "Get a weather forecast.",
                buildJsonObject {
                    put("location", stringProperty("Location."))
                    put("duration", numberProperty("Optional forecast duration in days."))
                    put("start", stringProperty("Optional start date in YYYY-MM-DD."))
                },
            ))
            put("sports", commandArrayProperty(
                "Get sports standings or schedules.",
                buildJsonObject {
                    put("fn", stringProperty("standings or schedule."))
                    put("league", stringProperty("League identifier."))
                    put("locale", stringProperty("Optional locale."))
                    put("num_games", numberProperty("Optional result count."))
                    put("team", stringProperty("Optional team."))
                    put("opponent", stringProperty("Optional opponent."))
                    put("date_from", stringProperty("Optional start date."))
                    put("date_to", stringProperty("Optional end date."))
                },
            ))
            put("time", commandArrayProperty(
                "Get the current time for a UTC offset.",
                buildJsonObject {
                    put("utc_offset", stringProperty("UTC offset, for example +08:00."))
                },
            ))
            put("response_length", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Requested result detail: short, medium, or long."))
            })
        },
    )
}

private fun commandArrayProperty(description: String, properties: JsonObject): JsonObject {
    return buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("description", JsonPrimitive(description))
        putJsonObject("items") {
            put("type", "object")
            put("properties", properties)
        }
    }
}

private fun stringProperty(description: String): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("string"))
    put("description", JsonPrimitive(description))
}

private fun numberProperty(description: String): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("number"))
    put("description", JsonPrimitive(description))
}

private fun stringArrayProperty(description: String): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("array"))
    put("description", JsonPrimitive(description))
    putJsonObject("items") { put("type", "string") }
}
