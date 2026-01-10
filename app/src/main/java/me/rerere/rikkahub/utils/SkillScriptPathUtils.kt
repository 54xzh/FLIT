package me.rerere.rikkahub.utils

import java.util.Locale

object SkillScriptPathUtils {
    fun normalizeAndValidateScriptPath(relativePathRaw: String): String? {
        val normalized = normalizeRelativePath(relativePathRaw) ?: return null
        if (!normalized.lowercase(Locale.ROOT).endsWith(".py")) return null
        if (!normalized.startsWith("scripts/")) return null
        return normalized
    }

    fun normalizeAndValidateWorkDirRelPath(relativePathRaw: String): String? {
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

