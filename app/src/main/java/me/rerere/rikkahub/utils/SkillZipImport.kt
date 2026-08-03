package me.rerere.rikkahub.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.files.SkillPaths
import me.rerere.rikkahub.data.model.Skill
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.random.Random

object SkillZipImport {
    sealed class ImportResult {
        data class Success(val skills: List<Skill>, val archiveName: String?) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    /** 预解析结果：只读取压缩包内容，不写入任何文件。 */
    sealed class PreviewResult {
        data class Success(val skills: List<SkillPreview>) : PreviewResult()
        data class Error(val message: String) : PreviewResult()
    }

    /** 压缩包内单个技能的预览信息。 */
    data class SkillPreview(
        val name: String,
        val description: String,
    )

    /**
     * 导入技能压缩包。
     *
     * 流程：先全量校验 → 解压到临时目录 → 改写每个 SKILL.md 的 name 使其与目录名一致 →
     * 原子地改名为正式技能名目录。任一校验失败则整体不导入，不留半安装目录。
     *
     * @param existingSkillNames 已安装技能名集合，用于拒绝重名导入（不自动加数字后缀）。
     */
    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        existingSkillNames: Set<String> = emptySet(),
    ): ImportResult = withContext(Dispatchers.IO) {
        val archiveName = getArchiveName(context, uri)
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return@withContext ImportResult.Error("Could not open file")

        importFromStream(
            inputStream = inputStream,
            cacheDir = context.cacheDir,
            skillsRoot = File(context.filesDir, "skills"),
            existingSkillNames = existingSkillNames,
            archiveName = archiveName,
        )
    }

    /**
     * 从 [inputStream] 读取 zip 并导入到 [skillsRoot]。不依赖 Android [Context]，
     * 便于在纯 JVM 单测中直接调用（传入临时目录）。
     *
     * 流程：先全量校验 → 解压到临时目录 → 改写每个 SKILL.md 的 name 使其与目录名一致 →
     * 原子地改名为正式技能名目录。任一校验失败则整体不导入，不留半安装目录。
     *
     * @param existingSkillNames 已安装技能名集合，用于拒绝重名导入（不自动加数字后缀）。
     */
    suspend fun importFromStream(
        inputStream: java.io.InputStream,
        cacheDir: File,
        skillsRoot: File,
        existingSkillNames: Set<String> = emptySet(),
        archiveName: String? = null,
    ): ImportResult = withContext(Dispatchers.IO) {
        val tempRoot = File(cacheDir, "skills_import/${System.currentTimeMillis()}_${Random.Default.nextLong()}")
        val tempSkillRoot = File(tempRoot, "unzipped")

        try {
            tempSkillRoot.mkdirs()

            // 1. 解压到临时目录（zip-slip 防护）。
            inputStream.use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val outFile = safeResolve(tempSkillRoot, entry.name)
                            ?: return@withContext ImportResult.Error("Zip contains invalid path: ${entry.name}")

                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { output ->
                                zip.copyTo(output)
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }

            importExtracted(
                tempSkillRoot = tempSkillRoot,
                skillsRoot = skillsRoot,
                existingSkillNames = existingSkillNames,
                archiveName = archiveName,
            )
        } catch (e: Exception) {
            ImportResult.Error("Failed to import skills: ${e.message}")
        } finally {
            runCatching { tempRoot.deleteRecursively() }
        }
    }

