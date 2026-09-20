package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.provider.ProviderSetting
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val VERCEL_AI_GATEWAY_HOST = "ai-gateway.vercel.sh"

enum class OpenAIDecisionProtocol {
    TYPESAFE,
    VERCEL_AI_GATEWAY,
}

data class OpenAIDecisionEndpoint(
    val url: String,
    val protocol: OpenAIDecisionProtocol,
)

fun ProviderSetting.OpenAI.resolveDecisionEndpoint(): OpenAIDecisionEndpoint {
    val baseUrl = normalizedDecisionBaseUrl()
    val parsedUrl = baseUrl.toHttpUrlOrNull()
    val isVercelGateway = parsedUrl?.host.equals(VERCEL_AI_GATEWAY_HOST, ignoreCase = true)
    val pathSegments = parsedUrl?.pathSegments.orEmpty().filter { it.isNotBlank() }
    val isTypeSafeEndpoint = pathSegments.any { it.equals("typesafe", ignoreCase = true) }
    val hasVersionPath = pathSegments.lastOrNull()?.equals("v1", ignoreCase = true) == true

    return if (isVercelGateway && !isTypeSafeEndpoint) {
        val versionedBaseUrl = if (hasVersionPath) baseUrl else "$baseUrl/v1"
        OpenAIDecisionEndpoint(
            url = "$versionedBaseUrl/evaluate",
            protocol = OpenAIDecisionProtocol.VERCEL_AI_GATEWAY,
        )
    } else {
        val versionedBaseUrl = if (isVercelGateway && isTypeSafeEndpoint && !hasVersionPath) {
            "$baseUrl/v1"
        } else {
            baseUrl
        }
        OpenAIDecisionEndpoint(
            url = "$versionedBaseUrl/systemone",
            protocol = OpenAIDecisionProtocol.TYPESAFE,
        )
    }
}

fun ProviderSetting.OpenAI.resolveModelsEndpoint(): String {
    val baseUrl = normalizedDecisionBaseUrl()
    val parsedUrl = baseUrl.toHttpUrlOrNull()
    val isVercelGateway = parsedUrl?.host.equals(VERCEL_AI_GATEWAY_HOST, ignoreCase = true)
    val pathSegments = parsedUrl?.pathSegments.orEmpty().filter { it.isNotBlank() }
    val hasVersionPath = pathSegments.lastOrNull()?.equals("v1", ignoreCase = true) == true
    val versionedBaseUrl = if (isVercelGateway && !hasVersionPath) {
        "$baseUrl/v1"
    } else {
        baseUrl
    }
    return "$versionedBaseUrl/models"
}

internal fun JsonObject.adaptDecisionRequestBody(
    protocol: OpenAIDecisionProtocol,
): JsonObject {
    if (protocol != OpenAIDecisionProtocol.VERCEL_AI_GATEWAY) return this
    val questions = this["questions"] as? JsonObject ?: return this
    val adaptedQuestions = JsonObject(
        questions.mapValues { (_, question) ->
            val questionObject = question as? JsonObject ?: return@mapValues question
            val type = questionObject["type"]?.jsonPrimitiveOrNull?.contentOrNull
            if (!type.equals("noul", ignoreCase = true)) {
                questionObject
            } else {
                JsonObject(
                    questionObject.toMutableMap().apply {
                        this["type"] = JsonPrimitive("boolean")
                    }
                )
            }
        }
    )
    return JsonObject(
        toMutableMap().apply {
            this["questions"] = adaptedQuestions
        }
    )
}

internal fun JsonObject.normalizeDecisionAnswers(
    protocol: OpenAIDecisionProtocol,
): JsonObject {
    if (protocol != OpenAIDecisionProtocol.VERCEL_AI_GATEWAY) return this
    return JsonObject(
        mapValues { (_, answer) ->
            val answerObject = answer as? JsonObject ?: return@mapValues answer
            val type = answerObject["type"]?.jsonPrimitiveOrNull?.contentOrNull
            val probability = answerObject["probability"] ?: return@mapValues answerObject
            if (!type.equals("boolean", ignoreCase = true)) {
                answerObject
            } else {
                JsonObject(
                    answerObject.toMutableMap().apply {
                        this["type"] = JsonPrimitive("noul")
                        this["noul"] = probability
                    }
                )
            }
        }
    )
}

private fun ProviderSetting.OpenAI.normalizedDecisionBaseUrl(): String {
    return baseUrl.trim().trimEnd('/')
}
