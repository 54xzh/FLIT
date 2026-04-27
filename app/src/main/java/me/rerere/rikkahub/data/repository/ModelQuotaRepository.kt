package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelQuota
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.QuotaResetPeriod
import me.rerere.rikkahub.data.datastore.findQuotaGroup
import me.rerere.rikkahub.data.datastore.findQuotaOwner
import me.rerere.rikkahub.data.db.dao.ModelQuotaUsageDAO
import me.rerere.rikkahub.data.db.entity.ModelQuotaUsageEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.uuid.Uuid

data class QuotaUsageResult(
    val usedTokens: Long,
    val tokenLimit: Long,
    val reminderPercentage: Float,
    val ownerModelId: Uuid,
    val quotaGroupId: Uuid? = null,
    val quotaGroupName: String? = null,
    val modelCount: Int = 1,
    val resetPeriod: QuotaResetPeriod,
    val lastResetAt: Long,
    val nextResetAt: Long,
) {
    val usagePercentage: Float get() = if (tokenLimit > 0) (usedTokens.toFloat() / tokenLimit) * 100f else 0f
    val isOverLimit: Boolean get() = tokenLimit > 0 && usedTokens >= tokenLimit
    val isAtReminder: Boolean get() = tokenLimit > 0 && usagePercentage >= reminderPercentage
}

