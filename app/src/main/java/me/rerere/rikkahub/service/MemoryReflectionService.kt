package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.AIRequestLogManager
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.db.entity.ChatEpisodeEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryReflectionAction
import me.rerere.rikkahub.data.model.MemoryReflectionActionType
import me.rerere.rikkahub.data.model.MemoryReflectionMode
import me.rerere.rikkahub.data.model.MemoryReflectionResponse
import me.rerere.rikkahub.data.model.MemoryReflectionSensitivity
import me.rerere.rikkahub.data.model.MemoryReflectionStability
import me.rerere.rikkahub.data.repository.MemoryRepository

private const val TAG = "MemoryReflection"

class MemoryReflectionService(
    private val json: Json,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val providerManager: me.rerere.ai.provider.ProviderManager,
    private val requestLogManager: AIRequestLogManager,
) {
    suspend fun previewReflectionSuggestions(
        assistantId: String,
        maxEpisodes: Int = 20,
        maxActions: Int = 7,
    ): List<MemoryReflectionAction> = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getAssistantById(kotlin.uuid.Uuid.parse(assistantId)) ?: return@withContext emptyList()
        val (model, provider) = resolveModelProviderOrNull(settings, assistant) ?: return@withContext emptyList()
        val providerHandler = providerManager.getProviderByType(provider)

        val episodes = memoryRepository.getEpisodeEntitiesOfAssistant(assistantId)
        val candidates = selectCandidateEpisodes(episodes, maxEpisodes)
        if (candidates.isEmpty()) return@withContext emptyList()

        val coreMemories = memoryRepository.getMemoryEntitiesOfAssistant(assistantId)
            .filter { it.type == me.rerere.rikkahub.data.db.entity.MemoryType.CORE }
            .sortedByDescending { it.lastAccessedAt }
            .take(30)

        val prompt = buildPrompt(
            candidates = candidates,
            coreMemories = coreMemories.map { it.content },
            maxActions = maxActions,
        )

        val params = TextGenerationParams(model = model, temperature = 0.2f)
        val startAt = System.currentTimeMillis()
        var responseText = ""
        var failure: Throwable? = null

        try {
            val response = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = params,
            )
            responseText = response.choices.firstOrNull()?.message?.toContentText().orEmpty()
            if (responseText.isBlank()) return@withContext emptyList()

            val jsonStr = extractJsonOrNull(responseText) ?: return@withContext emptyList()
            val parsed = runCatching { json.decodeFromString(MemoryReflectionResponse.serializer(), jsonStr) }.getOrNull()
                ?: return@withContext emptyList()

            parsed.actions
                .asSequence()
                .filter { it.type == MemoryReflectionActionType.CREATE }
                .filter { !it.content.isNullOrBlank() }
                .map { action ->
                    val reviewInDays = when (action.stability) {
                        MemoryReflectionStability.MID_TERM -> (action.review_in_days ?: 60).coerceIn(1, 365)
                        MemoryReflectionStability.LONG_TERM -> action.review_in_days
                    }
                    action.copy(
                        content = action.content?.trim(),
                        evidence_episode_ids = action.evidence_episode_ids.map(String::trim).filter(String::isNotBlank),
                        review_in_days = reviewInDays,
                    )
                }
                .filter { it.content?.length ?: 0 >= 6 }
                .toList()
        } catch (t: Throwable) {
            failure = t
            Log.e(TAG, "previewReflectionSuggestions failed", t)
            emptyList()
        } finally {
            val durationMs = System.currentTimeMillis() - startAt
            requestLogManager.logTextGeneration(
                source = AIRequestSource.MEMORY_REFLECTION,
                providerSetting = provider,
                params = params,
                requestMessages = listOf(UIMessage.user(prompt)),
                responseText = responseText,
                stream = false,
                latencyMs = durationMs,
                durationMs = durationMs,
                error = failure,
            )
        }
    }

    suspend fun runAutoReflectionIfNeeded(
        assistantId: String,
        isFullScan: Boolean,
    ): AutoReflectionResult? = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val assistantUuid = runCatching { kotlin.uuid.Uuid.parse(assistantId) }.getOrNull() ?: return@withContext null
        val assistant = settings.getAssistantById(assistantUuid) ?: return@withContext null

        if (!assistant.enableMemory || !assistant.enableMemoryConsolidation) return@withContext null
        if (!assistant.enableMemoryReflection) return@withContext null
        if (assistant.memoryReflectionMode != MemoryReflectionMode.AUTO_CONSERVATIVE) return@withContext null

        val now = System.currentTimeMillis()
        val intervalMs = assistant.memoryReflectionIntervalHours.coerceIn(1, 72) * 60 * 60 * 1000L
        val due = isFullScan || (assistant.lastReflectionTime <= 0L) || (now - assistant.lastReflectionTime >= intervalMs)
        if (!due) return@withContext null

        val suggestions = previewReflectionSuggestions(assistantId = assistantId)
        val (createdCount, skippedCount) = applyCreateSuggestions(
            assistantId = assistantId,
            suggestions = suggestions,
            auto = true,
        )

        val resultMsg = if (createdCount > 0) {
            "新增 $createdCount 条核心记忆（自动）"
        } else if (skippedCount > 0) {
            "无新增（自动），已跳过 $skippedCount 条建议"
        } else {
            "无新增（自动）"
        }

        updateAssistantReflectionStats(
            assistantUuid = assistantUuid,
            time = now,
            result = resultMsg,
        )

        AutoReflectionResult(createdCount = createdCount, skippedCount = skippedCount, result = resultMsg)
    }

    suspend fun applyCreateSuggestions(
        assistantId: String,
        suggestions: List<MemoryReflectionAction>,
        auto: Boolean,
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var created = 0
        var skipped = 0

        for (suggestion in suggestions) {
            val content = suggestion.content?.trim().orEmpty()
            if (content.isBlank()) continue

            if (auto) {
                val confidence = suggestion.confidence ?: 0.0
                val evidenceCount = suggestion.evidence_episode_ids.size
                if (suggestion.sensitivity == MemoryReflectionSensitivity.HIGH) {
                    skipped++
                    continue
                }
                if (confidence < 0.78) {
                    skipped++
                    continue
                }
                if (evidenceCount < 2) {
                    skipped++
                    continue
                }
            }

            val isDuplicate = memoryRepository.isLikelyDuplicateCoreMemory(
                assistantId = assistantId,
                content = content,
                similarityThreshold = 0.82f,
            )
            if (isDuplicate) {
                skipped++
                continue
            }

            memoryRepository.addMemory(assistantId, content)
            created++
        }

        created to skipped
    }

    suspend fun updateAssistantReflectionStats(
        assistantUuid: kotlin.uuid.Uuid,
        time: Long,
        result: String,
    ) = withContext(Dispatchers.IO) {
        settingsStore.update { current ->
            current.copy(
                assistants = current.assistants.map { assistant ->
                    if (assistant.id == assistantUuid) {
                        assistant.copy(
                            lastReflectionTime = time,
                            lastReflectionResult = result,
                        )
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    private fun resolveModelProviderOrNull(
        settings: Settings,
        assistant: Assistant,
    ): Pair<Model, ProviderSetting>? {
        val backgroundModelId = assistant.summarizerModelId ?: assistant.backgroundModelId ?: settings.chatModelId
            ?: return null
        val model = settings.findModelById(backgroundModelId) ?: return null
        val provider = model.findProvider(settings.providers) ?: return null
        return model to provider
    }

    private fun selectCandidateEpisodes(
        episodes: List<ChatEpisodeEntity>,
        maxEpisodes: Int,
    ): List<ChatEpisodeEntity> {
        val now = System.currentTimeMillis()
        val scored = episodes
            .asSequence()
            .filter { it.content.isNotBlank() && it.content.length >= 12 }
            .map { episode ->
                val sig = (episode.significance.coerceIn(1, 10) - 1) / 9.0f

                val ageInDays = (now - episode.startTime) / (1000.0 * 60 * 60 * 24)
                val recency = (1.0 / (1.0 + (ageInDays / 14.0))).toFloat()

                val accessAgeInDays = (now - episode.lastAccessedAt) / (1000.0 * 60 * 60 * 24)
                val access = (1.0 / (1.0 + (accessAgeInDays / 14.0))).toFloat()

                val score = sig * 0.5f + recency * 0.3f + access * 0.2f
                episode to score
            }
            .sortedByDescending { it.second }
            .take(maxEpisodes.coerceIn(5, 50))
            .map { it.first }
            .toList()

        return scored
    }

    private fun buildPrompt(
        candidates: List<ChatEpisodeEntity>,
        coreMemories: List<String>,
        maxActions: Int,
    ): String {
        val episodesText = candidates.joinToString("\n") { episode ->
            val tag = "E${episode.id}"
            val sig = episode.significance.coerceIn(1, 10)
            "[#$tag sig=$sig start=${episode.startTime} end=${episode.endTime}] ${episode.content}"
        }

        val coreText = if (coreMemories.isEmpty()) {
            "NONE"
        } else {
            coreMemories
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(30)
                .mapIndexed { index, text -> "${index + 1}. $text" }
                .joinToString("\n")
        }

        val max = maxActions.coerceIn(3, 7)

        return """
            You are helping an AI assistant build user memories from recent episodic summaries (episodes).
            
            Goal: Extract ONLY stable information that is likely to remain useful in future chats.
            We use a "lenient long-term stability" rule:
            - LONG_TERM: stable for years (identity, relationships, long-term preferences, long-term goals, major life events).
            - MID_TERM: stable for weeks to months (ongoing projects, recurring habits, stable constraints). MID_TERM must include review_in_days=60.
            
            Hard rules:
            - Return ONLY JSON. No markdown, no extra text.
            - Actions MUST be only: "create" or "skip". Do NOT output "update" or "merge".
            - Output at most $max actions, sorted by importance.
            - Do NOT include short-term mood, day-to-day status, one-off tasks, or guesses.
            - For each created memory, include evidence_episode_ids using the episode tags (e.g. ["E123"]).
            - Mark sensitivity: HIGH if it contains personal identifiers (phone/email/address/ID), medical or financial details.
            
            Existing core memories (for avoiding duplicates):
            $coreText
            
            Episodes (tagged):
            $episodesText
            
            Output schema:
            {
              "version": "v1",
              "mode": "extract_and_act",
              "actions": [
                {
                  "type": "create",
                  "stability": "LONG_TERM|MID_TERM",
                  "content": "...",
                  "reason": "...",
                  "confidence": 0.0,
                  "sensitivity": "LOW|MEDIUM|HIGH",
                  "evidence_episode_ids": ["E123"],
                  "review_in_days": 60
                }
              ],
              "notes": "..."
            }
        """.trimIndent()
    }

    private fun extractJsonOrNull(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) return null
        return text.substring(start, end + 1)
    }
}

data class AutoReflectionResult(
    val createdCount: Int,
    val skippedCount: Int,
    val result: String,
)
