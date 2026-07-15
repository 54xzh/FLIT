package me.rerere.rikkahub.data.migration

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.settingsDataStore
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.ExplicitSkillContextRow
import me.rerere.rikkahub.data.files.SkillPaths
import me.rerere.rikkahub.data.model.Skill
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SkillZipImport
import java.io.File

/**
 * 一次性迁移：把技能的主键从 UUID 改为技能名。
 *
 * 旧形态：
 * - 文件系统：`filesDir/skills/<UUID>/`，`SKILL.md` 的 front matter `name` 仅作元数据。
 * - `Settings.skills`：`List<Skill>`，每个 Skill 含 `id: Uuid`。
 * - `Assistant.enabledSkillIds: Set<Uuid>`。
 * - `Settings.enabledSkillScriptIds: Set<Uuid>`（脚本执行权限，随 Chaquopy 一并移除）。
 * - `ConversationEntity.explicit_skill_context_ids`：JSON `List<String>`，元素是 UUID 字符串。
 *
 * 新形态：
 * - 文件系统：`filesDir/skills/<技能名>/`，`目录名 = Skill.name = SKILL.md 的 name`。
 * - `Settings.skills`：`List<Skill>`，无 `id`。
 * - `Assistant.enabledSkills: Set<String>`（按技能名）。
 * - `ConversationEntity.explicit_skill_context_ids`：JSON `List<String>`，元素是技能名字符串。
 *
 * 迁移必须原子地按同一套 `UUID → 最终名` 映射改三处存储（文件系统、设置 JSON、Room 会话列），
 * 因此不走 DataStore 的类型化解码器（会丢旧字段），而是直接读原始 JSON 用 legacy 解码处理。
 *
 * 有两条调用路径：
 * - **升级路径** [migrateIfNeeded]：应用启动时跑一次，幂等（`KEY_DONE` 短路），阶段进度持久化，
 *   操作的是正式数据（filesDir/skills、正式 DataStore、正式 Room）。顺带清除脚本权限残留键。
 * - **恢复路径** [migrateRestoreData]：旧备份恢复时跑，操作的是临时恢复出的数据（临时 skills 目录、
 *   内存里的 settings JSON、临时 DB 文件），不写 `KEY_DONE`、不持久化进度、不碰正式数据。
 *
 * 两条路径复用同一套核心逻辑 [migrateInPlace]，只是传入的 [Targets] 不同。
 */
