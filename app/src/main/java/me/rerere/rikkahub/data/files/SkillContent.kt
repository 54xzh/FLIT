package me.rerere.rikkahub.data.files

import java.io.File
import java.util.Locale

/** Skill 文件列表和详情页共用的只读内容、目录读取工具。 */
internal object SkillContent {
    private const val MAX_PREVIEW_BYTES = 256 * 1024

    fun readPreview(skillsRoot: File, skillName: String, maxLines: Int = 3): String? {
        val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName) ?: return null
        val skillFile = SkillPaths.resolveSkillFile(skillDir, "SKILL.md")
            ?.takeIf { it.isFile }
            ?: return null
        val raw = runCatching { skillFile.readAtMost(MAX_PREVIEW_BYTES) }.getOrNull() ?: return null
        return previewFromText(raw, maxLines).takeIf { it.isNotBlank() }
    }

    /** 去掉位于文件开头、且完整闭合的 YAML front matter。 */
    fun extractBody(text: String): String {
        val lines = text.lineSequence().toList()
        if (lines.firstOrNull()?.trim() != "---") return text

        val closingIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (closingIndex < 0) return text

        return lines.drop(closingIndex + 2).joinToString("\n")
    }

    /** 返回正文最前面的若干非空行，供紧凑列表预览使用。 */
    fun previewFromText(text: String, maxLines: Int = 3): String =
        extractBody(text)
            .lineSequence()
            .filter { it.isNotBlank() }
            .take(maxLines.coerceAtLeast(1))
            .joinToString("\n")

    private fun File.readAtMost(maxBytes: Int): String = inputStream().use { input ->
        val bytes = ByteArray(maxBytes)
        var total = 0
        while (total < maxBytes) {
            val read = input.read(bytes, total, maxBytes - total)
            if (read <= 0) break
            total += read
        }
        bytes.decodeToString(endIndex = total)
    }
}

internal sealed interface SkillDirectoryNode {
    val name: String
    val relativePath: String

    data class FileNode(
        override val name: String,
        override val relativePath: String,
    ) : SkillDirectoryNode

    data class DirectoryNode(
        override val name: String,
        override val relativePath: String,
        val children: List<SkillDirectoryNode>,
    ) : SkillDirectoryNode
}

/** 从已校验的 Skill 根目录构建文件树，不跟随越界或循环链接。 */
internal object SkillDirectoryTree {
    fun load(skillsRoot: File, skillName: String): List<SkillDirectoryNode> {
        val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName)
            ?.takeIf { it.isDirectory }
            ?: return emptyList()
        return build(skillDir)
    }

    fun build(skillDir: File): List<SkillDirectoryNode> {
        val root = runCatching { skillDir.canonicalFile }.getOrNull() ?: return emptyList()
        if (!root.isDirectory) return emptyList()
        return buildDirectory(root, root, mutableSetOf(root.path))
    }

    private data class Entry(val name: String, val file: File)

    private fun buildDirectory(
        root: File,
        directory: File,
        visitingDirectories: MutableSet<String>,
    ): List<SkillDirectoryNode> {
        val entries = directory.listFiles()
            ?.mapNotNull { candidate ->
                val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@mapNotNull null
                if (!canonical.isSameOrInside(root)) return@mapNotNull null
                Entry(name = candidate.name, file = canonical)
            }
            .orEmpty()

        val directories = entries
            .filter { it.file.isDirectory }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .mapNotNull { entry ->
                if (!visitingDirectories.add(entry.file.path)) return@mapNotNull null
                val children = buildDirectory(root, entry.file, visitingDirectories)
                visitingDirectories.remove(entry.file.path)
                SkillDirectoryNode.DirectoryNode(
                    name = entry.name,
                    relativePath = entry.file.relativeTo(root).invariantSeparatorsPath,
                    children = children,
                )
            }

        val files = entries
            .filter { it.file.isFile }
            .sortedWith(compareBy<Entry>({ !it.name.equals("SKILL.md", ignoreCase = true) }, { it.name.lowercase(Locale.ROOT) }))
            .map { entry ->
                SkillDirectoryNode.FileNode(
                    name = entry.name,
                    relativePath = entry.file.relativeTo(root).invariantSeparatorsPath,
                )
            }

        return directories + files
    }

    private fun File.isSameOrInside(root: File): Boolean {
        val rootPath = root.path
        return path == rootPath || path.startsWith(rootPath + File.separator)
    }
}