class ModelQuotaRepository(
    private val dao: ModelQuotaUsageDAO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun recordUsage(modelId: Uuid, usage: TokenUsage) {
        recordUsage(
            modelId = modelId,
            inputTokens = usage.promptTokens.toLong(),
            outputTokens = usage.completionTokens.toLong(),
            cachedTokens = usage.cachedTokens.toLong(),
        )
    }

    suspend fun recordUsage(
        modelId: Uuid,
        inputTokens: Long,
        outputTokens: Long,
        cachedTokens: Long,
    ) {
        withContext(Dispatchers.IO) {
            val now = nowMillis()
            val idStr = modelId.toString()
            dao.insertIfMissing(newUsageEntity(idStr, now))
            dao.addUsage(
                modelId = idStr,
                inputTokens = inputTokens.coerceAtLeast(0L),
                outputTokens = outputTokens.coerceAtLeast(0L),
                cachedTokens = cachedTokens.coerceAtLeast(0L),
                timestamp = now,
            )
        }
    }

    suspend fun getQuotaUsage(model: Model, allModels: List<Model>): QuotaUsageResult? {
        return withContext(Dispatchers.IO) {
            val scope = model.resolveQuotaScope(allModels) ?: return@withContext null
            resetQuotaIfNeeded(scope)
            buildQuotaUsage(scope, dao.getUsageForModels(scope.modelIds))
        }
    }

    suspend fun getQuotaUsageForProviders(model: Model, providers: List<ProviderSetting>): QuotaUsageResult? {
        return withContext(Dispatchers.IO) {
            val scope = model.resolveProviderQuotaScope(providers) ?: return@withContext null
            resetQuotaIfNeeded(scope)
            buildQuotaUsage(scope, dao.getUsageForModels(scope.modelIds))
        }
    }

    fun getQuotaUsageFlow(model: Model, allModels: List<Model>): Flow<QuotaUsageResult?> {
        val scope = model.resolveQuotaScope(allModels) ?: return kotlinx.coroutines.flow.flowOf(null)
        val usageChanges = dao.getUsageForModelsFlow(scope.modelIds).map { Unit }
        val resetTicks = flow {
            while (true) {
                emit(Unit)
                val delayMs = (calculateNextResetAt(scope.quota) - nowMillis()).coerceAtLeast(1_000L)
                delay(delayMs)
            }
        }

        return merge(usageChanges, resetTicks)
            .map {
                withContext(Dispatchers.IO) {
                    resetQuotaIfNeeded(scope)
                    buildQuotaUsage(scope, dao.getUsageForModels(scope.modelIds))
                }
            }
            .distinctUntilChanged()
    }

    fun getQuotaUsageFlowForProviders(model: Model, providers: List<ProviderSetting>): Flow<QuotaUsageResult?> {
        val scope = model.resolveProviderQuotaScope(providers) ?: return kotlinx.coroutines.flow.flowOf(null)
        val usageChanges = dao.getUsageForModelsFlow(scope.modelIds).map { Unit }
        val resetTicks = flow {
            while (true) {
                emit(Unit)
                val delayMs = (calculateNextResetAt(scope.quota) - nowMillis()).coerceAtLeast(1_000L)
                delay(delayMs)
            }
        }

        return merge(usageChanges, resetTicks)
            .map {
                withContext(Dispatchers.IO) {
                    resetQuotaIfNeeded(scope)
                    buildQuotaUsage(scope, dao.getUsageForModels(scope.modelIds))
                }
            }
            .distinctUntilChanged()
    }

    suspend fun resetQuota(ownerModelId: Uuid, sharedModelIds: Set<Uuid>) {
        withContext(Dispatchers.IO) {
            val now = nowMillis()
            val allIds = buildList {
                add(ownerModelId.toString())
                sharedModelIds.forEach { add(it.toString()) }
            }
            allIds.forEach { dao.insertIfMissing(newUsageEntity(it, now)) }
            dao.resetUsageForModels(allIds, now)
        }
    }

    suspend fun resetQuota(modelIds: Set<Uuid>) {
        withContext(Dispatchers.IO) {
            val now = nowMillis()
            val allIds = modelIds.map { it.toString() }
            allIds.forEach { dao.insertIfMissing(newUsageEntity(it, now)) }
            dao.resetUsageForModels(allIds, now)
        }
    }

    suspend fun checkAndAutoReset(model: Model, allModels: List<Model>) {
        withContext(Dispatchers.IO) {
            val scope = model.resolveQuotaScope(allModels) ?: return@withContext
            resetQuotaIfNeeded(scope)
        }
    }

    suspend fun checkAndAutoResetForProviders(model: Model, providers: List<ProviderSetting>) {
        withContext(Dispatchers.IO) {
            val scope = model.resolveProviderQuotaScope(providers) ?: return@withContext
            resetQuotaIfNeeded(scope)
        }
    }

    private suspend fun resetQuotaIfNeeded(scope: QuotaScope) {
        val now = nowMillis()
        scope.modelIds.forEach { dao.insertIfMissing(newUsageEntity(it, now)) }

        val currentResetAt = calculateCurrentResetAt(scope.quota, now)
        val usages = dao.getUsageForModels(scope.modelIds)
        if (usages.any { it.lastResetAt < currentResetAt }) {
            dao.resetUsageForModels(scope.modelIds, now)
        }
    }

    private fun buildQuotaUsage(
        scope: QuotaScope,
        usages: List<ModelQuotaUsageEntity>,
    ): QuotaUsageResult {
        val totalUsed = usages.sumOf { it.inputTokens + it.outputTokens + it.cachedTokens }
        val lastResetAt = usages.minOfOrNull { it.lastResetAt } ?: nowMillis()
        return QuotaUsageResult(
            usedTokens = totalUsed,
            tokenLimit = scope.quota.tokenLimit,
            reminderPercentage = scope.quota.reminderPercentage,
            ownerModelId = scope.owner.id,
            quotaGroupId = scope.groupId,
            quotaGroupName = scope.groupName,
            modelCount = scope.modelIds.size,
            resetPeriod = scope.quota.resetPeriod,
            lastResetAt = lastResetAt,
            nextResetAt = calculateNextResetAt(scope.quota),
        )
    }

    private fun Model.resolveQuotaScope(allModels: List<Model>): QuotaScope? {
        val owner = findQuotaOwner(allModels) ?: return null
        val quota = owner.quota ?: return null
        val modelIds = owner.findQuotaGroup(allModels).map { it.id.toString() }
        return QuotaScope(
            owner = owner,
            quota = quota,
            modelIds = modelIds,
        )
    }

    private fun Model.resolveProviderQuotaScope(providers: List<ProviderSetting>): QuotaScope? {
        val provider = providers.firstOrNull { provider ->
            provider.models.any { it.id == this.id }
        }
        val providerModels = provider?.models
        if (provider != null && providerModels != null) {
            val knownIds = providerModels.map { it.id }.toSet()
            val group = provider.quotaGroups.firstOrNull { this.id in it.modelIds }
            if (group != null) {
                val modelIds = group.modelIds.filter { it in knownIds }
                val owner = providerModels.firstOrNull { it.id == modelIds.firstOrNull() } ?: this
                return QuotaScope(
                    owner = owner,
                    quota = group.quota,
                    modelIds = modelIds.map { it.toString() },
                    groupId = group.id,
                    groupName = group.name,
                ).takeIf { it.quota.enabled && it.modelIds.isNotEmpty() }
            }

            val modelQuota = quota
            val hasLegacySharing = modelQuota?.sharedModelIds?.isNotEmpty() == true ||
                    providerModels.any { otherModel -> this.id in otherModel.quota?.sharedModelIds.orEmpty() }
            if (hasLegacySharing) {
                val legacyScope = resolveQuotaScope(providerModels)
                if (legacyScope != null) {
                    return legacyScope
                }
            }

            if (modelQuota?.enabled == true) {
                return QuotaScope(
                    owner = this,
                    quota = modelQuota,
                    modelIds = listOf(id.toString()),
                )
            }

            return null
        }

        val allModels = providers.flatMap { it.models }
        return resolveQuotaScope(allModels)
    }

    private fun newUsageEntity(modelId: String, timestamp: Long): ModelQuotaUsageEntity {
        return ModelQuotaUsageEntity(
            modelId = modelId,
            lastResetAt = timestamp,
            lastUpdatedAt = timestamp,
        )
    }

    private fun calculateCurrentResetAt(quota: ModelQuota, nowMillis: Long = this.nowMillis()): Long {
        val now = nowMillis.toLocalDateTime()
        val candidate = currentPeriodResetAt(quota, now)
        val current = if (candidate.isAfter(now)) {
            when (quota.resetPeriod) {
                QuotaResetPeriod.DAILY -> candidate.minusDays(1)
                QuotaResetPeriod.WEEKLY -> candidate.minusWeeks(1)
                QuotaResetPeriod.MONTHLY -> previousMonthlyResetBefore(candidate, quota)
            }
        } else {
            candidate
        }
        return current.atZone(zoneId).toInstant().toEpochMilli()
    }

    private fun calculateNextResetAt(quota: ModelQuota): Long {
        val now = nowMillis().toLocalDateTime()
        val candidate = currentPeriodResetAt(quota, now)
        val next = if (candidate.isAfter(now)) {
            candidate
        } else {
            when (quota.resetPeriod) {
                QuotaResetPeriod.DAILY -> candidate.plusDays(1)
                QuotaResetPeriod.WEEKLY -> candidate.plusWeeks(1)
                QuotaResetPeriod.MONTHLY -> nextMonthlyResetAfter(candidate, quota)
            }
        }
        return next.atZone(zoneId).toInstant().toEpochMilli()
    }

    private fun currentPeriodResetAt(quota: ModelQuota, now: LocalDateTime): LocalDateTime {
        return when (quota.resetPeriod) {
            QuotaResetPeriod.DAILY -> now.toLocalDate()
                .atTime(quota.safeResetHour(), quota.safeResetMinute())

            QuotaResetPeriod.WEEKLY -> now.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(quota.safeResetDayOfWeek()))
                .atTime(quota.safeResetHour(), quota.safeResetMinute())

            QuotaResetPeriod.MONTHLY -> monthlyResetAt(now, quota)
        }
    }

    private fun monthlyResetAt(current: LocalDateTime, quota: ModelQuota): LocalDateTime {
        val monthStart = current.toLocalDate().withDayOfMonth(1)
        val day = quota.safeResetDayOfMonth().coerceAtMost(monthStart.lengthOfMonth())
        return monthStart
            .withDayOfMonth(day)
            .atTime(quota.safeResetHour(), quota.safeResetMinute())
    }

    private fun previousMonthlyResetBefore(current: LocalDateTime, quota: ModelQuota): LocalDateTime {
        return monthlyResetAt(current.minusMonths(1), quota)
    }

    private fun nextMonthlyResetAfter(current: LocalDateTime, quota: ModelQuota): LocalDateTime {
        val nextMonthDate = current.toLocalDate().withDayOfMonth(1).plusMonths(1)
        val day = quota.safeResetDayOfMonth().coerceAtMost(nextMonthDate.lengthOfMonth())
        return nextMonthDate
            .withDayOfMonth(day)
            .atTime(quota.safeResetHour(), quota.safeResetMinute())
    }

    private fun Long.toLocalDateTime(): LocalDateTime {
        return Instant.ofEpochMilli(this)
            .atZone(zoneId)
            .toLocalDateTime()
    }

    private fun ModelQuota.safeResetHour(): Int = resetHour.coerceIn(0, 23)

    private fun ModelQuota.safeResetMinute(): Int = resetMinute.coerceIn(0, 59)

    private fun ModelQuota.safeResetDayOfWeek(): DayOfWeek {
        return DayOfWeek.of(resetDayOfWeek.coerceIn(1, 7))
    }

    private fun ModelQuota.safeResetDayOfMonth(): Int = resetDayOfMonth.coerceIn(1, 31)

    private data class QuotaScope(
        val owner: Model,
        val quota: ModelQuota,
        val modelIds: List<String>,
        val groupId: Uuid? = null,
        val groupName: String? = null,
    )
}