class SkillUuidMigration(
    private val context: Context,
    private val conversationDAO: ConversationDAO,
) {
    private val dataStore = context.settingsDataStore
    /**
     * 迁移作用的目标存储。把"读写设置 JSON"和"读写会话技能上下文列"抽象出来，
     * 升级路径用正式 DataStore + 正式 Room，恢复路径用内存 JSON + 临时 SQLiteDatabase。
     */
    interface Targets {
        /** 读取 skills 数组的原始 JSON（含旧 `id` 字段），缺失返回 null。 */
        suspend fun readSkillsJson(): String?
        /** 读取 assistants 数组的原始 JSON（含旧 `enabledSkillIds`），缺失返回 null。 */
        suspend fun readAssistantsJson(): String?
        /** 写回迁移后的 skills JSON。 */
        suspend fun writeSkillsJson(value: String)
        /** 写回迁移后的 assistants JSON。 */
        suspend fun writeAssistantsJson(value: String)
        /** 清除随 Chaquopy 移除的脚本权限残留键（仅升级路径有意义，恢复路径可空实现）。 */
        suspend fun clearLegacyScriptKeys()
        /** 读所有会话的 `explicit_skill_context_ids` 列（id + 列内容 JSON）。 */
        suspend fun readAllExplicitSkillContexts(): List<ExplicitSkillContextRow>
        /** 重写单条会话的 `explicit_skill_context_ids` 列。 */
        suspend fun updateExplicitSkillContexts(id: String, json: String)
    }

    /** 升级路径：操作正式 DataStore + 正式 Room，并清除脚本权限残留键。 */
    private inner class LiveTargets : Targets {
        override suspend fun readSkillsJson() = readRawPreference(SKILLS_KEY)
        override suspend fun readAssistantsJson() = readRawPreference(ASSISTANTS_KEY)
        override suspend fun writeSkillsJson(value: String) {
            dataStore.edit { it[SKILLS_KEY] = value }
        }
        override suspend fun writeAssistantsJson(value: String) {
            dataStore.edit { it[ASSISTANTS_KEY] = value }
        }
        override suspend fun clearLegacyScriptKeys() {
            dataStore.edit {
                it.remove(ENABLED_SKILL_SCRIPT_IDS_KEY)
                it.remove(ENABLE_SKILL_SCRIPT_EXECUTION_KEY)
            }
        }
        override suspend fun readAllExplicitSkillContexts() =
            conversationDAO.getAllExplicitSkillContexts()
        override suspend fun updateExplicitSkillContexts(id: String, json: String) {
            conversationDAO.updateExplicitSkillContexts(id, json)
        }
    }

    /**
     * 升级路径：应用启动时调用一次。已迁移过则直接短路。
     */
    suspend fun migrateIfNeeded(prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(KEY_DONE, false)) return
        val stage = prefs.getInt(KEY_STAGE, STAGE_NOT_STARTED)
        try {
            Companion.runMigration(
                skillsRoot = File(context.filesDir, "skills"),
                targets = LiveTargets(),
                startStage = stage,
                onStage = { s ->
                    check(prefs.edit().putInt(KEY_STAGE, s).commit()) {
                        "Failed to persist skill migration stage $s"
                    }
                },
                loadPersistedMap = { loadUuidMap() },
                savePersistedMap = { saveUuidMap(it) },
            )
            check(
                prefs.edit()
                    .putBoolean(KEY_DONE, true)
                    .putInt(KEY_STAGE, STAGE_COMPLETE)
                    .commit(),
            ) { "Failed to persist skill migration completion" }
            Log.i(TAG, "Skill UUID→name migration completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Skill UUID→name migration failed at stage ${prefs.getInt(KEY_STAGE, STAGE_NOT_STARTED)}", e)
            throw e
        }
    }

    /**
     * 恢复路径入口：对临时恢复出的数据执行迁移（不写 `KEY_DONE`、不持久化进度、不碰正式存储）。
     *
     * - [tempSkillsRoot]：临时 skills 目录（恢复出的技能文件夹所在）。
     * - [targets]：作用于临时数据的存储目标（内存 JSON + 临时 SQLiteDatabase）。
     *
     * 调用方负责：迁移成功后才把临时数据替换为正式数据；失败则丢弃临时数据、保持当前数据不变。
     */
    suspend fun migrateRestoreData(
        tempSkillsRoot: File,
        targets: Targets,
        migrateFiles: Boolean = true,
        migrateDatabase: Boolean = true,
    ) {
        Companion.runMigration(
            skillsRoot = tempSkillsRoot,
            targets = targets,
            startStage = STAGE_NOT_STARTED,
            onStage = null,
            loadPersistedMap = { emptyMap() },
            savePersistedMap = { /* 恢复路径不持久化映射 */ },
            migrateFiles = migrateFiles,
            migrateDatabase = migrateDatabase,
        )
    }

    // ---- 升级路径持久映射（filesDir/skill_uuid_map.json） ----

    private fun loadUuidMap(): Map<String, String> {
        val file = uuidMapFile()
        check(file.isFile) { "Skill migration map is missing" }
        return JsonInstant.decodeFromString<Map<String, String>>(file.readText())
    }

    private fun saveUuidMap(map: Map<String, String>) {
        val target = uuidMapFile()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(JsonInstant.encodeToString(map))
        if (temp.renameTo(target)) return
        // rename 跨文件系统会失败；退回复制，失败由 IO 异常暴露。
        temp.copyTo(target, overwrite = true)
        temp.delete()
    }

    private fun uuidMapFile(): File = File(context.filesDir, "skill_uuid_map.json")

    // ---- 原始 preference 读取（升级路径 LiveTargets 用） ----

    private suspend fun readRawPreference(key: androidx.datastore.preferences.core.Preferences.Key<String>): String? {
        return dataStore.data.first()[key]
    }

    companion object {
        private const val TAG = "SkillUuidMigration"
        private const val KEY_DONE = "skill_uuid_migrated_v1"
        private const val KEY_STAGE = "skill_uuid_migration_stage"

        private const val STAGE_NOT_STARTED = 0
        private const val STAGE_MAP_BUILT = 1
        private const val STAGE_FILES_MIGRATED = 2
        private const val STAGE_SETTINGS_MIGRATED = 3
        private const val STAGE_DB_MIGRATED = 4
        private const val STAGE_COMPLETE = 5

        // 与 PreferencesStore 中的键保持一致（键名不变，只改内部 JSON 编码）。
        private val SKILLS_KEY = stringPreferencesKey("skills")
        private val ASSISTANTS_KEY = stringPreferencesKey("assistants")
        private val ENABLED_SKILL_SCRIPT_IDS_KEY = stringPreferencesKey("enabled_skill_script_ids")
        private val ENABLE_SKILL_SCRIPT_EXECUTION_KEY = stringPreferencesKey("enable_skill_script_execution")

        // ---- 纯逻辑函数（不依赖实例状态，便于单测直接调用） ----

        /**
         * 分配一个不与 [usedNames] 冲突的安全名。
         * - 有合法 baseName → 优先用 baseName，冲突时加数字后缀（baseName1、baseName2…）。
         * - 无合法 baseName → 用 `skill`、`skill1`、`skill2`… 兜底（仅迁移用，新导入不自动加号）。
         */
        internal fun allocateUniqueName(baseName: String?, usedNames: MutableSet<String>): String {
            if (baseName != null && Skill.isValidName(baseName) && baseName !in usedNames) return baseName
            val root = baseName?.takeIf { Skill.isValidName(it) } ?: "skill"
            var i = 1
            while ("$root$i" in usedNames) i++
            return "$root$i"
        }

        /**
         * 重写 SKILLS JSON：每个元素删 `id` 键，并把旧 UUID 对应的 name 更新为最终分配名。
         * 解析失败直接抛，避免上层把“未改写”的旧 JSON 当成迁移成功。
         */
        internal fun rewriteSkillsJson(raw: String, uuidToName: Map<String, String>): String {
            val arr = Json.parseToJsonElement(raw).jsonArray
            val out = buildJsonArray {
                for (el in arr) {
                    val obj = el.jsonObject
                    val uuid = obj["id"]?.jsonPrimitive?.contentOrNull
                    add(
                        JsonObject(
                            obj.toMutableMap().apply {
                                remove("id")
                                uuid?.let { uuidToName[it] }?.let { finalName ->
                                    this["name"] = JsonPrimitive(finalName)
                                }
                            },
                        ),
                    )
                }
            }
            return out.toString()
        }

        /**
         * 重写 ASSISTANTS JSON：把每个元素的 `enabledSkillIds`(UUID 数组) 换成 `enabledSkills`(名数组)，
         * 映射不到的 UUID 丢弃。
         * 解析失败直接抛，避免上层把“未改写”的旧 JSON 当成迁移成功。
         */
        internal fun rewriteAssistantsJson(raw: String, uuidToName: Map<String, String>): String {
            val arr = Json.parseToJsonElement(raw).jsonArray
            val out = buildJsonArray {
                for (el in arr) {
                    val obj = el.jsonObject
                    val enabledSkillIds = obj["enabledSkillIds"]?.jsonArray
                    val mappedNames: List<String> = if (enabledSkillIds != null) {
                        enabledSkillIds.mapNotNull { idEl ->
                            val uuid = idEl.jsonPrimitive.contentOrNull
                            uuid?.let { uuidToName[it] }
                        }.distinct()
                    } else {
                        obj["enabledSkills"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                    }

                    val mutated = obj.toMutableMap()
                    mutated.remove("enabledSkillIds")
                    mutated["enabledSkills"] = buildJsonArray {
                        mappedNames.forEach { add(JsonPrimitive(it)) }
                    }
                    add(JsonObject(mutated))
                }
            }
            return out.toString()
        }

        /**
         * 把会话 explicit_skill_context_ids 列内容里的 UUID 串换成映射后的名。
         * 映射不到的 UUID 丢弃；已是技能名的保留。返回 null 表示无需更新。
         */
        internal fun rewriteSkillContextsColumn(raw: String, uuidToName: Map<String, String>): String? {
            val arr = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return null
            val mapped = mutableListOf<String>()
            var changed = false
            for (el in arr) {
                val s = el.jsonPrimitive.contentOrNull ?: continue
                val mappedName = uuidToName[s]
                if (mappedName != null) {
                    mapped.add(mappedName)
                    changed = true
                } else if (looksLikeUuid(s)) {
                    // UUID 形态但映射不到：对应技能已不存在，丢弃。
                    changed = true
                } else {
                    // 非 UUID 字符串（像技能名）：无法判断有效性，原样保留，交给一致性检查处理。
                    mapped.add(s)
                }
            }
            if (!changed) return null
            return buildJsonArray { mapped.forEach { add(JsonPrimitive(it)) } }.toString()
        }

        /** 是否像 UUID 串（用于迁移时区分"待映射的 UUID"与"已存在的技能名"）。 */
        internal fun looksLikeUuid(s: String): Boolean {
            // UUID 正式格式：8-4-4-4-12 个十六进制位。
            return Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(s)
        }

        // ---- 核心迁移编排（两条路径共用，纯 JVM，便于单测） ----

        /**
         * 核心迁移逻辑（升级 / 恢复两条路径共用）。不依赖 Android Context：文件迁移走
         * [SkillPaths] / [SkillZipImport] 纯 JVM，持久映射与进度通过回调注入。
         */
        internal suspend fun runMigration(
            skillsRoot: File,
            targets: Targets,
            startStage: Int,
            onStage: ((Int) -> Unit)?,
            loadPersistedMap: () -> Map<String, String>,
            savePersistedMap: (Map<String, String>) -> Unit,
            migrateFiles: Boolean = true,
            migrateDatabase: Boolean = true,
        ) {
            // 阶段 1：建立 UUID → 最终名 映射（含文件系统扫描 + 安全名分配）。
            val uuidToName: Map<String, String> = if (startStage >= STAGE_MAP_BUILT) {
                loadPersistedMap()
            } else {
                val map = buildUuidToNameMap(skillsRoot, targets)
                savePersistedMap(map)
                onStage?.invoke(STAGE_MAP_BUILT)
                map
            }

            // 阶段 2：文件系统 skills/<UUID>/ → skills/<name>/，改写 SKILL.md front matter。
            if (migrateFiles && startStage < STAGE_FILES_MIGRATED) {
                migrateSkillDirs(skillsRoot, uuidToName)
                onStage?.invoke(STAGE_FILES_MIGRATED)
            }

            // 阶段 3：设置 JSON 重写（SKILLS 删 id、ASSISTANTS 的 enabledSkillIds → enabledSkills 按名）。
            if (startStage < STAGE_SETTINGS_MIGRATED) {
                rewriteSettingsInPlace(targets, uuidToName)
                onStage?.invoke(STAGE_SETTINGS_MIGRATED)
            }

            // 阶段 4：会话 explicit_skill_context_ids 列内容重写（UUID 串 → 名）。
            if (migrateDatabase && startStage < STAGE_DB_MIGRATED) {
                rewriteConversationSkillContexts(targets, uuidToName)
                onStage?.invoke(STAGE_DB_MIGRATED)
            }

            // 所有引用都已更新后，才清理旧 UUID 目录。中途失败时旧数据仍完整保留。
            if (migrateFiles) {
                cleanupLegacySkillDirs(skillsRoot, uuidToName)
            }
        }

        /** 阶段 1：建立 UUID → 最终名 映射。 */
        private suspend fun buildUuidToNameMap(
            skillsRoot: File,
            targets: Targets,
        ): Map<String, String> {
            val rawSkillsJson = targets.readSkillsJson() ?: return emptyMap()
            val arr = Json.parseToJsonElement(rawSkillsJson).jsonArray

            val usedNames = mutableSetOf<String>()
            // 把磁盘上已有的、看起来已经是新形态（名字合法）的目录名纳入占用，避免迁移产物撞名。
            if (skillsRoot.exists()) {
                skillsRoot.listFiles().orEmpty()
                    .filter { it.isDirectory && Skill.isValidName(it.name) }
                    .forEach { usedNames.add(it.name) }
            }

            val uuidToName = LinkedHashMap<String, String>()
            for (el in arr) {
                val obj = el.jsonObject
                val idEl = obj["id"] ?: continue // 新形态条目无 id，跳过
                val uuidStr = idEl.jsonPrimitive.contentOrNull ?: continue

                if (uuidToName.containsKey(uuidStr)) continue

                val rawName = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val baseName = rawName.takeIf { it.isNotBlank() && Skill.isValidName(it) }
                val finalName = allocateUniqueName(baseName, usedNames)
                usedNames.add(finalName)
                uuidToName[uuidStr] = finalName
            }
            return uuidToName
        }

        /**
         * 阶段 2：把 skills/<UUID>/ 改名为 skills/<最终名>/，并改写 SKILL.md 的 front matter name
         * 使其与目录名一致。已存在且内容一致的目标目录跳过；目标已存在但来源不同则跳过、不覆盖。
         */
        private fun migrateSkillDirs(skillsRoot: File, uuidToName: Map<String, String>) {
            if (uuidToName.isEmpty()) return
            if (!skillsRoot.exists()) {
                Log.w(TAG, "Skills directory is missing; references will still be migrated")
                return
            }
            check(skillsRoot.isDirectory) { "Skills path is not a directory" }

            for ((uuidStr, finalName) in uuidToName) {
                val srcDir = File(skillsRoot, uuidStr)
                if (!srcDir.exists()) {
                    Log.w(TAG, "Skill dir missing for UUID $uuidStr -> $finalName; references will still be migrated")
                    continue
                }
                check(srcDir.isDirectory) { "Legacy skill path is not a directory for UUID $uuidStr" }

                val targetDir = SkillPaths.resolveSkillDir(skillsRoot, finalName)
                checkNotNull(targetDir) { "Resolved skill dir escapes root for $finalName" }

                // 目标已存在：必须与源目录的完整预期内容一致才能跳过，不能只看 SKILL.md 名称。
                if (targetDir.isDirectory) {
                    check(isMigratedCopyComplete(srcDir, targetDir, finalName)) {
                        "Target skill dir already exists for $finalName but content differs"
                    }
                    continue
                }

                // 改写 SKILL.md front matter name -> finalName（先在源目录改，再改名）。
                val md = File(srcDir, "SKILL.md")
                check(md.isFile) { "SKILL.md missing for UUID $uuidStr" }

                // 先复制到临时目标，旧 UUID 目录保留到设置和数据库全部更新成功。
                val tempTarget = File(skillsRoot, ".$finalName.migrating")
                if (tempTarget.exists()) {
                    check(tempTarget.deleteRecursively()) { "Failed to clear stale migration temp for $finalName" }
                }
                check(srcDir.copyRecursively(tempTarget, overwrite = false)) {
                    "Failed to copy skill dir $uuidStr -> $finalName"
                }
                val tempMd = File(tempTarget, "SKILL.md")
                check(SkillZipImport.ensureFrontMatterName(tempMd, finalName)) {
                    "Failed to rewrite SKILL.md for $finalName"
                }

                if (!tempTarget.renameTo(targetDir)) {
                    check(tempTarget.copyRecursively(targetDir, overwrite = false)) {
                        "Failed to land skill dir $uuidStr -> $finalName"
                    }
                    check(tempTarget.deleteRecursively()) {
                        "Failed to clear migration temp for $finalName"
                    }
                }
            }
        }

        private fun cleanupLegacySkillDirs(skillsRoot: File, uuidToName: Map<String, String>) {
            for ((uuidStr, finalName) in uuidToName) {
                val legacyDir = File(skillsRoot, uuidStr)
                if (!legacyDir.exists()) {
                    // 可能是技能文件早已缺失，也可能是上一轮清理后、写完成标记前被系统终止。
                    // 引用迁移不依赖文件存在，交给技能一致性检查向用户报告缺失目录。
                    continue
                }
                check(legacyDir.isDirectory) { "Legacy skill path is not a directory for $uuidStr" }
                val targetDir = SkillPaths.resolveSkillDir(skillsRoot, finalName)
                check(targetDir?.isDirectory == true) { "Migrated skill dir missing for $finalName" }
                check(isMigratedCopyComplete(legacyDir, targetDir, finalName)) {
                    "Migrated skill content is inconsistent for $finalName"
                }
                check(legacyDir.deleteRecursively()) { "Failed to clean legacy skill dir $uuidStr" }
            }
        }

        private fun isMigratedCopyComplete(sourceDir: File, targetDir: File, finalName: String): Boolean {
            if (!sourceDir.isDirectory || !targetDir.isDirectory) return false

            val sourceEntries = sourceDir.walkTopDown()
                .drop(1)
                .associateBy { it.relativeTo(sourceDir).invariantSeparatorsPath }
            val targetEntries = targetDir.walkTopDown()
                .drop(1)
                .associateBy { it.relativeTo(targetDir).invariantSeparatorsPath }
            if (sourceEntries.keys != targetEntries.keys) return false

            return sourceEntries.all { (relativePath, source) ->
                val target = targetEntries.getValue(relativePath)
                if (source.isDirectory != target.isDirectory) return@all false
                if (source.isDirectory) return@all true
                if (!target.isFile) return@all false

                if (relativePath == "SKILL.md") {
                    val expected = SkillZipImport.rewriteFrontMatterName(source.readText(Charsets.UTF_8), finalName)
                    target.readText(Charsets.UTF_8) == expected
                } else {
                    source.length() == target.length() && source.readBytes().contentEquals(target.readBytes())
                }
            }
        }

        /**
         * 阶段 3：通过 [targets] 读写设置 JSON。SKILLS 删 id；ASSISTANTS 的 enabledSkillIds 换成
         * enabledSkills（按名，映射不到的 UUID 丢弃）。顺带清除脚本权限残留键。
         */
        private suspend fun rewriteSettingsInPlace(targets: Targets, uuidToName: Map<String, String>) {
            val rawSkills = targets.readSkillsJson()
            val rawAssistants = targets.readAssistantsJson()

            // 解析失败由 rewrite* 直接抛，避免“预解析 + 软失败”两层互相抵消。
            val newSkillsJson = rawSkills?.let { rewriteSkillsJson(it, uuidToName) }
            val newAssistantsJson = rawAssistants?.let { rewriteAssistantsJson(it, uuidToName) }

            if (newSkillsJson != null) targets.writeSkillsJson(newSkillsJson)
            if (newAssistantsJson != null) targets.writeAssistantsJson(newAssistantsJson)
            targets.clearLegacyScriptKeys()
        }

        /** 阶段 4：遍历会话 explicit_skill_context_ids 列，UUID 串换名、未映射 UUID 丢弃。 */
        private suspend fun rewriteConversationSkillContexts(
            targets: Targets,
            uuidToName: Map<String, String>,
        ) {
            val rows = targets.readAllExplicitSkillContexts()
            for (row in rows) {
                val rewritten = rewriteSkillContextsColumn(row.explicitSkillContextIds, uuidToName) ?: continue
                targets.updateExplicitSkillContexts(row.id, rewritten)
            }
        }
    }
}
