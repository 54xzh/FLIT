package me.rerere.ai.provider.providers.openai

import me.rerere.ai.registry.RemoteModelCapability

fun interface OpenRouterModelCapabilityProvider {
    suspend fun resolve(modelId: String): RemoteModelCapability?
}
