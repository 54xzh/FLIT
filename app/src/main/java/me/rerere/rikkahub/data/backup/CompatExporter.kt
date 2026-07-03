package me.rerere.rikkahub.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.repository.StorageScanUtils

private const val TAG = "CompatExporter"
private const val COMPAT_DB_PREFIX = "compat_v24_"

// 目标 = 用户安装的原版 RikkaHub 2.3.4 发布版 (release, applicationId 无后缀)。
// 本机头像 / 聊天附件图片以 file:///data/data/<本机包>/files/... 存储, 直接拷给原版时
// Coil 按绝对路径读取, 路径里的包名是本机的, 原版读不到自己私有目录之外的文件。
// 导出时把这些 file:// 绝对路径重写成原版 filesDir/upload/ 下的路径, 并把对应文件
// 打进 zip 的 upload/ 目录 (原版恢复时会把 upload/* 落到它自己的 filesDir/upload/)。
private const val TARGET_UPSTREAM_PACKAGE = "me.rerere.rikkahub"
private const val TARGET_UPSTREAM_FILES_DIR = "/data/data/$TARGET_UPSTREAM_PACKAGE/files"

/**
 * 导出"原版 RikkaHub 客户端兼容"的备份包。
 *
 * 与本地 LastChat 备份的差异:
 * - 文件名前缀 `backup_`(原版只认这个前缀)
 * - 数据库降级到 v24 schema: 把本地 conversationentity.nodes 的消息树 JSON 拆到独立 message_node 表;
 *   跳过群聊对话;丢弃本地特有表(embedding/定时任务/工具结果归档等)
 * - nodes JSON 里 UIMessagePart 的多态 discriminator 从全限定类名改成原版 @SerialName 短名,
 *   并剔除原版不认识的 part 类型(Thinking/ToolApproval/AskUser)
 * - settings.json 做白名单过滤,只保留原版 Settings 认识的字段
 * - 只带 upload/ 文件目录(扁平),不带 skills/fonts
 *
 * 不影响本地现有的 LastChat 备份/恢复逻辑,纯新增。
 */
