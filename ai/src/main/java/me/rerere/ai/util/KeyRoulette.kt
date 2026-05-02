package me.rerere.ai.util

import me.rerere.ai.provider.ProviderKeyStrategy
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.activeApiKeyValuesForRequest
import me.rerere.ai.provider.getApiKeyValue
import me.rerere.ai.provider.getProviderKeyStrategy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

interface KeyRoulette {
    fun next(keys: String): String
    fun next(providerSetting: ProviderSetting): String
    fun next(
        ownerId: String,
        keys: List<String>,
        strategy: ProviderKeyStrategy,
        fallback: String = "",
    ): String

    companion object {
        fun default(): KeyRoulette = DefaultKeyRoulette()
    }
}

private val SPLIT_KEY_REGEX=  "[\\s,]+".toRegex()//空格换行和逗号

private fun splitKey(key: String): List<String> {
    return key
        .split(SPLIT_KEY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

private class DefaultKeyRoulette : KeyRoulette {
    private val roundRobinCounters = ConcurrentHashMap<String, AtomicInteger>()

    override fun next(keys: String): String {
        val keyList = splitKey(keys)
        return next(
            ownerId = keys,
            keys = keyList,
            strategy = ProviderKeyStrategy.RANDOM,
            fallback = keys,
        )
    }

    override fun next(providerSetting: ProviderSetting): String {
        return next(
            ownerId = providerSetting.id.toString(),
            keys = providerSetting.activeApiKeyValuesForRequest(),
            strategy = providerSetting.getProviderKeyStrategy(),
            fallback = providerSetting.getApiKeyValue(),
        )
    }

    override fun next(
        ownerId: String,
        keys: List<String>,
        strategy: ProviderKeyStrategy,
        fallback: String,
    ): String {
        val keyList = keys
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (keyList.isEmpty()) return fallback

        return when (strategy) {
            ProviderKeyStrategy.RANDOM -> keyList.random()
            ProviderKeyStrategy.ROUND_ROBIN -> {
                val counter = roundRobinCounters.getOrPut(ownerId) {
                    AtomicInteger(0)
                }
                val index = counter.getAndUpdate { current ->
                    if (current == Int.MAX_VALUE) 0 else current + 1
                }
                keyList[Math.floorMod(index, keyList.size)]
            }
        }
    }
}
