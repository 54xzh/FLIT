package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class PreferencesStoreReadPositionTest {
    @Test
    fun getConversationReadPosition_returnsMatchedPosition() {
        val conversationId = Uuid.parse("00000000-0000-0000-0000-000000000101")
        val nodeId = Uuid.parse("00000000-0000-0000-0000-000000000201")
        val settings = Settings(
            conversationReadPositions = mapOf(
                conversationId.toString() to ConversationReadPosition(
                    nodeId = nodeId.toString(),
                    offset = 42,
                    updatedAt = 1000L,
                    itemIndex = 7,
                )
            )
        )

        val position = settings.getConversationReadPosition(conversationId)

        assertEquals(nodeId.toString(), position?.nodeId)
        assertEquals(42, position?.offset)
        assertEquals(7, position?.itemIndex)
    }

    @Test
    fun sanitize_dropsInvalidEntries_andNormalizesOffset() {
        val validConversationId = Uuid.parse("00000000-0000-0000-0000-000000000102")
        val validNodeId = Uuid.parse("00000000-0000-0000-0000-000000000202")
        val rawPositions = mapOf(
            "invalid-conversation-id" to ConversationReadPosition(
                nodeId = validNodeId.toString(),
                offset = 5,
                updatedAt = 10L,
            ),
            validConversationId.toString() to ConversationReadPosition(
                nodeId = validNodeId.toString(),
                offset = -99,
                updatedAt = -1L,
                itemIndex = -8,
            ),
            Uuid.parse("00000000-0000-0000-0000-000000000103").toString() to ConversationReadPosition(
                nodeId = "invalid-node-id",
                offset = 1,
                updatedAt = 2L,
            ),
        )

        val sanitized = sanitizeConversationReadPositions(rawPositions)

        val validPosition = sanitized[validConversationId.toString()]
        assertEquals(1, sanitized.size)
        assertEquals(0, validPosition?.offset)
        assertEquals(0L, validPosition?.updatedAt)
        assertEquals(0, validPosition?.itemIndex)
        assertNull(sanitized["invalid-conversation-id"])
    }

    @Test
    fun getHttpRetryMaxRetries_clampsToRange() {
        val below = Settings(httpRetryMaxRetries = -3)
        val normal = Settings(httpRetryMaxRetries = 2)
        val above = Settings(httpRetryMaxRetries = 99)

        assertEquals(0, below.getHttpRetryMaxRetries())
        assertEquals(2, normal.getHttpRetryMaxRetries())
        assertEquals(10, above.getHttpRetryMaxRetries())
    }

    @Test
    fun getHttpRetryDelaySeconds_clampsToRange() {
        val below = Settings(httpRetryDelaySeconds = -3)
        val normal = Settings(httpRetryDelaySeconds = 12)
        val above = Settings(httpRetryDelaySeconds = 99)

        assertEquals(1, below.getHttpRetryDelaySeconds())
        assertEquals(12, normal.getHttpRetryDelaySeconds())
        assertEquals(30, above.getHttpRetryDelaySeconds())
    }
}
