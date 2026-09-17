package me.rerere.rikkahub.data.model

import kotlin.uuid.Uuid

internal fun resolveNewConversationProjectId(
    projectFeatureEnabled: Boolean,
    requestedProjectId: Uuid?,
    selectedProjectId: Uuid?,
): Uuid? = if (projectFeatureEnabled) {
    requestedProjectId ?: selectedProjectId
} else {
    null
}

internal fun resolveNewChatNavigationProjectId(
    projectFeatureEnabled: Boolean,
    currentConversationProjectId: Uuid?,
    selectedProjectId: Uuid?,
): Uuid? = if (projectFeatureEnabled) {
    currentConversationProjectId ?: selectedProjectId
} else {
    null
}

internal fun resolveProjectPreviewId(
    projectFeatureEnabled: Boolean,
    conversationExistsInStorage: Boolean,
    conversationProjectId: Uuid?,
    selectedProjectId: Uuid?,
): Uuid? = if (!projectFeatureEnabled) {
    null
} else if (conversationExistsInStorage) {
    conversationProjectId
} else {
    selectedProjectId
}
