package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelQuota
import me.rerere.ai.provider.QuotaResetPeriod
import me.rerere.rikkahub.data.db.dao.ModelQuotaUsageDAO
import me.rerere.rikkahub.data.db.entity.ModelQuotaUsageEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.uuid.Uuid

class ModelQuotaRepositoryTest {
    private val zoneId: ZoneId = ZoneId.of("UTC")

    @Test
    fun `getQuotaUsage resets expired usage from shared models`() = runBlocking {
        val ownerId = Uuid.parse("00000000-0000-0000-0000-000000000101")
        val sharedId = Uuid.parse("00000000-0000-0000-0000-000000000102")
        val now = millis("2026-04-26T10:00:00")
        val dao = FakeModelQuotaUsageDao(
            ModelQuotaUsageEntity(
                modelId = sharedId.toString(),
                inputTokens = 40,
                outputTokens = 2,
                cachedTokens = 3,
                lastResetAt = millis("2026-04-25T08:59:00"),
                lastUpdatedAt = millis("2026-04-25T09:10:00"),
            )
        )
        val repo = ModelQuotaRepository(
            dao = dao,
            nowMillis = { now },
            zoneId = zoneId,
        )
        val owner = quotaModel(
            id = ownerId,
            quota = ModelQuota(
                enabled = true,
                tokenLimit = 100,
                sharedModelIds = setOf(sharedId),
                resetPeriod = QuotaResetPeriod.DAILY,
                resetHour = 9,
            )
        )
        val shared = quotaModel(id = sharedId, quota = owner.quota?.copy(sharedModelIds = setOf(ownerId)))

        val usage = repo.getQuotaUsage(shared, listOf(owner, shared))

        assertEquals(0L, usage?.usedTokens)
        assertEquals(now, dao.getUsage(sharedId.toString())?.lastResetAt)
        assertEquals(now, dao.getUsage(ownerId.toString())?.lastResetAt)
    }

    @Test
    fun `getQuotaUsage keeps usage before today's reset time`() = runBlocking {
        val modelId = Uuid.parse("00000000-0000-0000-0000-000000000201")
        val now = millis("2026-04-26T08:30:00")
        val lastResetAt = millis("2026-04-25T09:00:00")
        val dao = FakeModelQuotaUsageDao(
            ModelQuotaUsageEntity(
                modelId = modelId.toString(),
                inputTokens = 20,
                outputTokens = 5,
                cachedTokens = 1,
                lastResetAt = lastResetAt,
                lastUpdatedAt = millis("2026-04-25T10:00:00"),
            )
        )
        val repo = ModelQuotaRepository(
            dao = dao,
            nowMillis = { now },
            zoneId = zoneId,
        )
        val model = quotaModel(
            id = modelId,
            quota = ModelQuota(
                enabled = true,
                tokenLimit = 100,
                resetPeriod = QuotaResetPeriod.DAILY,
                resetHour = 9,
            )
        )

        val usage = repo.getQuotaUsage(model, listOf(model))

        assertEquals(26L, usage?.usedTokens)
        assertEquals(lastResetAt, dao.getUsage(modelId.toString())?.lastResetAt)
    }

    @Test
    fun `getQuotaUsage resets after monthly fallback day arrives`() = runBlocking {
        val modelId = Uuid.parse("00000000-0000-0000-0000-000000000301")
        val now = millis("2026-02-28T00:30:00")
        val dao = FakeModelQuotaUsageDao(
            ModelQuotaUsageEntity(
                modelId = modelId.toString(),
                inputTokens = 10,
                outputTokens = 5,
                cachedTokens = 0,
                lastResetAt = millis("2026-01-31T00:00:00"),
                lastUpdatedAt = millis("2026-02-01T00:00:00"),
            )
        )
        val repo = ModelQuotaRepository(
            dao = dao,
            nowMillis = { now },
            zoneId = zoneId,
        )
        val model = quotaModel(
            id = modelId,
            quota = ModelQuota(
                enabled = true,
                tokenLimit = 100,
                resetPeriod = QuotaResetPeriod.MONTHLY,
                resetDayOfMonth = 31,
            )
        )

        val usage = repo.getQuotaUsage(model, listOf(model))

        assertEquals(0L, usage?.usedTokens)
        assertEquals(now, dao.getUsage(modelId.toString())?.lastResetAt)
    }

    private fun quotaModel(id: Uuid, quota: ModelQuota?): Model {
        return Model(
            id = id,
            modelId = id.toString(),
            displayName = id.toString(),
            quota = quota,
        )
    }

    private fun millis(value: String): Long {
        return LocalDateTime.parse(value)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
    }

    private class FakeModelQuotaUsageDao(
        vararg initialUsage: ModelQuotaUsageEntity,
    ) : ModelQuotaUsageDAO {
        private val rows = MutableStateFlow(
            initialUsage.associateBy { it.modelId }.toMutableMap()
        )

        override suspend fun getUsage(modelId: String): ModelQuotaUsageEntity? {
            return rows.value[modelId]
        }

        override suspend fun getUsageForModels(modelIds: List<String>): List<ModelQuotaUsageEntity> {
            val ids = modelIds.toSet()
            return rows.value.values.filter { it.modelId in ids }
        }

        override fun getUsageForModelsFlow(modelIds: List<String>): Flow<List<ModelQuotaUsageEntity>> {
            val ids = modelIds.toSet()
            return rows.map { currentRows ->
                currentRows.values.filter { it.modelId in ids }
            }
        }

        override suspend fun insertIfMissing(entity: ModelQuotaUsageEntity) {
            updateRows { currentRows ->
                currentRows.putIfAbsent(entity.modelId, entity)
            }
        }

        override suspend fun addUsage(
            modelId: String,
            inputTokens: Long,
            outputTokens: Long,
            cachedTokens: Long,
            timestamp: Long,
        ) {
            updateRows { currentRows ->
                val current = currentRows[modelId] ?: return@updateRows
                currentRows[modelId] = current.copy(
                    inputTokens = current.inputTokens + inputTokens,
                    outputTokens = current.outputTokens + outputTokens,
                    cachedTokens = current.cachedTokens + cachedTokens,
                    lastUpdatedAt = timestamp,
                )
            }
        }

        override suspend fun resetUsage(modelId: String, timestamp: Long) {
            resetUsageForModels(listOf(modelId), timestamp)
        }

        override suspend fun resetUsageForModels(modelIds: List<String>, timestamp: Long) {
            val ids = modelIds.toSet()
            updateRows { currentRows ->
                ids.forEach { id ->
                    val current = currentRows[id] ?: return@forEach
                    currentRows[id] = current.copy(
                        inputTokens = 0,
                        outputTokens = 0,
                        cachedTokens = 0,
                        lastResetAt = timestamp,
                        lastUpdatedAt = timestamp,
                    )
                }
            }
        }

        private fun updateRows(block: (MutableMap<String, ModelQuotaUsageEntity>) -> Unit) {
            val nextRows = rows.value.toMutableMap()
            block(nextRows)
            rows.value = nextRows
        }
    }
}
