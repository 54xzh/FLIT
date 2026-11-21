package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.db.entity.WorkspaceType

/**
 * 规整工作区内的相对工作目录（cwd）。
 *
 * 约定：
 * - `null` 表示未设置（继承上一层）
 * - `""` 表示显式使用工作区根目录
 * - `"foo/bar"` 表示工作区内的相对子目录
 * - 若包含非法字符（NUL）或路径穿越段（`.` / `..`），返回 `null`
 */
fun normalizeWorkspaceCwd(raw: String?): String? {
    if (raw == null) return null
    val trimmed = raw.trim().replace('\\', '/')
    if (trimmed.contains('\u0000')) return null
    if (trimmed.isEmpty() || trimmed == "/" || trimmed == ".") return ""

    val withoutSandboxPrefix = when {
        trimmed == "/workspace" || trimmed == "/workspace/" -> ""
        trimmed.startsWith("/workspace/") -> trimmed.removePrefix("/workspace/")
        else -> trimmed
    }
    val stripped = withoutSandboxPrefix.trim('/')
    if (stripped.isEmpty()) return ""

    val segments = stripped.split('/').filter { it.isNotEmpty() }
    if (segments.any { it == "." || it == ".." }) return null
    return segments.joinToString("/")
}

enum class WorkspaceLocationSource {
    ASSISTANT,
    PROJECT,
    CONVERSATION,
}

/**
 * 经过「助手 → 项目 → 会话」三层继承计算后的最终有效工作位置。
 *
 * 核心规则：工作区与目录作为整体继承。一旦某层切换了其他工作区，
 * 上一层的目录（cwd）自动截断，避免跨工作区张冠李戴。
 */
data class EffectiveWorkspaceLocation(
    val workspaceId: String?,
    /** 规范化后的相对路径；空字符串 `""` 表示工作区根目录。 */
    val cwd: String = "",
    val workspaceSource: WorkspaceLocationSource = WorkspaceLocationSource.ASSISTANT,
    val cwdSource: WorkspaceLocationSource = WorkspaceLocationSource.ASSISTANT,
)

fun resolveEffectiveWorkspaceLocation(
    assistantWorkspaceId: String?,
    projectWorkspaceId: String? = null,
    projectWorkspaceCwd: String? = null,
    conversationWorkspaceId: String? = null,
    conversationWorkspaceCwd: String? = null,
    allowConversationOverride: Boolean = false,
    projectFeatureEnabled: Boolean = true,
    workspaceExists: (String) -> Boolean = { true },
): EffectiveWorkspaceLocation {
    val validAssistantWorkspaceId = assistantWorkspaceId
        ?.trim()
        ?.takeIf { it.isNotEmpty() && workspaceExists(it) }

    var effectiveWorkspaceId: String? = validAssistantWorkspaceId
    var effectiveCwd = ""
    var workspaceSource = WorkspaceLocationSource.ASSISTANT
    var cwdSource = WorkspaceLocationSource.ASSISTANT

    // 1. 项目层（仅在开启项目功能时生效，保证界面展示与后台生成链路完全一致）
    if (projectFeatureEnabled) {
        val rawProjectWorkspaceId = projectWorkspaceId?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedProjectCwd = normalizeWorkspaceCwd(projectWorkspaceCwd)
        if (rawProjectWorkspaceId != null) {
            if (workspaceExists(rawProjectWorkspaceId)) {
                val workspaceChanged = rawProjectWorkspaceId != effectiveWorkspaceId
                val nextCwd = normalizedProjectCwd ?: ""
                val cwdChanged = workspaceChanged || nextCwd != effectiveCwd
                effectiveWorkspaceId = rawProjectWorkspaceId
                effectiveCwd = nextCwd
                if (workspaceChanged) workspaceSource = WorkspaceLocationSource.PROJECT
                if (cwdChanged) cwdSource = WorkspaceLocationSource.PROJECT
            }
            // 若项目绑定的工作区已失效，则整体回退到助手层，不沿用旧工作区的 cwd。
        } else if (normalizedProjectCwd != null && effectiveWorkspaceId != null) {
            val cwdChanged = normalizedProjectCwd != effectiveCwd
            effectiveCwd = normalizedProjectCwd
            if (cwdChanged) cwdSource = WorkspaceLocationSource.PROJECT
        }
    }

    // 2. 会话层（仅在助手允许会话自定义工作位置时生效）
    if (allowConversationOverride) {
        val rawConversationWorkspaceId = conversationWorkspaceId?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedConversationCwd = normalizeWorkspaceCwd(conversationWorkspaceCwd)
        if (rawConversationWorkspaceId != null) {
            if (workspaceExists(rawConversationWorkspaceId)) {
                val workspaceChanged = rawConversationWorkspaceId != effectiveWorkspaceId
                val nextCwd = normalizedConversationCwd ?: ""
                val cwdChanged = workspaceChanged || nextCwd != effectiveCwd
                effectiveWorkspaceId = rawConversationWorkspaceId
                effectiveCwd = nextCwd
                if (workspaceChanged) workspaceSource = WorkspaceLocationSource.CONVERSATION
                if (cwdChanged) cwdSource = WorkspaceLocationSource.CONVERSATION
            }
            // 若会话覆写的工作区已失效，则整体回退到项目/助手层，不沿用旧工作区的 cwd。
        } else if (normalizedConversationCwd != null && effectiveWorkspaceId != null) {
            val cwdChanged = normalizedConversationCwd != effectiveCwd
            effectiveCwd = normalizedConversationCwd
            if (cwdChanged) cwdSource = WorkspaceLocationSource.CONVERSATION
        }
    }

    return EffectiveWorkspaceLocation(
        workspaceId = effectiveWorkspaceId,
        cwd = if (effectiveWorkspaceId != null) effectiveCwd else "",
        workspaceSource = workspaceSource,
        cwdSource = if (effectiveWorkspaceId != null) cwdSource else WorkspaceLocationSource.ASSISTANT,
    )
}

