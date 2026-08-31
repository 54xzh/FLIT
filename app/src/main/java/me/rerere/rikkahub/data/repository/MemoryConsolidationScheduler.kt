package me.rerere.rikkahub.data.repository

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.MemoryConsolidationWorker
import kotlin.uuid.Uuid

/**
 * Schedules user-started memory consolidation separately from automatic work.
 *
 * Keeping every manual action under a stable unique name lets the UI expose a
 * reliable cancel button without cancelling automatic consolidation jobs.
 */
class MemoryConsolidationScheduler(
    context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueueFullScan(assistantId: String) {
        enqueue(
            workName = fullScanWorkName(assistantId),
            inputData = workDataOf(
                MemoryConsolidationWorker.INPUT_FULL_SCAN to true,
                MemoryConsolidationWorker.INPUT_ASSISTANT_ID to assistantId,
            ),
        )
    }

    fun enqueueConversation(conversationId: String, assistantId: String) {
        enqueue(
            workName = conversationWorkName(conversationId),
            inputData = workDataOf(
                MemoryConsolidationWorker.INPUT_FORCE_CONVERSATION_ID to conversationId,
                MemoryConsolidationWorker.INPUT_ASSISTANT_ID to assistantId,
            ),
            tags = setOf("$CONVERSATION_TAG_PREFIX$conversationId"),
        )
    }

    fun enqueueGroupFullScan(templateId: String) {
        enqueue(
            workName = groupFullScanWorkName(templateId),
            inputData = workDataOf(
                MemoryConsolidationWorker.INPUT_FULL_SCAN to true,
                MemoryConsolidationWorker.INPUT_GROUP_CHAT_TEMPLATE_ID to templateId,
            ),
        )
    }

    suspend fun cancelFullScan(assistantId: String) {
        awaitCancellation(workManager.cancelUniqueWork(fullScanWorkName(assistantId)))
    }

    suspend fun cancelConversation(conversationId: String) {
        awaitCancellation(workManager.cancelUniqueWork(conversationWorkName(conversationId)))
    }

    suspend fun cancelGroupFullScan(templateId: String) {
        awaitCancellation(workManager.cancelUniqueWork(groupFullScanWorkName(templateId)))
    }

    suspend fun cancelAllManual(): Int = withContext(Dispatchers.IO) {
        val runningCount = workManager.getWorkInfosByTag(MANUAL_CONSOLIDATION_TAG)
            .get()
            .count { !it.state.isFinished }
        workManager.cancelAllWorkByTag(MANUAL_CONSOLIDATION_TAG).result.get()
        runningCount
    }

    suspend fun cancelLegacyConsolidationWorkIfNeeded(settingsStore: SettingsStore): Boolean {
        if (settingsStore.isLegacyMemoryConsolidationCancellationApplied()) return false
        // WorkManager automatically tags every request with its worker class name.
        // This catches anonymous requests created before manual jobs had their own tag.
        awaitCancellation(
            workManager.cancelAllWorkByTag(MemoryConsolidationWorker::class.java.name),
        )
        settingsStore.markLegacyMemoryConsolidationCancellationApplied()
        return true
    }

    fun observeFullScan(assistantId: String): Flow<Boolean> =
        observeWork(fullScanWorkName(assistantId))

    fun observeGroupFullScan(templateId: String): Flow<Boolean> =
        observeWork(groupFullScanWorkName(templateId))

    fun observeRunningConversationIds(): Flow<Set<Uuid>> =
        workManager.getWorkInfosByTagFlow(MANUAL_CONSOLIDATION_TAG).map { workInfos ->
            workInfos.asSequence()
                .filter { !it.state.isFinished }
                .flatMap { it.tags.asSequence() }
                .filter { it.startsWith(CONVERSATION_TAG_PREFIX) }
                .map { it.removePrefix(CONVERSATION_TAG_PREFIX) }
                .mapNotNull { id -> runCatching { Uuid.parse(id) }.getOrNull() }
                .toSet()
        }

    fun observeManualWorkCount(): Flow<Int> =
        workManager.getWorkInfosByTagFlow(MANUAL_CONSOLIDATION_TAG).map { workInfos ->
            workInfos.count { !it.state.isFinished }
        }

    private suspend fun awaitCancellation(operation: androidx.work.Operation) = withContext(Dispatchers.IO) {
        operation.result.get()
    }

    private fun enqueue(
        workName: String,
        inputData: androidx.work.Data,
        tags: Set<String> = emptySet(),
    ) {
        val requestBuilder = OneTimeWorkRequestBuilder<MemoryConsolidationWorker>()
            .setInputData(inputData)
            .addTag(MANUAL_CONSOLIDATION_TAG)
        tags.forEach(requestBuilder::addTag)
        val request = requestBuilder
            .build()
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request)
    }

    private fun observeWork(workName: String): Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(workName)
            .map { workInfos -> workInfos.any { !it.state.isFinished } }

    companion object {
        const val MANUAL_CONSOLIDATION_TAG = "manual_memory_consolidation"
        private const val CONVERSATION_TAG_PREFIX = "manual_memory_consolidation_conversation:"

        fun fullScanWorkName(assistantId: String) =
            "manual_memory_consolidation_assistant_$assistantId"

        fun conversationWorkName(conversationId: String) =
            "manual_memory_consolidation_conversation_$conversationId"

        fun groupFullScanWorkName(templateId: String) =
            "manual_memory_consolidation_group_$templateId"
    }
}
