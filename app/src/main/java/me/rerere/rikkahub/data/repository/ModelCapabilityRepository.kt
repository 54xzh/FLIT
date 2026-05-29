package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelCapabilitySource
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.openai.OpenRouterModelCapabilityProvider
import me.rerere.ai.registry.ModelsDevCapabilityParser
import me.rerere.ai.registry.RemoteModelCapability
import me.rerere.ai.registry.RemoteModelCapabilityCache
import me.rerere.ai.registry.RemoteModelNameResolver
import me.rerere.ai.registry.RemoteModelCapabilityResolver
import me.rerere.ai.registry.ModelRegistry
import me.rerere.common.http.await
import me.rerere.rikkahub.data.datastore.ModelCapabilityStore
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.time.Clock

private const val TAG = "ModelCapabilityRepo"
private const val OPENROUTER_PROVIDER_ID = "openrouter"
private const val MODELS_DEV_API_URL = "https://models.dev/api.json"
private const val REFRESH_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L

class ModelCapabilityRepository(
    private val client: OkHttpClient,
    private val store: ModelCapabilityStore,
    private val json: Json = JsonInstant,
) {
    private val refreshMutex = Mutex()

    suspend fun refreshOpenRouterIfStale(force: Boolean = false): Result<RemoteModelCapabilityCache> =
        refreshMutex.withLock {
            val current = store.getOpenRouterCache()
            if (!force && current != null && !current.isStale()) {
                return@withLock Result.success(current)
            }

            runCatching {
                fetchOpenRouterCache()
            }.onSuccess { cache ->
                store.saveOpenRouterCache(cache)
            }.onFailure {
                Log.w(TAG, "Failed to refresh OpenRouter capabilities", it)
            }.recoverCatching {
                if (force) {
                    throw it
                }
                current ?: throw it
            }
        }

    suspend fun getOpenRouterCache(): RemoteModelCapabilityCache? = store.getOpenRouterCache()

    suspend fun getOpenRouterResolver(): RemoteModelCapabilityResolver? =
        getOpenRouterCache()?.resolver()

    suspend fun getOpenRouterNameResolver(): RemoteModelNameResolver? =
        getOpenRouterCache()?.nameResolver()

    suspend fun resolveOpenRouterCapability(modelId: String): RemoteModelCapability? =
        getOpenRouterResolver()?.resolve(modelId)?.capabilityOrNull

    suspend fun resolveOpenRouterDisplayName(modelId: String): String? =
        getOpenRouterNameResolver()?.resolveDisplayName(modelId)

    suspend fun resolveDisplayNameForProvider(modelId: String, provider: ProviderSetting): String? {
        return if (provider.canUseRemoteModelCapabilityDefaults()) {
            resolveOpenRouterDisplayName(modelId)
        } else {
            null
        }
    }

    fun applyCapability(model: Model, capability: RemoteModelCapability): Model {
        return model.copy(
            inputModalities = capability.inputModalities,
            outputModalities = capability.outputModalities,
            abilities = capability.abilities,
            capabilitySource = ModelCapabilitySource.AUTO,
        )
    }

    suspend fun applyOpenRouterCapability(model: Model): Model {
        val capability = resolveOpenRouterCapability(model.modelId) ?: return model.withRegistryCapabilities()
        return applyCapability(model, capability)
    }

    suspend fun applyCapabilitiesForProvider(model: Model, provider: ProviderSetting): Model {
        return if (provider.canUseRemoteModelCapabilityDefaults()) {
            applyOpenRouterCapability(model)
        } else {
            model.withRegistryCapabilities()
        }
    }

    suspend fun applyNewModelDefaultsForProvider(model: Model, provider: ProviderSetting): Model {
        val withCapabilities = applyCapabilitiesForProvider(model, provider)
        if (!provider.canUseRemoteModelCapabilityDefaults()) {
            return withCapabilities
        }
        val displayName = resolveDisplayNameForProvider(withCapabilities.modelId, provider)
            ?: withCapabilities.modelId
        return withCapabilities.copy(displayName = displayName)
    }

    suspend fun applyOpenRouterCapabilitiesToProvider(provider: ProviderSetting): ProviderSetting {
        if (!provider.canUseRemoteModelCapabilityDefaults()) return provider

        val resolver = getOpenRouterResolver() ?: return provider
        val updatedModels = provider.models.map { model ->
            if (model.capabilitySource != ModelCapabilitySource.AUTO) {
                model
            } else {
                resolver.resolve(model.modelId).capabilityOrNull
                    ?.let { applyCapability(model, it) }
                    ?: model
            }
        }

        return provider.copyProvider(models = updatedModels)
    }

    private suspend fun fetchOpenRouterCache(): RemoteModelCapabilityCache = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(MODELS_DEV_API_URL)
            .get()
            .build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to fetch models.dev: ${response.code}")
        }

        val body = response.body?.string().orEmpty()
        val root = json.parseToJsonElement(body)
        val capabilities = ModelsDevCapabilityParser.parseOpenRouterCapabilities(root)
        if (capabilities.isEmpty()) {
            error("models.dev OpenRouter capabilities are empty")
        }

        RemoteModelCapabilityCache(
            providerId = OPENROUTER_PROVIDER_ID,
            sourceUrl = MODELS_DEV_API_URL,
            updatedAtMillis = Clock.System.now().toEpochMilliseconds(),
            sourceModelCount = capabilities.size,
            capabilities = capabilities,
        )
    }

    private fun RemoteModelCapabilityCache.isStale(): Boolean {
        val age = Clock.System.now().toEpochMilliseconds() - updatedAtMillis
        return age >= REFRESH_INTERVAL_MILLIS
    }
}

class CachedOpenRouterModelCapabilityProvider(
    private val repository: ModelCapabilityRepository,
) : OpenRouterModelCapabilityProvider {
    override suspend fun resolve(modelId: String): RemoteModelCapability? {
        return repository.resolveOpenRouterCapability(modelId)
    }
}

fun ProviderSetting.canUseRemoteModelCapabilityDefaults(): Boolean {
    return this is ProviderSetting.OpenAI
}

fun Model.withRegistryCapabilities(): Model {
    val inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId)
    val outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(modelId)
    val abilities = ModelRegistry.MODEL_ABILITIES.getData(modelId)
    return copy(
        inputModalities = inputModalities,
        outputModalities = outputModalities,
        abilities = abilities,
        capabilitySource = ModelCapabilitySource.AUTO,
    )
}

fun Model.markCapabilitiesManual(): Model {
    return copy(capabilitySource = ModelCapabilitySource.MANUAL)
}

fun Model.withSafeChatDefaults(): Model {
    return when (type) {
        ModelType.CHAT, ModelType.IMAGE -> copy(
            inputModalities = inputModalities.ifEmpty { listOf(Modality.TEXT) },
            outputModalities = outputModalities.ifEmpty { listOf(Modality.TEXT) },
        )

        ModelType.EMBEDDING -> this
    }
}
