package me.rerere.rikkahub.utils

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object SkillScriptPathUtils {
    private val WORKDIR_DATE_PLACEHOLDER_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE
    private val WORKDIR_DATE_PLACEHOLDER_SUFFIX_REGEX = Regex("^ \\(\\d+\\)$")

    fun normalizeAndValidateScriptPath(relativePathRaw: String): String? {
        val normalized = normalizeRelativePath(relativePathRaw) ?: return null
        if (!normalized.lowercase(Locale.ROOT).endsWith(".py")) return null
        if (!normalized.startsWith("scripts/")) return null
        return normalized
    }

    fun normalizeAndValidateWorkDirRelPath(relativePathRaw: String): String? {
        return normalizeRelativePath(relativePathRaw)
    }

    fun normalizeAndValidateWorkspaceFileRelPath(relativePathRaw: String): String? {
        return normalizeRelativePath(relativePathRaw)
    }

    fun sanitizeWorkDirBaseName(title: String): String {
        val cleaned = title
            .replace('\u0000', ' ')
            .trim()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("[^\\p{L}\\p{N} _().\\-]"), "_")
            .trim()

        val trimmed = cleaned.trimEnd('.', ' ')
        val safe = trimmed.ifBlank { "Chat" }
        return safe.take(64)
    }

    fun datePlaceholderWorkDirBaseName(
        createAt: Instant,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        return WORKDIR_DATE_PLACEHOLDER_FORMATTER.withZone(zoneId).format(createAt)
    }

    fun isDatePlaceholderWorkDirBaseName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false
        val datePart = trimmed.substringBefore(" (").trim()
        val parsed = runCatching { LocalDate.parse(datePart) }.getOrNull() ?: return false
        val suffix = trimmed.removePrefix(parsed.toString())
        if (suffix.isEmpty()) return true
        return WORKDIR_DATE_PLACEHOLDER_SUFFIX_REGEX.matches(suffix)
    }

    fun pickUniqueName(existing: Set<String>, base: String): String {
        if (base !in existing) return base
        var index = 2
        while (true) {
            val candidate = "$base ($index)"
            if (candidate !in existing) return candidate
            index++
        }
    }

    private fun normalizeRelativePath(relativePathRaw: String): String? {
        var s = relativePathRaw.replace('\\', '/').trim()
        if (s.isBlank()) return null
        while (s.startsWith("./")) {
            s = s.removePrefix("./")
        }
        if (s.startsWith("/")) return null
        val parts = s.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        if (parts.any { it == "." || it == ".." }) return null
        return parts.joinToString("/")
    }
}
