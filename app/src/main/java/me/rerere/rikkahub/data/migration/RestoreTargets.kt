package me.rerere.rikkahub.data.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.Migration_6_7
import me.rerere.rikkahub.data.db.dao.ExplicitSkillContextRow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 恢复路径专用：把迁移作用在一个内存里的 settings JSON 字符串 + 临时 SQLite 数据库文件上，
 * 不触正式 DataStore / 正式 Room。
 *
 * 由 [me.rerere.rikkahub.data.sync.WebdavSync] 在恢复旧 UUID 格式备份后使用：
 * - 备份里的 `settings.json` 先解码成字符串，迁移重写后存回字符串供调用方落盘；
 * - 备份里的 `rikka_hub.db` 先解到临时文件 ([tempDbFile])，迁移用 Room 临时打开它重写会话列。
 *
 * 关键：迁移期间读写的是临时数据，正式数据保持不变；调用方确认全部成功后才把临时数据替换为正式。
 *
 * 用 [org.json.JSONObject] 直接操作 settings.json 顶层字段，避免 kotlinx 序列化解码丢未知键
 * （恢复路径拿到的是任意版本的备份 JSON，字段集合未知）。
 */
class RestoreTargets(
    private val context: Context,
    private val settingsJsonHolder: SettingsJsonHolder,
    tempDbFile: File?,
) : SkillUuidMigration.Targets, AutoCloseable {

    private val tempRoomDb: AppDatabase? = tempDbFile?.let { file ->
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            file.absolutePath,
        )
            .addMigrations(
                Migration_6_7,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_14_16,
                AppDatabase.MIGRATION_34_35,
                AppDatabase.MIGRATION_35_36,
                AppDatabase.MIGRATION_36_37,
                AppDatabase.MIGRATION_37_38,
                AppDatabase.MIGRATION_38_39,
                AppDatabase.MIGRATION_39_40,
                AppDatabase.MIGRATION_40_41,
                AppDatabase.MIGRATION_41_42,
                AppDatabase.MIGRATION_42_43,
            )
            .allowMainThreadQueries() // 仅用于恢复期一次性迁移
            .build()
    }

    private val rawDb: SupportSQLiteDatabase? = tempRoomDb?.openHelper?.writableDatabase

    init {
        // 备份库可能带 wal，先强制 checkpoint 让数据落主文件，保证后续读到完整行。
        rawDb?.let { db -> runCatching { db.execSQL("PRAGMA wal_checkpoint(FULL)") } }
    }

    override suspend fun readSkillsJson(): String? {
        val raw = settingsJsonHolder.json ?: return null
        return extractArrayField(raw, "skills")
    }

    override suspend fun readAssistantsJson(): String? {
        val raw = settingsJsonHolder.json ?: return null
        return extractArrayField(raw, "assistants")
    }

    override suspend fun writeSkillsJson(value: String) {
        settingsJsonHolder.json = settingsJsonHolder.json?.let { putArrayField(it, "skills", value) }
    }

    override suspend fun writeAssistantsJson(value: String) {
        settingsJsonHolder.json = settingsJsonHolder.json?.let { putArrayField(it, "assistants", value) }
    }

    /**
     * 清除随 Chaquopy 移除的脚本权限残留字段。恢复路径用内存 JSON，没有 DataStore 键概念，
     * 这里直接从 settings JSON 顶层删 `enabledSkillScriptIds` / `enableSkillScriptExecution`。
     */
    override suspend fun clearLegacyScriptKeys() {
        settingsJsonHolder.json = settingsJsonHolder.json?.let {
            removeFields(it, "enabledSkillScriptIds", "enableSkillScriptExecution")
        }
    }

    override suspend fun readAllExplicitSkillContexts(): List<ExplicitSkillContextRow> {
        val db = rawDb ?: return emptyList()
        val cursor = db.query("SELECT id, explicit_skill_context_ids FROM conversationentity")

        val rows = ArrayList<ExplicitSkillContextRow>()
        cursor.use { c ->
            val idIdx = c.getColumnIndex("id")
            val jsonIdx = c.getColumnIndex("explicit_skill_context_ids")
            if (idIdx < 0) return emptyList()
            while (c.moveToNext()) {
                val id = c.getString(idIdx)
                val json = if (jsonIdx >= 0) c.getString(jsonIdx) else ""
                rows.add(ExplicitSkillContextRow(id = id, explicitSkillContextIds = json ?: ""))
            }
        }
        return rows
    }

    override suspend fun updateExplicitSkillContexts(id: String, json: String) {
        val db = checkNotNull(rawDb) { "Temporary database is unavailable" }
        val cv = android.content.ContentValues().apply {
            put("explicit_skill_context_ids", json)
        }
        val updated = db.update(
            "conversationentity",
            SQLiteDatabase.CONFLICT_REPLACE,
            cv,
            "id = ?",
            arrayOf(id),
        )
        check(updated == 1) { "Conversation $id was not updated during skill migration" }
    }

    override fun close() {
        runCatching { tempRoomDb?.close() }
    }

    // ---- settings.json 顶层字段的字符串级操作 ----

    private fun extractArrayField(settingsJson: String, field: String): String? {
        return runCatching {
            val obj = JSONObject(settingsJson)
            val arr = obj.opt(field) ?: return null
            if (arr is JSONArray) arr.toString() else null
        }.getOrNull()
    }

    private fun putArrayField(settingsJson: String, field: String, arrayJson: String): String {
        // 恢复路径的写入失败必须上抛，交给调用方回滚；不能吞掉后装作成功。
        val obj = JSONObject(settingsJson)
        obj.put(field, JSONArray(arrayJson))
        return obj.toString()
    }

    private fun removeFields(settingsJson: String, vararg fields: String): String {
        val obj = JSONObject(settingsJson)
        for (f in fields) obj.remove(f)
        return obj.toString()
    }
}

/** 持有当前恢复路径的 settings JSON 字符串，供迁移双方读写。 */
class SettingsJsonHolder(
    @Volatile var json: String?,
)
