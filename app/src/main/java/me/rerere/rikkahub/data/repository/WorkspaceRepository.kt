package me.rerere.rikkahub.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDao
import me.rerere.rikkahub.data.db.entity.WORKSPACE_TOOL_NAMES
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.entity.toolDefaultNeedsApproval
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/**
 * 工作区仓库。这一阶段工作区 = 一个 SAF 授权目录，绑定在助手上。
 *
 * 不做沙盒（shellStatus 恒为 DISABLED）。未来移植沙盒时在此扩展 installRootfs / executeCommand 等。
 */
class WorkspaceRepository(
    private val dao: WorkspaceDao,
    private val settingsStore: SettingsStore,
    private val context: Context,
) {
    fun listFlow() = dao.listFlow()

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    suspend fun getByTreeUri(treeUri: String): WorkspaceEntity? = dao.getByTreeUri(treeUri)

    suspend fun getAll(): List<WorkspaceEntity> = dao.getAll()

    /**
     * 创建工作区。名字与 treeUri 均查重。
     * @param name 工作区名（trim 后非空，否则用默认名）
     * @param treeUri SAF 授权目录 URI（必须未被其他工作区占用）
     * @return 新建的 WorkspaceEntity
     * @throws IllegalArgumentException 名字已被占用或 treeUri 已被占用
     */
    suspend fun create(name: String, treeUri: String): WorkspaceEntity = withContext(Dispatchers.IO) {
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
        }
        require(dao.getByTreeUri(treeUri) == null) {
            "Workspace tree uri already exists: $treeUri"
        }
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            treeUri = treeUri,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        dao.upsert(workspace)
        workspace
    }

    suspend fun rename(id: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    /**
     * 设置某工具在某工作区的审批覆盖。
     * @param needsApproval true = 该工具需要审批卡片；false = 免审批
     */
    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = dao.getById(id) ?: return false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        dao.updateToolApprovals(
            id = id,
            toolApprovals = JsonInstant.encodeToString(overrides),
            updatedAt = System.currentTimeMillis(),
        )
        return true
    }

    /**
     * 一次性设置全部工具审批覆盖（用于详情页批量保存）。
     */
    suspend fun setToolApprovals(id: String, overrides: Map<String, Boolean>): Boolean {
        val workspace = dao.getById(id) ?: return false
        dao.updateToolApprovals(
            id = id,
            toolApprovals = JsonInstant.encodeToString(overrides),
            updatedAt = System.currentTimeMillis(),
        )
        return true
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        dao.deleteById(id)
        cleanupAssistantReferences(id)
        true
    }

    /**
     * 把引用了该工作区的助手的 workspaceId 置 null。
     */
    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId == workspaceId) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    /**
     * 完整性检查：遍历所有工作区，treeUri 访问失效（目录不存在/被撤销授权）则删除记录并清理助手引用。
     * 在 App 启动时调用。
     *
     * 同时做一次性「默认审批覆盖」迁移：toolApprovals 仍为空（"{}"，从未手动设置过）的工作区，
     * 写入默认审批覆盖（敏感操作需审批，其余免审批）。写过后即非空，下次启动不会重复覆盖，
     * 用户后续的修改也不会被回滚。
     */
    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        for (workspace in dao.getAll()) {
            if (!isTreeUriAccessible(workspace.treeUri)) {
                dao.deleteById(workspace.id)
                cleanupAssistantReferences(workspace.id)
                continue
            }
            // 解析后为空 map 才迁移；用解析而非字符串比较，兼容旧版可能存在的空串/非标准 JSON
            if (workspace.toolApprovalOverrides().isEmpty()) {
                migrateDefaultToolApprovals(workspace)
            }
        }
    }

    /**
     * 给从未设置过审批的工作区写入默认审批覆盖。
     * 默认值见 [me.rerere.rikkahub.data.db.entity.DEFAULT_NEEDS_APPROVAL_TOOLS]：
     * 写文件/删除/执行 python/执行脚本需审批，其余免审批。
     */
    private suspend fun migrateDefaultToolApprovals(workspace: WorkspaceEntity) {
        val defaults = WORKSPACE_TOOL_NAMES.associateWith { tool ->
            toolDefaultNeedsApproval(tool)
        }
        dao.updateToolApprovals(
            id = workspace.id,
            toolApprovals = JsonInstant.encodeToString(defaults),
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun isTreeUriAccessible(uriString: String): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        val rootDoc = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
        return rootDoc?.isDirectory == true
    }

    /**
     * 从 treeUri 解析出一个友好显示名（授权目录的最后一段），失败回退到 [fallback]。
     */
    fun friendlyName(treeUri: String, fallback: String): String {
        val doc = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) }.getOrNull()
        return doc?.name?.takeIf { it.isNotBlank() } ?: fallback
    }

    /**
     * 把 [treeUri] 解析成可读短路径（如 "Documents/chat"）。
     *
     * 先尝试从 URI path 段解码出真实路径并去掉卷标识（如 primary/）；
     * 失败则回退到 [friendlyName]。供工作区列表卡片副标题等复用。
     * 内部无 SAF 查询（纯 URI 解析），但稳妥起见仍建议在 IO 线程调用。
     */
    fun friendlyShortPath(treeUri: String): String {
        val decoded = runCatching {
            val raw = Uri.parse(treeUri)
                .pathSegments
                .dropWhile { it != "tree" }
                .drop(1) // 去掉 "tree" 段
                .joinToString("/")
            val path = Uri.decode(raw).replace(':', '/')
            // 去掉开头的卷标识（如 "primary/"），保留后面的相对路径
            path.substringAfter('/').takeIf { it.isNotBlank() }
        }.getOrNull()
        if (!decoded.isNullOrEmpty()) return "/$decoded"
        val friendly = runCatching { friendlyName(treeUri, treeUri) }.getOrNull()
        // friendlyName 失败时回退返回原始 treeUri，此时不加前缀，避免出现 "/content://..."
        return if (!friendly.isNullOrBlank() && friendly != treeUri) "/$friendly" else treeUri
    }

    /**
     * 把 [treeUri] 解析成可操作的 [DocumentFile] 根目录。授权失效/不可访问返回 null。
     * 内部只做同步的 DocumentFile.fromTreeUri，但仍建议在 IO 线程调用以稳妥。
     */
    fun resolveRoot(treeUri: String): DocumentFile? = runCatching {
        DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
    }.getOrNull()?.takeIf { it.isDirectory }
}