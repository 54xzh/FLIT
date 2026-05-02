package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

private val PROVIDER_API_KEY_SPLIT_REGEX = "[\\s,]+".toRegex()

@Serializable
data class ProviderApiKey(
    val id: Uuid = Uuid.random(),
    val value: String = "",
    val enabled: Boolean = true,
    val alias: String = "",
)

@Serializable
enum class ProviderKeyStrategy {
    @SerialName("random")
    RANDOM,

    @SerialName("round_robin")
    ROUND_ROBIN,
}

fun splitProviderApiKeys(raw: String): List<String> {
    return raw
        .split(PROVIDER_API_KEY_SPLIT_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

fun List<ProviderApiKey>.normalizedProviderApiKeys(): List<ProviderApiKey> {
    val seen = mutableSetOf<String>()
    return map { key ->
        key.copy(
            value = key.value.trim(),
            alias = key.alias.trim(),
        )
    }
        .filter { it.value.isNotBlank() }
        .filter { seen.add(it.value) }
}

fun ProviderSetting.getApiKeyValue(): String = when (this) {
    is ProviderSetting.OpenAI -> apiKey
    is ProviderSetting.Google -> apiKey
    is ProviderSetting.Claude -> apiKey
}

fun ProviderSetting.isMultiKeyEnabled(): Boolean = when (this) {
    is ProviderSetting.OpenAI -> multiKeyEnabled
    is ProviderSetting.Google -> multiKeyEnabled
    is ProviderSetting.Claude -> multiKeyEnabled
}

fun ProviderSetting.getProviderApiKeys(): List<ProviderApiKey> = when (this) {
    is ProviderSetting.OpenAI -> apiKeys
    is ProviderSetting.Google -> apiKeys
    is ProviderSetting.Claude -> apiKeys
}

fun ProviderSetting.getProviderKeyStrategy(): ProviderKeyStrategy = when (this) {
    is ProviderSetting.OpenAI -> keyStrategy
    is ProviderSetting.Google -> keyStrategy
    is ProviderSetting.Claude -> keyStrategy
}

fun ProviderSetting.getLegacyApiKeyBackup(): String = when (this) {
    is ProviderSetting.OpenAI -> legacyApiKeyBackup
    is ProviderSetting.Google -> legacyApiKeyBackup
    is ProviderSetting.Claude -> legacyApiKeyBackup
}

fun ProviderSetting.copyWithApiKeyConfig(
    apiKey: String = getApiKeyValue(),
    multiKeyEnabled: Boolean = isMultiKeyEnabled(),
    apiKeys: List<ProviderApiKey> = getProviderApiKeys(),
    keyStrategy: ProviderKeyStrategy = getProviderKeyStrategy(),
    legacyApiKeyBackup: String = getLegacyApiKeyBackup(),
): ProviderSetting = when (this) {
    is ProviderSetting.OpenAI -> copy(
        apiKey = apiKey,
        multiKeyEnabled = multiKeyEnabled,
        apiKeys = apiKeys,
        keyStrategy = keyStrategy,
        legacyApiKeyBackup = legacyApiKeyBackup,
    )

    is ProviderSetting.Google -> copy(
        apiKey = apiKey,
        multiKeyEnabled = multiKeyEnabled,
        apiKeys = apiKeys,
        keyStrategy = keyStrategy,
        legacyApiKeyBackup = legacyApiKeyBackup,
    )

    is ProviderSetting.Claude -> copy(
        apiKey = apiKey,
        multiKeyEnabled = multiKeyEnabled,
        apiKeys = apiKeys,
        keyStrategy = keyStrategy,
        legacyApiKeyBackup = legacyApiKeyBackup,
    )
}

fun ProviderSetting.syncEnabledApiKeysToLegacyField(): ProviderSetting {
    if (!isMultiKeyEnabled()) return this

    val normalizedKeys = getProviderApiKeys().normalizedProviderApiKeys()
    val enabledKeys = normalizedKeys
        .filter { it.enabled }
        .joinToString(",") { it.value }

    return copyWithApiKeyConfig(
        apiKey = enabledKeys,
        apiKeys = normalizedKeys,
    )
}

fun ProviderSetting.normalizeProviderApiKeys(): ProviderSetting {
    val rawApiKey = getApiKeyValue().trim()
    val existingKeys = getProviderApiKeys().normalizedProviderApiKeys()
    val legacyKeys = splitProviderApiKeys(rawApiKey)
    val shouldImportLegacy = existingKeys.isEmpty() && (isMultiKeyEnabled() || legacyKeys.size > 1)
    val normalizedKeys = if (shouldImportLegacy) {
        legacyKeys.map { value ->
            ProviderApiKey(value = value)
        }
    } else {
        existingKeys
    }
    val shouldEnableMultiKey = isMultiKeyEnabled() || (shouldImportLegacy && normalizedKeys.size > 1)
    val backup = getLegacyApiKeyBackup().ifBlank {
        rawApiKey.takeIf { legacyKeys.size > 1 }.orEmpty()
    }

    return copyWithApiKeyConfig(
        apiKey = rawApiKey,
        multiKeyEnabled = shouldEnableMultiKey,
        apiKeys = normalizedKeys,
        legacyApiKeyBackup = backup,
    ).syncEnabledApiKeysToLegacyField()
}

fun ProviderSetting.activeApiKeyValuesForRequest(): List<String> {
    if (!isMultiKeyEnabled()) return emptyList()
    return getProviderApiKeys()
        .normalizedProviderApiKeys()
        .filter { it.enabled }
        .map { it.value }
}

fun ProviderSetting.withSingleApiKeyForRequest(apiKey: String): ProviderSetting {
    return copyWithApiKeyConfig(
        apiKey = apiKey.trim(),
        multiKeyEnabled = false,
        apiKeys = emptyList(),
    )
}
