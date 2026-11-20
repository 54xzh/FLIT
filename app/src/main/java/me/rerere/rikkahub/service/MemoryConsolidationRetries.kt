package me.rerere.rikkahub.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.HttpStatusException
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.shouldRetryHttpRequest
import me.rerere.rikkahub.utils.JsonInstant
import java.security.MessageDigest

private val Context.memoryConsolidationRetryStore by preferencesDataStore(
    name = "memory_consolidation_retries",
)
private val retryStateKey = stringPreferencesKey("retry_state")
private const val MAX_ATTEMPTS = 3
private const val FIRST_RETRY_DELAY_MILLIS = 5 * 60_000L
private const val SECOND_RETRY_DELAY_MILLIS = 30 * 60_000L
// Manual and periodic workers share these locks so each sees the other's recorded failures.
private val modelRequestLocks = List(32) { Mutex() }

internal enum class MemoryRequestFailure {
    MODEL_CONFIGURATION,
    TEMPORARY,
    CONVERSATION,
}

internal fun memoryRequestFailure(error: Exception): MemoryRequestFailure {
    val visited = HashSet<Throwable>()
    val httpError = generateSequence<Throwable>(error) { it.cause }
        .takeWhile(visited::add)
        .filterIsInstance<HttpStatusException>()
        .firstOrNull()
    when (httpError?.statusCode) {
        401, 403, 404 -> return MemoryRequestFailure.MODEL_CONFIGURATION
        400 -> {
            val message = httpError.message.orEmpty().lowercase()
            val rejectedParameter = listOf(
                "thinking_level", "thinkinglevel", "thinking level", "thinking_budget",
                "thinkingbudget", "thinking budget", "reasoning_effort", "temperature",
                "top_p", "topp", "top_k", "topk", "valid levels",
            ).any(message::contains)
            return if (rejectedParameter) {
                MemoryRequestFailure.MODEL_CONFIGURATION
            } else {
                MemoryRequestFailure.CONVERSATION
            }
        }
    }
    return if (shouldRetryHttpRequest(error, attempt = 1, maxRetries = 1)) {
        MemoryRequestFailure.TEMPORARY
    } else {
        MemoryRequestFailure.CONVERSATION
    }
}

internal fun shouldRetryMemoryWork(error: Exception, runAttemptCount: Int): Boolean =
    runAttemptCount < MAX_ATTEMPTS - 1 && memoryRequestFailure(error) == MemoryRequestFailure.TEMPORARY

@Serializable
internal data class FailedMemoryRequest(
    val fingerprint: String,
    val attempts: Int,
    val retryAt: Long,
) {
    fun blocks(fingerprint: String, now: Long): Boolean =
        this.fingerprint == fingerprint && (attempts >= MAX_ATTEMPTS || now < retryAt)

    companion object {
        fun next(
            previous: FailedMemoryRequest?,
            fingerprint: String,
            failure: MemoryRequestFailure,
            now: Long,
        ): FailedMemoryRequest {
            val attempts = if (failure == MemoryRequestFailure.TEMPORARY) {
                val previousAttempts = previous?.takeIf { it.fingerprint == fingerprint }?.attempts ?: 0
                (previousAttempts + 1).coerceAtMost(MAX_ATTEMPTS)
            } else {
                MAX_ATTEMPTS
            }
            val retryAt = when (attempts) {
                1 -> now + FIRST_RETRY_DELAY_MILLIS
                2 -> now + SECOND_RETRY_DELAY_MILLIS
                else -> Long.MAX_VALUE
            }
            return FailedMemoryRequest(fingerprint, attempts, retryAt)
        }
    }
}

@Serializable
private data class ConsolidationRetryState(
    val blockedModels: Set<String> = emptySet(),
    val conversations: Map<String, FailedMemoryRequest> = emptyMap(),
)

internal class MemoryConsolidationRetries(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.memoryConsolidationRetryStore)

    private val stoppedModels = mutableSetOf<String>()
    var hasFailures: Boolean = false
        private set

    suspend fun runRequest(
        modelKey: String,
        conversationId: String,
        prompt: String,
        isManual: Boolean,
        request: suspend () -> Unit,
    ): Boolean = modelRequestLocks[modelKey.hashCode().mod(modelRequestLocks.size)].withLock {
        if (modelKey in stoppedModels) return@withLock false
        val state = store.data.first()[retryStateKey].decodeRetryState()
        val fingerprint = "$modelKey:$prompt".fingerprint()
        val previous = state.conversations[conversationId]
        if (!isManual && (modelKey in state.blockedModels ||
                previous?.blocks(fingerprint, System.currentTimeMillis()) == true)
        ) {
            return@withLock false
        }

        try {
            request()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            hasFailures = true
            val failure = memoryRequestFailure(error)
            if (failure != MemoryRequestFailure.CONVERSATION) stoppedModels += modelKey
            updateState { current ->
                if (failure == MemoryRequestFailure.MODEL_CONFIGURATION) {
                    return@updateState current.copy(
                        blockedModels = ((current.blockedModels - modelKey) + modelKey)
                            .toList().takeLast(128).toSet(),
                    )
                }
                val failedRequest = FailedMemoryRequest.next(
                    current.conversations[conversationId], fingerprint, failure, System.currentTimeMillis(),
                )
                val conversations = (current.conversations - conversationId) + (conversationId to failedRequest)
                current.copy(conversations = conversations.entries.toList().takeLast(1_024)
                    .associate { it.key to it.value })
            }
            throw error
        }

        val wasBlocked = modelKey in state.blockedModels
        if (previous != null || wasBlocked) {
            updateState { current ->
                current.copy(
                    blockedModels = if (wasBlocked) current.blockedModels - modelKey else current.blockedModels,
                    conversations = if (current.conversations[conversationId] == previous) {
                        current.conversations - conversationId
                    } else {
                        current.conversations
                    },
                )
            }
        }
        true
    }

    private suspend fun updateState(update: (ConsolidationRetryState) -> ConsolidationRetryState) {
        store.edit { preferences ->
            val current = preferences[retryStateKey].decodeRetryState()
            preferences[retryStateKey] = JsonInstant.encodeToString(update(current))
        }
    }
}

internal fun consolidationModelKey(provider: ProviderSetting, model: Model): String {
    val requestProvider = provider.copyProvider(
        name = "",
        models = emptyList(),
        quotaGroups = emptyList(),
        tags = emptyList(),
        customIconUri = null,
    )
    val providerJson = JsonInstant.encodeToString<ProviderSetting>(requestProvider)
    val requestModel = model.copy(
        displayName = "",
        providerOverwrite = null,
        iconUrl = null,
        customIconUri = null,
        quota = null,
    )
    val modelJson = JsonInstant.encodeToString(requestModel)
    return "${BuildConfig.VERSION_CODE}\n$providerJson\n$modelJson".fingerprint()
}

private fun String?.decodeRetryState(): ConsolidationRetryState =
    if (this == null) ConsolidationRetryState() else JsonInstant.decodeFromString(this)

private fun String.fingerprint(): String =
    MessageDigest.getInstance("SHA-256").digest(encodeToByteArray()).toHexString()
