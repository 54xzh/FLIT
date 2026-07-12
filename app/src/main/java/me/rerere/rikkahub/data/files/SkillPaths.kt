package me.rerere.rikkahub.data.files

import java.io.File

/**
 * 技能目录路径安全解析。
 *
 * 技能以 `filesDir/skills/<技能名>/` 形式存放，技能名来自 SKILL.md 的 front matter `name` 字段，
 * 不受压缩包目录名限制，因此所有写入磁盘的路径都必须经过这里做边界检查，防止 `/`、`..`、
 * 绝对路径等越界写入。
 */
internal object SkillPaths {
    /**
     * 解析技能目录。名字为空、为 `.`/`..`、含 `/` 或 `\`、或 canonical 后逃出 skills 根时返回 null。
     */
    fun resolveSkillDir(skillsRoot: File, skillName: String): File? {
        if (skillName.isBlank()) return null
        if (skillName == "." || skillName == "..") return null
        if (skillName.contains('/') || skillName.contains('\\')) return null

        val canonicalRoot = skillsRoot.canonicalFile
        val canonicalDir = canonicalRoot.resolve(skillName).canonicalFile
        val parent = canonicalDir.parentFile ?: return null

        if (parent != canonicalRoot) return null
        if (!canonicalDir.isSameOrInside(canonicalRoot)) return null

        return canonicalDir
    }

    /**
     * 在技能目录内解析相对路径（如 SKILL.md / scripts/foo.py）。越出技能目录返回 null。
     */
    fun resolveSkillFile(skillDir: File, relativePath: String): File? {
        if (relativePath.isBlank()) return null

        val canonicalSkillDir = skillDir.canonicalFile
        val canonicalTarget = canonicalSkillDir.resolve(relativePath).canonicalFile

        return canonicalTarget.takeIf { it.isSameOrInside(canonicalSkillDir) }
    }

    private fun File.isSameOrInside(root: File): Boolean {
        val rootPath = root.canonicalFile.path
        val currentPath = canonicalFile.path
        return currentPath == rootPath || currentPath.startsWith(rootPath + File.separator)
    }
}