package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.ConversationReadPosition
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatReadPositionRestorePolicyTest {
    @Test
    fun shouldRunReadPositionRestore_respectsPriority() {
        val jumpTarget = Uuid.parse("00000000-0000-0000-0000-000000000301")

        assertTrue(
            shouldRunReadPositionRestore(
                initialSearchQuery = null,
                pendingJumpNodeId = null,
                previewMode = false,
            )
        )
        assertFalse(
            shouldRunReadPositionRestore(
                initialSearchQuery = "keyword",
                pendingJumpNodeId = null,
                previewMode = false,
            )
        )
        assertFalse(
            shouldRunReadPositionRestore(
                initialSearchQuery = null,
                pendingJumpNodeId = jumpTarget,
                previewMode = false,
            )
        )
        assertFalse(
            shouldRunReadPositionRestore(
                initialSearchQuery = null,
                pendingJumpNodeId = null,
                previewMode = true,
            )
        )
    }

    @Test
    fun shouldStartInitialReadPositionRestore_waitsUntilSettingsReady() {
        assertFalse(
            shouldStartInitialReadPositionRestore(
                settingsReady = false,
                conversationInitialized = true,
                initialEntryHandled = false,
                initialSearchQuery = null,
                pendingJumpNodeId = null,
                previewMode = false,
            )
        )
        assertFalse(
            shouldStartInitialReadPositionRestore(
                settingsReady = true,
                conversationInitialized = false,
                initialEntryHandled = false,
                initialSearchQuery = null,
                pendingJumpNodeId = null,
                previewMode = false,
            )
        )
        assertFalse(
            shouldStartInitialReadPositionRestore(
                settingsReady = true,
                conversationInitialized = true,
                initialEntryHandled = true,
                initialSearchQuery = null,
                pendingJumpNodeId = null,
                previewMode = false,
            )
        )
        assertTrue(
            shouldStartInitialReadPositionRestore(
                settingsReady = true,
                conversationInitialized = true,
                initialEntryHandled = false,
                initialSearchQuery = null,
                pendingJumpNodeId = null,
                previewMode = false,
            )
        )
    }

    @Test
    fun shouldPersistCurrentReadPosition_requiresSettingsAndHandledEntry() {
        assertFalse(
            shouldPersistCurrentReadPosition(
                settingsReady = false,
                previewMode = false,
                initialEntryHandled = true,
            )
        )
        assertFalse(
            shouldPersistCurrentReadPosition(
                settingsReady = true,
                previewMode = true,
                initialEntryHandled = true,
            )
        )
        assertFalse(
            shouldPersistCurrentReadPosition(
                settingsReady = true,
                previewMode = false,
                initialEntryHandled = false,
            )
        )
        assertTrue(
            shouldPersistCurrentReadPosition(
                settingsReady = true,
                previewMode = false,
                initialEntryHandled = true,
            )
        )
    }

    @Test
    fun resolveReadPositionNodeIndex_returnsExpectedIndex() {
        val firstId = Uuid.parse("00000000-0000-0000-0000-000000000401")
        val secondId = Uuid.parse("00000000-0000-0000-0000-000000000402")
        val nodes = listOf(
            MessageNode(
                id = firstId,
                messages = listOf(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text("a")),
                    )
                )
            ),
            MessageNode(
                id = secondId,
                messages = listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text("b")),
                    )
                )
            ),
        )

        assertEquals(1, resolveReadPositionNodeIndex(nodes, secondId.toString()))
        assertEquals(-1, resolveReadPositionNodeIndex(nodes, null))
        assertEquals(-1, resolveReadPositionNodeIndex(nodes, ""))
        assertEquals(-1, resolveReadPositionNodeIndex(nodes, "invalid-node-id"))
        assertEquals(-1, resolveReadPositionNodeIndex(nodes, Uuid.random().toString()))
        assertEquals(secondId, parseReadPositionNodeId(secondId.toString()))
        assertEquals(null, parseReadPositionNodeId(""))
        assertEquals(null, parseReadPositionNodeId("broken-id"))
    }

    @Test
    fun shouldPersistConversationReadPosition_detectsItemIndexChanges() {
        val nodeId = Uuid.parse("00000000-0000-0000-0000-000000000501").toString()
        val existing = ConversationReadPosition(
            nodeId = nodeId,
            offset = 12,
            updatedAt = 10L,
            itemIndex = 3,
        )

        assertFalse(
            shouldPersistConversationReadPosition(
                existing = existing,
                incoming = existing.copy(updatedAt = 99L),
            )
        )
        assertTrue(
            shouldPersistConversationReadPosition(
                existing = existing,
                incoming = existing.copy(itemIndex = 4),
            )
        )
        assertTrue(
            shouldPersistConversationReadPosition(
                existing = existing,
                incoming = existing.copy(offset = 13),
            )
        )
    }

    @Test
    fun isCachedScrollPositionUsable_checksRange() {
        assertTrue(isCachedScrollPositionUsable(0 to 0, itemCount = 1))
        assertTrue(isCachedScrollPositionUsable(4 to 0, itemCount = 5))
        assertFalse(isCachedScrollPositionUsable(null, itemCount = 5))
        assertFalse(isCachedScrollPositionUsable(5 to 0, itemCount = 5))
        assertFalse(isCachedScrollPositionUsable(0 to 0, itemCount = 0))
    }

    @Test
    fun resolveInitialChatListPosition_defaultsToBottomWhenNothingSaved() {
        val initialPosition = resolveInitialChatListPosition(
            cachedPosition = null,
            persistedReadPosition = null,
            itemCount = 4,
        )

        assertEquals(4, initialPosition.index)
        assertEquals(0, initialPosition.offset)
    }

    @Test
    fun resolveCurrentReadPositionSample_fallsBackToLastMessageWhenAtBottomSpacer() {
        val firstId = Uuid.parse("00000000-0000-0000-0000-000000000601")
        val secondId = Uuid.parse("00000000-0000-0000-0000-000000000602")
        val nodes = listOf(
            MessageNode(
                id = firstId,
                messages = listOf(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text("hello")),
                    )
                )
            ),
            MessageNode(
                id = secondId,
                messages = listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text("world")),
                    )
                )
            ),
        )

        val sample = resolveCurrentReadPositionSample(
            messageNodes = nodes,
            itemIndex = 2,
            offset = 18,
        )

        assertEquals(secondId, sample?.nodeId)
        assertEquals(18, sample?.offset)
        assertEquals(1, sample?.itemIndex)
    }
}
