package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFileReferenceEntryTrackerTest {
    @Test
    fun `remembers an entered file when its display location changes`() {
        val tracker = WorkspaceFileReferenceEntryTracker()
        val fileKey = "workspace-1\u0000output/report.pdf\u0000report.pdf\u0000application/pdf\u00001024"

        assertFalse(tracker.hasEntered(fileKey))

        tracker.markEntered(fileKey)

        assertTrue(tracker.hasEntered(fileKey))
    }

    @Test
    fun `recognizes files that already existed when a conversation opened`() {
        val fileKey = "workspace-1\u0000output/report.pdf\u0000report.pdf\u0000application/pdf\u00001024"
        val tracker = WorkspaceFileReferenceEntryTracker(
            initiallyEnteredKeys = setOf(fileKey),
        )

        assertTrue(tracker.hasEntered(fileKey))
    }

    @Test
    fun `treats repeated sends of the same file as separate entries`() {
        val content = buildJsonObject {
            put("ok", true)
            put("type", "workspace_file_reference")
            put("workspace_id", "workspace-1")
            put("path", "output/report.pdf")
            put("name", "report.pdf")
            put("mime", "application/pdf")
            put("size_bytes", 1024)
        }
        val firstKey = requireNotNull(
            UIMessagePart.ToolResult(
                toolCallId = "call_file_1",
                toolName = "workspace_send_file",
                content = content,
                arguments = buildJsonObject { put("path", "output/report.pdf") },
            ).workspaceFileReferenceRenderItem()?.workspaceFileReferenceEntryKey()
        )
        val secondKey = requireNotNull(
            UIMessagePart.ToolResult(
                toolCallId = "call_file_2",
                toolName = "workspace_send_file",
                content = content,
                arguments = buildJsonObject { put("path", "output/report.pdf") },
            ).workspaceFileReferenceRenderItem()?.workspaceFileReferenceEntryKey()
        )

        assertNotEquals(firstKey, secondKey)

        val tracker = WorkspaceFileReferenceEntryTracker(setOf(firstKey))
        assertFalse(tracker.hasEntered(secondKey))
    }
}
