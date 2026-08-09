package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

private const val TAG = "ChatReadPositionStore"

private val Context.chatReadPositionDataStore by preferencesDataStore(name = "chat_read_positions")

/**
 * 会话阅读位置的独立存储。
 *
 * 阅读位置随滚动高频更新，之前存在全局 Settings 里：每次更新都要重新序列化
 * 整份 Settings（约 90 个 key、含所有助手/提供商等大对象）并推送给全 App 的
 * Settings 订阅者。挪到独立 DataStore 后，滚动落盘只写这一份小文件，
 * 也不再触发任何 Settings 订阅者重组。
 *
 * 内存态 [positionsFlow] 在构造时从磁盘加载（含旧版 Settings 数据的一次性迁移），
 * [readyFlow] 变 true 后即可同步读取；写入先更新内存再异步落盘。
 */
class ChatReadPositionStore(
    private val context: Context,
    private val settingsStore: SettingsStore,
    scope: AppScope,
) {
    private val dataStore = context.chatReadPositionDataStore

    private object Keys {
        val POSITIONS = stringPreferencesKey("positions")
    }

    private val _positions = MutableStateFlow<Map<String, ConversationReadPosition>>(emptyMap())
    val positionsFlow: StateFlow<Map<String, ConversationReadPosition>> = _positions.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val readyFlow: StateFlow<Boolean> = _ready.asStateFlow()

    private val writeMutex = Mutex()

    // 初始加载失败时内存是空的，此时若照常落盘会把磁盘上的完整数据整体覆盖掉。
    // 置 false 后 update/remove 只改内存（本次会话内行为正常），不再写盘；
    // replaceAll（备份恢复，语义就是整体替换）不受限并会重新放开落盘。
    @Volatile
    private var persistAllowed = true

    init {
        scope.launch(Dispatchers.IO) {
            runCatching { loadAndMigrate() }
                .onFailure {
                    Log.w(TAG, "Failed to load read positions, disable persistence", it)
                    persistAllowed = false
                }
            _ready.value = true
        }
    }

    private suspend fun loadAndMigrate() {
        val own = dataStore.data.first()[Keys.POSITIONS]
        if (own != null) {
            val decoded = decode(own)
            _positions.value = decoded ?: emptyMap()
            if (decoded == null) {
                // 自有数据存在但解码失败：停写保护磁盘现场，避免后续滚动用空表覆盖
                Log.w(TAG, "Own read positions corrupted, disable persistence")
                persistAllowed = false
            }
            // 迁移完成后老 key 可能因进程中断而残留，顺手清一次（幂等）。
            // 清理失败与本 store 无关，只记日志，不能连累 persistAllowed。
            cleanupLegacyKey()
            return
        }

        // 首次启动新版本：把旧版存在 Settings DataStore 里的数据搬过来。
        // 顺序保证不丢数据：拷贝先落盘、确认成功后才删除旧 key；
        // 中途进程被杀或落盘失败，旧 key 仍在，下次启动重试迁移。
        val legacy = settingsStore.peekLegacyConversationReadPositions() ?: return
        val positions = sanitizeConversationReadPositions(decode(legacy) ?: emptyMap())
        _positions.value = positions
        if (positions.isEmpty() || persist(positions)) {
            cleanupLegacyKey()
        }
    }

    private suspend fun cleanupLegacyKey() {
        runCatching { settingsStore.removeLegacyConversationReadPositions() }
            .onFailure { Log.w(TAG, "Failed to remove legacy read positions key", it) }
    }

    private fun decode(raw: String): Map<String, ConversationReadPosition>? =
        runCatching { JsonInstant.decodeFromString<Map<String, ConversationReadPosition>>(raw) }
            .getOrNull()

    private suspend fun persist(positions: Map<String, ConversationReadPosition>): Boolean {
        return try {
            dataStore.edit { preferences ->
                preferences[Keys.POSITIONS] = JsonInstant.encodeToString(positions)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist read positions", e)
            false
        }
    }

    /** 同步读当前内存态；[readyFlow] 为 true 前可能返回 null（磁盘尚未加载完） */
    fun get(conversationId: Uuid): ConversationReadPosition? =
        _positions.value[conversationId.toString()]

    suspend fun update(conversationId: Uuid, position: ConversationReadPosition) {
        awaitReady()
        writeMutex.withLock {
            val current = _positions.value
            val key = conversationId.toString()
            if (!shouldPersistConversationReadPosition(current[key], position)) return
            var next = current + (key to position)
            if (next.size > MAX_ENTRIES) {
                next = sanitizeConversationReadPositions(next, MAX_ENTRIES)
            }
            _positions.value = next
            if (persistAllowed) persist(next)
        }
    }

    suspend fun remove(conversationId: Uuid) {
        awaitReady()
        writeMutex.withLock {
            val current = _positions.value
            val next = current - conversationId.toString()
            if (next.size == current.size) return
            _positions.value = next
            if (persistAllowed) persist(next)
        }
    }

    /** 备份恢复用：整体替换（写入前做校验与截断） */
    suspend fun replaceAll(positions: Map<String, ConversationReadPosition>) {
        awaitReady()
        writeMutex.withLock {
            val next = sanitizeConversationReadPositions(positions, MAX_ENTRIES)
            _positions.value = next
            if (persist(next)) {
                persistAllowed = true
            }
        }
    }

    suspend fun awaitReady() {
        readyFlow.first { it }
    }

    companion object {
        private const val MAX_ENTRIES = 500
    }
}

/** 位置没有实质变化时跳过落盘（nodeId/offset/itemIndex 均相同） */
internal fun shouldPersistConversationReadPosition(
    existing: ConversationReadPosition?,
    incoming: ConversationReadPosition,
): Boolean {
    if (existing == null) return true
    return existing.nodeId != incoming.nodeId ||
        existing.offset != incoming.offset ||
        existing.itemIndex != incoming.itemIndex
}