    /**
     * 预解析压缩包：流式读取 SKILL.md front matter 并校验，不写入任何文件。
     * 用于安装前向用户展示包内技能信息。
     *
     * 注意：这里不做"与已安装重名"的硬校验（只在 [importFromStream] 阶段拒绝），
     * 预解析阶段即使存在重名也返回成功，调用方可在 UI 上自行标注。
     */
    suspend fun previewFromUri(context: Context, uri: Uri): PreviewResult = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return@withContext PreviewResult.Error("Could not open file")
        inputStream.use { previewFromStream(it) }
    }

    /**
     * 从 [inputStream] 流式读取 zip 并预解析：只把名为 `SKILL.md` 的 entry 读进内存
     * （每个限制 [MAX_SKILL_MD_PREVIEW_BYTES]），不解压其他文件、不写磁盘。
     *
     * 相比全量解压，避免 zip 炸弹风险并显著降低预览开销。
     * [cacheDir] 参数保留以兼容旧调用方，当前实现不再使用。
     */
    suspend fun previewFromStream(
        inputStream: java.io.InputStream,
        @Suppress("UNUSED_PARAMETER") cacheDir: File? = null,
    ): PreviewResult = withContext(Dispatchers.IO) {
        try {
            inputStream.use { input ->
                ZipInputStream(input).use { zip ->
                    // 收集所有 SKILL.md 的（目录名, 内容）。
                    val skillMdContents = mutableListOf<Pair<String, String>>()

                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.replace('\\', '/')
                        // zip-slip 防御：拒绝绝对路径与越界路径，但预览只读 SKILL.md，
                        // 不会落地文件，这里仍做基本校验避免读取恶意路径名。
                        if (name.startsWith("/") || name.contains("../")) {
                            zip.closeEntry()
                            continue
                        }
                        if (!entry.isDirectory && name.substringAfterLast('/').equals("SKILL.md", ignoreCase = true)) {
                            val dirName = name.substringBeforeLast('/').substringAfterLast('/')
                            val content = zip.readLimitedText(MAX_SKILL_MD_PREVIEW_BYTES)
                            skillMdContents += dirName to content
                        }
                        zip.closeEntry()
                    }

                    previewFromContents(skillMdContents)
                }
            }
        } catch (e: Exception) {
            PreviewResult.Error("Failed to read skill package: ${e.message}")
        }
    }

    /** 单个 SKILL.md 预览读取的最大字节数，防止超大 SKILL.md 占用内存。 */
    private const val MAX_SKILL_MD_PREVIEW_BYTES = 256 * 1024

    /** 从 ZipInputStream 当前 entry 读取文本，最多 [maxBytes] 字节。 */
    private fun ZipInputStream.readLimitedText(maxBytes: Int): String {
        val buffer = ByteArrayOutputStream(maxBytes.coerceAtMost(8192))
        val chunk = ByteArray(8192)
        var total = 0
        while (total < maxBytes) {
            val toRead = minOf(chunk.size, maxBytes - total)
            val read = read(chunk, 0, toRead)
            if (read <= 0) break
            buffer.write(chunk, 0, read)
            total += read
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    /**
     * 基于内存中的 SKILL.md（dirName → 内容）列表做预解析校验。
     * 校验项与 [parseSkillFiles] 一致，但不检查与已安装重名。
     */
    internal fun previewFromContents(skillMdContents: List<Pair<String, String>>): PreviewResult {
        if (skillMdContents.isEmpty()) {
            return PreviewResult.Error("No SKILL.md found in zip")
        }

        val seenNamesInZip = mutableSetOf<String>()
        val previews = mutableListOf<SkillPreview>()

        for ((_, raw) in skillMdContents) {
            val frontMatter = parseFrontMatter(raw)
            val name = frontMatter.name?.trim().orEmpty()

            if (name.isBlank()) {
                return PreviewResult.Error(
                    "A SKILL.md is missing a 'name' field. " +
                        "Every skill must declare a name in its front matter.",
                )
            }

            if (!Skill.isValidName(name)) {
                return PreviewResult.Error(
                    "Invalid skill name \"$name\". " +
                        "Names must be lowercase letters, digits, and hyphens " +
                        "(e.g. \"translator\", \"pdf-reader\"). " +
                        "No spaces, slashes, leading/trailing hyphens.",
                )
            }

            if (name.contains('/') || name.contains('\\') || name == "." || name == "..") {
                return PreviewResult.Error(
                    "Invalid skill name \"$name\": path separators and traversal segments are not allowed.",
                )
            }

            if (!seenNamesInZip.add(name)) {
                return PreviewResult.Error(
                    "Duplicate skill name \"$name\" inside the zip. Each skill must have a unique name.",
                )
            }

            previews += SkillPreview(name = name, description = frontMatter.description?.trim().orEmpty())
        }

        return PreviewResult.Success(skills = previews)
    }

    /**
     * 对已解压到 [tempSkillRoot] 的技能目录做全量校验（不安装）。
     * 校验项与 [importExtracted] 的解析阶段一致，但不检查与已安装重名。
     */
    internal fun previewExtracted(tempSkillRoot: File): PreviewResult {
        val parsed = parseSkillFiles(tempSkillRoot).fold(
            ifLeft = { message -> return PreviewResult.Error(message) },
            ifRight = { it -> it },
        )
        return PreviewResult.Success(
            skills = parsed.map { SkillPreview(name = it.name, description = it.description) },
        )
    }

    /** 解析后的技能信息（内部用，安装阶段还需要 skillDir）。 */
    private data class ParsedSkill(val skillDir: File, val name: String, val description: String)

    /** 简单的二选一类型，避免引入额外依赖。 */
    private sealed class Either<out L, out R> {
        data class Left<L>(val value: L) : Either<L, Nothing>()
        data class Right<R>(val value: R) : Either<Nothing, R>()

        inline fun <T> fold(ifLeft: (L) -> T, ifRight: (R) -> T): T = when (this) {
            is Left -> ifLeft(value)
            is Right -> ifRight(value)
        }
    }

    /**
     * 解析 [tempSkillRoot] 下所有 SKILL.md，做技能名合法性、zip 内唯一性校验。
     * 成功返回技能列表，失败返回错误信息。
     *
     * 预解析（[previewExtracted]）与安装（[importExtracted]）共用此方法，
     * 唯一区别是安装阶段额外检查"与已安装重名"。
     */
    private fun parseSkillFiles(
        tempSkillRoot: File,
        existingSkillNames: Set<String> = emptySet(),
    ): Either<String, List<ParsedSkill>> {
        val skillFiles = tempSkillRoot
            .walkTopDown()
            .filter { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) }
            .toList()

        if (skillFiles.isEmpty()) {
            return Either.Left("No SKILL.md found in zip")
        }

        val seenNamesInZip = mutableSetOf<String>()
        val parsed = mutableListOf<ParsedSkill>()

        for (skillFile in skillFiles) {
            val skillDir = skillFile.parentFile
                ?: return Either.Left("Invalid skill folder structure")

            val raw = runCatching { skillFile.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
            val frontMatter = parseFrontMatter(raw)
            val name = frontMatter.name?.trim().orEmpty()

            if (name.isBlank()) {
                return Either.Left(
                    "A SKILL.md is missing a 'name' field. " +
                        "Every skill must declare a name in its front matter.",
                )
            }

            if (!Skill.isValidName(name)) {
                return Either.Left(
                    "Invalid skill name \"$name\". " +
                        "Names must be lowercase letters, digits, and hyphens " +
                        "(e.g. \"translator\", \"pdf-reader\"). " +
                        "No spaces, slashes, leading/trailing hyphens.",
                )
            }

            if (name.contains('/') || name.contains('\\') || name == "." || name == "..") {
                return Either.Left(
                    "Invalid skill name \"$name\": path separators and traversal segments are not allowed.",
                )
            }

            if (!seenNamesInZip.add(name)) {
                return Either.Left(
                    "Duplicate skill name \"$name\" inside the zip. Each skill must have a unique name.",
                )
            }

            if (name in existingSkillNames) {
                return Either.Left(
                    "A skill named \"$name\" is already installed. " +
                        "Rename it in the zip's SKILL.md or remove the existing one first.",
                )
            }

            parsed += ParsedSkill(
                skillDir = skillDir,
                name = name,
                description = frontMatter.description?.trim().orEmpty(),
            )
        }

        return Either.Right(parsed)
    }

    /**
     * 对已解压到 [tempSkillRoot] 的技能目录做全量校验 + 原子落地。
     * 任一校验失败或落地失败则整体不导入并回滚已落地的目录。
     */
    internal fun importExtracted(
        tempSkillRoot: File,
        skillsRoot: File,
        existingSkillNames: Set<String>,
        archiveName: String?,
    ): ImportResult {
        val parsed = parseSkillFiles(tempSkillRoot, existingSkillNames).fold(
            ifLeft = { message -> return ImportResult.Error(message) },
            ifRight = { it -> it },
        )

        // 校验通过：改写每个 SKILL.md 的 front matter name（与目录名一致），再把技能目录
        // 原子地改名为正式技能名目录。任一落地失败则回滚已落地的目录。
        val landed = mutableListOf<File>()
        val installed = mutableListOf<Skill>()

        for (item in parsed) {
            // 改写 SKILL.md：把 front matter 的 name 规整为与目录名一致（缺 front matter 时补齐）。
            val skillFile = File(item.skillDir, "SKILL.md")
            if (!skillFile.isFile) {
                return rollback(skillsRoot, landed,
                    "SKILL.md missing for skill \"${item.name}\" after extraction")
            }
            val rewritten = runCatching {
                ensureFrontMatterName(skillFile, item.name)
            }.getOrElse {
                return rollback(skillsRoot, landed,
                    "Failed to rewrite SKILL.md for \"${item.name}\": ${it.message}")
            }
            if (!rewritten) {
                return rollback(skillsRoot, landed,
                    "Failed to normalize SKILL.md front matter for \"${item.name}\"")
            }

            // 解析最终目标目录，走 SkillPaths 做边界检查。
            skillsRoot.mkdirs()
            val targetDir = SkillPaths.resolveSkillDir(skillsRoot, item.name)
                ?: return rollback(skillsRoot, landed,
                    "Resolved skill directory escapes skills root for \"${item.name}\"")

            if (targetDir.exists()) {
                // 名字唯一性已校验过；这里存在说明是脏数据，不静默覆盖。
                return rollback(skillsRoot, landed,
                    "Target directory already exists for \"${item.name}\"; aborting to avoid overwrite")
            }

            // 原子落地：先在临时区内改名到目标，再 renameTo 正式目录。
            // 由于 item.skillDir 在 tempSkillRoot 下，直接 renameTo 到 skillsRoot/<name>。
            if (!item.skillDir.renameTo(targetDir)) {
                // renameTo 跨挂载点可能失败，回退到 copy + delete。
                val copied = runCatching {
                    targetDir.mkdirs() && item.skillDir.copyRecursively(targetDir, overwrite = true)
                }.getOrElse {
                    runCatching { targetDir.deleteRecursively() }
                    return rollback(skillsRoot, landed,
                        "Failed to land skill \"${item.name}\": ${it.message}")
                }
                if (!copied) {
                    runCatching { targetDir.deleteRecursively() }
                    return rollback(skillsRoot, landed,
                        "Failed to copy skill \"${item.name}\" to its target directory")
                }
                runCatching { item.skillDir.deleteRecursively() }
            }

            landed += targetDir
            installed += Skill(
                name = item.name,
                description = item.description,
            )
        }

        return ImportResult.Success(skills = installed, archiveName = archiveName)
    }

    /**
     * 回滚已落地的技能目录，返回一个 [ImportResult.Error]。
     */
    private fun rollback(skillsRoot: File, landed: List<File>, message: String): ImportResult.Error {
        landed.forEach { dir ->
            runCatching {
                if (dir.canonicalFile.startsWith(skillsRoot.canonicalFile)) {
                    dir.deleteRecursively()
                }
            }
        }
        return ImportResult.Error(message)
    }

    private fun getArchiveName(context: Context, uri: Uri): String? {
        val raw = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index < 0) return@use null
                    cursor.getString(index)
                }
        }.getOrNull() ?: uri.lastPathSegment

        val name = raw
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            .orEmpty()

        if (name.isBlank()) return null

        val trimmed = name.trim()
        val withoutArchive = when {
            trimmed.endsWith(".zip", ignoreCase = true) -> trimmed.dropLast(4).trim()
            trimmed.endsWith(".skill", ignoreCase = true) -> trimmed.dropLast(6).trim()
            else -> trimmed
        }
        return withoutArchive.ifBlank { null }
    }

    internal data class SkillFrontMatter(
        val name: String?,
        val description: String?,
    )

    internal fun parseFrontMatter(text: String): SkillFrontMatter {
        val lines = text.lineSequence().toList()
        if (lines.isEmpty() || lines.first().trim() != "---") return SkillFrontMatter(null, null)

        val endIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (endIndex < 0) return SkillFrontMatter(null, null)

        val frontMatterLines = lines.subList(1, endIndex + 1)
        var name: String? = null
        var description: String? = null

        var index = 0
        while (index < frontMatterLines.size) {
            val line = frontMatterLines[index]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || line.leadingSpaces() > 0) {
                index++
                continue
            }

            val colonIndex = trimmed.indexOf(':')
            if (colonIndex <= 0) {
                index++
                continue
            }

            val key = trimmed.substring(0, colonIndex).trim()
            val rawValue = trimmed.substring(colonIndex + 1).trim()
            val blockHeader = rawValue.toBlockScalarHeader()
            val value = if (blockHeader != null) {
                val block = readBlockScalar(frontMatterLines, startIndex = index + 1, header = blockHeader)
                index = block.nextIndex
                block.value
            } else {
                index++
                rawValue.toYamlScalar()
            }

            when (key) {
                "name" -> name = value
                "description" -> description = value
            }
        }

        return SkillFrontMatter(name = name, description = description)
    }

    /**
     * 改写 [skillFile] 的 front matter，使 `name` 字段等于 [skillName]，且 front matter 存在。
     *
     * - 若文件已有 front matter 且含 `name` 行：就地替换该行的值。
     * - 若已有 front matter 但无 `name` 行：在 front matter 开头插入 `name: <skillName>`。
     * - 若没有 front matter：在文件最前面补一段 `---\nname: <skillName>\n---\n`。
     *
     * 返回 false 表示写入失败（I/O 错误等），由调用方决定如何回滚。
     */
    internal fun ensureFrontMatterName(skillFile: File, skillName: String): Boolean {
        val original = runCatching { skillFile.readText(Charsets.UTF_8) }.getOrNull() ?: return false
        return runCatching { skillFile.writeText(rewriteFrontMatterName(original, skillName)) }.isSuccess
    }

    internal fun rewriteFrontMatterName(text: String, skillName: String): String {
        val lines = text.lineSequence().toList()
        if (lines.isEmpty() || lines.first().trim() != "---") {
            // 无 front matter，补一段。
            return "---\nname: $skillName\n---\n" + text
        }

        val endIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (endIndex < 0) {
            // 开了 front matter 但没闭合，保守补一段新的在前面。
            return "---\nname: $skillName\n---\n" + text
        }

        val frontMatterRange = 1 until (endIndex + 1) // front matter 内容行索引
        val nameLineIndex = frontMatterRange.firstOrNull { idx ->
            val trimmed = lines[idx].trim()
            trimmed.startsWith("name:") && lines[idx].leadingSpaces() == 0
        }

        val newLines = lines.toMutableList()
        if (nameLineIndex != null) {
            newLines[nameLineIndex] = "name: $skillName"
        } else {
            // 在 front matter 第一行（紧跟开头的 `---`）插入 name 行。
            newLines.add(1, "name: $skillName")
        }
        return newLines.joinToString("\n")
    }

    private data class BlockScalarHeader(
        val style: Char,
        val chomp: Char?,
    )

    private data class BlockScalar(
        val value: String,
        val nextIndex: Int,
    )

    private fun String.toBlockScalarHeader(): BlockScalarHeader? {
        val style = firstOrNull()?.takeIf { it == '>' || it == '|' } ?: return null
        val header = drop(1).substringBefore('#').trim()
        if (header.any { it !in "+-0123456789" }) return null
        return BlockScalarHeader(
            style = style,
            chomp = header.firstOrNull { it == '-' || it == '+' },
        )
    }

    private fun readBlockScalar(
        lines: List<String>,
        startIndex: Int,
        header: BlockScalarHeader,
    ): BlockScalar {
        val rawBlockLines = mutableListOf<String>()
        var index = startIndex
        while (index < lines.size) {
            val line = lines[index]
            if (line.isNotBlank() && line.leadingSpaces() == 0) break
            rawBlockLines += line
            index++
        }

        val contentIndent = rawBlockLines
            .filter { it.isNotBlank() }
            .minOfOrNull { it.leadingSpaces() }
            ?: 0

        val blockLines = rawBlockLines.map { line ->
            if (line.length >= contentIndent) line.drop(contentIndent) else ""
        }
        val value = when (header.style) {
            '>' -> blockLines.toFoldedYamlText()
            else -> blockLines.joinToString("\n") { it.trimEnd() }
        }.applyYamlChomp(header.chomp)

        return BlockScalar(value = value, nextIndex = index)
    }

    private fun List<String>.toFoldedYamlText(): String {
        val builder = StringBuilder()
        var previousBlank = false

        forEach { rawLine ->
            val line = rawLine.trimEnd()
            if (line.isBlank()) {
                if (builder.isNotEmpty() && !builder.endsWith("\n")) {
                    builder.append('\n')
                }
                previousBlank = true
            } else {
                if (builder.isNotEmpty()) {
                    if (previousBlank || builder.endsWith("\n")) {
                        if (!builder.endsWith("\n")) builder.append('\n')
                    } else {
                        builder.append(' ')
                    }
                }
                builder.append(line)
                previousBlank = false
            }
        }

        return builder.toString()
    }

    private fun String.applyYamlChomp(chomp: Char?): String = when (chomp) {
        '-' -> trimEnd('\n')
        '+' -> this
        else -> trimEnd('\n')
    }

    private fun String.toYamlScalar(): String {
        val value = stripYamlComment().trim()
        if (value.length >= 2) {
            val first = value.first()
            val last = value.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.lastIndex)
            }
        }
        return value
    }

    private fun String.stripYamlComment(): String {
        var quote: Char? = null
        var escaped = false
        for (index in indices) {
            val char = this[index]
            if (quote != null) {
                if (quote == '"' && char == '\\' && !escaped) {
                    escaped = true
                    continue
                }
                if (char == quote && !escaped) {
                    quote = null
                }
                escaped = false
                continue
            }

            if (char == '"' || char == '\'') {
                quote = char
                continue
            }
            if (char == '#' && (index == 0 || this[index - 1].isWhitespace())) {
                return substring(0, index)
            }
        }
        return this
    }

    private fun String.leadingSpaces(): Int = takeWhile { it == ' ' }.length

    private fun safeResolve(rootDir: File, entryName: String): File? {
        val normalized = entryName.replace('\\', '/')
        if (normalized.startsWith("/")) return null
        val file = File(rootDir, normalized)
        val rootPath = rootDir.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        return if (filePath.startsWith(rootPath)) file else null
    }
}
