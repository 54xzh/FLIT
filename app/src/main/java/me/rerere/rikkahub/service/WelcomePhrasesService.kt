package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.AIRequestLogManager
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_WELCOME_PHRASES_PROMPT
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "WelcomePhrasesService"

class WelcomePhrasesService(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val memoryRepository: MemoryRepository,
    private val requestLogManager: AIRequestLogManager,
) {
    private val mutex = Mutex()
    private val inFlightAssistantIds = mutableSetOf<Uuid>()

    fun enqueueAutoRefreshForCurrentAssistantIfNeeded(context: Context) {
        val settingsSnapshot = settingsStore.settingsFlow.value
        if (settingsSnapshot.init) return
        enqueueAutoRefreshForAssistantIfNeeded(context, settingsSnapshot.assistantId)
    }

    fun enqueueAutoRefreshForAssistantIfNeeded(context: Context, assistantId: Uuid) {
        val settingsSnapshot = settingsStore.settingsFlow.value
        if (settingsSnapshot.init) return

        val assistant = settingsSnapshot.getAssistantById(assistantId) ?: return
        if (!assistant.enableWelcomePhrases) return
        if (assistant.presetMessages.isNotEmpty()) return

        val todayEpochDay = LocalDate.now().toEpochDay()
        val alreadyFresh = assistant.lastWelcomePhrasesRequestEpochDay == todayEpochDay &&
            assistant.welcomePhrases.size == WELCOME_PHRASES_TOTAL
        if (alreadyFresh) return

        val model = settingsSnapshot.findModelById(settingsSnapshot.suggestionModelId) ?: return
        model.findProvider(settingsSnapshot.providers) ?: return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<WelcomePhrasesAutoRefreshWorker>()
            .setInputData(
                Data.Builder()
                    .putString(WelcomePhrasesAutoRefreshWorker.KEY_ASSISTANT_ID, assistantId.toString())
                    .build()
            )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
            .addTag("welcome_phrases_auto_refresh")
            .build()

        val workName = "welcome_phrases_auto_refresh_${assistantId}"
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    suspend fun refreshForCurrentAssistantIfNeeded(maxAttempts: Int = 3): WelcomePhrasesRefreshStatus {
        val settingsSnapshot = settingsStore.settingsFlow.value
        if (settingsSnapshot.init) return WelcomePhrasesRefreshStatus.NotEligible
        return refreshForAssistantIfNeeded(settingsSnapshot.assistantId, maxAttempts = maxAttempts)
    }

    suspend fun refreshForAssistantIfNeeded(
        assistantId: Uuid,
        maxAttempts: Int = 3,
    ): WelcomePhrasesRefreshStatus {
        return refreshForAssistant(assistantId, force = false, maxAttempts = maxAttempts)
    }

    suspend fun forceRefreshForAssistant(
        assistantId: Uuid,
        maxAttempts: Int = 3,
    ): WelcomePhrasesRefreshStatus {
        return refreshForAssistant(assistantId, force = true, maxAttempts = maxAttempts)
    }

    private suspend fun refreshForAssistant(
        assistantId: Uuid,
        force: Boolean,
        maxAttempts: Int,
    ): WelcomePhrasesRefreshStatus {
        val plan = mutex.withLock {
            val settingsSnapshot = settingsStore.settingsFlow.value
            if (settingsSnapshot.init) return@withLock RefreshPlan.Skip(WelcomePhrasesRefreshStatus.NotEligible)

            val assistant = settingsSnapshot.getAssistantById(assistantId)
                ?: return@withLock RefreshPlan.Skip(WelcomePhrasesRefreshStatus.NotEligible)

            if (!assistant.enableWelcomePhrases) return@withLock RefreshPlan.Skip(WelcomePhrasesRefreshStatus.NotEligible)
            if (assistant.presetMessages.isNotEmpty()) return@withLock RefreshPlan.Skip(WelcomePhrasesRefreshStatus.NotEligible)

            val todayEpochDay = LocalDate.now().toEpochDay()
            val alreadyFresh = assistant.lastWelcomePhrasesRequestEpochDay == todayEpochDay &&
                assistant.welcomePhrases.size == WELCOME_PHRASES_TOTAL
            if (!force && alreadyFresh) return@withLock RefreshPlan.Skip(WelcomePhrasesRefreshStatus.UpToDate)

            if (inFlightAssistantIds.contains(assistantId)) {
                return@withLock RefreshPlan.Skip(WelcomePhrasesRefreshStatus.InProgress)
            }

            val model = settingsSnapshot.findModelById(settingsSnapshot.suggestionModelId)
                ?: return@withLock RefreshPlan.Skip(WelcomePhrasesRefreshStatus.NotEligible)
            val provider = model.findProvider(settingsSnapshot.providers)
                ?: return@withLock RefreshPlan.Skip(WelcomePhrasesRefreshStatus.NotEligible)

            inFlightAssistantIds.add(assistantId)
            RefreshPlan.Fetch(
                PendingRequest(
                    assistantId = assistantId,
                    assistantSystemPrompt = assistant.systemPrompt,
                    todayEpochDay = todayEpochDay,
                    model = model,
                    provider = provider,
                    locale = Locale.getDefault(),
                    enableMemory = assistant.enableMemory,
                    ragSimilarityThreshold = assistant.ragSimilarityThreshold,
                    ragIncludeCore = assistant.ragIncludeCore,
                    ragIncludeEpisodes = assistant.ragIncludeEpisodes,
                    requireFullCount = force,
                )
            )
        }

        return when (plan) {
            is RefreshPlan.Skip -> plan.status
            is RefreshPlan.Fetch -> {
                try {
                    val phrases = fetchWithRetry(plan.pending, maxAttempts = maxAttempts)
                        ?: return WelcomePhrasesRefreshStatus.Failed

                    settingsStore.update { current ->
                        val updatedAssistants = current.assistants.map { assistant ->
                            if (assistant.id == plan.pending.assistantId) {
                                assistant.copy(
                                    welcomePhrases = phrases,
                                    lastWelcomePhrasesRequestEpochDay = plan.pending.todayEpochDay,
                                )
                            } else {
                                assistant
                            }
                        }
                        current.copy(assistants = updatedAssistants)
                    }
                    WelcomePhrasesRefreshStatus.Refreshed
                } finally {
                    mutex.withLock {
                        inFlightAssistantIds.remove(assistantId)
                    }
                }
            }
        }
    }

    private suspend fun fetchWithRetry(
        pending: PendingRequest,
        maxAttempts: Int,
    ): List<String>? {
        for (attempt in 1..maxAttempts) {
            val phrases = runCatching { fetchOnce(pending) }.getOrElse { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "fetch attempt $attempt failed: ${e.message}", e)
                null
            }
            if (phrases != null) return phrases
            if (attempt < maxAttempts) delay(400L * attempt)
        }
        return null
    }

    private suspend fun fetchOnce(pending: PendingRequest): List<String> {
        val providerHandler = providerManager.getProviderByType(pending.provider)
        val localeHint = "${pending.locale.toLanguageTag()} (${pending.locale.displayName})"
        val today = LocalDate.ofEpochDay(pending.todayEpochDay)
        val todayHint = buildString {
            append(today)
            append(" (")
            append(today.dayOfWeek.getDisplayName(TextStyle.FULL, pending.locale))
            append(")")
        }

        val memoryContext = if (!pending.enableMemory) {
            MemoryContext.EMPTY
        } else {
            withContext(Dispatchers.IO) {
                val assistantIdString = pending.assistantId.toString()
                val dateQuery = buildDateQuery(today)

                val ragMemories = memoryRepository.retrieveRelevantMemories(
                    assistantId = assistantIdString,
                    query = dateQuery,
                    limit = 10,
                    similarityThreshold = pending.ragSimilarityThreshold,
                    includeCore = pending.ragIncludeCore,
                    includeEpisodes = pending.ragIncludeEpisodes,
                )

                val recentMemories = memoryRepository.getRecentCombinedMemories(
                    assistantId = assistantIdString,
                    limit = 5,
                    includeCore = pending.ragIncludeCore,
                    includeEpisodes = pending.ragIncludeEpisodes,
                )

                MemoryContext(
                    ragMemoriesText = formatMemoriesForPrompt(ragMemories),
                    recentMemoriesText = formatMemoriesForPrompt(recentMemories),
                )
            }
        }

        val messages = buildList {
            pending.assistantSystemPrompt
                .takeIf { it.isNotBlank() }
                ?.let { add(UIMessage.system(it)) }
            add(
                UIMessage.user(
                    DEFAULT_WELCOME_PHRASES_PROMPT.applyPlaceholders(
                        "locale" to localeHint,
                        "date" to todayHint,
                        "rag_memories" to memoryContext.ragMemoriesText,
                        "recent_memories" to memoryContext.recentMemoriesText,
                    )
                )
            )
        }

        var requestBodyJson: String? = null
        val params = TextGenerationParams(
            model = pending.model,
            temperature = 0.9f,
            thinkingBudget = 0,
            onRequestBody = { requestBodyJson = it },
        )
        val startAt = System.currentTimeMillis()
        var failure: Throwable? = null
        var raw = ""
        var rawResponseText = ""
        withContext(Dispatchers.IO) {
            try {
                val result = providerHandler.generateText(
                    providerSetting = pending.provider,
                    messages = messages,
                    params = params,
                )
                rawResponseText = result.rawResponse.orEmpty()
                raw = result.choices.firstOrNull()?.message?.toContentText().orEmpty()
            } catch (t: Throwable) {
                failure = t
                throw t
            } finally {
                requestLogManager.logTextGeneration(
                    source = AIRequestSource.WELCOME_PHRASES,
                    providerSetting = pending.provider,
                    params = params,
                    requestMessages = messages,
                    requestBodyJson = requestBodyJson,
                    responseText = raw,
                    responseRawText = rawResponseText,
                    stream = false,
                    latencyMs = System.currentTimeMillis() - startAt,
                    durationMs = System.currentTimeMillis() - startAt,
                    error = failure,
                )
            }
        }

        val phrases = parseWelcomePhrases(raw)
        if (pending.requireFullCount) {
            require(phrases.size == WELCOME_PHRASES_TOTAL) {
                "Expected $WELCOME_PHRASES_TOTAL welcome phrases, got ${phrases.size}. raw=$raw"
            }
        } else {
            require(phrases.size >= MIN_AUTO_ACCEPTED_COUNT) {
                "Expected >= $MIN_AUTO_ACCEPTED_COUNT welcome phrases for auto refresh, got ${phrases.size}. raw=$raw"
            }
            if (phrases.size != WELCOME_PHRASES_TOTAL) {
                Log.w(TAG, "auto refresh got ${phrases.size}/$WELCOME_PHRASES_TOTAL phrases; will keep trying later")
            }
        }
        return phrases.take(WELCOME_PHRASES_TOTAL)
    }

    private data class PendingRequest(
        val assistantId: Uuid,
        val assistantSystemPrompt: String,
        val todayEpochDay: Long,
        val model: Model,
        val provider: ProviderSetting,
        val locale: Locale,
        val enableMemory: Boolean,
        val ragSimilarityThreshold: Float,
        val ragIncludeCore: Boolean,
        val ragIncludeEpisodes: Boolean,
        val requireFullCount: Boolean,
    )

    private sealed interface RefreshPlan {
        data class Skip(val status: WelcomePhrasesRefreshStatus) : RefreshPlan
        data class Fetch(val pending: PendingRequest) : RefreshPlan
    }

    private data class MemoryContext(
        val ragMemoriesText: String,
        val recentMemoriesText: String,
    ) {
        companion object {
            val EMPTY = MemoryContext(
                ragMemoriesText = "none",
                recentMemoriesText = "none",
            )
        }
    }

    private fun formatMemoriesForPrompt(
        memories: List<me.rerere.rikkahub.data.model.AssistantMemory>,
    ): String {
        if (memories.isEmpty()) return "none"
        return buildString {
            memories.forEachIndexed { index, memory ->
                if (index > 0) append('\n')
                val typeLabel = when (memory.type) {
                    me.rerere.rikkahub.data.db.entity.MemoryType.CORE -> "CORE"
                    me.rerere.rikkahub.data.db.entity.MemoryType.EPISODIC -> "EPISODIC"
                    else -> "UNKNOWN"
                }
                append("- [").append(typeLabel).append("] ")
                append(memory.content.trim().replace("\n", " ").take(120))
            }
        }
    }

    private fun buildDateQuery(today: LocalDate): String {
        val monthDayDash = "%02d-%02d".format(today.monthValue, today.dayOfMonth)
        val monthDaySlash = "${today.monthValue}/${today.dayOfMonth}"
        return buildString {
            append(today)
            append(' ')
            append(monthDayDash)
            append(' ')
            append(monthDaySlash)
            append(" today")
        }
    }

    private companion object {
        private const val MIN_AUTO_ACCEPTED_COUNT = 8
    }
}

sealed interface WelcomePhrasesRefreshStatus {
    data object Refreshed : WelcomePhrasesRefreshStatus
    data object UpToDate : WelcomePhrasesRefreshStatus
    data object NotEligible : WelcomePhrasesRefreshStatus
    data object InProgress : WelcomePhrasesRefreshStatus
    data object Failed : WelcomePhrasesRefreshStatus
}
