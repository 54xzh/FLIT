package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertFalse
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
}
