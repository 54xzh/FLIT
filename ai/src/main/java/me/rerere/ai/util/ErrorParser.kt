package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class HttpException(
    message: String
) : RuntimeException(message)

private data class ParsedErrorDetail(
    val message: String,
    val statusCode: Int? = null,
)

private val RATE_LIMIT_MARKERS = setOf(
    "rate_limit_error",
    "rate_limit_exceeded",
    "too_many_requests",
    "too many requests",
    "resource_exhausted",
)

fun JsonElement.parseErrorDetail(): Exception {
    val detail = extractErrorDetail()
    return detail.statusCode?.let { statusCode ->
        HttpStatusException(
            statusCode = statusCode,
            message = detail.message,
        )
    } ?: HttpException(detail.message)
}

fun String.isLikelySsePayload(): Boolean {
    return lineSequence()
        .map { it.trimStart() }
        .firstOrNull { it.isNotBlank() }
        ?.startsWith("data:") == true
}

private fun JsonElement.extractErrorDetail(): ParsedErrorDetail {
    return when (this) {
        is JsonObject -> extractErrorDetailFromObject()
        is JsonArray -> firstOrNull()?.extractErrorDetail()
            ?: ParsedErrorDetail("Unknown error: Empty JSON array")
        is JsonPrimitive -> {
            val message = jsonPrimitive.contentOrNull ?: toString()
            ParsedErrorDetail(
                message = message,
                statusCode = message.toStatusCodeOrNull(),
            )
        }
    }
}

private fun JsonObject.extractErrorDetailFromObject(): ParsedErrorDetail {
    val nestedDetail = listOf("error", "detail")
        .asSequence()
        .mapNotNull { key -> get(key) }
        .map { value -> value.extractErrorDetail() }
        .firstOrNull()

    val ownMessage = listOf("message", "description")
        .asSequence()
        .mapNotNull { key -> get(key)?.extractMessageOrNull() }
        .firstOrNull()

    val serialized = Json.encodeToString(JsonElement.serializer(), this)
    val message = ownMessage ?: nestedDetail?.message ?: serialized
    val statusCode = extractStatusCodeOrNull()
        ?: ownMessage?.toStatusCodeOrNull()
        ?: nestedDetail?.statusCode

    return ParsedErrorDetail(
        message = message,
        statusCode = statusCode,
    )
}

private fun JsonObject.extractStatusCodeOrNull(): Int? {
    return listOf("code", "status_code", "statusCode", "status", "type")
        .asSequence()
        .mapNotNull { key -> get(key)?.extractStatusCodeOrNull() }
        .firstOrNull()
}

private fun JsonElement.extractStatusCodeOrNull(): Int? {
    return when (this) {
        is JsonObject -> extractStatusCodeOrNull()
        is JsonArray -> firstOrNull()?.extractStatusCodeOrNull()
        is JsonPrimitive -> jsonPrimitive.contentOrNull?.toStatusCodeOrNull()
    }
}

private fun JsonElement.extractMessageOrNull(): String? {
    return when (this) {
        is JsonObject -> extractErrorDetail().message
        is JsonArray -> firstOrNull()?.extractMessageOrNull()
        is JsonPrimitive -> jsonPrimitive.contentOrNull
    }?.takeIf { message -> message.isNotBlank() }
}

private fun String.toStatusCodeOrNull(): Int? {
    val normalized = trim()
    val normalizedLowercase = normalized.lowercase()
    val numericCode = normalized.toIntOrNull()
    if (numericCode != null && numericCode in 400..599) {
        return numericCode
    }
    if (normalizedLowercase in RATE_LIMIT_MARKERS) {
        return 429
    }
    if ("429" in normalizedLowercase || "too many requests" in normalizedLowercase) {
        return 429
    }
    return null
}