class CompatExporter(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val conversationDAO: ConversationDAO,
    private val memoryDAO: MemoryDAO,
    private val genMediaDAO: GenMediaDAO,
) {
    data class ExportResult(
        val file: File,
        val conversationCount: Int,
        val messageNodeCount: Int,
        val skippedGroupChatCount: Int,
        val avatarCount: Int,
    )

    /**
     * 跨包文件重映射表。
     * key   = 本机 filesDir 下源文件的真实绝对路径 (canonical)
     * value = 重写后该文件在原版备份里对应的相对名 (会放进 zip 的 upload/ 目录)
     *
     * 导出过程中遇到的每个本地 file:// 路径 (头像 / 消息附件), 都会在这里登记一份,
     * 统一在 zip 里以 upload/<uuid>.<ext> 落盘, 并把引用处的 URL 改写成原版路径。
     */
    private val sourcePathToTargetName = LinkedHashMap<String, String>()

    /** 反向: 原版 upload 目标名 -> 本地源文件 (打包用)。 */
    private val targetNameToSourceFile = LinkedHashMap<String, File>()

    /**
     * 把一个本地 file:// / 绝对路径 / 相对路径 注册进重映射表, 返回改写后的原版 file:// 路径。
     * 多次注册同一个文件会复用已分配的目标名 (幂等)。
     * 解析失败 / 指向 filesDir 之外 / 文件不存在时返回 null (调用方保留原 URL)。
     */
    private fun remapLocalUriToUpstream(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        val srcFile = StorageScanUtils.toLocalFileOrNull(rawUrl, context.filesDir.canonicalFile)
            ?: return null
        // 只重映射落在本机 filesDir 下的本地文件, 不动 http(s) / 外部 content:// 等
        if (!StorageScanUtils.isInChildOf(srcFile, context.filesDir.canonicalFile)) return null
        if (!srcFile.isFile || !srcFile.exists()) return null
        val canonical = StorageScanUtils.normalizePath(srcFile)
        val targetName = sourcePathToTargetName.getOrPut(canonical) {
            val ext = srcFile.extension.ifBlank { "bin" }
            "avatar_${Uuid.random()}.$ext".also { targetNameToSourceFile[it] = srcFile }
        }
        return "file://$TARGET_UPSTREAM_FILES_DIR/upload/$targetName"
    }

    suspend fun exportRikkaHubCompat(): ExportResult = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        if (settings.init) throw IllegalStateException("Settings not ready")

        val groupChatIds: Set<String> = settings.groupChatTemplates
            .map { it.id.toString() }
            .toSet()

        // 读取所有对话(原始本地格式)
        val allConversations = conversationDAO.getAllConversationsSuspend()
        val memories = runCatching { memoryDAO.getAllMemoriesSuspend() }.getOrDefault(emptyList())
        val genMedia = runCatching { genMediaDAO.getAllMedia() }.getOrDefault(emptyList())

        // 分群聊与普通对话
        val normalConversations = allConversations.filter { it.assistantId !in groupChatIds }
        val skippedCount = allConversations.size - normalConversations.size

        // 建一个新的 v24 db 文件
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val dbFile = File(context.cacheDir, "${COMPAT_DB_PREFIX}${timestamp}.db")
        if (dbFile.exists()) dbFile.delete()

        var messageNodeCount = 0
        try {
            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.CREATE_IF_NECESSARY,
            )
            db.use {
                it.execSQL("PRAGMA foreign_keys = OFF")
                createV24Schema(it)
                populateConversations(it, normalConversations)
                messageNodeCount = populateMessageNodes(it, normalConversations)
                populateMemories(it, memories)
                populateGenMedia(it, genMedia)
                it.execSQL("PRAGMA user_version = 23")
                it.execSQL("PRAGMA foreign_keys = ON")
                // 强制把所有数据落盘到主 db 文件, 切回普通回滚日志模式并清掉 wal/shm 旁路文件。
                // 上游恢复时只覆盖 rikka_hub.db 主文件, 不删旧的 -wal/-shm; 若残留旧的 WAL 会把
                // 主文件覆写成旧内容 (实测: 原版重开时 recovered N frames from WAL → 新数据被冲掉)。
                // 导出一个无 WAL 旁路的干净 db, 并在 zip 里塞空白 -wal/-shm 覆盖目标残留。
                //
                // 注意: 这两个 PRAGMA 都会返回结果行, 必须用 rawQuery 消费 cursor;
                // 用 execSQL 会抛 "Queries can be performed using ... query or rawQuery methods only"。
                it.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c -> if (c.moveToFirst()) c.getString(0) }
                it.rawQuery("PRAGMA journal_mode = DELETE", null).use { c -> if (c.moveToFirst()) c.getString(0) }
            }
        } catch (e: Exception) {
            if (dbFile.exists()) dbFile.delete()
            throw e
        }

        // 自检: 重开 db 验证产物是否真正落地 (行数 / user_version / 关键表存在)
        verifyExportedDb(dbFile)
        // 诊断: 核对会话 assistant_id 能否在上游 settings 的助手列表里命中,
        // 排查"恢复后会话列表为空"的根因 (上游按当前选中助手过滤列表)。
        diagnoseAssistantIdMatch(dbFile, settings)
        // 兜底: 删掉自检/写入过程可能产生的 -wal/-shm 旁路文件, 确保导出的是干净主库
        File(dbFile.parentFile, dbFile.name + "-wal").delete()
        File(dbFile.parentFile, dbFile.name + "-shm").delete()

        // settings.json 白名单过滤
        val settingsJson = buildCompatSettingsJson(settings)

        // 打包 zip
        val zipFile = File(context.cacheDir, "backup_$timestamp.zip")
        if (zipFile.exists()) zipFile.delete()
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
                addVirtualFileToZip(zipOut, "settings.json", settingsJson)
                addFileToZip(zipOut, dbFile, "rikka_hub.db")
                // 塞空的 rikka_hub-wal / rikka_hub-shm: 上游恢复只覆盖这三个文件名,
                // 若不提供 -wal/-shm, 目标库残留的旧 WAL 会在重开时把主文件覆写成旧内容。
                // 这里用 0 字节占位覆盖掉目标残留的脏 -wal/-shm。
                addVirtualFileToZip(zipOut, "rikka_hub-wal", "")
                addVirtualFileToZip(zipOut, "rikka_hub-shm", "")
                addUploadDirToZip(zipOut)
            }
        } finally {
            if (dbFile.exists()) dbFile.delete()
        }

        Log.i(
            TAG,
            "exportRikkaHubCompat: done. conversations=${normalConversations.size}, " +
                "nodes=$messageNodeCount, skippedGroupChats=$skippedCount, " +
                "avatarFiles=${targetNameToSourceFile.size}, " +
                "file=${zipFile.name} (${zipFile.length()} bytes)"
        )

        ExportResult(
            file = zipFile,
            conversationCount = normalConversations.size,
            messageNodeCount = messageNodeCount,
            skippedGroupChatCount = skippedCount,
            avatarCount = targetNameToSourceFile.size,
        )
    }

    // ---------- v24 schema ----------

    /**
     * 自检: 重新打开导出的 db 文件, 验证数据真正落盘 + schema 关键项正确。
     * 主要用于排查"导入后聊天列表空"——区分是导出产物就没数据, 还是上游 Room 拒收。
     */
    private fun verifyExportedDb(dbFile: File) {
        try {
            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            db.use {
                val userVersion = it.rawQuery("PRAGMA user_version", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else -1
                }
                val journalMode = it.rawQuery("PRAGMA journal_mode", null).use { c ->
                    if (c.moveToFirst()) c.getString(0) else "?"
                }
                val walExists = File(dbFile.parentFile, dbFile.name + "-wal").exists()
                Log.i(
                    TAG,
                    "verifyExportedDb: user_version=$userVersion, journal_mode=$journalMode, " +
                        "wal_sidecar_exists=$walExists"
                )
                val convCount = it.rawQuery("SELECT COUNT(*) FROM ConversationEntity", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else -1
                }
                val nodeCount = it.rawQuery("SELECT COUNT(*) FROM message_node", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else -1
                }
                val tables = it.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name",
                    null,
                ).use { c ->
                    val names = mutableListOf<String>()
                    while (c.moveToNext()) names.add(c.getString(0))
                    names
                }
                Log.i(
                    TAG,
                    "verifyExportedDb: conversationentity rows=$convCount, message_node rows=$nodeCount, " +
                        "tables=$tables"
                )
                // 抽查一条 conversation + 对应 message_node, 确认关联存在
                if (convCount > 0) {
                    it.rawQuery(
                        "SELECT id, assistant_id, title, length(nodes) FROM ConversationEntity LIMIT 3",
                        null,
                    ).use { c ->
                        while (c.moveToNext()) {
                            val cid = c.getString(0)
                            val aid = c.getString(1)
                            val title = c.getString(2)
                            val nodesLen = c.getInt(3)
                            val linkedNodes = it.rawQuery(
                                "SELECT COUNT(*) FROM message_node WHERE conversation_id=?",
                                arrayOf(cid),
                            ).use { cc -> if (cc.moveToFirst()) cc.getInt(0) else -1 }
                            Log.i(
                                TAG,
                                "verifyExportedDb: conv id=$cid aid=$aid title=$title " +
                                    "nodes_len=$nodesLen linked_msg_nodes=$linkedNodes"
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "verifyExportedDb: FAILED to verify exported db", e)
        }
    }

    /**
     * 诊断会话 assistant_id 与 settings 助手列表的匹配情况。
     *
     * 上游会话列表按当前选中助手过滤 (WHERE assistant_id = :settings.assistantId)。
     * 若导出会话的 assistant_id 都对不上当前选中助手, 恢复后列表就全空。
     * 这里把"当前选中助手 / 助手列表 / db 会话助手分布 + 命中率"打出来, 方便定位。
     */
    private fun diagnoseAssistantIdMatch(dbFile: File, settings: Settings) {
        try {
            val selectedAssistantId = settings.assistantId.toString()
            val assistantIds = settings.assistants.map { it.id.toString() }.toSet()
            Log.i(
                TAG,
                "diagnoseAid: selectedAssistantId=$selectedAssistantId, " +
                    "assistantsInSettings=${assistantIds.size}, " +
                    "containsSelected=${assistantIds.contains(selectedAssistantId)}"
            )
            Log.i(TAG, "diagnoseAid: assistant ids=${assistantIds.joinToString(limit = 30)}")
            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
            )
            db.use {
                // 统计 db 里各 assistant_id 的会话条数, 并标记是否命中 settings 助手列表
                it.rawQuery(
                    "SELECT assistant_id, COUNT(*) FROM ConversationEntity GROUP BY assistant_id",
                    null,
                ).use { c ->
                    var matchRows = 0
                    var totalRows = 0
                    while (c.moveToNext()) {
                        val aid = c.getString(0)
                        val cnt = c.getInt(1)
                        totalRows += cnt
                        val selected = aid == selectedAssistantId
                        if (assistantIds.contains(aid)) matchRows += cnt
                        Log.i(
                            TAG,
                            "diagnoseAid: db aid=$aid conv_count=$cnt " +
                                "is_selected_assistant=$selected " +
                                "exists_in_assistants=${assistantIds.contains(aid)}"
                        )
                    }
                    Log.i(
                        TAG,
                        "diagnoseAid: summary total_conv_rows=$totalRows, " +
                            "matched_by_any_assistant=$matchRows"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "diagnoseAid: failed", e)
        }
    }

    private fun createV24Schema(db: SQLiteDatabase) {
        // conversationentity (upstream v24)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `ConversationEntity` (
                `id` TEXT NOT NULL,
                `assistant_id` TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e',
                `title` TEXT NOT NULL,
                `nodes` TEXT NOT NULL,
                `create_at` INTEGER NOT NULL,
                `update_at` INTEGER NOT NULL,
                `suggestions` TEXT NOT NULL DEFAULT '[]',
                `is_pinned` INTEGER NOT NULL DEFAULT 0,
                `custom_system_prompt` TEXT NOT NULL DEFAULT '',
                `mode_injection_ids` TEXT NOT NULL DEFAULT '[]',
                `lorebook_ids` TEXT NOT NULL DEFAULT '[]',
                `workspace_cwd` TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        // message_node (upstream 2.3.4 / schema v23)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `message_node` (
                `id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `node_index` INTEGER NOT NULL,
                `messages` TEXT NOT NULL,
                `select_index` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_message_node_conversation_id` " +
                "ON `message_node` (`conversation_id`)"
        )

        // MemoryEntity (上游无 tableName 注解 → 默认表名 = 类名 MemoryEntity)
        // 严格对齐 23.json: content 无 default
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `MemoryEntity` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `assistant_id` TEXT NOT NULL,
                `content` TEXT NOT NULL
            )
            """.trimIndent()
        )

        // genmediaentity (上游无 tableName → 默认表名 GenMediaEntity)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `GenMediaEntity` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `path` TEXT NOT NULL,
                `model_id` TEXT NOT NULL,
                `prompt` TEXT NOT NULL,
                `create_at` INTEGER NOT NULL,
                `type` TEXT NOT NULL DEFAULT 'image_generation',
                `source_paths` TEXT
            )
            """.trimIndent()
        )

        // 上游特有的表(空表, Room 全库 hash 校验需要表名/列/index 全部对齐)
        // 表名严格按上游 @Entity(tableName=...) 注解
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `managed_files` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `folder` TEXT NOT NULL,
                `relative_path` TEXT NOT NULL,
                `display_name` TEXT NOT NULL,
                `mime_type` TEXT NOT NULL,
                `size_bytes` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_managed_files_relative_path` ON `managed_files` (`relative_path`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_managed_files_folder` ON `managed_files` (`folder`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `favorites` (
                `id` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `ref_key` TEXT NOT NULL,
                `ref_json` TEXT NOT NULL,
                `snapshot_json` TEXT NOT NULL,
                `meta_json` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_favorites_ref_key` ON `favorites` (`ref_key`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_favorites_type` ON `favorites` (`type`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_favorites_created_at` ON `favorites` (`created_at`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `workspaces` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `root` TEXT NOT NULL,
                `shell_status` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `last_access_at` INTEGER,
                `tool_approvals` TEXT NOT NULL DEFAULT '{}',
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workspaces_root` ON `workspaces` (`root`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspaces_updated_at` ON `workspaces` (`updated_at`)")

        // Room identity hash 表: 写入上游 2.3.4 (schema v23) 的真实 identity_hash
        // (取自 upstream tag 2.3.4 的 schemas/23.json, 653d38...1c4)。
        // 目标设备装的是 2.3.4 正式版 (version=23), hash 必须用 23 的, 且 user_version=23;
        // 若用 v24 的 hash/版本, Room 会要求 24→23 降级迁移, 上游未配该迁移会直接崩。
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '653d3893a297d63a5097283eb29c41c4')"
        )
    }

    private fun populateConversations(db: SQLiteDatabase, conversations: List<ConversationEntity>) {
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT INTO ConversationEntity " +
                    "(id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned, " +
                    "custom_system_prompt, mode_injection_ids, lorebook_ids, workspace_cwd) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            )
            for (c in conversations) {
                stmt.bindAllArgsAsStrings(
                    c.id,
                    c.assistantId,
                    c.title.ifEmpty { "" },
                    "[]", // upstream stores real nodes in message_node table
                    c.createAt.toString(),
                    c.updateAt.toString(),
                    c.chatSuggestions.ifBlank { "[]" },
                    if (c.isPinned) "1" else "0",
                    "", // custom_system_prompt
                    "[]", // mode_injection_ids
                    "[]", // lorebook_ids
                    "", // workspace_cwd
                )
                stmt.executeInsert()
                stmt.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun populateMessageNodes(db: SQLiteDatabase, conversations: List<ConversationEntity>): Int {
        var total = 0
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                    "VALUES (?, ?, ?, ?, ?)"
            )
            for (c in conversations) {
                val nodes = decodeAndConvertNodes(c.nodes) ?: continue
                for ((index, node) in nodes.withIndex()) {
                    // 重新生成随机 UUID 作主键, 规避历史数据里可能存在的跨对话重复 node id
                    // (与原版 Migration_11_12 同款做法: 它也是 Uuid.random().toString())
                    val rowId = Uuid.random().toString()
                    stmt.bindAllArgsAsStrings(
                        rowId,
                        c.id,
                        index.toString(),
                        node.messagesJson,
                        node.selectIndex.toString(),
                    )
                    stmt.executeInsert()
                    stmt.clearBindings()
                    total++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return total
    }

    private fun populateMemories(
        db: SQLiteDatabase,
        memories: List<me.rerere.rikkahub.data.db.entity.MemoryEntity>,
    ) {
        if (memories.isEmpty()) return
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT INTO MemoryEntity (assistant_id, content) VALUES (?, ?)"
            )
            for (m in memories) {
                stmt.bindAllArgsAsStrings(m.assistantId, m.content.ifEmpty { "" })
                stmt.executeInsert()
                stmt.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun populateGenMedia(
        db: SQLiteDatabase,
        items: List<me.rerere.rikkahub.data.db.entity.GenMediaEntity>,
    ) {
        if (items.isEmpty()) return
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT INTO GenMediaEntity (path, model_id, prompt, create_at, type, source_paths) " +
                    "VALUES (?, ?, ?, ?, ?, ?)"
            )
            for (g in items) {
                stmt.bindAllArgsAsStrings(
                    g.path,
                    g.modelId,
                    g.prompt,
                    g.createAt.toString(),
                    "image_generation",
                    null,
                )
                stmt.executeInsert()
                stmt.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- nodes JSON 转换 ----------

    private data class ConvertedNode(
        val messagesJson: String,
        val selectIndex: Int,
    )

    private fun decodeAndConvertNodes(nodesJson: String): List<ConvertedNode>? {
        if (nodesJson.isBlank()) return null
        return runCatching {
            val migrated = migrateLegacyNodesJson(nodesJson)
            val root = JsonInstant.parseToJsonElement(migrated)
            if (root !is JsonArray) return@runCatching null
            root.jsonArray.mapNotNull { nodeEl ->
                if (nodeEl !is JsonObject) return@mapNotNull null
                val selectIndex = nodeEl["selectIndex"]?.jsonPrimitive?.intOrNull ?: 0
                val messages = nodeEl["messages"]
                // message_node.messages 列只存 List<UIMessage> 的 JSON (与原版 Migration_11_12 一致),
                // 不含 node 的 id / selectIndex 字段
                val convertedMessages = if (messages is JsonArray) {
                    JsonArray(messages.jsonArray.mapNotNull { msgEl ->
                        if (msgEl !is JsonObject) return@mapNotNull null
                        convertMessage(msgEl)
                    })
                } else {
                    JsonArray(emptyList())
                }
                ConvertedNode(
                    messagesJson = JsonInstant.encodeToString(JsonElement.serializer(), convertedMessages),
                    selectIndex = selectIndex,
                )
            }
        }.onFailure {
            Log.w(TAG, "decodeAndConvertNodes: failed to parse nodes, skipping", it)
        }.getOrNull()
    }

    /** 把单个 UIMessage 的 parts discriminator 改成原版短名,剔除原版不认识的 part 类型。
     *  同时:
     *  - 重写 url/file:// 形式的本地路径指向原版 filesDir/upload/ (跨包名可读);
     *  - 对 parts 顺序做一次"推理前置"归一化 (等价于本地 normalizeMessagePartsForDisplay),
     *    否则原版按数组原序渲染时, 排在正文之后的推理块会显示在正文后面。 */
    private fun convertMessage(msg: JsonObject): JsonElement {
        val rawParts = msg["parts"]
        val convertedParts = if (rawParts is JsonArray) {
            rawParts.jsonArray.mapNotNull { partEl ->
                if (partEl !is JsonObject) return@mapNotNull null
                val type = partEl["type"]?.jsonPrimitive?.contentOrNull
                val mapped = mapPartType(type) ?: return@mapNotNull null
                buildJsonObject {
                    put("type", JsonPrimitive(mapped))
                    partEl.entries.forEach { (pk, pv) ->
                        if (pk == "type") return@forEach
                        if (pk == "url") {
                            // Image / Video / Audio / Document 的 url 重写
                            val url = pv.jsonPrimitiveOrNull?.contentOrNull
                            val remapped = remapLocalUriToUpstream(url)
                            put("url", JsonPrimitive(remapped ?: url))
                        } else if (pk == "text" && mapped == "text") {
                            // Text 正文里可能内嵌 file:// 引用 (markdown 图片), 一并重写
                            put("text", JsonPrimitive(rewriteFileUrlsInText(pv.jsonPrimitiveOrNull?.contentOrNull ?: "")))
                        } else {
                            put(pk, pv)
                        }
                    }
                }
            }
        } else {
            null
        }
        val orderedParts = convertedParts?.let(::normalizeReasoningToFront)
        return buildJsonObject {
            msg.entries.forEach { (key, value) ->
                if (key == "parts" && orderedParts != null) {
                    put("parts", JsonArray(orderedParts))
                } else {
                    put(key, value)
                }
            }
            // 兜底: 原 msg 没有 parts 字段时也补一个空数组 (不应发生, 但防 NPE)
            if (orderedParts != null && !msg.keys.contains("parts")) {
                put("parts", JsonArray(emptyList()))
            }
        }
    }

    /**
     * 把排在第一个"可渲染正文"之后的 Reasoning 块提到它前面, 等价于本地
     * [me.rerere.rikkahub.ui.components.message.normalizeMessagePartsForDisplay] 的行为。
     * 原版 groupMessageParts 按数组原序分块渲染, 不做这种归一化; 不重排就会导致
     * 流式生成时附在正文后的推理块在原版里显示在正文之后。
     */
    private fun normalizeReasoningToFront(parts: List<JsonElement>): List<JsonElement> {
        if (parts.size < 2) return parts
        fun isReasoning(el: JsonElement): Boolean {
            val t = (el as? JsonObject)?.get("type")?.jsonPrimitiveOrNull?.contentOrNull
            return t == "reasoning"
        }
        fun isRenderableContent(el: JsonElement): Boolean {
            val obj = el as? JsonObject ?: return false
            return when (obj["type"]?.jsonPrimitiveOrNull?.contentOrNull) {
                "text" -> obj["text"]?.jsonPrimitiveOrNull?.contentOrNull?.isNotBlank() == true
                "image", "video", "audio", "document" ->
                    obj["url"]?.jsonPrimitiveOrNull?.contentOrNull?.isNotBlank() == true
                else -> false
            }
        }
        val firstContent = parts.indexOfFirst { isRenderableContent(it) }
        if (firstContent < 0) return parts
        // 收集所有排在第一个正文之后的推理块 (本地会把这些前置)
        val deferred = parts.withIndex()
            .filter { (i, p) -> i > firstContent && isReasoning(p) }
            .map { it.value }
        if (deferred.isEmpty()) return parts
        val out = ArrayList<JsonElement>(parts.size)
        parts.forEachIndexed { index, part ->
            if (index == firstContent) out.addAll(deferred)
            if (index > firstContent && isReasoning(part)) return@forEachIndexed
            out.add(part)
        }
        return out
    }

    private val fileUrlInTextRegex = Regex("""file:[^\s")\]]+""")

    /** Text 正文里内嵌的 file:// 引用 (如 markdown 图片) 重写到原版 upload/ 路径。 */
    private fun rewriteFileUrlsInText(text: String): String {
        if (!text.contains("file:")) return text
        return fileUrlInTextRegex.replace(text) { m ->
            remapLocalUriToUpstream(m.value) ?: m.value
        }
    }

    private fun mapPartType(fqcn: String?): String? {
        if (fqcn == null) return null
        return when {
            fqcn.endsWith(".UIMessagePart.Text") -> "text"
            fqcn.endsWith(".UIMessagePart.Image") -> "image"
            fqcn.endsWith(".UIMessagePart.Video") -> "video"
            fqcn.endsWith(".UIMessagePart.Audio") -> "audio"
            fqcn.endsWith(".UIMessagePart.Document") -> "document"
            fqcn.endsWith(".UIMessagePart.Reasoning") -> "reasoning"
            fqcn.endsWith(".UIMessagePart.ToolCall") -> "tool_call"
            fqcn.endsWith(".UIMessagePart.ToolResult") -> "tool_result"
            fqcn.endsWith(".UIMessagePart.Search") -> "search"
            fqcn.endsWith(".UIMessagePart.ToolApproval") -> null // 原版不认识,丢弃
            fqcn.endsWith(".UIMessagePart.AskUser") -> null      // 原版不认识,丢弃
            fqcn.endsWith(".UIMessagePart.Thinking") -> "reasoning" // 已被 migrateLegacyNodesJson 处理,兜底
            else -> null
        }
    }

    /** 内联自 ConversationRepository.migrateLegacyNodesJson: 把 deprecated Thinking 转成 Reasoning。 */
    private fun migrateLegacyNodesJson(json: String): String {
        if (!json.contains("me.rerere.ai.ui.UIMessagePart.Thinking")) return json
        return runCatching {
            val element = JsonInstant.parseToJsonElement(json)
            if (element !is JsonArray) return json
            val newArray = JsonArray(element.jsonArray.map { node ->
                if (node !is JsonObject) return@map node
                buildJsonObject {
                    node.entries.forEach { (key, value) ->
                        if (key == "messages" && value is JsonArray) {
                            put("messages", JsonArray(value.jsonArray.map { message ->
                                if (message !is JsonObject) return@map message
                                buildJsonObject {
                                    message.entries.forEach { (msgKey, msgValue) ->
                                        if (msgKey == "parts" && msgValue is JsonArray) {
                                            put("parts", JsonArray(msgValue.jsonArray.map { part ->
                                                if (part !is JsonObject) return@map part
                                                val type = part["type"]?.jsonPrimitive?.contentOrNull
                                                if (type == "me.rerere.ai.ui.UIMessagePart.Thinking") {
                                                    buildJsonObject {
                                                        put("type", JsonPrimitive("me.rerere.ai.ui.UIMessagePart.Reasoning"))
                                                        part.entries.forEach { (pk, pv) ->
                                                            when (pk) {
                                                                "type" -> {}
                                                                "thinking" -> put("reasoning", pv)
                                                                else -> put(pk, pv)
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    part
                                                }
                                            }))
                                        } else {
                                            put(msgKey, msgValue)
                                        }
                                    }
                                }
                            }))
                        } else {
                            put(key, value)
                        }
                    }
                }
            })
            JsonInstant.encodeToString(JsonElement.serializer(), newArray)
        }.onFailure {
            Log.w(TAG, "migrateLegacyNodesJson: failed", it)
        }.getOrDefault(json)
    }

    // ---------- settings 白名单 ----------

    private fun buildCompatSettingsJson(settings: Settings): String {
        val full = JsonInstant.encodeToString(Settings.serializer(), settings)
        val root = JsonInstant.parseToJsonElement(full) as? JsonObject ?: return full
        val allowed = setOf(
            "init", "dynamicColor", "themeId", "customThemes", "developerMode",
            "displaySetting", "enableWebSearch", "favoriteModels", "chatModelId",
            "imageGenerationModelId", "titleModelId", "titlePrompt", "translateModeId",
            "translatePrompt", "translateThinkingBudget", "enableSuggestion",
            "suggestionModelId", "suggestionPrompt", "ocrModelId", "ocrPrompt",
            "compressModelId", "compressPrompt", "assistantId", "providers",
            "assistants", "assistantTags", "searchServices", "searchCommonOptions",
            "searchServiceSelected", "mcpServers", "webDavConfig", "s3Config",
            "ttsProviders", "selectedTTSProviderId", "asrProviders",
            "selectedASRProviderId", "modeInjections", "lorebooks", "quickMessages",
            "webServerEnabled", "webServerPort", "webServerJwtEnabled",
            "webServerAccessPassword", "webServerLocalhostOnly",
            "backupReminderConfig", "launchCount",
        )
        // s3Config 本地没有,写空对象让原版用默认值反序列化
        val filtered = buildJsonObject {
            root.entries.forEach { (k, v) ->
                if (k in allowed) put(k, sanitizeForUpstream(k, v))
            }
            if (!root.keys.contains("s3Config")) put("s3Config", JsonObject(emptyMap()))
        }
        return JsonInstant.encodeToString(JsonElement.serializer(), filtered)
    }

    /**
     * 清理 settings 里原版 RikkaHub 不认识的多态子类 / 不兼容结构。
     *
     * 与字段级 ignoreUnknownKeys 不同, sealed class 的 discriminator 不命中会直接抛异常,
     * 必须在 JSON 树层级把不认识的子类元素剔除。
     *
     * 已知差异(本地多出、原版没有的 discriminator):
     * - BuiltInTools (providers[].models[].tools[]): claude_web_search / claude_web_search_disabled
     *   / grok_web_search / grok_x_search (原版只有 search / url_context / image_generation)
     * - LocalToolOption (assistants[].localTools[]): python_engine / device_control /
     *   workspace_files / lorebooks_editor / scheduled_task_manager / memory_search /
     *   chat_search / get_current_time (原版只 javascript_engine / ask_user / time_info /
     *   clipboard / tts / screen_time / calendar)
     * - Avatar (displaySetting.userAvatar, assistants[].avatar): Resource (原版只有
     *   Dummy / Emoji / Image) — discard avatar 时用原版默认值由原版兜底
     *
     * 另有不兼容结构:
     * - lorebooks[].entries: 本地 List<LorebookEntry>(普通data class, 无 type discriminator),
     *   原版 List<PromptInjection.RegexInjection>(sealed, 期望 type:"regex")。
     *   结构对不上, 直接清空 entries 最稳妥。
     */
    private fun sanitizeForUpstream(key: String, value: JsonElement): JsonElement {
        return when (key) {
            "providers" -> sanitizeProviders(value)
            "assistants" -> sanitizeAssistants(value)
            "lorebooks" -> sanitizeLorebooks(value)
            "displaySetting" -> sanitizeDisplaySetting(value)
            else -> value
        }
    }

    /** assistants[]{ avatar, localTools } */
    private fun sanitizeAssistants(element: JsonElement): JsonElement {
        if (element !is JsonArray) return element
        return JsonArray(element.jsonArray.mapNotNull { assistantEl ->
            if (assistantEl !is JsonObject) return@mapNotNull assistantEl
            buildJsonObject {
                assistantEl.entries.forEach { (k, v) ->
                    when (k) {
                        "avatar" -> put(k, sanitizeAvatar(v))
                        "localTools" -> put(k, sanitizeLocalTools(v))
                        else -> put(k, v)
                    }
                }
            }
        })
    }

    /** Avatar: 本地多的 Resource 子类原版没有, 替换成原版能解析的 Dummy;
     *  Avatar.Image 的 url 是本机 file:// 绝对路径 (含本机包名), 原版 Coil 读不到,
     *  把 url 重写成原版 filesDir/upload/ 路径, 并把对应头像文件登记进重映射表 (后续打进 zip)。
     *  (sealed class 多态判别器: 不命中会直接抛异常, ignoreUnknownKeys 救不了。
     *   这里显式写入原版认识的判别器, 保证整条 assistants 列表能被解析)。 */
    private fun sanitizeAvatar(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element
        val type = element["type"]?.jsonPrimitive?.contentOrNull
        return when {
            // Avatar 子类均无 @SerialName, discriminator 默认用全限定类名
            type?.endsWith(".Avatar.Resource") == true -> buildJsonObject {
                put("type", JsonPrimitive("me.rerere.rikkahub.data.model.Avatar.Dummy"))
            }
            type?.endsWith(".Avatar.Image") == true -> {
                val url = element["url"]?.jsonPrimitive?.contentOrNull
                val remapped = remapLocalUriToUpstream(url)
                if (remapped == null) return element // 原样保留 (Coil 可能是 http url, 不必动)
                buildJsonObject {
                    put("type", element["type"]!!) // 保持 Discriminator 全限定类名不动 (两边一致)
                    put("url", JsonPrimitive(remapped))
                    element.entries.forEach { (k, v) ->
                        if (k != "type" && k != "url") put(k, v)
                    }
                }
            }
            else -> element
        }
    }

    /** LocalToolOption: 剔除原版不认识的工具。 */
    private fun sanitizeLocalTools(element: JsonElement): JsonElement {
        if (element !is JsonArray) return element
        val allowed = setOf("javascript_engine", "ask_user")
        return JsonArray(element.jsonArray.filter { toolEl ->
            if (toolEl !is JsonObject) return@filter true
            val type = toolEl["type"]?.jsonPrimitive?.contentOrNull
            type in allowed
        })
    }

    /** lorebooks[].entries: 结构对不上, 清空。 */
    private fun sanitizeLorebooks(element: JsonElement): JsonElement {
        if (element !is JsonArray) return element
        return JsonArray(element.jsonArray.mapNotNull { lorebookEl ->
            if (lorebookEl !is JsonObject) return@mapNotNull lorebookEl
            buildJsonObject {
                lorebookEl.entries.forEach { (k, v) ->
                    if (k == "entries") {
                        put("entries", JsonArray(emptyList()))
                    } else {
                        put(k, v)
                    }
                }
            }
        })
    }

    /** displaySetting.userAvatar */
    private fun sanitizeDisplaySetting(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element
        return buildJsonObject {
            element.entries.forEach { (k, v) ->
                if (k == "userAvatar") {
                    put(k, sanitizeAvatar(v))
                } else {
                    put(k, v)
                }
            }
        }
    }

    /**
     * providers 是 List<ProviderSetting>, 序列化为 JsonArray; 每个元素形如
     * {"type":"openai"|...,"apiHost":...,"models":[...]}。遍历每个 provider
     * 条目清理其 models[].tools[]。
     */
    private fun sanitizeProviders(element: JsonElement): JsonElement {
        if (element !is JsonArray) return element
        return JsonArray(element.jsonArray.mapNotNull { providerEl ->
            if (providerEl !is JsonObject) return@mapNotNull providerEl
            sanitizeProviderEntry(providerEl)
        })
    }

    /** 单个 provider 条目: { apiHost, apiKey, models: [...] }
     *  取出 models[].tools[] 过滤掉原版不认识的 BuiltInTools discriminator。 */
    private fun sanitizeProviderEntry(provider: JsonObject): JsonElement {
        val models = provider["models"]
        val cleanedModels = if (models is JsonArray) {
            JsonArray(models.jsonArray.mapNotNull { modelEl ->
                if (modelEl !is JsonObject) return@mapNotNull modelEl
                val tools = modelEl["tools"]
                val cleanedTools = if (tools is JsonArray) {
                    JsonArray(tools.jsonArray.filter { toolEl ->
                        if (toolEl !is JsonObject) return@filter true
                        val type = toolEl["type"]?.jsonPrimitive?.contentOrNull
                        // 原版 BuiltInTools 认识的 discriminator 白名单
                        type in setOf("search", "url_context", "image_generation")
                    })
                } else {
                    tools ?: JsonArray(emptyList())
                }
                buildJsonObject {
                    modelEl.entries.forEach { (mk, mv) ->
                        if (mk == "tools") {
                            put("tools", cleanedTools)
                        } else {
                            put(mk, mv)
                        }
                    }
                    // 若原 model 没有 tools 字段, 不补(原版默认空Set)
                }
            })
        } else {
            models ?: JsonArray(emptyList())
        }
        return buildJsonObject {
            provider.entries.forEach { (pk, pv) ->
                if (pk == "models") {
                    put("models", cleanedModels)
                } else {
                    put(pk, pv)
                }
            }
        }
    }

    // ---------- zip 工具 ----------

    private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
        val entry = ZipEntry(name)
        zipOut.putNextEntry(entry)
        zipOut.write(content.toByteArray(Charsets.UTF_8))
        zipOut.closeEntry()
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { fis ->
            zipOut.putNextEntry(ZipEntry(entryName))
            fis.copyTo(zipOut)
            zipOut.closeEntry()
        }
    }

    private fun addUploadDirToZip(zipOut: ZipOutputStream) {
        // 用重映射表里登记的目标名落盘: 头像 / 聊天附件在 settings/nodes JSON 里都已改写成
        // file://<原版filesDir>/upload/<targetName>, 这里必须用同名文件对应, 否则原版读不到。
        // 没被任何 JSON 引用的本地 upload/ 文件不带 (原版恢复时也不需要它们)。
        for ((targetName, sourceFile) in targetNameToSourceFile) {
            if (!sourceFile.isFile || !sourceFile.exists()) continue
            addFileToZip(zipOut, sourceFile, "upload/$targetName")
        }
    }
}

// 辅助: SQLiteStatement 不接受 null + 一起 bind,这里给个便捷扩展
private fun android.database.sqlite.SQLiteStatement.bindAllArgsAsStrings(vararg args: String?) {
    args.forEachIndexed { index, arg ->
        val idx = index + 1
        if (arg == null) bindNull(idx) else bindString(idx, arg)
    }
}
