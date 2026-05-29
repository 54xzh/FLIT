package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.rerere.ai.registry.RemoteModelCapabilityCache
import me.rerere.rikkahub.utils.JsonInstant

private const val TAG = "ModelCapabilityStore"

private val Context.modelCapabilityStore by preferencesDataStore(name = "model_capabilities")

class ModelCapabilityStore(
    private val context: Context,
) {
    private val dataStore = context.modelCapabilityStore

    private object Keys {
        val OPENROUTER_CACHE = stringPreferencesKey("openrouter_cache")
    }

    val openRouterCacheFlow: Flow<RemoteModelCapabilityCache?> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[Keys.OPENROUTER_CACHE]?.let { raw ->
                runCatching {
                    JsonInstant.decodeFromString<RemoteModelCapabilityCache>(raw)
                }.onFailure {
                    Log.w(TAG, "Failed to decode OpenRouter capability cache", it)
                }.getOrNull()
            }
        }

    suspend fun getOpenRouterCache(): RemoteModelCapabilityCache? = withContext(Dispatchers.IO) {
        openRouterCacheFlow
            .map { it }
            .catch { emit(null) }
            .first()
    }

    suspend fun saveOpenRouterCache(cache: RemoteModelCapabilityCache) = withContext(Dispatchers.IO) {
        if (cache.capabilities.isEmpty()) {
            Log.w(TAG, "Skip saving empty OpenRouter capability cache")
            return@withContext
        }

        dataStore.edit { preferences ->
            preferences[Keys.OPENROUTER_CACHE] = JsonInstant.encodeToString(cache)
        }
    }
}
