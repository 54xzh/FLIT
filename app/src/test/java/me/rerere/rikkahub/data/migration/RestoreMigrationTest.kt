package me.rerere.rikkahub.data.migration

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.db.dao.ExplicitSkillContextRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 恢复路径集成测试：用内存(fake) [SkillUuidMigration.Targets] + 临时技能目录喂给
 * [SkillUuidMigration.runMigration]，验证临时数据上的 UUID→名 迁移链路文件 / settings JSON /
 * 会话列三处都被正确改写，且不依赖 Android Context 或 Room。
 *
 * 复用升级路径同一套核心编排（[SkillUuidMigration.runMigration]），区别仅是 Targets。
 */
class RestoreMigrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val migration = SkillUuidMigration.Companion

    /** 内存版 Targets：把 skills / assistants 当顶层 JSON 字段存在 [settingsJson] 里，
     * 会话行放在 [convRows]。用 kotlinx Json 操作顶层字段，避免在纯 JVM Android 单元测试下
     * 直接用 org.json（android.jar 未 mock 会抛 "not mocked"）。 */
    private class FakeTargets(
        var settingsJson: String?,
        var convRows: MutableList<ExplicitSkillContextRow>,
    ) : SkillUuidMigration.Targets {
        private fun arrayField(field: String): String? {
            val raw = settingsJson ?: return null
            return runCatching {
                val obj = Json.parseToJsonElement(raw) as? JsonObject
                val arr = obj?.get(field) as? JsonArray
                arr?.toString()
            }.getOrNull()
        }

        private fun putArrayField(field: String, arrayJson: String) {
            // 与正式 RestoreTargets 一致：写入失败直接抛，不能装作成功。
            val raw = settingsJson ?: return
            val obj = Json.parseToJsonElement(raw) as? JsonObject
                ?: error("settings JSON root is not an object")
            val arr = Json.parseToJsonElement(arrayJson) as JsonArray
            settingsJson = JsonObject(obj.toMutableMap().apply { set(field, arr) }).toString()
        }

        override suspend fun readSkillsJson() = arrayField("skills")
        override suspend fun readAssistantsJson() = arrayField("assistants")
        override suspend fun writeSkillsJson(value: String) = putArrayField("skills", value)
        override suspend fun writeAssistantsJson(value: String) = putArrayField("assistants", value)
        override suspend fun clearLegacyScriptKeys() {
            val raw = settingsJson ?: return
            val obj = Json.parseToJsonElement(raw) as? JsonObject
                ?: error("settings JSON root is not an object")
            settingsJson = JsonObject(
                obj.toMutableMap().apply {
                    remove("enabledSkillScriptIds")
                    remove("enableSkillScriptExecution")
                }
            ).toString()
        }
        override suspend fun readAllExplicitSkillContexts() = convRows.toList()
        override suspend fun updateExplicitSkillContexts(id: String, json: String) {
            val idx = convRows.indexOfFirst { it.id == id }
            if (idx >= 0) convRows[idx] = convRows[idx].copy(explicitSkillContextIds = json)
        }
    }

    private fun makeSkillDir(parentRoot: File, uuid: String, name: String): File {
        val dir = File(parentRoot, uuid)
        dir.mkdirs()
        File(dir, "SKILL.md").writeText(
            """---
            |name: $name
            |description: skill $name
            |---
            |content
            """.trimMargin()
        )
        return dir
    }

    @Test
    fun `runMigration renames uuid skill dirs to names on temp data`() = runBlocking {
        val skillsRoot = tempFolder.newFolder("skills")
        val uuid1 = "11111111-1111-4111-8111-111111111111"
        val uuid2 = "22222222-2222-4222-8222-222222222222"
        makeSkillDir(skillsRoot, uuid1, "translator")
        makeSkillDir(skillsRoot, uuid2, "pdf-reader")

        val settingsJson = """{"skills":[{"id":"$uuid1","name":"translator","description":"t","folderId":null},{"id":"$uuid2","name":"pdf-reader","description":"p","folderId":null}],"assistants":[{"id":"a1","enabledSkillIds":["$uuid1"]}],"enabledSkillScriptIds":["$uuid1"]}"""

        val targets = FakeTargets(
            settingsJson = settingsJson,
            convRows = mutableListOf(ExplicitSkillContextRow(id = "c1", explicitSkillContextIds = """["$uuid1","$uuid2"]""")),
        )

        migration.runMigration(
            skillsRoot = skillsRoot,
            targets = targets,
            startStage = 0,
            onStage = null,
            loadPersistedMap = { emptyMap() },
            savePersistedMap = {},
        )

        // 文件侧：UUID 目录消失，名字目录出现，SKILL.md 的 name 与目录名一致。
        assertFalse(File(skillsRoot, uuid1).exists())
        assertFalse(File(skillsRoot, uuid2).exists())
        assertTrue(File(skillsRoot, "translator").isDirectory)
        assertTrue(File(skillsRoot, "pdf-reader").isDirectory)

        // settings JSON：每个 技能 元素删 id（助手自己的 id 保留）；assistants enabledSkillIds → enabledSkills；
        // 脚本权限键删。
        val skillsArr = (Json.parseToJsonElement(targets.settingsJson!!) as JsonObject)["skills"] as JsonArray
        assertTrue("skill element should not carry id: $skillsArr", skillsArr.none { (it as JsonObject).containsKey("id") })
        assertFalse(targets.settingsJson!!.contains("enabledSkillIds"))
        assertFalse(targets.settingsJson!!.contains("enabledSkillScriptIds"))
        assertTrue(targets.settingsJson!!.contains("\"enabledSkills\":[\"translator\"]"))

        // 会话列：UUID 串换成名。
        assertEquals("""["translator","pdf-reader"]""", targets.convRows.first().explicitSkillContextIds)
    }

    @Test
    fun `runMigration drops unmapped conversation uuids and keeps names`() = runBlocking {
        val skillsRoot = tempFolder.newFolder("skills")
        val uuid1 = "11111111-1111-4111-8111-111111111111"
        makeSkillDir(skillsRoot, uuid1, "translator")
        val settingsJson = """{"skills":[{"id":"$uuid1","name":"translator"}],"assistants":[]}"""

        val targets = FakeTargets(
            settingsJson = settingsJson,
            convRows = mutableListOf(
                ExplicitSkillContextRow(
                    id = "c1",
                    // 一个能映射的 UUID + 一个映射不到的 UUID + 一个像名的串。
                    explicitSkillContextIds = """["$uuid1","99999999-9999-4999-8999-999999999999","writer"]""",
                ),
            ),
        )

        migration.runMigration(
            skillsRoot = skillsRoot,
            targets = targets,
            startStage = 0,
            onStage = null,
            loadPersistedMap = { emptyMap() },
            savePersistedMap = {},
        )

        assertEquals("""["translator","writer"]""", targets.convRows.first().explicitSkillContextIds)
    }

    @Test
    fun `runMigration idempotent on already-new-format data`() = runBlocking {
        val skillsRoot = tempFolder.newFolder("skills")
        // 已经是新形态：名字目录、skills 无 id。
        makeSkillDir(skillsRoot, "translator", "translator")
        val settingsJson = """{"skills":[{"name":"translator","description":"t"}],"assistants":[{"enabledSkills":["translator"]}]}"""

        val targets = FakeTargets(
            settingsJson = settingsJson,
            convRows = mutableListOf(ExplicitSkillContextRow(id = "c1", explicitSkillContextIds = """["translator"]""")),
        )

        migration.runMigration(
            skillsRoot = skillsRoot,
            targets = targets,
            startStage = 0,
            onStage = null,
            loadPersistedMap = { emptyMap() },
            savePersistedMap = {},
        )

        // 不会误删已有目录，settings/会话不变。
        assertTrue(File(skillsRoot, "translator").isDirectory)
        assertTrue(targets.settingsJson!!.contains("\"name\":\"translator\""))
        assertEquals("""["translator"]""", targets.convRows.first().explicitSkillContextIds)
    }

    @Test
    fun `runMigration handles collision with pre-existing name directory`() = runBlocking {
        val skillsRoot = tempFolder.newFolder("skills")
        // 磁盘已有 translator（新形态），同时旧 UUID 技能也叫 translator → 应分配 translator1。
        makeSkillDir(skillsRoot, "translator", "translator")
        val uuid1 = "11111111-1111-4111-8111-111111111111"
        makeSkillDir(skillsRoot, uuid1, "translator")
        val settingsJson = """{"skills":[{"id":"$uuid1","name":"translator"}],"assistants":[]}"""

        val targets = FakeTargets(settingsJson = settingsJson, convRows = mutableListOf())

        migration.runMigration(
            skillsRoot = skillsRoot,
            targets = targets,
            startStage = 0,
            onStage = null,
            loadPersistedMap = { emptyMap() },
            savePersistedMap = {},
        )

        assertTrue(File(skillsRoot, "translator").isDirectory)
        assertTrue(File(skillsRoot, "translator1").isDirectory)
        assertFalse(File(skillsRoot, uuid1).exists())
        assertTrue(targets.settingsJson!!.contains("\"name\":\"translator1\""))
    }

    @Test
    fun `duplicate legacy names stay consistent across files settings assistants and conversations`() = runBlocking {
        val skillsRoot = tempFolder.newFolder("duplicate-name-skills")
        val uuid1 = "11111111-1111-4111-8111-111111111111"
        val uuid2 = "22222222-2222-4222-8222-222222222222"
        makeSkillDir(skillsRoot, uuid1, "translator")
        makeSkillDir(skillsRoot, uuid2, "translator")
        val targets = FakeTargets(
            settingsJson = """{"skills":[{"id":"$uuid1","name":"translator"},{"id":"$uuid2","name":"translator"}],"assistants":[{"enabledSkillIds":["$uuid1","$uuid2"]}]}""",
            convRows = mutableListOf(ExplicitSkillContextRow("c1", """["$uuid1","$uuid2"]""")),
        )

        migration.runMigration(
            skillsRoot = skillsRoot,
            targets = targets,
            startStage = 0,
            onStage = null,
            loadPersistedMap = { emptyMap() },
            savePersistedMap = {},
        )

        assertTrue(File(skillsRoot, "translator").isDirectory)
        assertTrue(File(skillsRoot, "translator1").isDirectory)
        val migrated = targets.settingsJson!!
        assertTrue(migrated.contains("\"name\":\"translator\""))
        assertTrue(migrated.contains("\"name\":\"translator1\""))
        assertTrue(migrated.contains("\"enabledSkills\":[\"translator\",\"translator1\"]"))
        assertEquals("""["translator","translator1"]""", targets.convRows.single().explicitSkillContextIds)
    }

    @Test
    fun `invalid legacy name uses the same safe name everywhere`() = runBlocking {
        val skillsRoot = tempFolder.newFolder("invalid-name-skills")
        val uuid = "11111111-1111-4111-8111-111111111111"
        makeSkillDir(skillsRoot, uuid, "Bad Name")
        val targets = FakeTargets(
            settingsJson = """{"skills":[{"id":"$uuid","name":"Bad Name"}],"assistants":[{"enabledSkillIds":["$uuid"]}]}""",
            convRows = mutableListOf(ExplicitSkillContextRow("c1", """["$uuid"]""")),
        )

        migration.runMigration(
            skillsRoot = skillsRoot,
            targets = targets,
            startStage = 0,
            onStage = null,
            loadPersistedMap = { emptyMap() },
            savePersistedMap = {},
        )

        assertTrue(File(skillsRoot, "skill1").isDirectory)
        assertTrue(targets.settingsJson!!.contains("\"name\":\"skill1\""))
        assertTrue(targets.settingsJson!!.contains("\"enabledSkills\":[\"skill1\"]"))
        assertEquals("""["skill1"]""", targets.convRows.single().explicitSkillContextIds)
    }

    @Test
    fun `runMigration with empty settings and no skill dirs is a no-op`() = runBlocking {
        val skillsRoot = tempFolder.newFolder("skills")
        val targets = FakeTargets(settingsJson = """{"skills":[],"assistants":[]}""", convRows = mutableListOf())

        migration.runMigration(
            skillsRoot = skillsRoot,
            targets = targets,
            startStage = 0,
            onStage = null,
            loadPersistedMap = { emptyMap() },
            savePersistedMap = {},
        )

        assertEquals("""{"skills":[],"assistants":[]}""", targets.settingsJson)
        assertNull(targets.convRows.firstOrNull())
    }

    @Test
    fun `settings-only restore migrates assistant skill references without files or database`() = runBlocking {
        val skillsRoot = tempFolder.newFolder("settings-only-skills")
        val uuid = "11111111-1111-4111-8111-111111111111"
        val targets = FakeTargets(
            settingsJson = """{"skills":[{"id":"$uuid","name":"translator"}],"assistants":[{"enabledSkillIds":["$uuid"]}]}""",
            convRows = mutableListOf(),
        )

        migration.runMigration(
            skillsRoot = skillsRoot,
            targets = targets,
            startStage = 0,
            onStage = null,
            loadPersistedMap = { emptyMap() },
            savePersistedMap = {},
            migrateFiles = false,
            migrateDatabase = false,
        )

        assertFalse(targets.settingsJson!!.contains("enabledSkillIds"))
        assertTrue(targets.settingsJson!!.contains("\"enabledSkills\":[\"translator\"]"))
        assertFalse(targets.settingsJson!!.contains("\"id\":\"$uuid\""))
    }

    @Test
    fun `missing legacy directory still migrates references without blocking startup`() = runBlocking {
        val skillsRoot = tempFolder.newFolder("missing-dir-skills")
        val uuid = "11111111-1111-4111-8111-111111111111"
        val targets = FakeTargets(
            settingsJson = """{"skills":[{"id":"$uuid","name":"translator"}],"assistants":[{"enabledSkillIds":["$uuid"]}]}""",
            convRows = mutableListOf(ExplicitSkillContextRow("c1", """["$uuid"]""")),
        )

        migration.runMigration(
            skillsRoot = skillsRoot,
            targets = targets,
            startStage = 0,
            onStage = null,
            loadPersistedMap = { emptyMap() },
            savePersistedMap = {},
        )

        assertFalse(targets.settingsJson!!.contains("enabledSkillIds"))
        assertTrue(targets.settingsJson!!.contains("\"enabledSkills\":[\"translator\"]"))
        assertFalse(targets.settingsJson!!.contains("\"id\":\"$uuid\""))
        assertEquals("""["translator"]""", targets.convRows.single().explicitSkillContextIds)
        assertFalse(File(skillsRoot, "translator").exists())
    }

    @Test
    fun `missing skills root still migrates references`() = runBlocking {
        val skillsRoot = File(tempFolder.root, "missing-skills-root")
        val uuid = "11111111-1111-4111-8111-111111111111"
        val targets = FakeTargets(
            settingsJson = """{"skills":[{"id":"$uuid","name":"translator"}],"assistants":[{"enabledSkillIds":["$uuid"]}]}""",
            convRows = mutableListOf(ExplicitSkillContextRow("c1", """["$uuid"]""")),
        )

        migration.runMigration(
            skillsRoot = skillsRoot,
            targets = targets,
            startStage = 0,
            onStage = null,
            loadPersistedMap = { emptyMap() },
            savePersistedMap = {},
        )

        assertTrue(targets.settingsJson!!.contains("\"enabledSkills\":[\"translator\"]"))
        assertEquals("""["translator"]""", targets.convRows.single().explicitSkillContextIds)
        assertFalse(skillsRoot.exists())
    }

    @Test
    fun `database failure keeps legacy directory for a safe retry`() = runBlocking {
        val skillsRoot = tempFolder.newFolder("db-failure-skills")
        val uuid = "11111111-1111-4111-8111-111111111111"
        makeSkillDir(skillsRoot, uuid, "translator")
        val targets = object : SkillUuidMigration.Targets {
            private var skills = """[{"id":"$uuid","name":"translator"}]"""
            private var assistants = """[{"enabledSkillIds":["$uuid"]}]"""
            override suspend fun readSkillsJson() = skills
            override suspend fun readAssistantsJson() = assistants
            override suspend fun writeSkillsJson(value: String) { skills = value }
            override suspend fun writeAssistantsJson(value: String) { assistants = value }
            override suspend fun clearLegacyScriptKeys() = Unit
            override suspend fun readAllExplicitSkillContexts() = listOf(
                ExplicitSkillContextRow("c1", """["$uuid"]"""),
            )
            override suspend fun updateExplicitSkillContexts(id: String, json: String) {
                error("database write failed")
            }
        }

        val error = runCatching {
            migration.runMigration(
                skillsRoot = skillsRoot,
                targets = targets,
                startStage = 0,
                onStage = null,
                loadPersistedMap = { emptyMap() },
                savePersistedMap = {},
            )
        }.exceptionOrNull()

        assertTrue(error != null)
        assertTrue(File(skillsRoot, uuid).isDirectory)
        assertTrue(File(skillsRoot, "translator").isDirectory)
    }

    @Test
    fun `partial target directory is rejected and complete legacy source is kept`() = runBlocking {
        val skillsRoot = tempFolder.newFolder("partial-target-skills")
        val uuid = "11111111-1111-4111-8111-111111111111"
        val source = makeSkillDir(skillsRoot, uuid, "translator")
        File(source, "scripts/run.py").apply {
            parentFile?.mkdirs()
            writeText("print('complete')")
        }
        makeSkillDir(skillsRoot, "translator", "translator") // 模拟中断后只复制了 SKILL.md。
        val original = """{"skills":[{"id":"$uuid","name":"translator"}],"assistants":[]}"""
        val targets = FakeTargets(settingsJson = original, convRows = mutableListOf())

        val error = runCatching {
            migration.runMigration(
                skillsRoot = skillsRoot,
                targets = targets,
                startStage = 1,
                onStage = null,
                loadPersistedMap = { mapOf(uuid to "translator") },
                savePersistedMap = {},
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(File(skillsRoot, uuid).isDirectory)
        assertTrue(File(source, "scripts/run.py").isFile)
        assertEquals(original, targets.settingsJson)
    }

    @Test
    fun `cleanup resumes when some legacy directories were already removed`() = runBlocking {
        val skillsRoot = tempFolder.newFolder("cleanup-resume-skills")
        val uuid1 = "11111111-1111-4111-8111-111111111111"
        val uuid2 = "22222222-2222-4222-8222-222222222222"
        makeSkillDir(skillsRoot, "translator", "translator")
        makeSkillDir(skillsRoot, uuid2, "pdf-reader")
        makeSkillDir(skillsRoot, "pdf-reader", "pdf-reader")
        val targets = FakeTargets(
            settingsJson = """{"skills":[{"name":"translator"},{"name":"pdf-reader"}],"assistants":[]}""",
            convRows = mutableListOf(),
        )

        migration.runMigration(
            skillsRoot = skillsRoot,
            targets = targets,
            startStage = 4,
            onStage = null,
            loadPersistedMap = { mapOf(uuid1 to "translator", uuid2 to "pdf-reader") },
            savePersistedMap = {},
        )

        assertFalse(File(skillsRoot, uuid1).exists())
        assertFalse(File(skillsRoot, uuid2).exists())
        assertTrue(File(skillsRoot, "translator").isDirectory)
        assertTrue(File(skillsRoot, "pdf-reader").isDirectory)
    }
}