/**
 * 计算保存时应写入的覆写值 `(workspaceId, cwd)`：
 * - 与继承来源完全一致时返回 `(null, null)`（跟随上级）；
 * - 一旦自定义了目录或切换了工作区，始终连同当时选中的 `targetWorkspaceId` 一并保存，
 *   避免后续切换项目或助手工作区时把旧目录误套到另一个工作区上。
 */
fun computeWorkspaceLocationOverride(
    inheritedWorkspaceId: String?,
    inheritedCwd: String,
    targetWorkspaceId: String?,
    targetCwd: String,
): Pair<String?, String?> {
    val cleanTargetWs = targetWorkspaceId?.trim()?.takeIf { it.isNotEmpty() } ?: return null to null
    val cleanTargetCwd = normalizeWorkspaceCwd(targetCwd) ?: ""
    val cleanInheritedWs = inheritedWorkspaceId?.trim()?.takeIf { it.isNotEmpty() }
    val cleanInheritedCwd = normalizeWorkspaceCwd(inheritedCwd) ?: ""

    return if (cleanTargetWs == cleanInheritedWs && cleanTargetCwd == cleanInheritedCwd) {
        null to null
    } else {
        cleanTargetWs to cleanTargetCwd
    }
}

/** 统一解析工作区内实际存在的目录结果。 */
data class ResolvedWorkspaceDirectory(
    val resolvedCwd: String,
    val fellBack: Boolean,
)

/**
 * 逐级向上查找最近一个真实存在的目录（最远退回根目录 `""`），供界面与工具共用。
 */
suspend fun resolveExistingWorkspaceDirectoryPath(
    preferredCwd: String,
    directoryExists: suspend (String) -> Boolean,
): ResolvedWorkspaceDirectory {
    val normalized = normalizeWorkspaceCwd(preferredCwd).orEmpty()
    if (normalized.isEmpty()) {
        return ResolvedWorkspaceDirectory(resolvedCwd = "", fellBack = false)
    }
    var candidate = normalized
    while (candidate.isNotEmpty()) {
        if (directoryExists(candidate)) {
            return ResolvedWorkspaceDirectory(
                resolvedCwd = candidate,
                fellBack = candidate != normalized,
            )
        }
        candidate = candidate.substringBeforeLast('/', missingDelimiterValue = "")
    }
    return ResolvedWorkspaceDirectory(resolvedCwd = "", fellBack = true)
}

/** 面向界面展示的目录路径格式。 */
fun formatWorkspaceCwdForDisplay(cwd: String, type: WorkspaceType?): String {
    val clean = normalizeWorkspaceCwd(cwd).orEmpty()
    return when (type) {
        WorkspaceType.SANDBOX -> if (clean.isEmpty()) "/workspace" else "/workspace/$clean"
        WorkspaceType.LIGHTWEIGHT, null -> if (clean.isEmpty()) "/" else "/$clean"
    }
}
