package me.rerere.rikkahub.data.migration

import android.util.Log
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

/**
 * 一次性迁移：把旧的会话级工作区数据迁移到助手级工作区。
 *
 * 旧数据（Settings）：
 * - workspaceRootTreeUri: 全局默认根目录 URI
 * - conversationWorkspaceRoots: Map<会话id字符串, 根URI> —— 每会话根目录覆盖
 * - conversationWorkDirs: Map<会话id字符串, ConversationWorkDirBinding> —— 含 relPath 子路径
 *
 * 迁移后：
 * - 每个出现过的不同根 URI 去重建一个 WorkspaceEntity（treeUri 唯一）
 * - 每个助手绑定到它“最近一次会话用过的根 URI”对应的工作区
 * - 子路径信息（relPath）丢弃；旧文件仍在原 SAF 位置
 * - 旧 DataStore 字段保留（这一版不删，由清理步骤处理）
 *
 * 幂等：用 SharedPreferences 标记位短路。
 */
class WorkspaceMigration(
    private val settingsStore: SettingsStore,
    private val workspaceRepository: WorkspaceRepository,
    private val conversationRepository: ConversationRepository,
) {
    suspend fun migrateIfNeeded(prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(KEY_DONE, false)) return
        try {
            migrate()
            prefs.edit().putBoolean(KEY_DONE, true).apply()
            Log.i(TAG, "Workspace v2 migration completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Workspace v2 migration failed", e)
        }
    }

    private suspend fun migrate() {
        val settings = settingsStore.settingsFlow.value
        // 旧字段（迁移期间仍存在）
        val globalRoot = settings.workspaceRootTreeUri?.trim()?.takeIf { it.isNotBlank() }
        val perConvRoots: Map<String, String> = settings.conversationWorkspaceRoots
            .mapKeys { it.key.trim() }
            .mapValues { it.value.trim() }
            .filterValues { it.isNotBlank() }

        // 1. 收集所有出现过的不同根 URI
        val allRootUris: Set<String> = buildSet {
            globalRoot?.let { add(it) }
            addAll(perConvRoots.values)
        }
        if (allRootUris.isEmpty()) return // 旧用户从未配过工作区，无需迁移

        // 2. 每个根 URI 建工作区（去重：treeUri 唯一索引 + getByTreeUri 复用）
        val uriToWorkspaceId = mutableMapOf<String, String>()
        for (uri in allRootUris) {
            val existing = workspaceRepository.getByTreeUri(uri)
            if (existing != null) {
                uriToWorkspaceId[uri] = existing.id
                continue
            }
            val friendlyName = workspaceRepository.friendlyName(uri, fallback = "Workspace")
            val uniqueName = ensureUniqueName(friendlyName)
            val created = workspaceRepository.create(name = uniqueName, treeUri = uri)
            uriToWorkspaceId[uri] = created.id
        }

        // 3. 助手自动绑定：每个助手取它最近一次会话用过的根 URI
        val conversations: List<Conversation> = conversationRepository.getAllLightConversations().first()

        // 按 assistantId 分组，每组取 updateAt 最大的会话
        val latestConvByAssistant: Map<Uuid, Conversation> =
            conversations
                .filter { it.updateAt != null }
                .groupBy { it.assistantId }
                .mapValues { (_, list) -> list.maxByOrNull { it.updateAt }!! }

        val assistantIdToWorkspaceId = mutableMapOf<Uuid, String>()
        for ((assistantId, latestConv) in latestConvByAssistant) {
            val convKey = latestConv.id.toString()
            val rootUri = perConvRoots[convKey] ?: globalRoot ?: continue
            val wsId = uriToWorkspaceId[rootUri] ?: continue
            assistantIdToWorkspaceId[assistantId] = wsId
        }

        if (assistantIdToWorkspaceId.isEmpty()) return

        // 4. 写入助手的 workspaceId
        settingsStore.update { s ->
            s.copy(
                assistants = s.assistants.map { assistant ->
                    val wsId = assistantIdToWorkspaceId[assistant.id]
                    if (wsId != null) assistant.copy(workspaceId = wsId) else assistant
                }
            )
        }
    }

    /** 工作区名字去重（不依赖 repo.isNameTaken 的 trim 行为，简单递增后缀） */
    private suspend fun ensureUniqueName(base: String): String {
        val all = workspaceRepository.getAll().map { it.name.trim() }.toSet()
        if (base !in all) return base
        var i = 2
        while ("$base ($i)" in all) i++
        return "$base ($i)"
    }

    companion object {
        private const val TAG = "WorkspaceMigration"
        private const val KEY_DONE = "workspace_v2_migrated"
    }
}