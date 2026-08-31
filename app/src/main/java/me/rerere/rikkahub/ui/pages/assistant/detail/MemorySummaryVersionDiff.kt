package me.rerere.rikkahub.ui.pages.assistant.detail

import org.bitbucket.cowwoc.diffmatchpatch.DiffMatchPatch
import me.rerere.rikkahub.ui.components.richtext.MarkdownVersionDiffMarkers

internal enum class MemorySummaryTextDiffOperation {
    EQUAL,
    INSERT,
    DELETE,
}

internal data class MemorySummaryTextDiffPart(
    val operation: MemorySummaryTextDiffOperation,
    val text: String,
)

internal data class MemorySummaryVersionDiffBlock(
    val key: String,
    val title: String?,
    val headingLevel: Int?,
    val operation: MemorySummaryTextDiffOperation,
    val parts: List<MemorySummaryTextDiffPart>,
)

private data class MemorySummaryMarkdownBlock(
    val key: String,
    val title: String?,
    val headingLevel: Int?,
    val content: String,
)

internal fun buildMemorySummaryVersionDiff(
    previous: String,
    current: String,
): List<MemorySummaryVersionDiffBlock> {
    val previousBlocks = parseMemorySummaryMarkdownBlocks(previous)
    val currentBlocks = parseMemorySummaryMarkdownBlocks(current)
    val previousByKey = previousBlocks.associateBy { it.key }.toMutableMap()
    val result = mutableListOf<MemorySummaryVersionDiffBlock>()

    currentBlocks.forEach { currentBlock ->
        val previousBlock = previousByKey.remove(currentBlock.key)
        val parts = textDiff(
            previous = previousBlock?.content.orEmpty(),
            current = currentBlock.content,
        )
        if (previousBlock == null || parts.any { it.operation != MemorySummaryTextDiffOperation.EQUAL }) {
            result += MemorySummaryVersionDiffBlock(
                key = "current:${currentBlock.key}",
                title = currentBlock.title,
                headingLevel = currentBlock.headingLevel,
                operation = if (previousBlock == null) {
                    MemorySummaryTextDiffOperation.INSERT
                } else {
                    MemorySummaryTextDiffOperation.EQUAL
                },
                parts = parts,
            )
        }
    }

    previousByKey.values.forEach { previousBlock ->
        result += MemorySummaryVersionDiffBlock(
            key = "previous:${previousBlock.key}",
            title = previousBlock.title,
            headingLevel = previousBlock.headingLevel,
            operation = MemorySummaryTextDiffOperation.DELETE,
            parts = textDiff(previous = previousBlock.content, current = ""),
        )
    }
    return result
}

internal fun buildMemorySummaryVersionDiffMarkdown(
    previous: String,
    current: String,
): String? = buildMemorySummaryVersionDiff(previous, current)
    .takeIf { it.isNotEmpty() }
    ?.joinToString(separator = "\n\n") { block ->
        buildString {
            block.title?.let { title ->
                append("#".repeat(block.headingLevel ?: 1))
                append(' ')
                if (block.operation == MemorySummaryTextDiffOperation.DELETE) {
                    append(MarkdownVersionDiffMarkers.DELETE_START)
                    append(MarkdownVersionDiffMarkers.encodeDeletedText(title))
                    append(MarkdownVersionDiffMarkers.DELETE_END)
                } else {
                    append(title)
                }
                append("\n\n")
            }
            block.parts.forEach { part ->
                when (part.operation) {
                    MemorySummaryTextDiffOperation.DELETE -> {
                        append(MarkdownVersionDiffMarkers.DELETE_START)
                        append(MarkdownVersionDiffMarkers.encodeDeletedText(part.text))
                        append(MarkdownVersionDiffMarkers.DELETE_END)
                    }

                    MemorySummaryTextDiffOperation.EQUAL,
                    -> append(part.text)

                    MemorySummaryTextDiffOperation.INSERT -> {
                        append(MarkdownVersionDiffMarkers.INSERT_START)
                        append(part.text)
                        append(MarkdownVersionDiffMarkers.INSERT_END)
                    }
                }
            }
        }.trim()
    }


private fun parseMemorySummaryMarkdownBlocks(content: String): List<MemorySummaryMarkdownBlock> {
    val headings = Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$")
    val blocks = mutableListOf<MemorySummaryMarkdownBlock>()
    val headingPath = mutableListOf<String>()
    val keyOccurrences = mutableMapOf<String, Int>()
    var currentTitle: String? = null
    var currentLevel: Int? = null
    var currentKey = "root"
    val currentContent = mutableListOf<String>()

    fun flush() {
        if (currentTitle != null || currentContent.any { it.isNotBlank() }) {
            blocks += MemorySummaryMarkdownBlock(
                key = currentKey,
                title = currentTitle,
                headingLevel = currentLevel,
                content = currentContent.joinToString("\n").trim(),
            )
        }
        currentContent.clear()
    }

    content.replace("\r\n", "\n").lineSequence().forEach { line ->
        val match = headings.matchEntire(line)
        if (match == null) {
            currentContent += line
            return@forEach
        }

        flush()
        val level = match.groupValues[1].length
        val title = match.groupValues[2].trim()
        while (headingPath.size >= level) {
            headingPath.removeLast()
        }
        headingPath += title
        currentTitle = title
        currentLevel = level
        val pathKey = headingPath.joinToString(separator = "\u001F")
        val occurrence = keyOccurrences.getOrDefault(pathKey, 0)
        keyOccurrences[pathKey] = occurrence + 1
        currentKey = "$pathKey\u001E$occurrence"
    }
    flush()
    return blocks
}

private fun textDiff(
    previous: String,
    current: String,
): List<MemorySummaryTextDiffPart> {
    val diffMatchPatch = DiffMatchPatch().apply {
        diffTimeout = 0.3f
    }
    val diffs = diffMatchPatch.diffMain(previous, current, false)
    diffMatchPatch.diffCleanupSemantic(diffs)
    diffMatchPatch.diffCleanupSemanticLossless(diffs)
    return diffs.mapNotNull { diff ->
        val operation = when (diff.operation) {
            DiffMatchPatch.Operation.EQUAL -> MemorySummaryTextDiffOperation.EQUAL
            DiffMatchPatch.Operation.INSERT -> MemorySummaryTextDiffOperation.INSERT
            DiffMatchPatch.Operation.DELETE -> MemorySummaryTextDiffOperation.DELETE
        }
        diff.text.takeIf { it.isNotEmpty() }?.let { text ->
            MemorySummaryTextDiffPart(operation = operation, text = text)
        }
    }
}
