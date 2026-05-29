package me.rerere.ai.registry

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility

@Serializable
data class RemoteModelCapability(
    val modelId: String,
    val displayName: String? = null,
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
)

@Serializable
data class RemoteModelCapabilityCache(
    val providerId: String,
    val sourceUrl: String,
    val updatedAtMillis: Long,
    val sourceModelCount: Int,
    val capabilities: List<RemoteModelCapability>,
) {
    fun resolver(): RemoteModelCapabilityResolver = RemoteModelCapabilityResolver(capabilities)

    fun nameResolver(): RemoteModelNameResolver = RemoteModelNameResolver(capabilities)
}

object ModelsDevCapabilityParser {
    private const val OPENROUTER_PROVIDER_ID = "openrouter"

    fun parseOpenRouterCapabilities(root: JsonElement): List<RemoteModelCapability> {
        val rootObject = root as? JsonObject ?: return emptyList()
        val openRouter = rootObject[OPENROUTER_PROVIDER_ID] as? JsonObject ?: return emptyList()
        val models = openRouter["models"] as? JsonObject ?: return emptyList()

        return models.values.mapNotNull { element ->
            val model = element as? JsonObject ?: return@mapNotNull null
            val id = (model["id"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?: return@mapNotNull null
            if (id.isBlank()) return@mapNotNull null

            RemoteModelCapability(
                modelId = id,
                displayName = (model["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() },
                inputModalities = parseModalities(model.modalitiesArray("input")),
                outputModalities = parseModalities(model.modalitiesArray("output")),
                abilities = buildList {
                    if ((model["tool_call"] as? JsonPrimitive)?.booleanOrNull == true) {
                        add(ModelAbility.TOOL)
                    }
                    if ((model["reasoning"] as? JsonPrimitive)?.booleanOrNull == true) {
                        add(ModelAbility.REASONING)
                    }
                },
            )
        }
    }

    private fun JsonObject.modalitiesArray(key: String): JsonArray? {
        val modalities = this["modalities"] as? JsonObject ?: return null
        return modalities[key] as? JsonArray
    }

    private fun parseModalities(values: JsonArray?): List<Modality> {
        val parsed = values
            ?.mapNotNull { element ->
                when ((element as? JsonPrimitive)?.contentOrNull?.lowercase()) {
                    "text" -> Modality.TEXT
                    "image" -> Modality.IMAGE
                    else -> null
                }
            }
            ?.distinct()
            .orEmpty()

        return parsed.ifEmpty { listOf(Modality.TEXT) }
    }
}

class RemoteModelNameResolver(
    capabilities: List<RemoteModelCapability>,
) {
    private val byCanonicalId = capabilities
        .mapNotNull { capability ->
            val displayName = capability.normalizedDisplayName() ?: return@mapNotNull null
            RemoteModelCapabilityResolver.normalizeId(capability.modelId) to displayName
        }
        .distinctBy { (modelId, _) -> modelId }
        .toMap()

    private val byShortId = capabilities
        .groupBy { capability ->
            RemoteModelCapabilityResolver.shortId(
                RemoteModelCapabilityResolver.normalizeId(capability.modelId)
            )
        }
        .filterKeys { it.isNotBlank() }
        .mapNotNull { (shortId, values) ->
            val uniqueCapabilities = values.distinctBy {
                RemoteModelCapabilityResolver.normalizeId(it.modelId)
            }
            if (uniqueCapabilities.size != 1) {
                return@mapNotNull null
            }
            uniqueCapabilities.first().normalizedDisplayName()?.let { displayName ->
                shortId to displayName
            }
        }
        .toMap()

    fun resolveDisplayName(modelId: String): String? {
        val normalized = RemoteModelCapabilityResolver.normalizeId(modelId)
        if (normalized.isBlank()) return null

        byCanonicalId[normalized]?.let { return it }

        if ('/' in normalized) return null
        return byShortId[normalized]
    }

    private fun RemoteModelCapability.normalizedDisplayName(): String? {
        return displayName?.trim()?.takeIf { it.isNotBlank() }
    }
}

sealed class RemoteCapabilityMatch {
    data class Exact(val capability: RemoteModelCapability) : RemoteCapabilityMatch()
    data class Alias(
        val capability: RemoteModelCapability,
        val matchedBy: String,
    ) : RemoteCapabilityMatch()

    data object None : RemoteCapabilityMatch()

    val capabilityOrNull: RemoteModelCapability?
        get() = when (this) {
            is Exact -> capability
            is Alias -> capability
            None -> null
        }
}

class RemoteModelCapabilityResolver(
    capabilities: List<RemoteModelCapability>,
) {
    private val byCanonicalId = capabilities
        .distinctBy { normalizeId(it.modelId) }
        .associateBy { normalizeId(it.modelId) }

    private val byShortId = buildUniqueIndex(capabilities) { capability ->
        shortId(normalizeId(capability.modelId))
    }

    private val bySuffixStrippedCanonicalId = buildUniqueIndex(capabilities) { capability ->
        stripTrailingColonSuffix(normalizeId(capability.modelId))
    }

    private val bySuffixStrippedShortId = buildUniqueIndex(capabilities) { capability ->
        stripTrailingColonSuffix(shortId(normalizeId(capability.modelId)))
    }

    fun resolve(modelId: String): RemoteCapabilityMatch {
        val normalized = normalizeId(modelId)
        if (normalized.isBlank()) return RemoteCapabilityMatch.None

        byCanonicalId[normalized]?.let {
            return RemoteCapabilityMatch.Exact(it)
        }

        candidateIds(normalized).forEach { candidate ->
            byCanonicalId[candidate]?.let {
                return RemoteCapabilityMatch.Alias(it, candidate)
            }
            bySuffixStrippedCanonicalId[candidate]?.let {
                return RemoteCapabilityMatch.Alias(it, candidate)
            }
            byShortId[candidate]?.let {
                return RemoteCapabilityMatch.Alias(it, candidate)
            }
            bySuffixStrippedShortId[candidate]?.let {
                return RemoteCapabilityMatch.Alias(it, candidate)
            }
        }

        return resolveContainment(normalized)
    }

    private fun resolveContainment(normalized: String): RemoteCapabilityMatch {
        val shortCandidates = listOf(
            shortId(normalized),
            stripTrailingColonSuffix(shortId(normalized)),
        ).distinct()

        val matches = byCanonicalId.entries
            .filter { (canonicalId, _) ->
                val canonicalShortId = shortId(canonicalId)
                hasDelimitedSuffix(normalized, canonicalId) ||
                    normalized.endsWith("/$canonicalId") ||
                    normalized.endsWith(":$canonicalId") ||
                    shortCandidates.any { candidate ->
                        hasDelimitedSuffix(candidate, canonicalShortId)
                    }
            }
            .map { it.value }
            .distinctBy { normalizeId(it.modelId) }

        return if (matches.size == 1) {
            RemoteCapabilityMatch.Alias(matches.first(), "containment")
        } else {
            RemoteCapabilityMatch.None
        }
    }

    private fun candidateIds(normalized: String): List<String> = buildList {
        fun addCandidate(value: String) {
            val candidate = value.trim('/')
            if (candidate.isNotBlank() && candidate !in this) {
                add(candidate)
            }
        }

        addCandidate(stripTrailingColonSuffix(normalized))

        val withoutColonPrefix = stripColonPrefix(normalized)
        if (withoutColonPrefix != normalized) {
            addCandidate(withoutColonPrefix)
            addCandidate(stripTrailingColonSuffix(withoutColonPrefix))
        }

        listOf(normalized, withoutColonPrefix).distinct().forEach { value ->
            val segments = value.split('/').filter { it.isNotBlank() }
            for (start in 1 until segments.size) {
                val window = segments.drop(start).joinToString("/")
                addCandidate(window)
                addCandidate(stripTrailingColonSuffix(window))
            }
        }

        addCandidate(shortId(normalized))
        addCandidate(stripTrailingColonSuffix(shortId(normalized)))
    }

    companion object {
        private val SUFFIX_DELIMITERS = setOf('-', '.', '_')

        fun normalizeId(modelId: String): String = modelId.trim().lowercase()

        fun shortId(modelId: String): String = modelId.substringAfterLast('/').trim()

        fun stripTrailingColonSuffix(modelId: String): String {
            val trimmed = modelId.trim()
            val lastSlash = trimmed.lastIndexOf('/')
            val lastColon = trimmed.lastIndexOf(':')
            return if (lastColon > lastSlash) {
                trimmed.substring(0, lastColon).trim()
            } else {
                trimmed
            }
        }

        fun stripColonPrefix(modelId: String): String {
            val trimmed = modelId.trim()
            val firstSlash = trimmed.indexOf('/')
            val firstColon = trimmed.indexOf(':')
            return if (firstColon >= 0 && (firstSlash < 0 || firstColon < firstSlash)) {
                trimmed.substring(firstColon + 1).trim()
            } else {
                trimmed
            }
        }

        private fun hasDelimitedSuffix(value: String, base: String): Boolean {
            if (!value.startsWith(base) || value.length <= base.length) return false
            return value[base.length] in SUFFIX_DELIMITERS
        }

        private fun buildUniqueIndex(
            capabilities: List<RemoteModelCapability>,
            keySelector: (RemoteModelCapability) -> String,
        ): Map<String, RemoteModelCapability> {
            return capabilities
                .groupBy { keySelector(it).trim('/') }
                .filterKeys { it.isNotBlank() }
                .filterValues { values ->
                    values.map { normalizeId(it.modelId) }.distinct().size == 1
                }
                .mapValues { (_, values) -> values.first() }
        }
    }
}
