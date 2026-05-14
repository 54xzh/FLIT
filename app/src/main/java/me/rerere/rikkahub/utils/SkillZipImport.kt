package me.rerere.rikkahub.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.Skill
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.uuid.Uuid

object SkillZipImport {
    sealed class ImportResult {
        data class Success(val skills: List<Skill>, val archiveName: String?) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    suspend fun importFromUri(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val archiveName = getArchiveName(context, uri)
        val tempRoot = File(context.cacheDir, "skills_import/${System.currentTimeMillis()}_${Uuid.random()}")
        val tempSkillRoot = File(tempRoot, "unzipped")
        val installed = mutableListOf<Skill>()

        try {
            tempSkillRoot.mkdirs()

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext ImportResult.Error("Could not open file")

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

            val skillFiles = tempSkillRoot
                .walkTopDown()
                .filter { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) }
                .toList()

            if (skillFiles.isEmpty()) {
                return@withContext ImportResult.Error("No SKILL.md found in zip")
            }

            skillFiles.forEach { skillFile ->
                val skillDir = skillFile.parentFile ?: return@forEach
                val raw = runCatching { skillFile.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
                val frontMatter = parseFrontMatter(raw)

                val id = Uuid.random()
                val targetDir = File(context.filesDir, "skills/${id}")
                if (targetDir.exists()) targetDir.deleteRecursively()
                targetDir.mkdirs()

                // Copy the folder containing SKILL.md as the skill root.
                skillDir.copyRecursively(targetDir, overwrite = true)

                installed += Skill(
                    id = id,
                    name = frontMatter.name?.takeIf { it.isNotBlank() } ?: skillDir.name,
                    description = frontMatter.description?.trim().orEmpty(),
                )
            }

            ImportResult.Success(skills = installed, archiveName = archiveName)
        } catch (e: Exception) {
            ImportResult.Error("Failed to import skills: ${e.message}")
        } finally {
            runCatching { tempRoot.deleteRecursively() }
        }
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
        val withoutZip = if (trimmed.endsWith(".zip", ignoreCase = true)) {
            trimmed.dropLast(4).trim()
        } else {
            trimmed
        }
        return withoutZip.ifBlank { null }
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
