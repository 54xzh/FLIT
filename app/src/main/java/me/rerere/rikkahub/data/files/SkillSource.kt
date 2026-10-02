package me.rerere.rikkahub.data.files

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.Skill
import java.io.File

/** 所有技能读取与预览共用的来源入口。内置文件仅物化到缓存，不覆盖用户目录。 */
class SkillSource(private val context: Context) {
    suspend fun directory(skill: Skill): File? = withContext(Dispatchers.IO) {
        if (!skill.isBuiltIn) {
            return@withContext SkillPaths.resolveSkillDir(File(context.filesDir, "skills"), skill.name)
        }
        val root = File(context.cacheDir, "builtin-skills/${skill.packageName}")
        synchronized(materializeLock) {
            copyAssets("builtin-skills/${skill.packageName}", root)
        }
        root
    }

    suspend fun read(skill: Skill, path: String): String? = withContext(Dispatchers.IO) {
        val root = directory(skill) ?: return@withContext null
        val file = SkillPaths.resolveSkillFile(root, path) ?: return@withContext null
        if (file.isFile) file.readText() else null
    }

    private fun copyAssets(assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            target.parentFile?.mkdirs()
            if (!target.isFile || !target.readBytes().contentEquals(bytes)) target.writeBytes(bytes)
        } else {
            target.mkdirs()
            children.forEach { copyAssets("$assetPath/$it", File(target, it)) }
        }
    }

    companion object {
        private val materializeLock = Any()
    }
}
