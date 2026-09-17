package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ProjectFeaturePolicyTest {
    private val currentProjectId = Uuid.random()
    private val requestedProjectId = Uuid.random()
    private val selectedProjectId = Uuid.random()

    @Test
    fun `project feature is disabled by default`() {
        assertFalse(Settings().projectFeatureEnabled)
    }

    @Test
    fun `disabled feature keeps every new conversation outside projects`() {
        assertNull(
            resolveNewConversationProjectId(
                projectFeatureEnabled = false,
                requestedProjectId = requestedProjectId,
                selectedProjectId = selectedProjectId,
            )
        )
        assertNull(
            resolveNewChatNavigationProjectId(
                projectFeatureEnabled = false,
                currentConversationProjectId = currentProjectId,
                selectedProjectId = selectedProjectId,
            )
        )
    }

    @Test
    fun `enabled feature preserves requested and inherited project behavior`() {
        assertEquals(
            requestedProjectId,
            resolveNewConversationProjectId(
                projectFeatureEnabled = true,
                requestedProjectId = requestedProjectId,
                selectedProjectId = selectedProjectId,
            )
        )
        assertEquals(
            selectedProjectId,
            resolveNewConversationProjectId(
                projectFeatureEnabled = true,
                requestedProjectId = null,
                selectedProjectId = selectedProjectId,
            )
        )
        assertEquals(
            currentProjectId,
            resolveNewChatNavigationProjectId(
                projectFeatureEnabled = true,
                currentConversationProjectId = currentProjectId,
                selectedProjectId = selectedProjectId,
            )
        )
        assertEquals(
            selectedProjectId,
            resolveNewChatNavigationProjectId(
                projectFeatureEnabled = true,
                currentConversationProjectId = null,
                selectedProjectId = selectedProjectId,
            )
        )
    }

    @Test
    fun `disabled feature always shows the unfiltered conversation list`() {
        assertNull(
            resolveProjectPreviewId(
                projectFeatureEnabled = false,
                conversationExistsInStorage = true,
                conversationProjectId = currentProjectId,
                selectedProjectId = selectedProjectId,
            )
        )
    }

    @Test
    fun `enabled feature previews the existing conversation project or new chat selection`() {
        assertEquals(
            currentProjectId,
            resolveProjectPreviewId(
                projectFeatureEnabled = true,
                conversationExistsInStorage = true,
                conversationProjectId = currentProjectId,
                selectedProjectId = selectedProjectId,
            )
        )
        assertEquals(
            selectedProjectId,
            resolveProjectPreviewId(
                projectFeatureEnabled = true,
                conversationExistsInStorage = false,
                conversationProjectId = currentProjectId,
                selectedProjectId = selectedProjectId,
            )
        )
    }
}
